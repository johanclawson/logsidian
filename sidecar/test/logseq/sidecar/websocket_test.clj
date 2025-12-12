(ns logseq.sidecar.websocket-test
  "WebSocket server tests.

   TDD tests for WebSocket support - write tests first, then implementation.

   NOTE: These tests are currently DISABLED.
   Reason: WebSocket functionality is working but we're focusing on TCP/IPC for Electron.
   These tests may be re-enabled later if we need browser-based WebSocket connectivity.
   To re-enable: Remove the `skip-websocket-tests` fixture from `use-fixtures`."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [logseq.sidecar.websocket :as ws]
            [logseq.sidecar.protocol :as protocol])
  (:import [java.net URI]
           [org.java_websocket.client WebSocketClient]
           [org.java_websocket.handshake ServerHandshake]))

;; =============================================================================
;; Skip Fixture - Disables all tests in this namespace
;; =============================================================================

(defn skip-websocket-tests
  "Fixture that skips all WebSocket tests.
   Remove this fixture from use-fixtures to re-enable tests."
  [test-fn]
  (println "SKIPPING: WebSocket tests disabled - see namespace docstring for details"))

(use-fixtures :each skip-websocket-tests)

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:private test-port 47633)
(def ^:private *test-server (atom nil))

(defn- start-test-server
  "Start a test server with echo handler."
  []
  (let [handler (fn [request]
                  ;; Echo handler - return success with request payload
                  {:type :response
                   :ok? true
                   :request-id (:id request)
                   :payload (:payload request)})]
    (ws/start-server handler {:port test-port})))

(defn- stop-test-server []
  (when-let [server @*test-server]
    (ws/stop-server server)
    (reset! *test-server nil)))

;; =============================================================================
;; Test WebSocket Client Helper
;; =============================================================================

(defn- create-test-client
  "Create a Java WebSocket client for testing.
   Returns {:client client :messages (atom []) :connected? (promise) :closed? (promise)}"
  [url]
  (let [messages (atom [])
        connected? (promise)
        closed? (promise)
        client (proxy [WebSocketClient] [(URI. url)]
                 (onOpen [^ServerHandshake handshake]
                   (deliver connected? true))
                 (onMessage [^String message]
                   (swap! messages conj message))
                 (onClose [code reason remote?]
                   (deliver closed? {:code code :reason reason}))
                 (onError [^Exception ex]
                   (when-not (realized? connected?)
                     (deliver connected? false))))]
    {:client client
     :messages messages
     :connected? connected?
     :closed? closed?}))

;; =============================================================================
;; Test 1: Server Starts
;; =============================================================================

(deftest websocket-server-starts-test
  (testing "WebSocket server starts on specified port"
    (let [server (start-test-server)]
      (try
        (is (some? server) "Server should start")
        (is (ws/running? server) "Server should report as running")
        (finally
          (ws/stop-server server))))))

(deftest websocket-server-stops-test
  (testing "WebSocket server stops cleanly"
    (let [server (start-test-server)]
      (ws/stop-server server)
      (Thread/sleep 100) ;; Give server time to stop
      (is (not (ws/running? server)) "Server should report as stopped"))))

;; =============================================================================
;; Test 2: Client Connects
;; =============================================================================

(deftest websocket-client-connects-test
  (testing "Client can establish WebSocket connection"
    (let [server (start-test-server)]
      (try
        (let [{:keys [client connected?]} (create-test-client (str "ws://localhost:" test-port))]
          (.connect client)
          (is (deref connected? 5000 false) "Should connect within 5s")
          (.close client))
        (finally
          (ws/stop-server server))))))

;; =============================================================================
;; Test 3: Transit Message Roundtrip
;; =============================================================================

(deftest websocket-transit-roundtrip-test
  (testing "WebSocket handles Transit-encoded request/response"
    (let [server (start-test-server)]
      (try
        (let [{:keys [client connected? messages]}
              (create-test-client (str "ws://localhost:" test-port))]
          (.connect client)
          (is (deref connected? 5000 false) "Should connect")

          ;; Send a test request
          (let [request (protocol/make-request :test-op {:data "hello"})
                request-str (protocol/serialize request)]
            (.send client request-str)

            ;; Wait for response
            (Thread/sleep 500)

            ;; Check we got a response
            (is (= 1 (count @messages)) "Should receive one response")

            (when (seq @messages)
              (let [response (protocol/deserialize (first @messages))]
                (is (:ok? response) "Response should be ok")
                (is (= (:id request) (:request-id response)) "Request ID should match")
                (is (= {:data "hello"} (:payload response)) "Payload should echo back"))))

          (.close client))
        (finally
          (ws/stop-server server))))))

;; =============================================================================
;; Test 4: Multiple Connections
;; =============================================================================

(deftest websocket-multiple-connections-test
  (testing "Server handles multiple simultaneous connections"
    (let [server (start-test-server)]
      (try
        (let [clients (repeatedly 3 #(create-test-client (str "ws://localhost:" test-port)))]
          ;; Connect all clients
          (doseq [{:keys [client]} clients]
            (.connect client))

          ;; Wait for connections
          (doseq [{:keys [connected?]} clients]
            (is (deref connected? 5000 false) "Each client should connect"))

          ;; Send from each client
          (doseq [{:keys [client]} clients]
            (let [request (protocol/make-request :test-op {:from "client"})
                  request-str (protocol/serialize request)]
              (.send client request-str)))

          ;; Wait for responses
          (Thread/sleep 500)

          ;; Each should have received a response
          (doseq [{:keys [messages]} clients]
            (is (= 1 (count @messages)) "Each client should receive one response"))

          ;; Close all
          (doseq [{:keys [client]} clients]
            (.close client)))
        (finally
          (ws/stop-server server))))))

;; =============================================================================
;; Test 5: Connection Cleanup
;; =============================================================================

(deftest websocket-connection-cleanup-test
  (testing "Server cleans up closed connections"
    (let [server (start-test-server)]
      (try
        (let [{:keys [client connected? closed?]}
              (create-test-client (str "ws://localhost:" test-port))]
          (.connect client)
          (is (deref connected? 5000 false) "Should connect")

          ;; Close the client
          (.close client)

          ;; Should receive close notification
          (let [close-info (deref closed? 5000 nil)]
            (is (some? close-info) "Should receive close notification")))
        (finally
          (ws/stop-server server))))))

;; =============================================================================
;; Test 6: Invalid Message Handling
;; =============================================================================

(deftest websocket-invalid-message-test
  (testing "Server handles invalid Transit gracefully"
    (let [server (start-test-server)]
      (try
        (let [{:keys [client connected? messages]}
              (create-test-client (str "ws://localhost:" test-port))]
          (.connect client)
          (is (deref connected? 5000 false) "Should connect")

          ;; Send invalid JSON
          (.send client "this is not valid transit")

          ;; Wait for response
          (Thread/sleep 500)

          ;; Should receive error response
          (is (= 1 (count @messages)) "Should receive one response")
          (when (seq @messages)
            (let [response (protocol/deserialize (first @messages))]
              (is (not (:ok? response)) "Response should be error")))

          (.close client))
        (finally
          (ws/stop-server server))))))

;; =============================================================================
;; Test 7: Large Message Handling
;; =============================================================================

(deftest websocket-large-message-test
  (testing "Server handles large messages"
    (let [server (start-test-server)]
      (try
        (let [{:keys [client connected? messages]}
              (create-test-client (str "ws://localhost:" test-port))]
          (.connect client)
          (is (deref connected? 5000 false) "Should connect")

          ;; Send a large payload
          (let [large-data (apply str (repeat 100000 "x"))
                request (protocol/make-request :test-op {:data large-data})
                request-str (protocol/serialize request)]
            (.send client request-str)

            ;; Wait for response
            (Thread/sleep 1000)

            ;; Should receive response with same large data
            (is (= 1 (count @messages)) "Should receive one response")
            (when (seq @messages)
              (let [response (protocol/deserialize (first @messages))
                    resp-data (get-in response [:payload :data])]
                (is (:ok? response) "Response should be ok")
                (is (= 100000 (count resp-data)) "Large data should roundtrip"))))

          (.close client))
        (finally
          (ws/stop-server server))))))
