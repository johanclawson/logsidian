(ns logseq.sidecar.mldoc-benchmark
  "Performance benchmarks for GraalJS mldoc parsing.

   Run benchmarks:
   cd sidecar && clj -J-Dpolyglot.engine.WarnInterpreterOnly=false -M:test -i benchmark

   Acceptance criteria:
   - Per-file latency < 50ms for small files (< 1KB)
   - Per-file latency < 200ms for medium files (< 10KB)
   - Per-file latency < 500ms for large files (< 100KB)
   - Batch throughput: 1000 files in < 60s"
  (:require [clojure.test :refer [deftest testing is]]
            [logseq.sidecar.mldoc :as mldoc]))

;; =============================================================================
;; Test Data Generators
;; =============================================================================

(defn generate-small-file
  "Generate ~500 bytes of markdown"
  []
  (str "# Page Title\n\n"
       "tags:: tag1, tag2\n\n"
       "- Block 1 with some content\n"
       "- Block 2 with more content\n"
       "  - Nested block A\n"
       "  - Nested block B\n"
       "- Block 3 with [[Page Link]]\n"))

(defn generate-medium-file
  "Generate ~5KB of markdown"
  []
  (let [base (generate-small-file)
        blocks (repeatedly 50 #(str "- " (apply str (repeat 80 "x")) "\n"))]
    (str base (apply str blocks))))

(defn generate-large-file
  "Generate ~50KB of markdown"
  []
  (let [base (generate-small-file)
        blocks (repeatedly 500 #(str "- " (apply str (repeat 80 "x"))
                                     " with [[Link " (rand-int 100) "]] and more\n"))]
    (str base (apply str blocks))))

;; =============================================================================
;; Timing Utilities
;; =============================================================================

(defn measure-time-ms
  "Measure execution time in milliseconds"
  [f]
  (let [start (System/nanoTime)
        result (f)
        end (System/nanoTime)]
    {:result result
     :time-ms (/ (- end start) 1000000.0)}))

(defn run-benchmark
  "Run a function n times and return timing statistics"
  [n f]
  (let [times (doall (repeatedly n #(:time-ms (measure-time-ms f))))
        sorted (sort times)]
    {:count n
     :min (first sorted)
     :max (last sorted)
     :mean (/ (reduce + times) n)
     :median (nth sorted (/ n 2))
     :p95 (nth sorted (int (* n 0.95)))
     :p99 (nth sorted (int (* n 0.99)))}))

(defn format-stats
  "Format benchmark stats for display"
  [{:keys [count min max mean median p95 p99]}]
  (format "n=%d min=%.1fms mean=%.1fms median=%.1fms p95=%.1fms p99=%.1fms max=%.1fms"
          count min mean median p95 p99 max))

;; =============================================================================
;; Warm-up
;; =============================================================================

(defn warm-up!
  "Warm up the GraalJS context with several parses"
  []
  (println "Warming up GraalJS context...")
  (dotimes [_ 10]
    (mldoc/parse-json (generate-small-file) :markdown))
  (println "Warm-up complete."))

;; =============================================================================
;; Individual Benchmarks
;; =============================================================================

(deftest ^:benchmark test-cold-start
  (testing "Cold start latency (first parse after reinit)"
    (mldoc/reinitialize!)
    (let [{:keys [time-ms]} (measure-time-ms
                             #(mldoc/parse-json "# Test" :markdown))]
      (println (format "Cold start: %.1fms" time-ms))
      ;; Cold start can be slow, but should be < 5s
      (is (< time-ms 5000) "Cold start should be < 5 seconds"))))

(deftest ^:benchmark test-small-file-latency
  (testing "Small file parsing latency (~500 bytes)"
    (warm-up!)
    (let [content (generate-small-file)
          stats (run-benchmark 100 #(mldoc/parse-json content :markdown))]
      (println "Small file:" (format-stats stats))
      (is (< (:p95 stats) 50) "95th percentile should be < 50ms"))))

(deftest ^:benchmark test-medium-file-latency
  (testing "Medium file parsing latency (~5KB)"
    (warm-up!)
    (let [content (generate-medium-file)
          stats (run-benchmark 50 #(mldoc/parse-json content :markdown))]
      (println "Medium file:" (format-stats stats))
      (is (< (:p95 stats) 200) "95th percentile should be < 200ms"))))

(deftest ^:benchmark test-large-file-latency
  (testing "Large file parsing latency (~50KB)"
    (warm-up!)
    (let [content (generate-large-file)
          stats (run-benchmark 20 #(mldoc/parse-json content :markdown))]
      (println "Large file:" (format-stats stats))
      (is (< (:p95 stats) 500) "95th percentile should be < 500ms"))))

(deftest ^:benchmark test-batch-throughput
  (testing "Batch throughput (1000 small files)"
    (warm-up!)
    (let [files (repeatedly 1000 generate-small-file)
          {:keys [time-ms]} (measure-time-ms
                             #(doall (map (fn [f] (mldoc/parse-json f :markdown)) files)))]
      (println (format "Batch 1000 files: %.1fms (%.1f files/sec)"
                       time-ms (/ 1000.0 (/ time-ms 1000))))
      (is (< time-ms 60000) "1000 files should complete in < 60 seconds"))))

(deftest ^:benchmark test-concurrent-throughput
  (testing "Concurrent batch throughput (1000 files, parallel)"
    (warm-up!)
    (let [files (repeatedly 1000 generate-small-file)
          {:keys [time-ms]} (measure-time-ms
                             #(doall (pmap (fn [f] (mldoc/parse-json f :markdown)) files)))]
      (println (format "Concurrent 1000 files: %.1fms (%.1f files/sec)"
                       time-ms (/ 1000.0 (/ time-ms 1000))))
      ;; Concurrent should be similar due to locking, but not worse
      (is (< time-ms 120000) "Concurrent batch should complete in < 120 seconds"))))

;; =============================================================================
;; Memory Benchmark
;; =============================================================================

(deftest ^:benchmark test-memory-usage
  (testing "Memory usage estimation"
    (warm-up!)
    (System/gc)
    (Thread/sleep 100)
    (let [runtime (Runtime/getRuntime)
          before-used (- (.totalMemory runtime) (.freeMemory runtime))]
      ;; Parse 100 files
      (dotimes [_ 100]
        (mldoc/parse-json (generate-medium-file) :markdown))
      (System/gc)
      (Thread/sleep 100)
      (let [after-used (- (.totalMemory runtime) (.freeMemory runtime))
            delta-mb (/ (- after-used before-used) 1048576.0)]
        (println (format "Memory delta after 100 parses: %.1f MB" delta-mb))
        ;; Should not leak more than 100MB
        (is (< delta-mb 100) "Memory growth should be < 100MB")))))

;; =============================================================================
;; Full Benchmark Suite
;; =============================================================================

(defn run-all-benchmarks
  "Run all benchmarks and print summary"
  []
  (println "\n========================================")
  (println "GraalJS Mldoc Benchmark Suite")
  (println "========================================\n")

  (println "1. Cold Start")
  (test-cold-start)

  (println "\n2. Single File Latency")
  (test-small-file-latency)
  (test-medium-file-latency)
  (test-large-file-latency)

  (println "\n3. Batch Throughput")
  (test-batch-throughput)
  (test-concurrent-throughput)

  (println "\n4. Memory Usage")
  (test-memory-usage)

  (println "\n========================================")
  (println "Benchmark Complete")
  (println "========================================\n"))

(comment
  ;; Run all benchmarks:
  (run-all-benchmarks)

  ;; Run individual benchmarks:
  (test-cold-start)
  (test-small-file-latency)
  (test-batch-throughput)

  ;; Generate test data:
  (count (generate-small-file))   ;; ~500 bytes
  (count (generate-medium-file))  ;; ~5KB
  (count (generate-large-file))   ;; ~50KB
  )
