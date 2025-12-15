(ns frontend.sidecar.routing-test
  "Unit tests for operation routing in hybrid worker/sidecar architecture.

   Tests verify:
   1. Operation classification (worker-only vs sidecar-preferred)
   2. Routing logic based on sidecar availability
   3. Worker registration and lookup
   4. Invocation through the routing layer

   The routing module ensures file operations (requiring mldoc) go to the
   web worker, while queries/outliner ops go to the sidecar when available."
  (:require [cljs.test :refer [deftest testing is are use-fixtures]]
            [frontend.sidecar.routing :as routing]))

;; =============================================================================
;; Fixtures: Reset routing state between tests
;; =============================================================================

(defn reset-routing-state []
  (routing/clear-sidecar-worker!)
  (routing/clear-all-sync-state!)
  ;; Reset web worker atom manually since there's no public clear function
  ;; We'll test with freshly registered workers
  )

(use-fixtures :each
  {:before reset-routing-state
   :after reset-routing-state})

;; =============================================================================
;; Test: Operation Classification
;; =============================================================================

(deftest worker-only-ops-classification
  (testing "File operations requiring mldoc should be worker-only"
    (is (contains? routing/worker-only-ops :thread-api/reset-file)
        "reset-file requires mldoc parsing")
    (is (contains? routing/worker-only-ops :thread-api/gc-graph)
        "gc-graph requires file operations")
    (is (contains? routing/worker-only-ops :thread-api/export-db)
        "export-db requires file operations")
    (is (contains? routing/worker-only-ops :thread-api/import-db)
        "import-db requires file operations")))

(deftest worker-only-ops-vec-search
  (testing "Vector search operations should be worker-only (uses WebGPU)"
    (is (contains? routing/worker-only-ops :thread-api/vec-search-init-embedding-model))
    (is (contains? routing/worker-only-ops :thread-api/vec-search-search))
    (is (contains? routing/worker-only-ops :thread-api/vec-upsert-blocks))))

(deftest worker-only-ops-rtc
  (testing "RTC operations should be worker-only (uses browser WebSocket)"
    (is (contains? routing/worker-only-ops :thread-api/rtc-start))
    (is (contains? routing/worker-only-ops :thread-api/rtc-stop))
    (is (contains? routing/worker-only-ops :thread-api/rtc-sync-graph!))))

(deftest sidecar-preferred-ops-queries
  (testing "Query operations should prefer sidecar"
    (is (contains? routing/sidecar-preferred-ops :thread-api/q)
        "Datalog queries benefit from sidecar lazy loading")
    (is (contains? routing/sidecar-preferred-ops :thread-api/pull)
        "Entity pull benefits from sidecar")
    (is (contains? routing/sidecar-preferred-ops :thread-api/pull-many)
        "Batch pull benefits from sidecar")
    (is (contains? routing/sidecar-preferred-ops :thread-api/datoms)
        "Datoms access benefits from sidecar")))

(deftest sidecar-preferred-ops-outliner
  (testing "Outliner operations should prefer sidecar"
    (is (contains? routing/sidecar-preferred-ops :thread-api/transact)
        "Transactions benefit from sidecar")
    (is (contains? routing/sidecar-preferred-ops :thread-api/apply-outliner-ops)
        "Outliner ops benefit from sidecar")))

(deftest graph-management-ops-are-worker-only
  (testing "Graph management operations should NOT be in sidecar-preferred (worker needs them)"
    (is (not (contains? routing/sidecar-preferred-ops :thread-api/create-or-open-db))
        "create-or-open-db must go to worker for file parsing")
    (is (not (contains? routing/sidecar-preferred-ops :thread-api/list-db))
        "list-db goes to worker")
    (is (not (contains? routing/sidecar-preferred-ops :thread-api/db-exists))
        "db-exists goes to worker")))

;; Note: get-view-data is NOT in sidecar-preferred-ops because the sidecar DB
;; may be empty during initial graph loading (files parsed in web worker).
;; Once initial sync is reliable, this test can be re-enabled.
#_(deftest sidecar-preferred-ops-view
    (testing "View data operations should prefer sidecar (for journal rendering)"
      (is (contains? routing/sidecar-preferred-ops :thread-api/get-view-data)
          "get-view-data should route to sidecar for journal data")))

(deftest get-view-data-routes-to-worker
  (testing "get-view-data should currently route to worker (sidecar DB may be empty during init)"
    (is (= :worker (routing/route-operation :thread-api/get-view-data true))
        "get-view-data routes to worker even when sidecar available")
    (is (= :worker (routing/route-operation :thread-api/get-view-data false))
        "get-view-data routes to worker when sidecar unavailable")))

;; =============================================================================
;; Test: Sync State Management
;; =============================================================================

(deftest sync-state-initially-incomplete
  (testing "Sync is incomplete for new repos"
    (is (not (routing/sync-complete? "test-repo"))
        "New repo should not have sync complete")))

(deftest sync-state-mark-complete
  (testing "mark-sync-complete! sets sync state"
    (routing/mark-sync-complete! "test-repo")
    (is (routing/sync-complete? "test-repo")
        "Repo should be sync complete after marking")))

(deftest sync-state-mark-incomplete
  (testing "mark-sync-incomplete! clears sync state"
    (routing/mark-sync-complete! "test-repo")
    (is (routing/sync-complete? "test-repo"))
    (routing/mark-sync-incomplete! "test-repo")
    (is (not (routing/sync-complete? "test-repo"))
        "Repo should not be sync complete after marking incomplete")))

(deftest sync-state-per-repo
  (testing "Sync state is tracked per-repo"
    (routing/mark-sync-complete! "repo-a")
    (is (routing/sync-complete? "repo-a"))
    (is (not (routing/sync-complete? "repo-b"))
        "Other repos should not be affected")))

(deftest sync-state-clear-all
  (testing "clear-all-sync-state! clears all repos"
    (routing/mark-sync-complete! "repo-a")
    (routing/mark-sync-complete! "repo-b")
    (routing/clear-all-sync-state!)
    (is (not (routing/sync-complete? "repo-a")))
    (is (not (routing/sync-complete? "repo-b")))))

;; =============================================================================
;; Test: Routing Logic
;; =============================================================================

(deftest route-operation-worker-only
  (testing "Worker-only ops should always route to worker"
    (is (= :worker (routing/route-operation :thread-api/reset-file true))
        "reset-file routes to worker even when sidecar available")
    (is (= :worker (routing/route-operation :thread-api/reset-file false))
        "reset-file routes to worker when sidecar not available")
    (is (= :worker (routing/route-operation :thread-api/vec-search-search true))
        "vec-search routes to worker even when sidecar available")))

(deftest route-operation-sidecar-preferred-with-sync
  (testing "Sidecar-preferred ops route to sidecar when available AND sync complete"
    ;; Mark sync complete for test repo
    (routing/mark-sync-complete! "test-repo")
    (is (= :sidecar (routing/route-operation :thread-api/q true "test-repo"))
        "Query routes to sidecar when available and sync complete")
    (is (= :sidecar (routing/route-operation :thread-api/pull true "test-repo"))
        "Pull routes to sidecar when available and sync complete")
    (is (= :sidecar (routing/route-operation :thread-api/transact true "test-repo"))
        "Transact routes to sidecar when available and sync complete")))

(deftest route-operation-sidecar-preferred-without-sync
  (testing "Sidecar-preferred ops route to worker when sync incomplete"
    ;; Do NOT mark sync complete
    (is (= :worker (routing/route-operation :thread-api/q true "test-repo"))
        "Query routes to worker when sync incomplete")
    (is (= :worker (routing/route-operation :thread-api/pull true "test-repo"))
        "Pull routes to worker when sync incomplete")
    (is (= :worker (routing/route-operation :thread-api/transact true "test-repo"))
        "Transact routes to worker when sync incomplete")))

(deftest route-operation-sidecar-unavailable
  (testing "Sidecar-preferred ops fall back to worker when sidecar unavailable"
    (routing/mark-sync-complete! "test-repo")
    (is (= :worker (routing/route-operation :thread-api/q false "test-repo"))
        "Query falls back to worker when sidecar unavailable")
    (is (= :worker (routing/route-operation :thread-api/pull false "test-repo"))
        "Pull falls back to worker when sidecar unavailable")))

(deftest route-operation-no-repo-provided
  (testing "Routing without repo defaults to worker for safety"
    ;; Without repo, we can't check sync status, so default to worker
    ;; unless nil repo is allowed (which currently it is via (or nil? repo))
    (is (= :sidecar (routing/route-operation :thread-api/q true nil))
        "Query routes to sidecar when no repo and sidecar available (nil allowed)")))

(deftest route-operation-unknown-ops
  (testing "Unknown/unclassified ops default to worker"
    (is (= :worker (routing/route-operation :thread-api/unknown-op true))
        "Unknown op goes to worker even when sidecar available")
    (is (= :worker (routing/route-operation :thread-api/unknown-op false))
        "Unknown op goes to worker")))

;; =============================================================================
;; Test: Worker Registration
;; =============================================================================

(deftest worker-registration-web-worker
  (testing "Web worker registration"
    (let [mock-worker (fn [qkw _ & args] {:called qkw :args args})]
      (routing/set-web-worker! mock-worker)
      (is (routing/web-worker-ready?)
          "Web worker should be ready after registration"))))

(deftest worker-registration-sidecar
  (testing "Sidecar worker registration"
    (is (not (routing/sidecar-ready?))
        "Sidecar should not be ready before registration")
    (let [mock-sidecar (fn [qkw _ & args] {:called qkw :args args})]
      (routing/set-sidecar-worker! mock-sidecar)
      (is (routing/sidecar-ready?)
          "Sidecar should be ready after registration"))))

(deftest worker-registration-clear-sidecar
  (testing "Clearing sidecar worker"
    (let [mock-sidecar (fn [& _] nil)]
      (routing/set-sidecar-worker! mock-sidecar)
      (is (routing/sidecar-ready?) "Sidecar should be ready")
      (routing/clear-sidecar-worker!)
      (is (not (routing/sidecar-ready?))
          "Sidecar should not be ready after clearing"))))

;; =============================================================================
;; Test: Operation Classification Completeness
;; =============================================================================

(deftest no-overlap-between-ops-sets
  (testing "Worker-only and sidecar-preferred sets should not overlap"
    (let [overlap (clojure.set/intersection routing/worker-only-ops
                                            routing/sidecar-preferred-ops)]
      (is (empty? overlap)
          (str "Overlapping ops found: " overlap)))))

(deftest critical-ops-classified
  (testing "Critical file operations are classified as worker-only"
    (are [op] (contains? routing/worker-only-ops op)
      :thread-api/reset-file
      :thread-api/gc-graph
      :thread-api/export-db
      :thread-api/import-db
      :thread-api/fix-broken-graph))

  (testing "Critical query operations are classified as sidecar-preferred"
    (are [op] (contains? routing/sidecar-preferred-ops op)
      :thread-api/q
      :thread-api/pull
      :thread-api/pull-many
      :thread-api/datoms
      :thread-api/transact)))

;; =============================================================================
;; Test: Edge Cases
;; =============================================================================

(deftest routing-with-nil-sidecar-status
  (testing "Routing handles nil sidecar status gracefully"
    ;; When sidecar-ready? returns false (no sidecar registered)
    (routing/clear-sidecar-worker!)
    (is (= :worker (routing/route-operation :thread-api/q false))
        "Query should route to worker when sidecar not ready")))

(deftest ops-sets-are-keywords
  (testing "All ops in sets are namespaced keywords"
    (doseq [op routing/worker-only-ops]
      (is (keyword? op) (str op " should be a keyword"))
      (is (namespace op) (str op " should be namespaced")))
    (doseq [op routing/sidecar-preferred-ops]
      (is (keyword? op) (str op " should be a keyword"))
      (is (namespace op) (str op " should be namespaced")))))
