(ns frontend.sidecar.ipc-test
  "Unit tests for sidecar IPC communication layer.

   These tests verify that the renderer-side sidecar client correctly
   calls IPC methods to communicate with the main process. The tests
   mock the IPC layer to test in isolation.

   Run with: yarn cljs:run-test -n frontend.sidecar.ipc-test"
  (:require [cljs.test :refer [deftest is testing use-fixtures async]]
            [frontend.sidecar.client :as client]
            [frontend.sidecar.spawn :as spawn]
            [promesa.core :as p]))

;; =============================================================================
;; Test Infrastructure - IPC Mocking
;; =============================================================================

(def ^:private *ipc-calls
  "Atom to track all IPC calls made during tests."
  (atom []))

(def ^:private *ipc-responses
  "Atom with mock responses for IPC operations.
   Map of operation keyword -> response value."
  (atom {}))

(def ^:private *original-ipc-fn
  "Store for original IPC function to restore after tests."
  (atom nil))

(defn- mock-ipc
  "Mock IPC function that records calls and returns mock responses."
  [& args]
  (swap! *ipc-calls conj (vec args))
  (let [op (first args)
        response (get @*ipc-responses op {:ok true})]
    (if (instance? js/Error response)
      (p/rejected response)
      (p/resolved response))))

(defn- setup-ipc-mock!
  "Replace the real IPC function with our mock."
  []
  ;; Store original if exists
  (when (exists? js/window.apis)
    (reset! *original-ipc-fn (.-doAction js/window.apis)))
  ;; Create mock window.apis if doesn't exist
  (when-not (exists? js/window.apis)
    (set! js/window.apis #js {}))
  ;; Set mock doAction
  (set! (.-doAction js/window.apis)
        (fn [args]
          (apply mock-ipc (js->clj args :keywordize-keys true)))))

(defn- teardown-ipc-mock!
  "Restore original IPC function."
  []
  (when @*original-ipc-fn
    (set! (.-doAction js/window.apis) @*original-ipc-fn)
    (reset! *original-ipc-fn nil)))

(defn- reset-mock-state!
  "Reset mock state between tests."
  []
  (reset! *ipc-calls [])
  (reset! *ipc-responses {}))

(defn- set-mock-response!
  "Set a mock response for an IPC operation."
  [op response]
  (swap! *ipc-responses assoc op response))

(defn- get-ipc-calls
  "Get all recorded IPC calls."
  []
  @*ipc-calls)

(defn- get-ipc-call
  "Get the nth IPC call (0-indexed)."
  [n]
  (nth @*ipc-calls n nil))

(defn- ipc-called?
  "Check if an IPC operation was called."
  [op]
  (some #(= op (first %)) @*ipc-calls))

(defn- ipc-call-count
  "Count how many times an IPC operation was called."
  [op]
  (count (filter #(= op (first %)) @*ipc-calls)))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(use-fixtures :each
  {:before (fn []
             (reset-mock-state!)
             (setup-ipc-mock!))
   :after (fn []
            (teardown-ipc-mock!))})

;; =============================================================================
;; Client Tests - Connection Management
;; =============================================================================

(deftest ^:ipc connect-calls-ipc-test
  (testing "connect! calls :sidecar/connect IPC"
    (async done
      (p/let [_ (client/connect!)]
        (is (ipc-called? :sidecar/connect)
            "connect! should call :sidecar/connect IPC")
        (done)))))

(deftest ^:ipc connect-with-options-test
  (testing "connect! passes port and timeout options"
    (async done
      (p/let [_ (client/connect! {:port 12345 :timeout-ms 5000})]
        (let [call (get-ipc-call 0)]
          (is (= :sidecar/connect (first call))
              "Should call :sidecar/connect")
          (is (= {:port 12345 :timeout-ms 5000} (second call))
              "Should pass port and timeout options"))
        (done)))))

(deftest ^:ipc connect-default-options-test
  (testing "connect! uses default port and timeout when not specified"
    (async done
      (p/let [_ (client/connect!)]
        (let [call (get-ipc-call 0)
              opts (second call)]
          (is (= client/DEFAULT_PORT (:port opts))
              "Should use default port")
          (is (= client/DEFAULT_TIMEOUT_MS (:timeout-ms opts))
              "Should use default timeout"))
        (done)))))

(deftest ^:ipc disconnect-calls-ipc-test
  (testing "disconnect! calls :sidecar/disconnect IPC"
    (async done
      (p/let [_ (client/disconnect!)]
        (is (ipc-called? :sidecar/disconnect)
            "disconnect! should call :sidecar/disconnect IPC")
        (done)))))

(deftest ^:ipc connected-calls-status-test
  (testing "connected? calls :sidecar/status and returns connected flag"
    (async done
      (set-mock-response! :sidecar/status {:connected? true :running? true :port 47632})
      (p/let [result (client/connected?)]
        (is (ipc-called? :sidecar/status)
            "connected? should call :sidecar/status IPC")
        (is (true? result)
            "Should return true when connected")
        (done)))))

(deftest ^:ipc connected-false-test
  (testing "connected? returns false when not connected"
    (async done
      (set-mock-response! :sidecar/status {:connected? false :running? false :port 47632})
      (p/let [result (client/connected?)]
        (is (false? result)
            "Should return false when not connected")
        (done)))))

;; =============================================================================
;; Client Tests - Request/Response
;; =============================================================================

(deftest ^:ipc send-request-calls-ipc-test
  (testing "send-request calls :sidecar/request IPC"
    (async done
      (set-mock-response! :sidecar/request {:result "test-data"})
      (p/let [_ (client/send-request :thread-api/q {:query '[:find ?e :where [?e :block/name]]})]
        (is (ipc-called? :sidecar/request)
            "send-request should call :sidecar/request IPC")
        (done)))))

(deftest ^:ipc send-request-payload-test
  (testing "send-request passes operation and payload correctly"
    (async done
      (set-mock-response! :sidecar/request {:result []})
      (p/let [_ (client/send-request :thread-api/transact
                                     {:repo "test-repo" :tx-data [{:block/name "test"}]})]
        (let [call (get-ipc-call 0)]
          (is (= :sidecar/request (first call))
              "Should call :sidecar/request")
          (is (= :thread-api/transact (second call))
              "Should pass operation as second arg")
          (is (= {:repo "test-repo" :tx-data [{:block/name "test"}]} (nth call 2))
              "Should pass payload as third arg"))
        (done)))))

(deftest ^:ipc send-request-timeout-option-test
  (testing "send-request passes timeout option"
    (async done
      (set-mock-response! :sidecar/request {:result []})
      (p/let [_ (client/send-request :thread-api/q {:query []} {:timeout-ms 60000})]
        (let [call (get-ipc-call 0)
              opts (nth call 3)]
          (is (= 60000 (:timeout-ms opts))
              "Should pass custom timeout"))
        (done)))))

(deftest ^:ipc send-request-returns-response-test
  (testing "send-request returns the response from IPC"
    (async done
      (set-mock-response! :sidecar/request {:entities [[1] [2] [3]]})
      (p/let [result (client/send-request :thread-api/q {:query []})]
        (is (= {:entities [[1] [2] [3]]} result)
            "Should return the response from IPC")
        (done)))))

;; =============================================================================
;; Client Tests - Worker Function
;; =============================================================================

(deftest ^:ipc create-worker-fn-signature-test
  (testing "create-worker-fn returns a function with correct arity"
    (let [worker-fn (client/create-worker-fn)]
      (is (fn? worker-fn)
          "Should return a function"))))

(deftest ^:ipc worker-fn-calls-send-request-test
  (testing "worker function calls send-request with args"
    (async done
      (set-mock-response! :sidecar/request {:result "ok"})
      (let [worker-fn (client/create-worker-fn)]
        (p/let [_ (worker-fn :thread-api/q false {:query []})]
          (is (ipc-called? :sidecar/request)
              "Worker fn should call :sidecar/request")
          (let [call (get-ipc-call 0)]
            (is (= :thread-api/q (second call))
                "Should pass operation keyword"))
          (done))))))

(deftest ^:ipc worker-fn-direct-pass-false-test
  (testing "worker function wraps args when direct-pass? is false"
    (async done
      (set-mock-response! :sidecar/request {:result "ok"})
      (let [worker-fn (client/create-worker-fn)]
        (p/let [_ (worker-fn :thread-api/transact false "repo" [{:block/name "test"}] {:meta true})]
          (let [call (get-ipc-call 0)
                payload (nth call 2)]
            (is (= {:args ["repo" [{:block/name "test"}] {:meta true}]} payload)
                "Should wrap args in :args key when direct-pass? is false"))
          (done))))))

(deftest ^:ipc worker-fn-direct-pass-true-test
  (testing "worker function marks direct-pass? in payload"
    (async done
      (set-mock-response! :sidecar/request {:result "ok"})
      (let [worker-fn (client/create-worker-fn)]
        (p/let [_ (worker-fn :thread-api/export-db true "repo")]
          (let [call (get-ipc-call 0)
                payload (nth call 2)]
            (is (= {:args ["repo"] :direct-pass? true} payload)
                "Should include :direct-pass? true in payload"))
          (done))))))

;; =============================================================================
;; Spawn Tests
;; =============================================================================

(deftest ^:ipc spawn-start-calls-ipc-test
  (testing "spawn/start! calls :sidecar/start IPC"
    (async done
      (set-mock-response! :sidecar/start {:status :running :port 47632 :pid 12345})
      (p/let [_ (spawn/start!)]
        (is (ipc-called? :sidecar/start)
            "start! should call :sidecar/start IPC")
        (done)))))

(deftest ^:ipc spawn-start-with-port-test
  (testing "spawn/start! passes port option"
    (async done
      (set-mock-response! :sidecar/start {:status :running :port 9999})
      (p/let [_ (spawn/start! {:port 9999})]
        (let [call (get-ipc-call 0)]
          (is (= {:port 9999} (second call))
              "Should pass port option"))
        (done)))))

(deftest ^:ipc spawn-stop-calls-ipc-test
  (testing "spawn/stop! calls :sidecar/stop IPC"
    (async done
      (p/let [_ (spawn/stop!)]
        (is (ipc-called? :sidecar/stop)
            "stop! should call :sidecar/stop IPC")
        (done)))))

(deftest ^:ipc spawn-running-calls-status-test
  (testing "spawn/running? calls :sidecar/status and returns running flag"
    (async done
      (set-mock-response! :sidecar/status {:connected? true :running? true :port 47632})
      (p/let [result (spawn/running?)]
        (is (ipc-called? :sidecar/status)
            "running? should call :sidecar/status IPC")
        (is (true? result)
            "Should return true when running")
        (done)))))

(deftest ^:ipc spawn-get-port-test
  (testing "spawn/get-port returns default port"
    (is (= spawn/DEFAULT_PORT (spawn/get-port))
        "Should return default port constant")))

(deftest ^:ipc spawn-register-shutdown-handler-noop-test
  (testing "spawn/register-shutdown-handler! is a no-op"
    (is (nil? (spawn/register-shutdown-handler!))
        "Should return nil (no-op in renderer)")))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest ^:ipc ipc-error-propagates-test
  (testing "IPC errors are propagated to caller"
    (async done
      (set-mock-response! :sidecar/request (js/Error. "Connection failed"))
      (-> (client/send-request :thread-api/q {:query []})
          (p/catch (fn [err]
                     (is (instance? js/Error err)
                         "Should propagate error")
                     (is (= "Connection failed" (.-message err))
                         "Should preserve error message")
                     (done)))))))
