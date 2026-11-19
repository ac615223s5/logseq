(ns logseq.db-worker.daemon-test
  (:require ["http" :as http]
            [cljs.test :refer [async deftest is testing]]
            [logseq.db-worker.daemon :as daemon]
            [promesa.core :as p]))

(deftest ready-uses-healthz-200-only
  (async done
    (-> (p/with-redefs [daemon/http-request (fn [{:keys [path]}]
                                              (is (= "/healthz" path))
                                              (p/resolved {:status 200
                                                           :body "{\"status\":\"ready\"}"}))]
          (daemon/ready? {:host "127.0.0.1" :port 7001}))
        (p/then (fn [result]
                  (is (= true result))))
        (p/catch (fn [e]
                   (is false (str "unexpected error: " e))))
        (p/finally done))))

(deftest responsive-waits-out-a-busy-worker
  (async done
    (let [timeouts (atom [])
          responses (atom [:timeout :timeout 200])]
      (-> (p/with-redefs [daemon/http-request (fn [{:keys [path timeout-ms]}]
                                                (is (= "/healthz" path))
                                                (swap! timeouts conj timeout-ms)
                                                (let [response (first @responses)]
                                                  (swap! responses rest)
                                                  (if (= :timeout response)
                                                    (p/rejected (ex-info "request timeout" {:code :timeout}))
                                                    (p/resolved {:status response :body "{}"}))))]
            (daemon/responsive? {:host "127.0.0.1" :port 7001} {:busy-budget-ms 30000}))
          (p/then (fn [result]
                    (is (= true result))
                    (is (= 1000 (first @timeouts)))
                    (is (= 3 (count @timeouts)))
                    (is (every? #(<= % 5000) (rest @timeouts)))))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally done)))))

(deftest responsive-fails-fast-when-worker-is-unreachable
  (async done
    (let [calls (atom 0)]
      (-> (p/with-redefs [daemon/http-request (fn [_]
                                                (swap! calls inc)
                                                (p/rejected (ex-info "connect ECONNREFUSED" {:code "ECONNREFUSED"})))]
            (daemon/responsive? {:host "127.0.0.1" :port 7001} {:busy-budget-ms 30000}))
          (p/then (fn [result]
                    (is (= false result))
                    (is (= 1 @calls))))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally done)))))

(deftest responsive-gives-up-on-a-worker-busy-past-the-budget
  (async done
    (-> (p/with-redefs [daemon/http-request (fn [_]
                                              (p/rejected (ex-info "request timeout" {:code :timeout})))]
          (daemon/responsive? {:host "127.0.0.1" :port 7001} {:busy-budget-ms 0}))
        (p/then (fn [result]
                  (is (= false result))))
        (p/catch (fn [e]
                   (is false (str "unexpected error: " e))))
        (p/finally done))))

(deftest responsive-treats-503-as-not-ready
  (async done
    (-> (p/with-redefs [daemon/http-request (fn [_]
                                              (p/resolved {:status 503 :body "{}"}))]
          (daemon/responsive? {:host "127.0.0.1" :port 7001} {:busy-budget-ms 30000}))
        (p/then (fn [result]
                  (is (= false result))))
        (p/catch (fn [e]
                   (is false (str "unexpected error: " e))))
        (p/finally done))))

(deftest responsive-waits-for-a-real-server-that-answers-late
  (testing "a server answering /healthz after the 1s probe timeout is busy, not dead"
    (async done
      (let [server (http/createServer
                    (fn [_req ^js res]
                      (js/setTimeout (fn []
                                       (.writeHead res 200)
                                       (.end res "{}"))
                                     1500)))]
        (.listen server 0 "127.0.0.1"
                 (fn []
                   (let [port (.-port (.address server))]
                     (-> (p/let [ready? (daemon/ready? {:host "127.0.0.1" :port port})
                                 responsive? (daemon/responsive? {:host "127.0.0.1" :port port}
                                                                 {:busy-budget-ms 30000})]
                           (is (= false ready?) "the 1s readiness probe times out")
                           (is (= true responsive?)))
                         (p/catch (fn [e]
                                    (is false (str "unexpected error: " e))))
                         (p/finally (fn []
                                      (.close server)
                                      (done)))))))))))
