(ns frontend.db.transact
  "Provides async transact for use with ldb/transact!"
  (:require [clojure.core.async :as async]
            [clojure.core.async.interop :refer [p->c]]
            [frontend.common.async-util :include-macros true :refer [<?]]
            [frontend.state :as state]
            [frontend.util :as util]
            [lambdaisland.glogi :as log]
            [logseq.outliner.op :as outliner-op]
            [promesa.core :as p]))

(defn worker-call
  [request-f]
  (let [response (p/deferred)]
    (async/go
      (let [result (<? (p->c (request-f)))]
        (if (:ex-data result)
          (do
            (log/error :worker-request-failed result)
            (p/reject! response result))
          (p/resolve! response result))))
    response))

(defn- ensure-local-op-tx-id
  [tx-meta]
  (cond-> (or tx-meta {})
    (nil? (:db-sync/tx-id tx-meta))
    (assoc :db-sync/tx-id (random-uuid))))

(defn- with-pending-editor-info
  "Send the editor info along with the transaction rather than in its own worker
  call. Awaiting a separate round trip before every transaction doubled the
  round trips per edit, and the worker strips this key back out before
  transacting, so tx-meta is unchanged."
  [tx-meta]
  (assoc tx-meta :undo-redo/pending-editor-info (state/get-editor-info)))

(defn transact [worker-transact repo tx-data tx-meta]
  (let [tx-meta' (-> tx-meta
                     ensure-local-op-tx-id
                     with-pending-editor-info
                     (assoc
                        ;; not from remote (rtc)
                      :local-tx? true))]
    (worker-call (fn async-request []
                   (worker-transact repo tx-data tx-meta')))))

(defn apply-outliner-ops
  [conn ops opts]
  (when (seq ops)
    (if util/node-test?
      (outliner-op/apply-ops! conn ops opts)
      (let [opts' (-> opts
                      ensure-local-op-tx-id
                      with-pending-editor-info
                      (assoc
                       :client-id (:client-id @state/state)
                       :local-tx? true))]
        (frontend.db.transact/worker-call
         (fn []
           (state/<invoke-db-worker
            :thread-api/apply-outliner-ops
            (state/get-current-repo)
            ops
            opts')))))))
