(ns logseq.sidecar.pipes-test
  "Phase 2: Named Pipes / IPC tests.

   Tests for cross-platform TCP socket communication.
   (Named Pipes optimization can be added later)"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async :refer [<!! >!! chan go timeout alts!!]]
            [logseq.sidecar.pipes :as pipes]
            [logseq.sidecar.protocol :as protocol]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-pipe-name* nil)
(def ^:dynamic *test-port* nil)

(def port-counter (atom 48100))

(defn next-test-port []
  (swap! port-counter inc))

(defn with-unique-pipe-name [f]
  (binding [*test-pipe-name* (str "logsidian-test-" (System/currentTimeMillis))
            *test-port* (next-test-port)]
    (f)))

(use-fixtures :each with-unique-pipe-name)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- echo-handler
  "Simple handler that echoes back requests"
  [msg]
  (protocol/make-response (:op msg) {:echo (:payload msg)} (:id msg)))

;; =============================================================================
;; Pipe Name Tests
;; =============================================================================

(deftest pipe-name-generation-test
  (testing "Pipe name is correctly formatted"
    (let [graph-name "my-graph"
          pipe-name (pipes/make-pipe-name graph-name)]
      (is (string? pipe-name))
      (is (clojure.string/includes? pipe-name "logsidian")
          "Pipe name should include identifier")))

  (testing "Graph names with special characters are normalized"
    (let [pipe-name (pipes/make-pipe-name "My Graph (Test)")]
      (is (not (clojure.string/includes? pipe-name " "))
          "Spaces should be removed/replaced")
      (is (not (clojure.string/includes? pipe-name "("))
          "Parentheses should be removed"))))

;; =============================================================================
;; Server Lifecycle Tests
;; =============================================================================

(deftest server-start-stop-test
  (testing "Server starts and stops cleanly"
    (let [port (next-test-port)
          server (pipes/start-server *test-pipe-name* echo-handler {:port port})]
      (is (some? server) "Server should be created")
      (is (pipes/server-running? server) "Server should be running")
      (pipes/stop-server server)
      (Thread/sleep 100)
      (is (not (pipes/server-running? server)) "Server should not be running after stop"))))

(deftest double-start-prevented-test
  (testing "Starting server twice on same port fails gracefully"
    (let [port (next-test-port)
          server1 (pipes/start-server *test-pipe-name* echo-handler {:port port})]
      (try
        (let [server2 (pipes/start-server *test-pipe-name* echo-handler {:port port})]
          (is (or (nil? server2)
                  (not (pipes/server-running? server2)))
              "Second server should fail or not run")
          (when server2 (pipes/stop-server server2)))
        (finally
          (pipes/stop-server server1))))))

;; =============================================================================
;; Request/Response Tests
;; =============================================================================

(deftest request-response-test
  (testing "Client sends request, server responds"
    (let [port (next-test-port)
          server (pipes/start-server *test-pipe-name* echo-handler {:port port})]
      (try
        (let [client (pipes/connect-client *test-pipe-name* {:port port})]
          (try
            (is (some? client) "Client should connect")
            (is (pipes/client-connected? client) "Client should be connected")

            ;; Send a request and get response
            (let [request (protocol/make-request :test {:data "hello"})
                  response-chan (pipes/send-request client request)
                  [response _] (alts!! [response-chan (timeout 5000)])]
              (is (some? response) "Should receive response")
              (is (:ok? response) "Response should be ok")
              (is (= {:data "hello"} (get-in response [:payload :echo]))
                  "Response should echo request"))
            (finally
              (pipes/disconnect-client client))))
        (finally
          (pipes/stop-server server))))))

(deftest multiple-requests-test
  (testing "Multiple sequential requests work"
    (let [port (next-test-port)
          server (pipes/start-server *test-pipe-name* echo-handler {:port port})]
      (try
        (let [client (pipes/connect-client *test-pipe-name* {:port port})]
          (try
            (doseq [i (range 5)]
              (let [request (protocol/make-request :test {:index i})
                    response-chan (pipes/send-request client request)
                    [response _] (alts!! [response-chan (timeout 5000)])]
                (is (some? response) (str "Should receive response " i))
                (is (= i (get-in response [:payload :echo :index]))
                    (str "Response should have correct index " i))))
            (finally
              (pipes/disconnect-client client))))
        (finally
          (pipes/stop-server server))))))

;; =============================================================================
;; Transit Integration Test
;; =============================================================================

(deftest transit-complex-data-test
  (testing "Complex data survives transit roundtrip over socket"
    (let [port (next-test-port)
          server (pipes/start-server *test-pipe-name* echo-handler {:port port})]
      (try
        (let [client (pipes/connect-client *test-pipe-name* {:port port})]
          (try
            (let [complex-data {:query '[:find ?e ?title
                                         :where
                                         [?e :block/title ?title]]
                                :uuid #uuid "550e8400-e29b-41d4-a716-446655440000"
                                :unicode "日本語 🚀 emoji"
                                :nested {:a {:b {:c [1 2 3]}}}}
                  request (protocol/make-request :query complex-data)
                  response-chan (pipes/send-request client request)
                  [response _] (alts!! [response-chan (timeout 5000)])]
              (is (some? response) "Should receive response")
              (is (= complex-data (get-in response [:payload :echo]))
                  "Complex data should survive roundtrip"))
            (finally
              (pipes/disconnect-client client))))
        (finally
          (pipes/stop-server server))))))

;; =============================================================================
;; Connection Timeout Test
;; =============================================================================

(deftest connection-timeout-test
  (testing "Client connection times out when server not available"
    (let [fake-port 59999  ; Port where nothing is listening
          result (pipes/connect-client "fake-pipe" {:port fake-port :timeout-ms 1000})]
      (is (or (nil? result)
              (not (pipes/client-connected? result)))
          "Connection to unavailable port should fail"))))
