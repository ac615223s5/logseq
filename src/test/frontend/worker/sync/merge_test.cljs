(ns frontend.worker.sync.merge-test
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [frontend.worker.sync.merge :as sync-merge]))

(def ^:private schema
  {:block/uuid {:db/unique :db.unique/identity}
   :db/ident {:db/unique :db.unique/identity}
   :block/title {}
   :block/parent {:db/valueType :db.type/ref}
   :block/refs {:db/valueType :db.type/ref
                :db/cardinality :db.cardinality/many}})

(def ^:private uuid-a #uuid "00000000-0000-0000-0000-00000000000a")
(def ^:private uuid-b #uuid "00000000-0000-0000-0000-00000000000b")
(def ^:private uuid-c #uuid "00000000-0000-0000-0000-00000000000c")

(defn- local-db
  [tx]
  (d/db-with (d/empty-db schema) tx))

(defn- reconcile
  [db datoms]
  (sync-merge/reconcile db datoms {:schema schema :remote-t 42}))

(deftest remote-only-entity-is-added-test
  (testing "an entity the local graph has never seen is created, not dropped"
    (let [db (local-db [{:block/uuid uuid-a :block/title "local a"}])
          datoms [{:e 900 :a :block/uuid :v uuid-b}
                  {:e 900 :a :block/title :v "remote b"}]
          {:keys [tx-data conflicts stats]} (reconcile db datoms)
          db' (d/db-with db tx-data)]
      (is (empty? conflicts))
      (is (= 2 (:added stats)))
      (is (= "remote b" (:block/title (d/entity db' [:block/uuid uuid-b]))))
      (is (= "local a" (:block/title (d/entity db' [:block/uuid uuid-a])))
          "local-only content survives the merge"))))

(deftest remote-entity-ids-are-remapped-not-reused-test
  (testing "a remote entity id must not overwrite the unrelated local entity sharing it"
    (let [db (local-db [{:db/id 900 :block/uuid uuid-a :block/title "local a"}])
          ;; remote entity 900 is a *different* block that happens to share the id
          datoms [{:e 900 :a :block/uuid :v uuid-b}
                  {:e 900 :a :block/title :v "remote b"}]
          {:keys [tx-data]} (reconcile db datoms)
          db' (d/db-with db tx-data)]
      (is (= "local a" (:block/title (d/entity db' [:block/uuid uuid-a])))
          "the local block keeping db/id 900 is untouched")
      (is (= "remote b" (:block/title (d/entity db' [:block/uuid uuid-b])))))))

(deftest matching-values-are-not-conflicts-test
  (let [db (local-db [{:block/uuid uuid-a :block/title "same"}])
        datoms [{:e 900 :a :block/uuid :v uuid-a}
                {:e 900 :a :block/title :v "same"}]
        {:keys [tx-data conflicts stats]} (reconcile db datoms)]
    (is (empty? conflicts))
    (is (empty? tx-data))
    (is (= 2 (:unchanged stats)))))

(deftest divergent-value-keeps-local-and-records-conflict-test
  (testing "both sides edited the same attribute: local stays live, remote surfaced"
    (let [db (local-db [{:block/uuid uuid-a :block/title "local edit"}])
          datoms [{:e 900 :a :block/uuid :v uuid-a}
                  {:e 900 :a :block/title :v "remote edit"}]
          {:keys [tx-data conflicts stats]} (reconcile db datoms)
          db' (d/db-with db tx-data)]
      (is (= [{:block-uuid uuid-a :attr :block/title :value "remote edit" :remote-t 42}]
             conflicts))
      (is (= 1 (:conflicts stats)))
      (is (= "local edit" (:block/title (d/entity db' [:block/uuid uuid-a])))
          "local value is not clobbered"))))

(deftest ref-values-are-remapped-test
  (testing "a ref pointing at a remote entity id resolves to the right local entity"
    (let [db (local-db [{:block/uuid uuid-a :block/title "parent"}
                        {:block/uuid uuid-b :block/title "child"}])
          parent-eid (:db/id (d/entity db [:block/uuid uuid-a]))
          ;; remote ids are deliberately unrelated to the local ones
          datoms [{:e 700 :a :block/uuid :v uuid-a}
                  {:e 800 :a :block/uuid :v uuid-b}
                  {:e 800 :a :block/parent :v 700}]
          {:keys [tx-data]} (reconcile db datoms)
          db' (d/db-with db tx-data)]
      (is (= parent-eid (:db/id (:block/parent (d/entity db' [:block/uuid uuid-b]))))))))

(deftest cardinality-many-unions-test
  (testing "many-valued attributes merge rather than conflict"
    (let [db (local-db [{:block/uuid uuid-a :block/title "a"}
                        {:block/uuid uuid-b :block/title "b"}
                        {:block/uuid uuid-c :block/title "c"}])
          b-eid (:db/id (d/entity db [:block/uuid uuid-b]))
          c-eid (:db/id (d/entity db [:block/uuid uuid-c]))
          db (d/db-with db [[:db/add [:block/uuid uuid-a] :block/refs b-eid]])
          datoms [{:e 700 :a :block/uuid :v uuid-a}
                  {:e 900 :a :block/uuid :v uuid-c}
                  {:e 700 :a :block/refs :v 900}]
          {:keys [tx-data conflicts]} (reconcile db datoms)
          db' (d/db-with db tx-data)
          refs (set (map :db/id (:block/refs (d/entity db' [:block/uuid uuid-a]))))]
      (is (empty? conflicts))
      (is (= #{b-eid c-eid} refs) "both sides' refs are kept"))))

(deftest identical-ref-is-unchanged-not-a-conflict-test
  (testing "a ref both sides already agree on must not read as divergent"
    (let [db (local-db [{:block/uuid uuid-a :block/title "child"}
                        {:block/uuid uuid-b :block/title "parent"}])
          parent-eid (:db/id (d/entity db [:block/uuid uuid-b]))
          db (d/db-with db [[:db/add [:block/uuid uuid-a] :block/parent parent-eid]])
          datoms [{:e 700 :a :block/uuid :v uuid-a}
                  {:e 800 :a :block/uuid :v uuid-b}
                  {:e 700 :a :block/parent :v 800}]
          {:keys [tx-data conflicts stats]} (reconcile db datoms)]
      (is (empty? conflicts))
      (is (empty? tx-data))
      (is (zero? (:unresolved-conflicts stats)))
      (is (= 3 (:unchanged stats))))))

(deftest entities-without-cross-graph-identity-are-skipped-test
  (testing "no :block/uuid or :db/ident means we cannot tell new from duplicate"
    (let [db (local-db [{:block/uuid uuid-a :block/title "a"}])
          datoms [{:e 900 :a :block/title :v "anonymous"}]
          {:keys [tx-data stats]} (reconcile db datoms)]
      (is (empty? tx-data))
      (is (= 1 (:skipped stats))))))

(deftest db-ident-entities-match-by-ident-test
  (testing "schema and kv entities are matched on :db/ident"
    (let [db (local-db [{:db/ident :logseq.kv/graph-uuid :block/title "old"}])
          local-eid (:db/id (d/entity db :logseq.kv/graph-uuid))
          datoms [{:e 555 :a :db/ident :v :logseq.kv/graph-uuid}
                  {:e 555 :a :block/title :v "new"}]
          {:keys [conflicts stats]} (reconcile db datoms)]
      (is (some? local-eid))
      (is (empty? conflicts) "no :block/uuid, so nothing the conflict table can hold")
      (is (= 1 (:unresolved-conflicts stats)) "but it is counted, not silently dropped"))))

(deftest identity-datoms-selects-cross-graph-identity-test
  (let [datoms [{:e 1 :a :block/uuid :v uuid-a}
                {:e 1 :a :block/title :v "t"}
                {:e 2 :a :db/ident :v :logseq.kv/graph-uuid}
                {:e 2 :a :block/parent :v 1}]]
    (is (= [{:e 1 :a :block/uuid :v uuid-a}
            {:e 2 :a :db/ident :v :logseq.kv/graph-uuid}]
           (sync-merge/identity-datoms datoms)))))

(deftest new-entity-tx-data-only-covers-unknown-entities-test
  (let [db (local-db [{:block/uuid uuid-a :block/title "known"}])
        index (sync-merge/index-remote-datoms
               [{:e 700 :a :block/uuid :v uuid-a}
                {:e 800 :a :block/uuid :v uuid-b}])
        shells (sync-merge/new-entity-tx-data db index)]
    (is (= [{:block/uuid uuid-b}] shells)
        "the entity already present locally gets no shell")))

(deftest entity-with-both-identities-gets-one-shell-test
  (testing "schema and kv entities carry :db/ident and a derived :block/uuid"
    (let [db (local-db [])
          index (sync-merge/index-remote-datoms
                 [{:e 555 :a :db/ident :v :logseq.kv/graph-uuid}
                  {:e 555 :a :block/uuid :v uuid-a}])
          shells (sync-merge/new-entity-tx-data db index)]
      (is (= [{:block/uuid uuid-a :db/ident :logseq.kv/graph-uuid}] shells)
          "one shell carrying both identities, not one per index")
      ;; two shells would trip the :block/uuid unique constraint on transact
      (let [conn (d/conn-from-db db)]
        (d/transact! conn (vec shells))
        (is (= 1 (count (d/datoms @conn :avet :block/uuid))))))))

(deftest batched-merge-resolves-refs-across-batches-test
  (testing "a ref whose target lands in a later batch still resolves"
    (let [db (local-db [])
          all-datoms [{:e 700 :a :block/uuid :v uuid-a}
                      {:e 700 :a :block/title :v "child"}
                      ;; the ref target's own datoms come in a *later* batch
                      {:e 800 :a :block/uuid :v uuid-b}
                      {:e 800 :a :block/title :v "parent"}]
          index (sync-merge/index-remote-datoms (sync-merge/identity-datoms all-datoms))
          ;; phase A: shells for everything the local graph lacks
          conn (d/conn-from-db db)
          _ (d/transact! conn (vec (sync-merge/new-entity-tx-data @conn index)))
          mapping (sync-merge/build-id-mapping @conn index (distinct (map :e all-datoms)))
          ;; phase B: reconcile in batches, ref datom in the first batch
          batches [[{:e 700 :a :block/parent :v 800}]
                   all-datoms]]
      (doseq [batch batches]
        (let [{:keys [tx-data]} (sync-merge/reconcile @conn batch
                                                      {:schema schema
                                                       :remote-t 7
                                                       :index index
                                                       :mapping mapping})]
          (when (seq tx-data) (d/transact! conn tx-data))))
      (let [child (d/entity @conn [:block/uuid uuid-a])]
        (is (= "child" (:block/title child)))
        (is (= (:db/id (d/entity @conn [:block/uuid uuid-b]))
               (:db/id (:block/parent child)))
            "ref resolved even though its target was reconciled in a later batch")))))

(deftest non-string-divergence-is-counted-test
  (testing "a differing ref cannot be stored as a conflict, so it is reported"
    (let [db (local-db [{:block/uuid uuid-a :block/title "a"}
                        {:block/uuid uuid-b :block/title "b"}
                        {:block/uuid uuid-c :block/title "c"}])
          b-eid (:db/id (d/entity db [:block/uuid uuid-b]))
          db (d/db-with db [[:db/add [:block/uuid uuid-a] :block/parent b-eid]])
          datoms [{:e 700 :a :block/uuid :v uuid-a}
                  {:e 900 :a :block/uuid :v uuid-c}
                  {:e 700 :a :block/parent :v 900}]
          {:keys [conflicts stats]} (reconcile db datoms)]
      (is (empty? conflicts))
      (is (= 1 (:unresolved-conflicts stats))))))
