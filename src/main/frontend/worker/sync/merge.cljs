(ns frontend.worker.sync.merge
  "Reconcile a remote graph snapshot into a live local graph.

  Downloading a graph replaces the local one: `prepare-import!` unlinks the
  local db and replays the snapshot's datoms straight in, which is only sound
  because the target is empty - snapshot datoms carry the *remote* graph's
  entity ids, and remote entity 20090 has nothing to do with local entity 20090.

  Merging keeps both sides. Entities are matched on identity that survives
  across graphs - `:block/uuid` for blocks, `:db/ident` for schema and kv
  entities - and every remote entity id is remapped onto the local one it
  denotes, or onto a tempid when the local graph has never seen it. Reference
  values are entity ids too, so they are remapped the same way.

  Where both sides set the same cardinality-one attribute to different values,
  the local value stays live and the remote value is recorded as a conflict for
  the user to resolve, matching how remote-sync-conflicts already behaves during
  tx apply. Nothing is dropped on either side without being surfaced."
  (:require [datascript.core :as d]))

(defn ref-attr?
  [schema a]
  (= :db.type/ref (get-in schema [a :db/valueType])))

(defn cardinality-many?
  [schema a]
  (= :db.cardinality/many (get-in schema [a :db/cardinality])))

(defn index-remote-datoms
  "Identity indexes over the snapshot: remote entity id -> :block/uuid, and
  remote entity id -> :db/ident."
  [datoms]
  (reduce (fn [acc {:keys [e a v]}]
            (case a
              :block/uuid (assoc-in acc [:e->uuid e] v)
              :db/ident (assoc-in acc [:e->ident e] v)
              acc))
          {:e->uuid {} :e->ident {}}
          datoms))

(defn- local-eid
  "The local entity denoted by a remote entity id, or nil when unknown here."
  [local-db {:keys [e->uuid e->ident]} remote-e]
  (or (when-let [ident (get e->ident remote-e)]
        (:db/id (d/entity local-db ident)))
      (when-let [uuid' (get e->uuid remote-e)]
        (:db/id (d/entity local-db [:block/uuid uuid'])))))

(defn build-id-mapping
  "remote entity id -> local :db/id, or a tempid string for entities the local
  graph doesn't have. Entities with no cross-graph identity at all are dropped:
  without :block/uuid or :db/ident there is no way to tell whether they are new
  or a duplicate of something already here."
  [local-db index remote-eids]
  (reduce (fn [acc remote-e]
            (if-let [eid (local-eid local-db index remote-e)]
              (assoc acc remote-e eid)
              (if (or (contains? (:e->uuid index) remote-e)
                      (contains? (:e->ident index) remote-e))
                (assoc acc remote-e (str "remote-" remote-e))
                acc)))
          {}
          remote-eids))

(defn- new-entity?
  [eid]
  (string? eid))

(defn- current-values
  "Local values already set for attribute `a` on entity `eid`, as a set.

  Read straight from the index rather than via d/entity: entity lookups return
  Entity instances for refs, which never compare equal to the numeric ids the
  remapping produces, so identical refs would read as divergent."
  [local-db eid a]
  (when-not (new-entity? eid)
    (let [vs (into #{} (map :v) (d/datoms local-db :eavt eid a))]
      (when (seq vs) vs))))

(defn identity-datoms
  "The datoms that establish cross-graph identity. Neither attribute is in
  encrypt-attr-set, so these can be read straight from the snapshot without
  decrypting it."
  [datoms]
  (filter #(contains? #{:block/uuid :db/ident} (:a %)) datoms))

(defn new-entity-tx-data
  "Shell entities for remote entities the local graph doesn't have yet.

  Merging runs in batches, and a tempid only holds within one transaction, so
  refs spanning batches would dangle. Creating the shells up front means every
  remote entity resolves to a real local id for the rest of the merge."
  [local-db {:keys [e->uuid e->ident] :as index}]
  (concat
   (keep (fn [[e uuid']]
           (when-not (local-eid local-db index e)
             {:block/uuid uuid'}))
         e->uuid)
   (keep (fn [[e ident]]
           (when-not (local-eid local-db index e)
             {:db/ident ident}))
         e->ident)))

(defn reconcile
  "Reconcile snapshot `datoms` into `local-db`.

  Returns {:tx-data [...] :conflicts [...] :stats {...}}. `:conflicts` matches
  what client-op/add-sync-conflicts! expects; only string values can be recorded
  there, so non-string divergences are counted in `:stats` instead of vanishing.

  `:index` and `:mapping` may be supplied when merging in batches, where they
  have to be built from the whole snapshot rather than the batch at hand."
  [local-db datoms {:keys [schema remote-t] :as opts}]
  (let [index (or (:index opts) (index-remote-datoms datoms))
        mapping (or (:mapping opts)
                    (build-id-mapping local-db index (distinct (map :e datoms))))
        init {:tx-data (transient [])
              :conflicts (transient [])
              :stats {:added 0 :unchanged 0 :conflicts 0
                      :unresolved-conflicts 0 :skipped 0}}
        result
        (reduce
         (fn [acc {:keys [e a v]}]
           (let [eid (get mapping e)
                 v' (if (ref-attr? schema a) (get mapping v ::missing) v)]
             (cond
               ;; entity or ref target has no cross-graph identity
               (or (nil? eid) (= ::missing v'))
               (update-in acc [:stats :skipped] inc)

               ;; brand new entity here: take the remote value wholesale
               (new-entity? eid)
               (-> acc
                   (update :tx-data conj! [:db/add eid a v'])
                   (update-in [:stats :added] inc))

               :else
               (let [existing (current-values local-db eid a)]
                 (cond
                   (nil? existing)
                   (-> acc
                       (update :tx-data conj! [:db/add eid a v'])
                       (update-in [:stats :added] inc))

                   (contains? existing v')
                   (update-in acc [:stats :unchanged] inc)

                   ;; cardinality-many is a union, never a conflict
                   (cardinality-many? schema a)
                   (-> acc
                       (update :tx-data conj! [:db/add eid a v'])
                       (update-in [:stats :added] inc))

                   ;; both sides set this attribute differently: keep local,
                   ;; surface remote
                   :else
                   (if-let [block-uuid (get-in index [:e->uuid e])]
                     (if (string? v')
                       (-> acc
                           (update :conflicts conj! {:block-uuid block-uuid
                                                     :attr a
                                                     :value v'
                                                     :remote-t remote-t})
                           (update-in [:stats :conflicts] inc))
                       (update-in acc [:stats :unresolved-conflicts] inc))
                     (update-in acc [:stats :unresolved-conflicts] inc)))))))
         init
         datoms)]
    {:tx-data (persistent! (:tx-data result))
     :conflicts (persistent! (:conflicts result))
     :stats (:stats result)}))
