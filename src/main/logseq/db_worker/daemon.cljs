(ns logseq.db-worker.daemon
  "Shared db-worker-node health and discovery helpers."
  (:require ["http" :as http]
            [clojure.string :as string]
            [logseq.common.defkeywords :refer [defkeyword]]
            [promesa.core :as p]))

(defkeyword :ownership-protocol "The graph ownership protocol published by a Node worker.")

(def ^:private valid-owner-sources
  #{:cli :electron :unknown})

(defn normalize-owner-source
  [owner-source]
  (let [owner-source (cond
                       (keyword? owner-source) owner-source
                       (string? owner-source) (keyword (string/trim owner-source))
                       :else :unknown)]
    (if (contains? valid-owner-sources owner-source)
      owner-source
      :unknown)))

(defn pid-status
  [pid]
  (when (number? pid)
    (try
      (.kill js/process pid 0)
      :alive
      (catch :default e
        (case (.-code e)
          "ESRCH" :not-found
          "EPERM" :no-permission
          :error)))))

(defn http-request
  [{:keys [method host port path headers body timeout-ms]}]
  (p/create
   (fn [resolve reject]
     (let [timeout-ms (or timeout-ms 5000)
           start-ms (js/Date.now)
           req (.request
                http
                #js {:method method
                     :hostname host
                     :port port
                     :path path
                     :headers (clj->js (or headers {}))}
                (fn [^js res]
                  (let [chunks (array)]
                    (.on res "data" (fn [chunk] (.push chunks chunk)))
                    (.on res "end" (fn []
                                     (let [buf (js/Buffer.concat chunks)]
                                       (resolve {:status (.-statusCode res)
                                                 :body (.toString buf "utf8")
                                                 :elapsed-ms (- (js/Date.now) start-ms)}))))
                    (.on res "error" reject))))
           timeout-id (js/setTimeout
                       (fn []
                         (.destroy req)
                         (reject (ex-info "request timeout" {:code :timeout})))
                       timeout-ms)]
       (.on req "error" (fn [err]
                          (js/clearTimeout timeout-id)
                          (reject err)))
       (when body
         (.write req body))
       (.end req)
       (.on req "response" (fn [_]
                             (js/clearTimeout timeout-id)))))))

(defn ready?
  [{:keys [host port]}]
  (-> (p/let [{:keys [status]} (http-request {:method "GET"
                                              :host host
                                              :port port
                                              :path "/healthz"
                                              :timeout-ms 1000})]
        (= 200 status))
      (p/catch (fn [_] false))))

(def ^:private busy-probe-max-timeout-ms 5000)

(defn- probe-health
  "One /healthz probe, classified. A worker whose event loop is blocked (e.g.
  while it prepares a graph upload) still has its socket accepted by the kernel
  but answers late, so a request timeout means :busy. A refused or reset
  connection means :unreachable."
  [{:keys [host port]} timeout-ms]
  (-> (p/let [{:keys [status]} (http-request {:method "GET"
                                              :host host
                                              :port port
                                              :path "/healthz"
                                              :timeout-ms timeout-ms})]
        (if (= 200 status) :ready :not-ready))
      (p/catch (fn [error]
                 (if (= :timeout (:code (ex-data error)))
                   :busy
                   :unreachable)))))

(defn responsive?
  "Like `ready?`, but keeps waiting while the worker is busy rather than dead.
  Restarting a worker only because it answered /healthz late kills whatever
  long-running request it is serving, such as a graph upload. Gives up and
  returns false once a busy worker has not answered for `busy-budget-ms`."
  [lock {:keys [busy-budget-ms]}]
  (let [deadline (+ (js/Date.now) busy-budget-ms)]
    (p/loop [timeout-ms 1000]
      (p/let [result (probe-health lock timeout-ms)
              remaining-ms (- deadline (js/Date.now))]
        (case result
          :ready true
          :busy (if (pos? remaining-ms)
                  (p/recur (min busy-probe-max-timeout-ms remaining-ms))
                  false)
          false)))))
