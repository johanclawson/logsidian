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

;; TEMPORARILY DISABLED: Query routing to sidecar is disabled during development.
;; Re-enable these tests once initial sync is reliable.
#_(deftest sidecar-preferred-ops-queries
    (testing "Query operations should prefer sidecar"
      (is (contains? routing/sidecar-preferred-ops :thread-api/q)
          "Datalog queries benefit from sidecar lazy loading")
      (is (contains? routing/sidecar-preferred-ops :thread-api/pull)
          "Entity pull benefits from sidecar")
      (is (contains? routing/sidecar-preferred-ops :thread-api/pull-many)
          "Batch pull benefits from sidecar")
      (is (contains? routing/sidecar-preferred-ops :thread-api/datoms)
          "Datoms access benefits from sidecar")))

#_(deftest sidecar-preferred-ops-outliner
    (testing "Outliner operations should prefer sidecar"
      (is (contains? routing/sidecar-preferred-ops :thread-api/transact)
          "Transactions benefit from sidecar")
      (is (contains? routing/sidecar-preferred-ops :thread-api/apply-outliner-ops)
          "Outliner ops benefit from sidecar")))

;; TEMPORARILY DISABLED: Graph management ops go to web worker for file parsing
#_(deftest sidecar-preferred-ops-graph
    (testing "Graph management operations still route to sidecar"
      (is (contains? routing/sidecar-preferred-ops :thread-api/create-or-open-db))
      (is (contains? routing/sidecar-preferred-ops :thread-api/list-db))
      (is (contains? routing/sidecar-preferred-ops :thread-api/db-exists))))

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

;; TEMPORARILY DISABLED: Query routing to sidecar is disabled during development.
#_(deftest route-operation-sidecar-preferred-available
    (testing "Sidecar-preferred ops route to sidecar when available"
      (is (= :sidecar (routing/route-operation :thread-api/q true))
          "Query routes to sidecar when available")
      (is (= :sidecar (routing/route-operation :thread-api/pull true))
          "Pull routes to sidecar when available")
      (is (= :sidecar (routing/route-operation :thread-api/transact true))
          "Transact routes to sidecar when available")))

#_(deftest route-operation-sidecar-preferred-unavailable
    (testing "Sidecar-preferred ops fall back to worker when sidecar unavailable"
      (is (= :worker (routing/route-operation :thread-api/q false))
          "Query falls back to worker when sidecar unavailable")
      (is (= :worker (routing/route-operation :thread-api/pull false))
          "Pull falls back to worker when sidecar unavailable")))

(deftest query-ops-route-to-worker
  (testing "Query operations currently route to worker (sidecar DB empty during init)"
    (is (= :worker (routing/route-operation :thread-api/q true))
        "Query routes to worker even when sidecar available")
    (is (= :worker (routing/route-operation :thread-api/pull true))
        "Pull routes to worker even when sidecar available")
    (is (= :worker (routing/route-operation :thread-api/transact true))
        "Transact routes to worker even when sidecar available")))

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
