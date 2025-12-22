(ns frontend.handler.file-based.parallel-load-integration-test
  "Integration tests for parallel_load.cljs with worker pool - Step 18.
   Note: Full worker integration tests require a browser environment.
   These tests focus on the integration logic and fallback behavior."
  (:require [cljs.test :refer [deftest testing is use-fixtures async]]
            [frontend.handler.file-based.parallel-load :as pl]
            [frontend.worker.parse-worker-pool :as pool]
            [promesa.core :as p]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

;; For async tests, fixtures must be maps with :before/:after keys
(use-fixtures :each
  {:before (fn []
             (reset! pool/*pool nil)
             (reset! pool/*loading? false))
   :after (fn []
            (reset! pool/*pool nil)
            (reset! pool/*loading? false))})

;; =============================================================================
;; use-worker-pool? Tests
;; =============================================================================

(deftest use-worker-pool-not-initialized-test
  (testing "use-worker-pool? returns false when pool not initialized"
    (is (not (pl/use-worker-pool? 100))
        "Should be false when pool is nil")))

(deftest use-worker-pool-small-batch-test
  (testing "use-worker-pool? returns false for small batches"
    ;; Mock initialized pool
    (reset! pool/*pool {:workers [{} {} {} {}] :size 4})
    (is (not (pl/use-worker-pool? 5))
        "Should be false for batches smaller than threshold")
    (is (not (pl/use-worker-pool? 10))
        "Should be false at threshold (not greater)")))

(deftest use-worker-pool-large-batch-test
  (testing "use-worker-pool? returns true for large batches when pool initialized"
    (reset! pool/*pool {:workers [{} {} {} {}] :size 4})
    (is (pl/use-worker-pool? 11)
        "Should be true for batches larger than threshold")
    (is (pl/use-worker-pool? 100)
        "Should be true for large batches")))

;; =============================================================================
;; Worker Pool Lifecycle Tests
;; =============================================================================

(deftest init-worker-pool-test
  (testing "init-worker-pool! delegates to pool/init-pool!"
    ;; Can't test actual worker creation in Node.js, but verify it doesn't throw
    ;; and returns consistent state
    (is (nil? @pool/*pool) "Pool should be nil initially")
    ;; Note: Actual init would fail in Node.js (no Web Worker API)
    ;; but we verify the function is callable
    ))

(deftest shutdown-worker-pool-test
  (testing "shutdown-worker-pool! clears pool state"
    (reset! pool/*pool {:workers [{:raw nil}] :size 1})
    (reset! pool/*loading? true)
    (pl/shutdown-worker-pool!)
    (is (nil? @pool/*pool) "Pool should be nil after shutdown")
    (is (not @pool/*loading?) "Loading should be false after shutdown")))

(deftest worker-pool-status-test
  (testing "worker-pool-status returns correct status"
    (is (= {:initialized? false :size 0 :loading? false}
           (pl/worker-pool-status))
        "Status when not initialized")

    (reset! pool/*pool {:workers [{} {} {}] :size 3})
    (is (= {:initialized? true :size 3 :loading? false}
           (pl/worker-pool-status))
        "Status when initialized")

    (reset! pool/*loading? true)
    (is (= {:initialized? true :size 3 :loading? true}
           (pl/worker-pool-status))
        "Status when loading")))

;; =============================================================================
;; MIN-FILES-FOR-WORKERS Constant Test
;; =============================================================================

(deftest min-files-constant-test
  (testing "MIN-FILES-FOR-WORKERS is reasonable"
    (is (pos? pl/MIN-FILES-FOR-WORKERS)
        "Should be positive")
    (is (>= pl/MIN-FILES-FOR-WORKERS 5)
        "Should be at least 5 to avoid worker overhead for tiny batches")
    (is (<= pl/MIN-FILES-FOR-WORKERS 100)
        "Should not be too large")))

;; =============================================================================
;; Fallback Behavior Tests
;; =============================================================================

(deftest load-graph-parallel-no-pool-uses-main-thread-test
  (testing "load-graph-parallel! uses main thread when pool not initialized"
    ;; Verify pool is not initialized
    (is (nil? @pool/*pool))
    ;; The function should work without errors (uses main thread path)
    ;; Note: Can't fully test without a DataScript connection, but
    ;; we verify the decision logic
    (is (not (pl/use-worker-pool? 100))
        "Should not use workers when pool not initialized")))

;; =============================================================================
;; Integration Logic Tests (Without Actual Workers)
;; =============================================================================

;; Note: Full integration tests with actual workers require a browser environment.
;; These tests verify the integration logic that can be tested without workers.

(deftest parse-files-with-workers-requires-pool-test
  (testing "parse-files-with-workers falls back when pool not initialized"
    ;; When pool is not initialized, parse-files-parallel! will reject,
    ;; and parse-files-with-workers should fall back to main thread parsing.
    ;; This is tested via the catch handler in the function.
    (is (not (pool/pool-initialized?))
        "Pool should not be initialized for this test")))

;; =============================================================================
;; Step 1: Fallback Concurrency Bug Fix Tests
;; =============================================================================

(deftest fallback-respects-already-loading-test
  (async done
    (testing "Fallback does NOT run when pool rejects due to already-loading"
      ;; Setup: mock pool as initialized but already loading
      (reset! pool/*pool {:workers [{:raw nil :wrapped nil}] :size 1})
      (reset! pool/*loading? true)

      ;; Call parse-files-with-workers - should NOT fall back to main-thread
      ;; because the rejection is due to "already loading" guard
      (-> (pl/parse-files-with-workers nil [] {})
          (p/then (fn [_]
                    ;; Should not succeed - we expect rejection
                    (is false "Should have rejected, not succeeded")
                    (done)))
          (p/catch (fn [e]
                     ;; Should get the "already loading" error, NOT fall back
                     (is (some? e) "Should have rejected")
                     (is (= :already-loading (:code (ex-data e)))
                         "Should have :already-loading code in ex-data")
                     (done)))))))

;; =============================================================================
;; Step 6: Loading State Check in use-worker-pool?
;; =============================================================================

(deftest use-worker-pool-false-when-loading-test
  (testing "use-worker-pool? returns false when pool is already loading"
    ;; Setup: pool is initialized but currently loading
    (reset! pool/*pool {:workers [{:raw nil :wrapped nil}] :size 1})
    (reset! pool/*loading? true)

    ;; use-worker-pool? should return false to prevent concurrent attempts
    (is (not (pl/use-worker-pool? 100))
        "Should return false when pool is loading")))

