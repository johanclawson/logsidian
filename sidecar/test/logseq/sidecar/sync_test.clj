(ns logseq.sidecar.sync-test
  "Tests for datom sync functionality.

   These tests verify:
   - Sidecar accepts datom batches from main process
   - Full sync (initial load) works correctly
   - Incremental sync (file changes) works correctly
   - Data is queryable after sync

   Run with: cd sidecar && clj -M:test -n logseq.sidecar.sync-test"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datascript.core :as d]
            [logseq.sidecar.server :as server]
            [logseq.sidecar.protocol :as protocol]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-server* nil)

(defn with-test-server
  "Fixture that creates a fresh server for each test.
   Creates a minimal server without starting TCP/WebSocket listeners."
  [f]
  (let [;; Create server structure directly for testing
        server (server/map->SidecarServer
                {:pipe-server nil
                 :ws-server nil
                 :graphs (java.util.concurrent.ConcurrentHashMap.)
                 :running? (atom true)})]
    (try
      (binding [*test-server* server]
        (f))
      (finally
        (reset! (:running? server) false)))))

(use-fixtures :each with-test-server)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn invoke
  "Invoke a server operation directly (bypassing network)."
  [op args]
  (let [request {:op op
                 :payload {:args args}
                 :id (str (random-uuid))}
        response (#'server/handle-request *test-server* request)]
    (if (false? (:ok? response))
      (throw (ex-info "Server error" response))
      (:payload response))))

(defn make-datom-vec
  "Create a datom vector [e a v tx] for sync."
  [e a v tx]
  [e a v tx true])  ;; true = added (not retracted)

;; =============================================================================
;; Sync Datoms Tests
;; =============================================================================

(deftest sync-datoms-basic-test
  (testing "Sidecar accepts datom batch from main process"
    (let [repo "test-repo"
          ;; Create graph first
          _ (invoke :thread-api/create-or-open-db [repo {}])
          ;; Sync some datoms
          datoms [(make-datom-vec 1 :block/name "test-page" 1000)
                  (make-datom-vec 1 :block/uuid #uuid "11111111-1111-1111-1111-111111111111" 1000)
                  (make-datom-vec 2 :block/content "Hello, World!" 1001)
                  (make-datom-vec 2 :block/parent 1 1001)
                  (make-datom-vec 2 :block/page 1 1001)
                  (make-datom-vec 2 :block/uuid #uuid "22222222-2222-2222-2222-222222222222" 1001)]
          result (invoke :thread-api/sync-datoms [repo datoms {:full-sync? true}])]
      ;; Should succeed
      (is (map? result) "Sync should return a result map")
      ;; Verify data is queryable
      (let [page (invoke :thread-api/pull [repo '[*] [:block/name "test-page"]])]
        (is (= "test-page" (:block/name page))
            "Page should be queryable after sync")))))

(deftest sync-datoms-incremental-test
  (testing "Sidecar handles incremental updates"
    (let [repo "test-repo"
          _ (invoke :thread-api/create-or-open-db [repo {}])
          ;; Initial sync
          initial-datoms [(make-datom-vec 1 :block/name "page1" 1000)
                          (make-datom-vec 1 :block/uuid #uuid "11111111-1111-1111-1111-111111111111" 1000)
                          (make-datom-vec 1 :block/content "Original content" 1000)]
          _ (invoke :thread-api/sync-datoms [repo initial-datoms {:full-sync? true}])

          ;; Incremental update - add content attribute
          update-datoms [(make-datom-vec 1 :block/content "Updated content" 1001)]
          _ (invoke :thread-api/sync-datoms [repo update-datoms {:full-sync? false}])]

      ;; Verify update
      (let [page (invoke :thread-api/pull [repo '[*] [:block/name "page1"]])]
        (is (= "Updated content" (:block/content page))
            "Content should be updated after incremental sync")))))

(deftest sync-datoms-with-refs-test
  (testing "Sync handles block references correctly"
    (let [repo "test-repo"
          _ (invoke :thread-api/create-or-open-db [repo {}])
          ;; Create pages and blocks with references
          datoms [(make-datom-vec 1 :block/name "page-a" 1000)
                  (make-datom-vec 1 :block/uuid #uuid "11111111-1111-1111-1111-111111111111" 1000)
                  (make-datom-vec 2 :block/name "page-b" 1000)
                  (make-datom-vec 2 :block/uuid #uuid "22222222-2222-2222-2222-222222222222" 1000)
                  ;; Block on page-a that references page-b
                  (make-datom-vec 3 :block/content "Link to [[page-b]]" 1001)
                  (make-datom-vec 3 :block/uuid #uuid "33333333-3333-3333-3333-333333333333" 1001)
                  (make-datom-vec 3 :block/page 1 1001)
                  (make-datom-vec 3 :block/refs 2 1001)]  ;; References page-b
          _ (invoke :thread-api/sync-datoms [repo datoms {:full-sync? true}])]

      ;; Query refs
      (let [block (invoke :thread-api/pull [repo '[:block/content {:block/refs [:block/name]}]
                                            [:block/uuid #uuid "33333333-3333-3333-3333-333333333333"]])]
        (is (= "Link to [[page-b]]" (:block/content block)))
        (is (some #(= "page-b" (:block/name %)) (:block/refs block))
            "Block should reference page-b")))))

(deftest sync-datoms-retraction-test
  (testing "Sync handles retracted datoms"
    (let [repo "test-repo"
          _ (invoke :thread-api/create-or-open-db [repo {}])
          ;; Initial sync
          initial-datoms [(make-datom-vec 1 :block/name "page-to-keep" 1000)
                          (make-datom-vec 1 :block/uuid #uuid "11111111-1111-1111-1111-111111111111" 1000)
                          (make-datom-vec 2 :block/name "page-to-delete" 1000)
                          (make-datom-vec 2 :block/uuid #uuid "22222222-2222-2222-2222-222222222222" 1000)]
          _ (invoke :thread-api/sync-datoms [repo initial-datoms {:full-sync? true}])

          ;; Retract the second page (false = retracted)
          retract-datoms [[2 :block/name "page-to-delete" 1001 false]
                          [2 :block/uuid #uuid "22222222-2222-2222-2222-222222222222" 1001 false]]
          _ (invoke :thread-api/sync-datoms [repo retract-datoms {:full-sync? false}])]

      ;; page-to-keep should still exist
      (let [page1 (invoke :thread-api/pull [repo '[*] [:block/name "page-to-keep"]])]
        (is (some? page1) "page-to-keep should exist"))

      ;; page-to-delete should be gone
      (let [page2 (invoke :thread-api/pull [repo '[*] [:block/name "page-to-delete"]])]
        (is (nil? page2) "page-to-delete should be retracted")))))

(deftest sync-datoms-query-after-sync-test
  (testing "Query operations work after sync"
    (let [repo "test-repo"
          _ (invoke :thread-api/create-or-open-db [repo {}])
          ;; Sync some pages
          datoms [(make-datom-vec 1 :block/name "alpha" 1000)
                  (make-datom-vec 1 :block/uuid #uuid "11111111-1111-1111-1111-111111111111" 1000)
                  (make-datom-vec 2 :block/name "beta" 1000)
                  (make-datom-vec 2 :block/uuid #uuid "22222222-2222-2222-2222-222222222222" 1000)
                  (make-datom-vec 3 :block/name "gamma" 1000)
                  (make-datom-vec 3 :block/uuid #uuid "33333333-3333-3333-3333-333333333333" 1000)]
          _ (invoke :thread-api/sync-datoms [repo datoms {:full-sync? true}])]

      ;; Query all page names
      (let [result (invoke :thread-api/q [repo ['{:find [?name]
                                                  :where [[?e :block/name ?name]]}]])]
        (is (= 3 (count result)) "Should find 3 pages")
        (is (= #{["alpha"] ["beta"] ["gamma"]}
               (set result))
            "Should find correct page names")))))

(deftest sync-datoms-large-batch-test
  (testing "Sync handles large batches efficiently"
    (let [repo "test-repo"
          _ (invoke :thread-api/create-or-open-db [repo {}])
          ;; Create 1000 blocks
          datoms (vec
                  (for [i (range 1000)]
                    (let [eid (+ i 1)]
                      [(make-datom-vec eid :block/uuid (random-uuid) 1000)
                       (make-datom-vec eid :block/content (str "Block " i) 1000)]))
                  )
          flat-datoms (vec (apply concat datoms))
          start-time (System/currentTimeMillis)
          _ (invoke :thread-api/sync-datoms [repo flat-datoms {:full-sync? true}])
          elapsed (- (System/currentTimeMillis) start-time)]

      ;; Should complete in reasonable time (< 5 seconds)
      (is (< elapsed 5000)
          (str "Large sync took " elapsed "ms, should be < 5000ms"))

      ;; Verify data
      (let [result (invoke :thread-api/q [repo ['{:find [(count ?e)]
                                                  :where [[?e :block/content _]]}]])]
        (is (= 1000 (ffirst result))
            "Should have 1000 blocks after sync")))))

;; =============================================================================
;; Storage Integration Tests
;; =============================================================================

(deftest create-graph-with-storage-test
  (testing "Create graph with in-memory storage"
    (let [repo "storage-test-repo"
          ;; Create graph with storage
          _ (server/create-graph *test-server* repo {:storage-path ":memory:"
                                                      :ref-type :soft})]
      ;; Graph should exist
      (is (server/graph-exists? *test-server* repo)
          "Graph should exist after creation")

      ;; Should be able to transact and query
      (let [datoms [(make-datom-vec 1 :block/name "storage-page" 1000)
                    (make-datom-vec 1 :block/uuid #uuid "44444444-4444-4444-4444-444444444444" 1000)]
            _ (invoke :thread-api/sync-datoms [repo datoms {:full-sync? true}])
            page (invoke :thread-api/pull [repo '[*] [:block/name "storage-page"]])]
        (is (= "storage-page" (:block/name page))
            "Data should be queryable in storage-backed graph"))))

  (testing "Remove graph closes storage"
    (let [repo "storage-close-test"
          _ (server/create-graph *test-server* repo {:storage-path ":memory:"})
          result (server/remove-graph *test-server* repo)]
      (is (true? result) "Graph should be removed")
      (is (not (server/graph-exists? *test-server* repo))
          "Graph should no longer exist"))))
