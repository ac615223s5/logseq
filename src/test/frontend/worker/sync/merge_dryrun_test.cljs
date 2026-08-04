(ns frontend.worker.sync.merge-dryrun-test
  "Opt-in dry run of the merge reconcile against a real graph db.

  The committed merge tests are synthetic and small. This one points the same
  code at an actual graph so the reconcile is exercised at real scale and
  against real shapes - deeply nested refs, schema entities, kv entities - none
  of which a hand-built fixture covers convincingly.

  Skipped unless LOGSEQ_MERGE_DRYRUN_DB names a graph's db.sqlite. Use a copy,
  never a live graph: Logseq holds a lock on the one it has open.

    LOGSEQ_MERGE_DRYRUN_DB=/path/to/backup/db.sqlite node static/tests.js \\
      -n frontend.worker.sync.merge-dryrun-test

  Two properties, both of which must hold for a merge to be trustworthy:

  1. Merging a snapshot into an empty graph reproduces it - a merge into
     nothing is a plain import, so anything lost here is lost generally.
  2. Merging a snapshot into a graph already holding it changes nothing and
     conflicts with nothing. Idempotence is what stops a re-link from
     manufacturing conflicts against content that already agrees."
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [datascript.storage :refer [IStorage]]
            [frontend.worker.sync.merge :as sync-merge]
            [logseq.db.common.sqlite :as common-sqlite]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.db.sqlite.util :as sqlite-util]))

(defn- dryrun-db-path
  []
  (when (exists? js/process)
    (let [v (aget (.-env js/process) "LOGSEQ_MERGE_DRYRUN_DB")]
      (when (and (string? v) (seq v)) v))))

(defn- readonly-storage
  "A read-only IStorage over better-sqlite3.

  temp-sqlite's storage speaks the wasm sqlite API (`.exec` with :sql/:bind),
  which better-sqlite3 doesn't implement; only -restore is needed here anyway."
  [^js db]
  (let [stmt (.prepare db "select content, addresses from kvs where addr = ?")]
    (reify IStorage
      (-store [_ _addr+data-seq _delete-addrs]
        (throw (ex-info "dry run storage is read-only" {})))
      (-restore [_ addr]
        (when-let [row (.get stmt addr)]
          (let [content (aget row "content")
                addresses (aget row "addresses")
                addresses (when addresses (js/JSON.parse addresses))
                data (sqlite-util/read-transit-str content)]
            (if (and addresses (map? data))
              (assoc data :addresses addresses)
              data)))))))

(defn- open-graph-conn
  [path]
  (let [Database (js/require "better-sqlite3")
        db (new Database path #js {:readonly true})]
    {:db db
     :conn (common-sqlite/get-storage-conn (readonly-storage db) db-schema/schema)}))

(defn- snapshot-datoms
  [conn]
  (into [] (map #(select-keys % [:e :a :v])) (d/datoms @conn :eavt)))

(defn- merge-into!
  "Run the real two-phase merge against `target-conn` and return the stats."
  [target-conn datoms]
  (let [index (sync-merge/index-remote-datoms (sync-merge/identity-datoms datoms))
        shells (sync-merge/new-entity-tx-data @target-conn index)
        _ (when (seq shells)
            (d/transact! target-conn (vec shells)))
        mapping (sync-merge/build-id-mapping @target-conn index (distinct (map :e datoms)))]
    (loop [batches (partition-all 10000 datoms)
           conflicts []
           stats {}]
      (if-let [batch (first batches)]
        (let [{:keys [tx-data] :as result}
              (sync-merge/reconcile @target-conn batch
                                    {:schema db-schema/schema
                                     :remote-t 1
                                     :index index
                                     :mapping mapping})]
          (when (seq tx-data)
            (d/transact! target-conn tx-data))
          (recur (rest batches)
                 (into conflicts (:conflicts result))
                 (merge-with + stats (:stats result))))
        {:conflicts conflicts :stats stats}))))

(deftest ^:dryrun merge-against-real-graph-test
  (if-let [path (dryrun-db-path)]
    (let [{:keys [db conn]} (open-graph-conn path)]
      (try
        (let [datoms (snapshot-datoms conn)
              source-entity-count (count (d/datoms @conn :avet :block/uuid))]
          (println "dry run source:" path)
          (println "  datoms:" (count datoms) " entities with :block/uuid:" source-entity-count)

          (testing "merging into an empty graph reproduces it"
            (let [target (d/create-conn db-schema/schema)
                  {:keys [conflicts stats]} (merge-into! target datoms)
                  reproduced (count (d/datoms @target :avet :block/uuid))]
              (println "  into-empty stats:" (pr-str stats))
              (is (empty? conflicts) "importing into nothing cannot conflict")
              (is (= source-entity-count reproduced)
                  "every identifiable entity is reproduced")))

          (testing "merging into a graph that already holds it is a no-op"
            ;; rebuilt from datoms so the target has no storage attached - the
            ;; source db is opened read-only and would reject the write-through
            (let [target (d/conn-from-datoms (d/datoms @conn :eavt) db-schema/schema)
                  before (count (d/datoms @target :eavt))
                  {:keys [conflicts stats]} (merge-into! target datoms)
                  after (count (d/datoms @target :eavt))]
              (println "  idempotent stats:" (pr-str stats))
              (is (empty? conflicts) "identical content must not manufacture conflicts")
              (is (zero? (:conflicts stats 0)))
              (is (zero? (:unresolved-conflicts stats 0)))
              (is (= before after) "no datoms added or removed"))))
        (finally
          (.close db))))
    (is true "LOGSEQ_MERGE_DRYRUN_DB not set - dry run skipped")))
