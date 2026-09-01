(ns frontend.worker.react-test
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [frontend.worker.react :as worker-react]
            [logseq.db.common.order :as db-order]
            [logseq.db.test.helper :as db-test]))

(deftest affected-keys-block-reactions
  (testing "reaction transactions affect block-reactions query key"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "Test"}
                  :blocks [{:block/title "Block"}]}])
          block (db-test/find-block-by-content @conn "Block")
          target-id (:db/id block)
          tx-report (d/transact! conn
                                 [{:block/uuid (random-uuid)
                                   :block/created-at 1
                                   :block/updated-at 1
                                   :logseq.property.reaction/emoji-id "+1"
                                   :logseq.property.reaction/target target-id}])
          affected (worker-react/get-affected-queries-keys tx-report)]
      (is (some #{[:frontend.worker.react/block-reactions target-id]} affected)))))

(deftest affected-keys-order-list-type
  (testing "changing order list type affects block query key"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "Test"}
                  :blocks [{:block/title "Block"
                            :build/properties {:logseq.property/order-list-type "number"}}]}])
          block (db-test/find-block-by-content @conn "Block")
          target-id (:db/id block)
          tx-report (d/transact! conn
                                 [[:db/retract target-id :logseq.property/order-list-type
                                   (:db/id (:logseq.property/order-list-type block))]])
          affected (worker-react/get-affected-queries-keys tx-report)]
      (is (some #{[:frontend.worker.react/block target-id]} affected)))))

(deftest affected-keys-order-list-right-siblings
  (testing "inserting a block before ordered-list siblings affects their block query keys"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "Test"}
                  :blocks [{:block/title "Block 1"
                            :build/properties {:logseq.property/order-list-type "number"}}
                           {:block/title "Block 2"
                            :build/properties {:logseq.property/order-list-type "number"}}
                           {:block/title "Block 3"
                            :build/properties {:logseq.property/order-list-type "number"}}]}])
          block-1 (db-test/find-block-by-content @conn "Block 1")
          block-2 (db-test/find-block-by-content @conn "Block 2")
          block-3 (db-test/find-block-by-content @conn "Block 3")
          tx-report (d/transact! conn
                                 [{:block/uuid (random-uuid)
                                   :block/title "Inserted"
                                   :block/page (:db/id (:block/page block-1))
                                   :block/parent (:db/id (:block/parent block-1))
                                   :block/order (db-order/gen-key (:block/order block-1)
                                                                  (:block/order block-2))
                                   :block/created-at 1
                                   :block/updated-at 1}])
          affected (worker-react/get-affected-queries-keys tx-report)]
      (is (some #{[:frontend.worker.react/block (:db/id block-2)]} affected))
      (is (some #{[:frontend.worker.react/block (:db/id block-3)]} affected)))))

(deftest affected-keys-bulk-insert-does-not-recompute-right-siblings-per-block
  (testing "bulk inserts keep affected query invalidation under the performance budget"
    (let [block-count 300
          conn (db-test/create-conn-with-blocks
                [{:page {:block/title "Bulk insert"}}])
          page (db-test/find-page-by-title @conn "Bulk insert")
          tx-report (d/transact! conn
                                 (mapv (fn [idx]
                                         {:block/uuid (random-uuid)
                                          :block/title (str "Inserted " idx)
                                          :block/page (:db/id page)
                                          :block/parent (:db/id page)
                                          :block/order (str "a" idx)
                                          :block/created-at 1
                                          :block/updated-at 1})
                                       (range block-count)))
          started-at (system-time)
          affected (worker-react/get-affected-queries-keys tx-report)
          elapsed-ms (- (system-time) started-at)]
      (println "affected-keys-bulk-insert elapsed:" elapsed-ms "ms")
      (is (<= block-count
              (count (filter (fn [[k]]
                               (= :frontend.worker.react/block k))
                             affected))))
      (is (< elapsed-ms 1000)))))

(deftest affected-keys-order-list-descendants
  (testing "changing ordered-list parent type affects nested ordered-list descendants"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "Test"}
                  :blocks [{:block/title "Parent"
                            :build/properties {:logseq.property/order-list-type "number"}
                            :build/children [{:block/title "Child"
                                              :build/properties {:logseq.property/order-list-type "number"}
                                              :build/children [{:block/title "Grandchild"
                                                                :build/properties {:logseq.property/order-list-type "number"}}]}]}]}])
          parent (db-test/find-block-by-content @conn "Parent")
          child (db-test/find-block-by-content @conn "Child")
          grandchild (db-test/find-block-by-content @conn "Grandchild")
          tx-report (d/transact! conn
                                 [[:db/retract (:db/id parent) :logseq.property/order-list-type
                                   (:db/id (:logseq.property/order-list-type parent))]])
          affected (worker-react/get-affected-queries-keys tx-report)]
      (is (some #{[:frontend.worker.react/block (:db/id child)]} affected))
      (is (some #{[:frontend.worker.react/block (:db/id grandchild)]} affected)))))

(deftest affected-keys-journals-when-journal-recycled
  (testing "recycling a journal page should refresh journals query key"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:build/journal 20240101}}
                 {:page {:build/journal 20240102}}])
          journal (db-test/find-journal-by-journal-day @conn 20240102)
          tx-report (d/transact! conn [{:db/id (:db/id journal)
                                        :logseq.property/deleted-at 1704196800000}])
          affected (worker-react/get-affected-queries-keys tx-report)]
      (is (some #{[:frontend.worker.react/journals]} affected)))))

(deftest affected-keys-property-change-leaves-parent-alone
  (testing "a property edit doesn't invalidate the parent, which would re-render the whole page"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "Test"}
                  :blocks [{:block/title "Block"}]}])
          block (db-test/find-block-by-content @conn "Block")
          parent-id (:db/id (:block/parent block))
          tx-report (d/transact! conn [{:db/id (:db/id block)
                                        :block/updated-at 2}])
          affected (worker-react/get-affected-queries-keys tx-report)]
      (is (some #{[:frontend.worker.react/block (:db/id block)]} affected))
      (is (not (some #{[:frontend.worker.react/block parent-id]} affected))))))

(deftest affected-keys-structural-change-refreshes-parent
  (testing "moving a block within its parent still refreshes the parent's children"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "Test"}
                  :blocks [{:block/title "First"} {:block/title "Second"}]}])
          first-block (db-test/find-block-by-content @conn "First")
          second-block (db-test/find-block-by-content @conn "Second")
          parent-id (:db/id (:block/parent first-block))
          tx-report (d/transact! conn [{:db/id (:db/id first-block)
                                        :block/order (db-order/gen-key (:block/order second-block) nil)}])
          affected (worker-react/get-affected-queries-keys tx-report)]
      (is (some #{[:frontend.worker.react/block parent-id]} affected)))))

(deftest affected-keys-journals-untouched-by-edits-inside-a-journal
  (testing "editing a block inside a journal doesn't refresh the journals list"
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:build/journal 20240101}
                  :blocks [{:block/title "Journal block"}]}])
          block (db-test/find-block-by-content @conn "Journal block")
          tx-report (d/transact! conn [{:db/id (:db/id block)
                                        :block/updated-at 2}])
          affected (worker-react/get-affected-queries-keys tx-report)]
      (is (not (some #{[:frontend.worker.react/journals]} affected))))))

(deftest affected-keys-journals-when-journal-created
  (testing "a new journal page still refreshes the journals list"
    (let [conn (db-test/create-conn-with-blocks [{:page {:build/journal 20240101}}])
          tx-report (d/transact! conn [{:block/uuid (random-uuid)
                                        :block/title "Jan 2nd, 2024"
                                        :block/name "jan 2nd, 2024"
                                        :block/journal-day 20240102
                                        :block/created-at 1
                                        :block/updated-at 1
                                        :block/tags #{:logseq.class/Journal :logseq.class/Page}}])
          affected (worker-react/get-affected-queries-keys tx-report)]
      (is (some #{[:frontend.worker.react/journals]} affected)))))
