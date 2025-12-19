(ns frontend.worker.parse-worker-pool-test
  "Tests for parse worker pool - Step 17.
   Note: Full worker integration tests require a browser environment.
   These tests focus on state management and logic that can be tested without workers."
  (:require [cljs.test :refer [deftest testing is use-fixtures]]
            [frontend.worker.parse-worker-pool :as pool]
            [promesa.core :as p]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-pool-fixture
  "Reset pool state before each test."
  [f]
  (reset! pool/*pool nil)
  (reset! pool/*loading? false)
  (f)
  (reset! pool/*pool nil)
  (reset! pool/*loading? false))

(use-fixtures :each reset-pool-fixture)

;; =============================================================================
;; Pool State Tests
;; =============================================================================

(deftest pool-initialized-test
  (testing "pool-initialized? returns false when not initialized"
    (is (not (pool/pool-initialized?)) "Should be false initially"))

  (testing "pool-initialized? returns false when size is 0"
    ;; Step 2 fix: Empty pool should not be considered initialized
    (reset! pool/*pool {:workers [] :size 0})
    (is (not (pool/pool-initialized?)) "Should be false when size is 0"))

  (testing "pool-initialized? returns true when pool has workers"
    (reset! pool/*pool {:workers [{:raw nil :wrapped nil}] :size 1})
    (is (pool/pool-initialized?) "Should be true when pool has workers")))

(deftest pool-size-test
  (testing "pool-size returns 0 when not initialized"
    (is (= 0 (pool/pool-size)) "Should be 0 initially"))

  (testing "pool-size returns correct size"
    (reset! pool/*pool {:workers [{} {} {}] :size 3})
    (is (= 3 (pool/pool-size)) "Should return pool size")))

(deftest loading-test
  (testing "loading? returns false initially"
    (is (not (pool/loading?)) "Should be false initially"))

  (testing "loading? returns true when loading"
    (reset! pool/*loading? true)
    (is (pool/loading?) "Should be true when loading")))

;; =============================================================================
;; Constants Tests
;; =============================================================================

(deftest constants-test
  (testing "Worker count constants are reasonable"
    (is (pos? pool/MIN-WORKERS) "MIN-WORKERS should be positive")
    (is (pos? pool/MAX-WORKERS) "MAX-WORKERS should be positive")
    (is (<= pool/MIN-WORKERS pool/MAX-WORKERS) "MIN <= MAX")
    (is (<= pool/MIN-WORKERS pool/DEFAULT-WORKERS) "MIN <= DEFAULT")
    (is (<= pool/DEFAULT-WORKERS pool/MAX-WORKERS) "DEFAULT <= MAX")
    ;; Allow aggressive worker counts for short burst loads
    (is (>= pool/MAX-WORKERS 8) "MAX-WORKERS should allow at least 8 for modern devices"))

  (testing "MIN-FILES-PER-WORKER prevents over-chunking"
    (is (pos? pool/MIN-FILES-PER-WORKER) "Should be positive")
    (is (>= pool/MIN-FILES-PER-WORKER 5) "Should be at least 5 to amortize overhead")
    (is (<= pool/MIN-FILES-PER-WORKER 20) "Should not be too large"))

  (testing "Timeout is reasonable"
    (is (>= pool/WORKER-TIMEOUT-MS 10000) "Timeout should be at least 10s")))

;; =============================================================================
;; Smart Worker Utilization Tests
;; =============================================================================

(defn- calc-effective-workers
  "Calculate effective workers for a given file count and pool size.
   Mirrors the logic in parse-files-parallel!"
  [file-count pool-size]
  (-> (js/Math.ceil (/ file-count pool/MIN-FILES-PER-WORKER))
      (max 1)
      (min pool-size)))

(deftest effective-workers-calculation-test
  (testing "Effective worker count adapts to file count"
    ;; Formula: effective = min(ceil(files / MIN-FILES-PER-WORKER), pool-size)
    ;; With MIN-FILES-PER-WORKER = 10 and pool-size = 8:

    (testing "Small file counts use fewer workers"
      ;; 15 files / 10 = 1.5 → ceil → 2 workers
      (is (= 2 (calc-effective-workers 15 8)))
      ;; 25 files / 10 = 2.5 → ceil → 3 workers
      (is (= 3 (calc-effective-workers 25 8))))

    (testing "Medium file counts scale up workers"
      ;; 50 files / 10 = 5 workers
      (is (= 5 (calc-effective-workers 50 8)))
      ;; 75 files / 10 = 7.5 → ceil → 8 workers (at pool limit)
      (is (= 8 (calc-effective-workers 75 8))))

    (testing "Large file counts cap at pool size"
      ;; 200 files / 10 = 20 → capped at 8
      (is (= 8 (calc-effective-workers 200 8)))
      ;; 1000 files / 10 = 100 → capped at 8
      (is (= 8 (calc-effective-workers 1000 8))))

    (testing "Tiny file counts still use at least 1 worker"
      ;; 5 files / 10 = 0.5 → ceil → 1 worker
      (is (= 1 (calc-effective-workers 5 8)))
      ;; 1 file / 10 = 0.1 → ceil → 1 worker
      (is (= 1 (calc-effective-workers 1 8))))

    (testing "Respects smaller pool sizes"
      ;; 100 files with only 4 workers → capped at 4
      (is (= 4 (calc-effective-workers 100 4)))
      ;; 50 files with 4 workers → 5 would be needed, capped at 4
      (is (= 4 (calc-effective-workers 50 4)))
      ;; 20 files with 4 workers → 2 workers (under pool limit)
      (is (= 2 (calc-effective-workers 20 4))))))

(deftest chunk-size-calculation-test
  (testing "Chunk sizes distribute files across workers"
    ;; chunk-size = ceil(file-count / effective-workers)

    (testing "Even distribution"
      ;; 100 files, 5 workers → 20 files each
      (is (= 20 (js/Math.ceil (/ 100 5))))
      ;; 80 files, 8 workers → 10 files each
      (is (= 10 (js/Math.ceil (/ 80 8)))))

    (testing "Uneven distribution rounds up (some workers get fewer)"
      ;; 25 files, 3 workers → ceil(8.33) = 9 files per chunk
      ;; Results in chunks of: 9, 9, 7
      (is (= 9 (js/Math.ceil (/ 25 3))))
      ;; 100 files, 8 workers → ceil(12.5) = 13 files per chunk
      (is (= 13 (js/Math.ceil (/ 100 8)))))))

(deftest worker-efficiency-scenarios-test
  (testing "Real-world scenarios use optimal worker counts"
    ;; These test that we don't waste workers on small batches

    (testing "Typical small graph (100 files, 8-core machine)"
      (let [files 100
            pool-size 8
            effective (calc-effective-workers files pool-size)
            chunk-size (js/Math.ceil (/ files effective))]
        (is (= 8 effective) "Use all 8 workers")
        (is (= 13 chunk-size) "~13 files per worker")))

    (testing "Tiny graph (20 files, 8-core machine)"
      (let [files 20
            pool-size 8
            effective (calc-effective-workers files pool-size)
            chunk-size (js/Math.ceil (/ files effective))]
        (is (= 2 effective) "Only use 2 workers (20/10=2)")
        (is (= 10 chunk-size) "10 files per worker")))

    (testing "Large graph (500 files, 4-core phone)"
      (let [files 500
            pool-size 4
            effective (calc-effective-workers files pool-size)
            chunk-size (js/Math.ceil (/ files effective))]
        (is (= 4 effective) "Use all 4 workers")
        (is (= 125 chunk-size) "125 files per worker")))

    (testing "Medium graph (50 files, 4-core phone)"
      (let [files 50
            pool-size 4
            effective (calc-effective-workers files pool-size)
            chunk-size (js/Math.ceil (/ files effective))]
        (is (= 4 effective) "Use all 4 workers (50/10=5, capped at 4)")
        (is (= 13 chunk-size) "~13 files per worker")))))

;; =============================================================================
;; Initialization Logic Tests (Without Actual Workers)
;; =============================================================================

;; Note: We can't test actual worker creation in Node.js environment
;; since js/Worker is not available. These tests verify the logic.

(deftest init-pool-when-already-initialized-test
  (testing "init-pool! does nothing when already initialized"
    (reset! pool/*pool {:workers [{:fake true}] :size 1})
    (let [original @pool/*pool]
      ;; This should not reinitialize
      (pool/init-pool! 4)
      (is (= original @pool/*pool) "Pool should not change when already initialized"))))

(deftest shutdown-pool-clears-state-test
  (testing "shutdown-pool! clears all state"
    (reset! pool/*pool {:workers [{:raw nil :wrapped nil}] :size 1})
    (reset! pool/*loading? true)
    (pool/shutdown-pool!)
    (is (nil? @pool/*pool) "Pool should be nil after shutdown")
    (is (not @pool/*loading?) "Loading should be false after shutdown")))

;; =============================================================================
;; Parse Files Logic Tests
;; =============================================================================

(deftest parse-files-parallel-requires-pool-test
  (testing "parse-files-parallel! rejects when pool not initialized"
    (let [result-atom (atom nil)]
      (-> (pool/parse-files-parallel! [] {})
          (p/catch (fn [e]
                     (reset! result-atom e))))
      ;; Give promise time to resolve
      (js/setTimeout
       (fn []
         (is (some? @result-atom) "Should have rejected")
         (is (= "Worker pool not initialized"
                (ex-message @result-atom))))
       10))))

(deftest parse-files-parallel-rejects-overlap-test
  (testing "parse-files-parallel! rejects when already loading"
    (reset! pool/*pool {:workers [] :size 0})
    (reset! pool/*loading? true)
    (let [result-atom (atom nil)]
      (-> (pool/parse-files-parallel! [] {})
          (p/catch (fn [e]
                     (reset! result-atom e))))
      ;; Give promise time to resolve
      (js/setTimeout
       (fn []
         (is (some? @result-atom) "Should have rejected")
         (is (= "Parallel load already in progress"
                (ex-message @result-atom))))
       10))))

;; =============================================================================
;; Worker Count Capping Tests (Logic Only)
;; =============================================================================

(deftest worker-count-capping-logic-test
  (testing "Worker count is capped between MIN and MAX"
    ;; Test the capping logic without actually creating workers
    (let [calc-workers (fn [n]
                         (-> n
                             (max pool/MIN-WORKERS)
                             (min pool/MAX-WORKERS)))]
      ;; Test below minimum
      (is (= pool/MIN-WORKERS (calc-workers 0)) "0 should become MIN")
      (is (= pool/MIN-WORKERS (calc-workers 1)) "1 should become MIN")

      ;; Test at minimum
      (is (= pool/MIN-WORKERS (calc-workers pool/MIN-WORKERS)) "MIN stays MIN")

      ;; Test in valid range
      (is (= 4 (calc-workers 4)) "4 stays 4")

      ;; Test at maximum
      (is (= pool/MAX-WORKERS (calc-workers pool/MAX-WORKERS)) "MAX stays MAX")

      ;; Test above maximum
      (is (= pool/MAX-WORKERS (calc-workers 100)) "100 should become MAX")
      (is (= pool/MAX-WORKERS (calc-workers 1000)) "1000 should become MAX"))))

;; =============================================================================
;; Step 4: Worker Replacement Dead Slot Tests
;; =============================================================================

(deftest replace-worker-preserves-old-on-failure-test
  (testing "If replacement creation fails, old worker is NOT terminated"
    ;; Setup: Create a mock pool with a fake worker
    (let [terminated? (atom false)
          fake-worker {:raw #js {:terminate (fn [] (reset! terminated? true))}
                       :wrapped nil}]
      (reset! pool/*pool {:workers [fake-worker] :size 1})

      ;; Call replace-worker-at-index! - since we can't actually create workers
      ;; in Node.js, create-worker will return nil
      (pool/replace-worker-at-index! 0)

      ;; The bug: old worker gets terminated even if replacement fails
      ;; After fix: old worker should NOT be terminated if replacement fails
      (is (not @terminated?)
          "Old worker should NOT be terminated when replacement fails")

      ;; Pool should still have the original worker
      (is (= 1 (:size @pool/*pool))
          "Pool size should remain unchanged"))))
