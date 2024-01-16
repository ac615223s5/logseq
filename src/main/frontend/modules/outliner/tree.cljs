(ns frontend.modules.outliner.tree
  (:require [frontend.db :as db]
            [frontend.state :as state]
            [logseq.outliner.tree :as otree]))

(def satisfied-inode? otree/satisfied-inode?)

(defn blocks->vec-tree
  "`blocks` need to be in the same page."
  ([blocks root-id]
   (blocks->vec-tree (state/get-current-repo) blocks root-id))
  ([repo blocks root-id]
   (let [db (db/get-db repo)]
     (otree/blocks->vec-tree repo db blocks root-id))))

(def block-entity->map otree/block-entity->map)

(def filter-top-level-blocks otree/filter-top-level-blocks)

(def non-consecutive-blocks->vec-tree otree/non-consecutive-blocks->vec-tree)
(def sort-blocks otree/sort-blocks)

(defn get-sorted-block-and-children
  [repo db-id]
  (let [db (db/get-db repo)]
    (otree/get-sorted-block-and-children repo db db-id)))
