(ns frontend.sidecar.integration-test
  "Integration tests for the JVM sidecar client.

   These tests require a running sidecar server on port 47632.

   Run with:
   1. Start sidecar: bb sidecar:run
   2. Run tests: bb sidecar:integration-test

   Test categories:
   - ^:integration - All integration tests (requires running sidecar)"
  (:require [clojure.test :refer [is use-fixtures]]
            [frontend.sidecar.client :as client]
            [frontend.test.helper :as test-helper :include-macros true :refer [deftest-async]]
            [promesa.core :as p]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:const TEST_PORT 47632)
(def ^:const TEST_GRAPH_ID "integration-test-graph")

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- cleanup-before []
  ;; Ensure clean state before test
  (client/disconnect!))

(defn- cleanup-after []
  ;; Ensure client is disconnected after each test
  (client/disconnect!))

(use-fixtures :each {:before cleanup-before
                     :after cleanup-after})

;; =============================================================================
;; Connection Tests
;; =============================================================================

(deftest-async ^:integration client-connects-test
  "Test that the client can connect to a running sidecar."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})]
      (is (client/connected?) "Client should be connected after connect!"))
    (client/disconnect!)
    (is (not (client/connected?)) "Client should not be connected after disconnect!")))

(deftest-async ^:integration client-connection-timeout-test
  "Test that connection times out when server is not running."
  (p/catch
    (p/let [_ (client/connect! {:port 47999  ; Unlikely to have anything listening
                                :timeout-ms 500})]
      (is false "Should have thrown timeout error"))
    (fn [e]
      (is (some? e) "Should throw an error")
      (is (or (= :timeout (-> e ex-data :type))
              (re-find #"ECONNREFUSED" (str e)))
          "Error should be timeout or connection refused"))))

;; =============================================================================
;; Graph Management Tests
;; =============================================================================

(deftest-async ^:integration create-graph-test
  "Test creating a graph on the sidecar."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            result (client/send-request :create-graph {:graph-id TEST_GRAPH_ID})]
      (is (= TEST_GRAPH_ID (:graph-id result)) "Should return the created graph ID"))))

(deftest-async ^:integration create-graph-idempotent-test
  "Test that creating the same graph twice returns the same graph."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "idempotent-test-" (random-uuid))
            result1 (client/send-request :create-graph {:graph-id graph-id})
            result2 (client/send-request :create-graph {:graph-id graph-id})]
      (is (= graph-id (:graph-id result1)))
      (is (= graph-id (:graph-id result2))
          "Creating the same graph twice should succeed"))))

;; =============================================================================
;; Query Tests
;; =============================================================================

(deftest-async ^:integration query-empty-graph-test
  "Test querying an empty graph returns empty results."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "query-test-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find ?e :where [?e :block/uuid]]})]
      (is (vector? result) "Result should be a vector")
      (is (empty? result) "Empty graph should return empty results"))))

(deftest-async ^:integration query-after-transact-test
  "Test that queries return data after transaction."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "query-transact-test-" (random-uuid))
            test-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            ;; Transact a block
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid test-uuid
                            :block/name "test-block"}]})
            ;; Query it back
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find ?name :where [?e :block/name ?name]]})]
      (is (= [["test-block"]] result)
          "Query should return transacted data"))))

;; =============================================================================
;; Transaction Tests
;; =============================================================================

(deftest-async ^:integration transact-basic-test
  "Test basic transaction returns tempids."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "transact-test-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            result (client/send-request :thread-api/transact
                     {:graph-id graph-id
                      :tx-data [{:block/uuid (random-uuid)
                                 :block/name "test-block"
                                 :block/content "Test content"}]})]
      (is (map? result) "Result should be a map")
      (is (contains? result :tempids) "Result should contain :tempids"))))

(deftest-async ^:integration transact-multiple-blocks-test
  "Test transacting multiple blocks at once."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "multi-transact-test-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            uuid1 (random-uuid)
            uuid2 (random-uuid)
            uuid3 (random-uuid)
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid uuid1 :block/name "block1"}
                           {:block/uuid uuid2 :block/name "block2"}
                           {:block/uuid uuid3 :block/name "block3"}]})
            result (client/send-request :thread-api/q
                     {:graph-id graph-id
                      :query '[:find (count ?e) :where [?e :block/name]]})]
      (is (= [[3]] result) "Should have 3 blocks after transacting 3"))))

;; =============================================================================
;; Pull Tests
;; =============================================================================

(deftest-async ^:integration pull-entity-test
  "Test pulling an entity by lookup ref."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "pull-test-" (random-uuid))
            test-uuid (random-uuid)
            _ (client/send-request :create-graph {:graph-id graph-id})
            _ (client/send-request :thread-api/transact
                {:graph-id graph-id
                 :tx-data [{:block/uuid test-uuid
                            :block/name "pulled-block"
                            :block/content "Pull test content"}]})
            result (client/send-request :thread-api/pull
                     {:graph-id graph-id
                      :selector '[:block/name :block/content]
                      :eid [:block/uuid test-uuid]})]
      (is (= "pulled-block" (:block/name result)))
      (is (= "Pull test content" (:block/content result))))))

;; =============================================================================
;; High-Level API Tests
;; =============================================================================

(deftest-async ^:integration invoke-api-test
  "Test the high-level invoke API."
  (p/do!
    (p/let [_ (client/connect! {:port TEST_PORT})
            graph-id (str "invoke-test-" (random-uuid))
            _ (client/send-request :create-graph {:graph-id graph-id})
            ;; Use invoke which mirrors the worker API
            result (client/invoke :thread-api/q graph-id '[:find ?e :where [?e :block/uuid]])]
      (is (vector? result) "invoke should return query results"))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest-async ^:integration query-nonexistent-graph-test
  "Test that querying a non-existent graph returns an error."
  (p/catch
    (p/do!
      (p/let [_ (client/connect! {:port TEST_PORT})
              _ (client/send-request :thread-api/q
                  {:graph-id "nonexistent-graph-12345"
                   :query '[:find ?e :where [?e :block/uuid]]})]
        (is false "Should have thrown error")))
    (fn [e]
      (is (some? e) "Should throw an error for nonexistent graph"))))

(deftest-async ^:integration request-when-disconnected-test
  "Test that requests when disconnected return an error."
  (p/catch
    (p/do!
      ;; Ensure not connected
      (client/disconnect!)
      (client/send-request :thread-api/q {:graph-id "any" :query '[:find ?e]}))
    (fn [e]
      (is (= :not-initialized (-> e ex-data :type))
          "Should get not-initialized error"))))
