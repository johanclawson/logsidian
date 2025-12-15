(ns logseq.sidecar.server-test
  "Tests for the sidecar server with DataScript backend."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [logseq.sidecar.server :as server]
            [logseq.sidecar.pipes :as pipes]
            [logseq.sidecar.protocol :as protocol]
            [datascript.core :as d]
            [clojure.core.async :refer [<!! timeout alts!!]]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-port* nil)
(def port-counter (atom 49100))

(defn next-test-port []
  (swap! port-counter inc))

(defn with-test-server [f]
  (binding [*test-port* (next-test-port)]
    (f)))

(use-fixtures :each with-test-server)

;; =============================================================================
;; Server Lifecycle Tests
;; =============================================================================

(deftest ^:server server-starts-and-stops-test
  (testing "Server starts and stops cleanly"
    (let [srv (server/start-server {:port *test-port*})]
      (is (some? srv) "Server should start")
      (is (server/running? srv) "Server should be running")
      (server/stop-server srv)
      (is (not (server/running? srv)) "Server should be stopped"))))

(deftest ^:server server-handles-multiple-graphs-test
  (testing "Server can manage multiple graph connections"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        ;; Create two graphs
        (let [graph1-id (server/create-graph srv "test-graph-1" {})
              graph2-id (server/create-graph srv "test-graph-2" {})]
          (is (some? graph1-id) "First graph should be created")
          (is (some? graph2-id) "Second graph should be created")
          (is (not= graph1-id graph2-id) "Graph IDs should be different")

          ;; Both graphs should be accessible
          (is (server/graph-exists? srv graph1-id))
          (is (server/graph-exists? srv graph2-id))

          ;; Remove one graph
          (server/remove-graph srv graph1-id)
          (is (not (server/graph-exists? srv graph1-id)) "Removed graph should not exist")
          (is (server/graph-exists? srv graph2-id) "Other graph should still exist"))
        (finally
          (server/stop-server srv))))))

;; =============================================================================
;; DataScript Query Tests
;; =============================================================================

(deftest ^:server query-empty-db-test
  (testing "Query on empty database returns empty results"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        (let [graph-id (server/create-graph srv "empty-graph" {})]
          (let [result (server/query srv graph-id
                                     '[:find ?e ?name
                                       :where [?e :block/name ?name]]
                                     [])]
            (is (empty? result) "Empty DB should return empty results")))
        (finally
          (server/stop-server srv))))))

(deftest ^:server transact-and-query-test
  (testing "Transact data and query it back"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        (let [graph-id (server/create-graph srv "tx-graph" {})]
          ;; Transact some blocks
          (server/transact! srv graph-id
                            [{:block/uuid (random-uuid)
                              :block/name "page-1"
                              :block/original-name "Page 1"}
                             {:block/uuid (random-uuid)
                              :block/name "page-2"
                              :block/original-name "Page 2"}])

          ;; Query the data back
          (let [result (server/query srv graph-id
                                     '[:find ?name
                                       :where [?e :block/name ?name]]
                                     [])]
            (is (= 2 (count result)) "Should find 2 pages")
            (is (= #{["page-1"] ["page-2"]} (set result)))))
        (finally
          (server/stop-server srv))))))

(deftest ^:server pull-entity-test
  (testing "Pull single entity by ID"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        (let [graph-id (server/create-graph srv "pull-graph" {})
              block-uuid (random-uuid)]
          ;; Transact a block
          (server/transact! srv graph-id
                            [{:block/uuid block-uuid
                              :block/name "test-page"
                              :block/original-name "Test Page"
                              :block/type "page"}])

          ;; Pull by UUID
          (let [entity (server/pull srv graph-id
                                    '[:block/name :block/original-name :block/type]
                                    [:block/uuid block-uuid])]
            (is (= "test-page" (:block/name entity)))
            (is (= "Test Page" (:block/original-name entity)))
            (is (= "page" (:block/type entity)))))
        (finally
          (server/stop-server srv))))))

(deftest ^:server pull-many-test
  (testing "Pull multiple entities"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        (let [graph-id (server/create-graph srv "pull-many-graph" {})
              uuid1 (random-uuid)
              uuid2 (random-uuid)]
          ;; Transact blocks
          (server/transact! srv graph-id
                            [{:block/uuid uuid1 :block/name "page-a"}
                             {:block/uuid uuid2 :block/name "page-b"}])

          ;; Pull many
          (let [entities (server/pull-many srv graph-id
                                           '[:block/name]
                                           [[:block/uuid uuid1]
                                            [:block/uuid uuid2]])]
            (is (= 2 (count entities)))
            (is (= #{#:block{:name "page-a"} #:block{:name "page-b"}}
                   (set entities)))))
        (finally
          (server/stop-server srv))))))

;; =============================================================================
;; IPC Integration Tests
;; =============================================================================

(deftest ^:server ipc-query-test
  (testing "Query through IPC client with :args payload format"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        (let [graph-id (server/create-graph srv "ipc-graph" {})
              _ (server/transact! srv graph-id
                                  [{:block/uuid (random-uuid)
                                    :block/name "ipc-page"}])
              ;; Connect client
              client (pipes/connect-client "test" {:port *test-port*})]
          (try
            (is (pipes/client-connected? client))

            ;; Send query request via IPC using :args format (like real client)
            ;; Client sends: {:args [repo inputs]} where inputs = [query & query-inputs]
            (let [request (protocol/make-request
                           :thread-api/q
                           {:args [graph-id ['[:find ?name :where [?e :block/name ?name]]]]})
                  response-chan (pipes/send-request client request {:timeout-ms 5000})
                  [response _] (alts!! [response-chan (timeout 5000)])]
              (is (some? response) "Should receive response")
              (is (:ok? response) "Response should be ok")
              (is (= #{["ipc-page"]} (set (:payload response)))))
            (finally
              (pipes/disconnect-client client))))
        (finally
          (server/stop-server srv))))))

(deftest ^:server ipc-transact-test
  (testing "Transact through IPC client with :args payload format"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        (let [graph-id (server/create-graph srv "ipc-tx-graph" {})
              client (pipes/connect-client "test" {:port *test-port*})]
          (try
            ;; Send transact request via IPC using :args format
            ;; Client sends: {:args [repo tx-data tx-meta context]}
            (let [block-uuid (random-uuid)
                  tx-data [{:block/uuid block-uuid :block/name "ipc-created"}]
                  request (protocol/make-request
                           :thread-api/transact
                           {:args [graph-id tx-data nil nil]})
                  response-chan (pipes/send-request client request {:timeout-ms 5000})
                  [response _] (alts!! [response-chan (timeout 5000)])]
              (is (some? response) "Should receive response")
              (is (:ok? response) "Response should be ok")

              ;; Verify via direct query
              (let [result (server/query srv graph-id
                                         '[:find ?name :where [?e :block/name ?name]]
                                         [])]
                (is (= #{["ipc-created"]} (set result)))))
            (finally
              (pipes/disconnect-client client))))
        (finally
          (server/stop-server srv))))))

(deftest ^:server ipc-pull-test
  (testing "Pull through IPC client with :args payload format"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        (let [graph-id (server/create-graph srv "ipc-pull-graph" {})
              block-uuid (random-uuid)
              _ (server/transact! srv graph-id
                                  [{:block/uuid block-uuid
                                    :block/name "pullable"
                                    :block/content "Hello world"}])
              client (pipes/connect-client "test" {:port *test-port*})]
          (try
            ;; Send pull request via IPC using :args format
            ;; Client sends: {:args [repo selector id]}
            (let [request (protocol/make-request
                           :thread-api/pull
                           {:args [graph-id
                                   '[:block/name :block/content]
                                   [:block/uuid block-uuid]]})
                  response-chan (pipes/send-request client request {:timeout-ms 5000})
                  [response _] (alts!! [response-chan (timeout 5000)])]
              (is (some? response) "Should receive response")
              (is (:ok? response) "Response should be ok")
              (is (= "pullable" (get-in response [:payload :block/name])))
              (is (= "Hello world" (get-in response [:payload :block/content]))))
            (finally
              (pipes/disconnect-client client))))
        (finally
          (server/stop-server srv))))))

;; =============================================================================
;; Thread-API State Management Tests
;; =============================================================================

(deftest ^:server sync-app-state-test
  (testing "sync-app-state merges state, not replaces"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        ;; First sync
        (server/handle-sync-app-state srv {:git/current-repo "repo-1" :config {:key1 "a"}})
        (is (= "repo-1" (:git/current-repo (server/get-app-state srv))))
        (is (= {:key1 "a"} (:config (server/get-app-state srv))))

        ;; Second sync - should MERGE
        (server/handle-sync-app-state srv {:config {:key2 "b"}})
        (is (= "repo-1" (:git/current-repo (server/get-app-state srv)))
            "Previous state should be preserved")
        (is (= {:key2 "b"} (:config (server/get-app-state srv)))
            "Config should be replaced (shallow merge)")
        (finally
          (server/stop-server srv))))))

(deftest ^:server set-context-test
  (testing "set-context merges context, not replaces"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        ;; First context
        (server/handle-set-context srv {:mobile? false :electron? true})
        (is (= false (:mobile? (server/get-context srv))))
        (is (= true (:electron? (server/get-context srv))))

        ;; Second context - should MERGE
        (server/handle-set-context srv {:extra-key "value"})
        (is (= false (:mobile? (server/get-context srv)))
            "Previous context should be preserved")
        (is (= "value" (:extra-key (server/get-context srv))))
        (finally
          (server/stop-server srv))))))

(deftest ^:server update-thread-atom-test
  (testing "update-thread-atom updates atoms by key"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        ;; Update thread atom
        (server/handle-update-thread-atom srv :thread-atom/editor-cursor {:line 10 :col 5})
        (is (= {:line 10 :col 5} (server/get-thread-atom srv :thread-atom/editor-cursor)))

        ;; Only :thread-atom/* keys allowed
        (is (thrown? clojure.lang.ExceptionInfo
                     (server/handle-update-thread-atom srv :other/key "value")))
        (finally
          (server/stop-server srv))))))

(deftest ^:server list-db-test
  (testing "list-db returns all graph names"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        (server/create-graph srv "graph-alpha" {})
        (server/create-graph srv "graph-beta" {})

        (let [dbs (server/handle-list-db srv)]
          (is (= 2 (count dbs)))
          (is (some #(= "graph-alpha" (:name %)) dbs))
          (is (some #(= "graph-beta" (:name %)) dbs)))
        (finally
          (server/stop-server srv))))))

(deftest ^:server init-test
  (testing "init stores rtc-ws-url"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        (server/handle-init srv "wss://rtc.example.com")
        (is (= "wss://rtc.example.com" (server/get-rtc-ws-url srv)))
        (finally
          (server/stop-server srv))))))

(deftest ^:server create-or-open-db-test
  (testing "create-or-open-db creates graph if not exists"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        (server/handle-create-or-open-db srv "test-repo" {:config ""})
        (is (server/graph-exists? srv "test-repo"))

        ;; Opening again should not error
        (server/handle-create-or-open-db srv "test-repo" {})
        (is (server/graph-exists? srv "test-repo"))
        (finally
          (server/stop-server srv))))))

(deftest ^:server get-initial-data-test
  (testing "get-initial-data returns schema and data"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        (server/handle-create-or-open-db srv "data-repo" {})
        ;; Add some test data
        (server/transact! srv "data-repo"
                          [{:block/uuid (random-uuid) :block/name "test-page"}])

        (let [result (server/handle-get-initial-data srv "data-repo" {})]
          (is (map? (:schema result)))
          (is (coll? (:initial-data result))))
        (finally
          (server/stop-server srv))))))

(deftest ^:server global-state-reset-test
  (testing "stop-server resets global state"
    (let [srv (server/start-server {:port *test-port*})]
      ;; Set up state
      (server/handle-sync-app-state srv {:git/current-repo "test"})
      (server/handle-set-context srv {:electron? true})
      (server/handle-init srv "wss://test.com")

      ;; Verify state exists
      (is (= "test" (:git/current-repo (server/get-app-state srv))))
      (is (= true (:electron? (server/get-context srv))))
      (is (= "wss://test.com" (server/get-rtc-ws-url srv)))

      ;; Stop server
      (server/stop-server srv)

      ;; Verify state was reset
      (is (empty? (server/get-app-state srv)))
      (is (empty? (server/get-context srv)))
      (is (nil? (server/get-rtc-ws-url srv))))))

;; =============================================================================
;; IPC State Operations Tests
;; =============================================================================

(deftest ^:server ipc-state-ops-test
  (testing "State operations through IPC"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        (let [client (pipes/connect-client "test" {:port *test-port*})]
          (try
            ;; Test sync-app-state via IPC
            (let [request (protocol/make-request
                           :thread-api/sync-app-state
                           {:args [{:git/current-repo "test-repo"}]})
                  response-chan (pipes/send-request client request {:timeout-ms 5000})
                  [response _] (alts!! [response-chan (timeout 5000)])]
              (is (:ok? response))
              (is (= "test-repo" (:git/current-repo (server/get-app-state srv)))))

            ;; Test set-context via IPC
            (let [request (protocol/make-request
                           :thread-api/set-context
                           {:args [{:mobile? false}]})
                  response-chan (pipes/send-request client request {:timeout-ms 5000})
                  [response _] (alts!! [response-chan (timeout 5000)])]
              (is (:ok? response))
              (is (= false (:mobile? (server/get-context srv)))))

            ;; Test list-db via IPC
            (let [_ (server/create-graph srv "ipc-graph" {})
                  request (protocol/make-request
                           :thread-api/list-db
                           {:args []})
                  response-chan (pipes/send-request client request {:timeout-ms 5000})
                  [response _] (alts!! [response-chan (timeout 5000)])]
              (is (:ok? response))
              (is (some #(= "ipc-graph" (:name %)) (:payload response))))

            ;; Test create-or-open-db via IPC
            (let [request (protocol/make-request
                           :thread-api/create-or-open-db
                           {:args ["ipc-db-repo" {}]})
                  response-chan (pipes/send-request client request {:timeout-ms 5000})
                  [response _] (alts!! [response-chan (timeout 5000)])]
              (is (:ok? response))
              (is (server/graph-exists? srv "ipc-db-repo")))
            (finally
              (pipes/disconnect-client client))))
        (finally
          (server/stop-server srv))))))

;; =============================================================================
;; View Data Tests (for journal rendering)
;; =============================================================================

(deftest ^:server get-view-data-journals-test
  (testing "get-view-data returns journal IDs when journals? is true"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        (let [graph-id (server/create-graph srv "journal-graph" {})]
          ;; Transact some journal pages with journal-day attribute
          (server/transact! srv graph-id
                            [{:db/id -1
                              :block/uuid (random-uuid)
                              :block/name "dec 15th, 2025"
                              :block/original-name "Dec 15th, 2025"
                              :block/journal? true
                              :block/journal-day 20251215}
                             {:db/id -2
                              :block/uuid (random-uuid)
                              :block/name "dec 14th, 2025"
                              :block/original-name "Dec 14th, 2025"
                              :block/journal? true
                              :block/journal-day 20251214}
                             {:db/id -3
                              :block/uuid (random-uuid)
                              :block/name "regular page"
                              :block/original-name "Regular Page"}])

          ;; Get view data for journals
          (let [result (server/handle-get-view-data srv graph-id nil {:journals? true})]
            (is (map? result) "Should return a map")
            (is (= 2 (:count result)) "Should have 2 journals")
            (is (= 2 (count (:data result))) "Data should have 2 IDs")
            ;; Verify the IDs are valid entity IDs (positive integers)
            (is (every? pos-int? (:data result)) "All IDs should be positive integers")
            ;; Pull the first entity to verify it's the most recent
            (let [first-id (first (:data result))
                  first-entity (server/pull srv graph-id [:block/journal-day] first-id)]
              (is (= 20251215 (:block/journal-day first-entity))
                  "Most recent journal should be first"))))
        (finally
          (server/stop-server srv))))))

(deftest ^:server get-view-data-non-journals-test
  (testing "get-view-data returns nil for non-journal views in file graphs"
    (let [srv (server/start-server {:port *test-port*})]
      (try
        (let [graph-id (server/create-graph srv "view-graph" {})]
          ;; get-view-data without journals? flag should return nil
          (let [result (server/handle-get-view-data srv graph-id 123 {})]
            (is (nil? result) "Non-journal view data should return nil")))
        (finally
          (server/stop-server srv))))))
