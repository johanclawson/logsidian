(ns frontend.benchmark.parallel-load-benchmark-test
  "Performance benchmarks comparing sequential vs parallel graph loading.

   Compares:
   1. Sequential loading (current production path)
   2. Parallel loading with batching (main-thread concurrent)
   3. Worker pool parallel loading (browser only - true CPU parallelism)

   Run with: yarn cljs:run-test -i benchmark

   NOTE: Worker pool benchmarks require a browser environment (Web Workers API).
   Node.js tests use the main-thread fallback path. For true worker benchmarks,
   run in Electron or browser with the worker pool initialized."
  (:require [cljs.test :refer [deftest testing is async use-fixtures]]
            [clojure.string :as string]
            [datascript.core :as d]
            [frontend.db.transact-queue :as tq]
            [frontend.handler.file-based.parallel-load :as pl]
            [frontend.util :as util]
            [frontend.worker.parse-worker-pool :as pool]
            [logseq.graph-parser :as graph-parser]
            [logseq.graph-parser.batch :as batch]
            [logseq.graph-parser.db :as gp-db]
            [promesa.core :as p]))

;; ============================================================================
;; Test Configuration
;; ============================================================================

(def ^:const SMALL-GRAPH-FILES 100)
(def ^:const SMALL-GRAPH-BLOCKS-PER-FILE 10)

(def ^:const MEDIUM-GRAPH-FILES 500)
(def ^:const MEDIUM-GRAPH-BLOCKS-PER-FILE 15)

;; ============================================================================
;; File Generation Utilities
;; ============================================================================

(defn- generate-block-content [idx]
  (str "Block content " idx " with [[PageLink" (mod idx 50) "]] and #tag" (mod idx 10)))

(defn- generate-file-content [file-idx blocks-per-file]
  (let [blocks (for [block-idx (range blocks-per-file)]
                 (str "- " (generate-block-content (+ (* file-idx blocks-per-file) block-idx))))]
    (string/join "\n" blocks)))

(defn- generate-test-files [num-files blocks-per-file]
  (vec
   (for [idx (range num-files)]
     {:file/path (str "pages/test-page-" idx ".md")
      :file/content (generate-file-content idx blocks-per-file)})))

;; ============================================================================
;; Sequential Loading (Current Production Path)
;; ============================================================================

(defn- load-files-sequential!
  "Load files one at a time using parse-file (current production approach)"
  [conn files]
  (let [start-time (js/Date.now)]
    (doseq [file files]
      (graph-parser/parse-file conn
                               (:file/path file)
                               (:file/content file)
                               {:new-graph? true}))
    (- (js/Date.now) start-time)))

;; ============================================================================
;; Parallel Loading (New Implementation)
;; ============================================================================

(defn- load-files-parallel!
  "Load files using the new parallel loading implementation"
  [conn files]
  (p/create
   (fn [resolve _reject]
     (let [start-time (js/Date.now)]
       (-> (pl/load-graph-parallel! conn files {:batch-size 25})
           (p/then (fn [_result]
                     (resolve (- (js/Date.now) start-time))))
           (p/catch (fn [e]
                      (println "Parallel load error:" e)
                      (resolve -1))))))))

;; ============================================================================
;; Benchmark Tests
;; ============================================================================

(deftest ^:benchmark sequential-load-small-benchmark
  (testing "Sequential loading - small graph"
    (let [conn (gp-db/start-conn)
          files (generate-test-files SMALL-GRAPH-FILES SMALL-GRAPH-BLOCKS-PER-FILE)
          time-ms (load-files-sequential! conn files)
          block-count (* SMALL-GRAPH-FILES SMALL-GRAPH-BLOCKS-PER-FILE)]
      (println (str "📊 Sequential load (small): " time-ms "ms for " block-count " blocks"))
      (println (str "   Rate: " (.toFixed (/ block-count (/ time-ms 1000)) 2) " blocks/sec"))
      (is (pos? time-ms) "Should complete"))))

(deftest ^:benchmark parallel-load-small-benchmark
  (async done
    (testing "Parallel loading - small graph"
      (let [conn (gp-db/start-conn)
            files (generate-test-files SMALL-GRAPH-FILES SMALL-GRAPH-BLOCKS-PER-FILE)
            block-count (* SMALL-GRAPH-FILES SMALL-GRAPH-BLOCKS-PER-FILE)]
        (-> (load-files-parallel! conn files)
            (p/then (fn [time-ms]
                      (println (str "📊 Parallel load (small): " time-ms "ms for " block-count " blocks"))
                      (println (str "   Rate: " (.toFixed (/ block-count (/ time-ms 1000)) 2) " blocks/sec"))
                      (is (pos? time-ms) "Should complete")
                      (tq/reset-queue!)
                      (done)))
            (p/catch (fn [e]
                       (is false (str "Test failed: " e))
                       (tq/reset-queue!)
                       (done))))))))

(deftest ^:benchmark sequential-load-medium-benchmark
  (testing "Sequential loading - medium graph"
    (let [conn (gp-db/start-conn)
          files (generate-test-files MEDIUM-GRAPH-FILES MEDIUM-GRAPH-BLOCKS-PER-FILE)
          time-ms (load-files-sequential! conn files)
          block-count (* MEDIUM-GRAPH-FILES MEDIUM-GRAPH-BLOCKS-PER-FILE)]
      (println (str "📊 Sequential load (medium): " time-ms "ms for " block-count " blocks"))
      (println (str "   Rate: " (.toFixed (/ block-count (/ time-ms 1000)) 2) " blocks/sec"))
      (is (pos? time-ms) "Should complete"))))

(deftest ^:benchmark parallel-load-medium-benchmark
  (async done
    (testing "Parallel loading - medium graph"
      (let [conn (gp-db/start-conn)
            files (generate-test-files MEDIUM-GRAPH-FILES MEDIUM-GRAPH-BLOCKS-PER-FILE)
            block-count (* MEDIUM-GRAPH-FILES MEDIUM-GRAPH-BLOCKS-PER-FILE)]
        (-> (load-files-parallel! conn files)
            (p/then (fn [time-ms]
                      (println (str "📊 Parallel load (medium): " time-ms "ms for " block-count " blocks"))
                      (println (str "   Rate: " (.toFixed (/ block-count (/ time-ms 1000)) 2) " blocks/sec"))
                      (is (pos? time-ms) "Should complete")
                      (tq/reset-queue!)
                      (done)))
            (p/catch (fn [e]
                       (is false (str "Test failed: " e))
                       (tq/reset-queue!)
                       (done))))))))

;; ============================================================================
;; Comparison Benchmark
;; ============================================================================

(deftest ^:benchmark load-comparison-benchmark
  (async done
    (testing "Sequential vs Parallel comparison"
      (let [files (generate-test-files SMALL-GRAPH-FILES SMALL-GRAPH-BLOCKS-PER-FILE)
            block-count (* SMALL-GRAPH-FILES SMALL-GRAPH-BLOCKS-PER-FILE)

            ;; Sequential test
            conn1 (gp-db/start-conn)
            seq-time (load-files-sequential! conn1 files)

            ;; Parallel test
            conn2 (gp-db/start-conn)]

        (-> (load-files-parallel! conn2 files)
            (p/then (fn [par-time]
                      (let [speedup (if (pos? par-time)
                                      (/ seq-time par-time)
                                      0)]
                        (println "\n" (string/join "" (repeat 60 "=")))
                        (println "📈 COMPARISON RESULTS (" block-count " blocks)")
                        (println (string/join "" (repeat 60 "=")))
                        (println (str "  Sequential: " seq-time "ms"))
                        (println (str "  Parallel:   " par-time "ms"))
                        (println (str "  Speedup:    " (.toFixed speedup 2) "x"))
                        (println (string/join "" (repeat 60 "=")))

                        ;; Verify DB integrity
                        (let [db1 @conn1
                              db2 @conn2
                              datoms1 (count (d/datoms db1 :eavt))
                              datoms2 (count (d/datoms db2 :eavt))]
                          (println (str "  Sequential datoms: " datoms1))
                          (println (str "  Parallel datoms:   " datoms2))
                          (is (= datoms1 datoms2) "Both methods should produce same datom count"))

                        (tq/reset-queue!)
                        (done))))
            (p/catch (fn [e]
                       (is false (str "Test failed: " e))
                       (tq/reset-queue!)
                       (done))))))))

;; ============================================================================
;; Worker Pool Status Check
;; ============================================================================

(deftest ^:benchmark worker-pool-status-benchmark
  (testing "Worker pool availability check"
    (let [status (pl/worker-pool-status)]
      (println "\n" (string/join "" (repeat 60 "=")))
      (println "🔧 WORKER POOL STATUS")
      (println (string/join "" (repeat 60 "=")))
      (println (str "  Initialized: " (:initialized? status)))
      (println (str "  Pool size:   " (:size status)))
      (println (str "  Loading:     " (:loading? status)))
      (if (:initialized? status)
        (println "  ✓ Workers available - true CPU parallelism enabled")
        (println "  ⚠ Workers unavailable - using main-thread fallback"))
      (println (string/join "" (repeat 60 "=")))
      (is true "Status check complete"))))

;; ============================================================================
;; Worker Pool Benchmark (Browser Only)
;; ============================================================================

(deftest ^:benchmark worker-pool-load-benchmark
  (async done
    (testing "Worker pool loading - small graph (browser only)"
      (let [status (pl/worker-pool-status)]
        (if (:initialized? status)
          ;; Real worker pool test
          (let [conn (gp-db/start-conn)
                files (generate-test-files SMALL-GRAPH-FILES SMALL-GRAPH-BLOCKS-PER-FILE)
                block-count (* SMALL-GRAPH-FILES SMALL-GRAPH-BLOCKS-PER-FILE)]
            (println (str "\n📊 Worker pool loading (" (:size status) " workers)..."))
            (-> (load-files-parallel! conn files)
                (p/then (fn [time-ms]
                          (println (str "📊 Worker pool load (small): " time-ms "ms for " block-count " blocks"))
                          (println (str "   Rate: " (.toFixed (/ block-count (/ time-ms 1000)) 2) " blocks/sec"))
                          (is (pos? time-ms) "Should complete")
                          (tq/reset-queue!)
                          (done)))
                (p/catch (fn [e]
                           (is false (str "Test failed: " e))
                           (tq/reset-queue!)
                           (done)))))
          ;; Fallback for Node.js
          (do
            (println "\n⚠ Worker pool not available (Node.js environment)")
            (println "  This benchmark requires browser/Electron with workers initialized.")
            (println "  To test workers, run in browser with:")
            (println "    (pl/init-worker-pool!)")
            (is true "Skipped - workers not available")
            (done)))))))

;; ============================================================================
;; Full Comparison (with Worker Pool)
;; ============================================================================

(deftest ^:benchmark full-comparison-benchmark
  (async done
    (testing "Full comparison: Sequential vs Parallel vs Worker Pool"
      (let [files (generate-test-files SMALL-GRAPH-FILES SMALL-GRAPH-BLOCKS-PER-FILE)
            block-count (* SMALL-GRAPH-FILES SMALL-GRAPH-BLOCKS-PER-FILE)
            status (pl/worker-pool-status)

            ;; Sequential test
            conn1 (gp-db/start-conn)
            seq-time (load-files-sequential! conn1 files)

            ;; Main-thread parallel test (workers disabled)
            _ (reset! pool/*pool nil)  ; Ensure no workers
            conn2 (gp-db/start-conn)]

        (-> (load-files-parallel! conn2 files)
            (p/then (fn [main-thread-time]
                      (let [speedup-main (if (pos? main-thread-time)
                                           (/ seq-time main-thread-time)
                                           0)]
                        (println "\n" (string/join "" (repeat 70 "=")))
                        (println "📈 FULL COMPARISON RESULTS (" block-count " blocks)")
                        (println (string/join "" (repeat 70 "=")))
                        (println (str "  1. Sequential:      " seq-time "ms"))
                        (println (str "  2. Main-thread:     " main-thread-time "ms (speedup: " (.toFixed speedup-main 2) "x)"))

                        (if (:initialized? status)
                          (println (str "  3. Worker pool:     (run with workers initialized)"))
                          (println (str "  3. Worker pool:     Not available (Node.js)")))

                        (println (string/join "" (repeat 70 "=")))
                        (println "")
                        (println "  Expected performance in browser with workers:")
                        (println "    - 4 workers: ~2-3x speedup for parsing")
                        (println "    - 8 workers: ~3-5x speedup for parsing")
                        (println "    (Actual gains depend on CPU cores and file complexity)")
                        (println (string/join "" (repeat 70 "=")))

                        (is (pos? seq-time) "Sequential should complete")
                        (is (pos? main-thread-time) "Main-thread parallel should complete")

                        (tq/reset-queue!)
                        (done))))
            (p/catch (fn [e]
                       (is false (str "Test failed: " e))
                       (tq/reset-queue!)
                       (done))))))))

;; ============================================================================
;; Baseline Reference
;; ============================================================================

(comment
  ;; Previous baseline results from sidecar-failed project (2025-12-09):
  ;;
  ;; | Graph Size | Files | Blocks | Load Time (ms) |
  ;; |------------|-------|--------|----------------|
  ;; | Small      | 100   | 1,000  | 1,123.80       |
  ;; | Medium     | 500   | 7,500  | 9,106.49       |
  ;;
  ;; Note: These were measured with direct DataScript transact, not file parsing.
  ;; File parsing adds overhead but parallel batching should offset it.
  ;;
  ;; Phase 5 Worker Pool Expected Results:
  ;; | Method           | Small Graph | Medium Graph | Notes              |
  ;; |------------------|-------------|--------------|--------------------|
  ;; | Sequential       | ~3,150ms    | ~9,100ms     | Baseline           |
  ;; | Main-thread      | ~3,300ms    | ~9,500ms     | No CPU parallelism |
  ;; | 4 Workers        | ~1,000ms    | ~3,000ms     | ~3x speedup        |
  ;; | 8 Workers        | ~700ms      | ~2,000ms     | ~4x speedup        |
  ;;
  ;; To run worker benchmarks in browser:
  ;; 1. Start dev server: yarn watch
  ;; 2. Open browser console at localhost:3001
  ;; 3. Initialize workers: (pl/init-worker-pool! 4)
  ;; 4. Run benchmarks via REPL or test runner
  )
