(ns frontend.sidecar.baseline-benchmark-test
  "Baseline performance benchmarks to measure before sidecar implementation.
   These tests capture current performance characteristics that the sidecar
   should improve upon.

   Run with: bb benchmark:baseline
   Results output to: docs/tests/performance_before_sidecar.md"
  (:require [cljs.test :refer [deftest testing is use-fixtures]]
            [datascript.core :as d]
            [frontend.db.conn :as conn]
            [frontend.state :as state]
            [frontend.test.helper :as test-helper]
            [frontend.util :as util]
            [logseq.db.common.order :as db-order]
            [logseq.db.sqlite.util :as sqlite-util]
            [clojure.string :as string]))

;; ============================================================================
;; Test Configuration
;; ============================================================================

(def ^:const SMALL-GRAPH-PAGES 100)
(def ^:const SMALL-GRAPH-BLOCKS-PER-PAGE 10)

(def ^:const MEDIUM-GRAPH-PAGES 500)
(def ^:const MEDIUM-GRAPH-BLOCKS-PER-PAGE 15)

(def ^:const LARGE-GRAPH-PAGES 2000)
(def ^:const LARGE-GRAPH-BLOCKS-PER-PAGE 10)

;; Storage for benchmark results
(def ^:private *benchmark-results (atom []))

;; Seeded random number generator for reproducible benchmarks
(def ^:private *rng-seed (atom 42))

(defn- seeded-rand-int
  "Returns a pseudo-random integer in range [0, n) using a seeded LCG.
   Uses same algorithm as java.util.Random for cross-platform consistency."
  [n]
  (let [seed @*rng-seed
        ;; Linear Congruential Generator (same as java.util.Random)
        new-seed (bit-and (+ (* seed 0x5DEECE66D) 0xB) 0xFFFFFFFFFFFF)]
    (reset! *rng-seed new-seed)
    (mod (bit-shift-right new-seed 17) n)))

(defn- reset-rng! []
  (reset! *rng-seed 42))

;; ============================================================================
;; Graph Generation Utilities
;; ============================================================================

(defn- generate-uuid []
  (random-uuid))

(defn- generate-block-content [idx]
  (str "Block content " idx " - Lorem ipsum dolor sit amet, consectetur adipiscing elit. "
       "[[PageLink" (seeded-rand-int 100) "]] #tag" (seeded-rand-int 20) " "
       "TODO: Sample task item"))

(defn- generate-page-with-blocks
  "Generate a single page with n blocks"
  [page-idx blocks-per-page]
  (let [page-uuid (generate-uuid)
        page-name (str "BenchPage-" page-idx)
        page-id [:block/uuid page-uuid]]
    {:page {:block/uuid page-uuid
            :block/name (string/lower-case page-name)
            :block/title page-name}
     :blocks (vec
              (for [block-idx (range blocks-per-page)]
                (let [block-uuid (generate-uuid)]
                  {:block/uuid block-uuid
                   :block/page page-id
                   :block/parent page-id
                   :block/order (db-order/gen-key nil)
                   :block/title (generate-block-content (+ (* page-idx blocks-per-page) block-idx))})))}))

(defn- generate-test-graph-data
  "Generate test graph data with specified dimensions"
  [num-pages blocks-per-page]
  (mapv #(generate-page-with-blocks % blocks-per-page) (range num-pages)))

(defn- flatten-graph-data
  "Convert graph data to flat transaction format"
  [graph-data]
  (reduce
   (fn [acc {:keys [page blocks]}]
     (into (conj acc (sqlite-util/block-with-timestamps page))
           (map sqlite-util/block-with-timestamps blocks)))
   []
   graph-data))

;; ============================================================================
;; Benchmark Recording
;; ============================================================================

(defn- record-benchmark!
  "Record a benchmark result"
  [category metric value unit]
  (swap! *benchmark-results conj
         {:category category
          :metric metric
          :value value
          :unit unit
          :timestamp (js/Date.now)}))

(defn- clear-benchmarks! []
  (reset! *benchmark-results []))

(defn get-benchmark-results []
  @*benchmark-results)

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(use-fixtures :each
  {:before (fn []
             (clear-benchmarks!)
             (reset-rng!))})

;; ============================================================================
;; Memory Measurement
;; ============================================================================

(defn- safe-gc!
  "Force GC if available (requires --expose-gc Node.js flag)"
  []
  (when (and (exists? js/gc) (fn? js/gc))
    (js/gc)))

(defn- get-heap-size-mb
  "Get current JS heap size in MB (Chrome/Node only)"
  []
  (when (exists? js/performance.memory)
    (/ (.-usedJSHeapSize js/performance.memory) (* 1024 1024))))

(defn- _measure-memory-delta
  "Measure memory change during operation (returns nil if not available)"
  [operation-fn]
  (when (exists? js/performance.memory)
    (safe-gc!)
    (let [before (.-usedJSHeapSize js/performance.memory)
          _ (operation-fn)
          after (.-usedJSHeapSize js/performance.memory)]
      (/ (- after before) (* 1024 1024)))))

;; ============================================================================
;; Benchmark Tests
;; ============================================================================

(deftest ^:benchmark graph-generation-benchmark
  (testing "Graph data generation performance"
    (let [{:keys [time _result]} (util/with-time
                                  (generate-test-graph-data SMALL-GRAPH-PAGES
                                                            SMALL-GRAPH-BLOCKS-PER-PAGE))]
      (record-benchmark! "generation"
                         (str "generate-" SMALL-GRAPH-PAGES "-pages")
                         time "ms")
      (is (< time 5000)
          (str "Small graph generation should complete in <5s (took " time "ms)")))))

(deftest ^:benchmark database-load-small-benchmark
  (testing "Small graph database load time"
    (test-helper/start-and-destroy-db
     (fn []
       (let [graph-data (generate-test-graph-data SMALL-GRAPH-PAGES
                                                   SMALL-GRAPH-BLOCKS-PER-PAGE)
             tx-data (flatten-graph-data graph-data)
             repo (state/get-current-repo)
             conn (conn/get-db repo false)
             {:keys [time]} (util/with-time (d/transact! conn tx-data))]
         (record-benchmark! "db-load"
                            (str "load-" SMALL-GRAPH-PAGES "-pages-"
                                 (* SMALL-GRAPH-PAGES SMALL-GRAPH-BLOCKS-PER-PAGE) "-blocks")
                            time "ms")
         (println (str "📊 Small graph load: " time "ms"))
         (is (< time 10000)
             (str "Small graph load should complete in <10s (took " time "ms)")))))))

(deftest ^:benchmark database-load-medium-benchmark
  (testing "Medium graph database load time"
    (test-helper/start-and-destroy-db
     (fn []
       (let [graph-data (generate-test-graph-data MEDIUM-GRAPH-PAGES
                                                   MEDIUM-GRAPH-BLOCKS-PER-PAGE)
             tx-data (flatten-graph-data graph-data)
             repo (state/get-current-repo)
             conn (conn/get-db repo false)
             {:keys [time]} (util/with-time (d/transact! conn tx-data))]
         (record-benchmark! "db-load"
                            (str "load-" MEDIUM-GRAPH-PAGES "-pages-"
                                 (* MEDIUM-GRAPH-PAGES MEDIUM-GRAPH-BLOCKS-PER-PAGE) "-blocks")
                            time "ms")
         (println (str "📊 Medium graph load: " time "ms"))
         ;; Medium graphs can take longer
         (is (< time 60000)
             (str "Medium graph load should complete in <60s (took " time "ms)")))))))

(deftest ^:benchmark query-simple-benchmark
  (testing "Simple entity lookup query performance"
    (test-helper/start-and-destroy-db
     (fn []
       (let [graph-data (generate-test-graph-data SMALL-GRAPH-PAGES
                                                   SMALL-GRAPH-BLOCKS-PER-PAGE)
             tx-data (flatten-graph-data graph-data)
             repo (state/get-current-repo)
             conn (conn/get-db repo false)
             _ (d/transact! conn tx-data)
             db @conn
             ;; Run query multiple times for more stable measurement
             iterations 100
             {:keys [time]} (util/with-time
                              (dotimes [_ iterations]
                                (d/q '[:find ?e
                                       :where [?e :block/name "benchpage-50"]]
                                     db)))
             avg-time (/ time iterations)]
         (record-benchmark! "query" "simple-entity-lookup-avg" avg-time "ms")
         (println (str "📊 Simple query avg: " avg-time "ms (over " iterations " iterations)"))
         (is (< avg-time 10)
             (str "Simple query should avg <10ms (took " avg-time "ms)")))))))

(deftest ^:benchmark query-backlinks-benchmark
  (testing "Backlinks query performance"
    (test-helper/start-and-destroy-db
     (fn []
       (let [graph-data (generate-test-graph-data SMALL-GRAPH-PAGES
                                                   SMALL-GRAPH-BLOCKS-PER-PAGE)
             tx-data (flatten-graph-data graph-data)
             repo (state/get-current-repo)
             conn (conn/get-db repo false)
             _ (d/transact! conn tx-data)
             db @conn
             iterations 50
             {:keys [time]} (util/with-time
                              (dotimes [_ iterations]
                                ;; Simulated backlinks query pattern
                                (d/q '[:find ?b ?content
                                       :where
                                       [?b :block/title ?content]
                                       [(clojure.string/includes? ?content "PageLink50")]]
                                     db)))
             avg-time (/ time iterations)]
         (record-benchmark! "query" "backlinks-pattern-avg" avg-time "ms")
         (println (str "📊 Backlinks query avg: " avg-time "ms"))
         (is (< avg-time 100)
             (str "Backlinks query should avg <100ms (took " avg-time "ms)")))))))

(deftest ^:benchmark query-property-filter-benchmark
  (testing "Property filter query performance"
    (test-helper/start-and-destroy-db
     (fn []
       (let [graph-data (generate-test-graph-data SMALL-GRAPH-PAGES
                                                   SMALL-GRAPH-BLOCKS-PER-PAGE)
             tx-data (flatten-graph-data graph-data)
             repo (state/get-current-repo)
             conn (conn/get-db repo false)
             _ (d/transact! conn tx-data)
             db @conn
             iterations 50
             {:keys [time]} (util/with-time
                              (dotimes [_ iterations]
                                ;; Query blocks containing TODO
                                (d/q '[:find ?b ?content
                                       :where
                                       [?b :block/title ?content]
                                       [(clojure.string/includes? ?content "TODO")]]
                                     db)))
             avg-time (/ time iterations)]
         (record-benchmark! "query" "property-filter-avg" avg-time "ms")
         (println (str "📊 Property filter query avg: " avg-time "ms"))
         (is (< avg-time 100)
             (str "Property filter query should avg <100ms (took " avg-time "ms)")))))))

(deftest ^:benchmark query-pull-entity-benchmark
  (testing "Pull entity pattern performance"
    (test-helper/start-and-destroy-db
     (fn []
       (let [graph-data (generate-test-graph-data SMALL-GRAPH-PAGES
                                                   SMALL-GRAPH-BLOCKS-PER-PAGE)
             tx-data (flatten-graph-data graph-data)
             repo (state/get-current-repo)
             conn (conn/get-db repo false)
             _ (d/transact! conn tx-data)
             db @conn
             ;; Get a page entity ID
             page-eid (ffirst (d/q '[:find ?e :where [?e :block/name "benchpage-50"]] db))
             iterations 100
             {:keys [time]} (util/with-time
                              (dotimes [_ iterations]
                                (d/pull db '[*] page-eid)))
             avg-time (/ time iterations)]
         (record-benchmark! "query" "pull-entity-avg" avg-time "ms")
         (println (str "📊 Pull entity avg: " avg-time "ms"))
         (is (< avg-time 5)
             (str "Pull entity should avg <5ms (took " avg-time "ms)")))))))

(deftest ^:benchmark transaction-single-block-benchmark
  (testing "Single block transaction performance"
    (test-helper/start-and-destroy-db
     (fn []
       (let [repo (state/get-current-repo)
             conn (conn/get-db repo false)
             iterations 50
             {:keys [time]} (util/with-time
                              (dotimes [i iterations]
                                (d/transact! conn
                                             [(sqlite-util/block-with-timestamps
                                               {:block/uuid (generate-uuid)
                                                :block/title (str "Transaction test block " i)
                                                :block/name (str "tx-test-page-" i)})])))
             avg-time (/ time iterations)]
         (record-benchmark! "transaction" "single-block-avg" avg-time "ms")
         (println (str "📊 Single block tx avg: " avg-time "ms"))
         (is (< avg-time 20)
             (str "Single block tx should avg <20ms (took " avg-time "ms)")))))))

(deftest ^:benchmark datom-count-benchmark
  (testing "Datom count for different graph sizes"
    (test-helper/start-and-destroy-db
     (fn []
       (let [graph-data (generate-test-graph-data SMALL-GRAPH-PAGES
                                                   SMALL-GRAPH-BLOCKS-PER-PAGE)
             tx-data (flatten-graph-data graph-data)
             repo (state/get-current-repo)
             conn (conn/get-db repo false)
             _ (d/transact! conn tx-data)
             db @conn
             datom-count (count (d/datoms db :eavt))]
         (record-benchmark! "storage"
                            (str "datom-count-" SMALL-GRAPH-PAGES "-pages")
                            datom-count "datoms")
         (println (str "📊 Datom count (small graph): " datom-count)))))))

(deftest ^:benchmark memory-usage-benchmark
  (testing "Memory usage for graph loading"
    (when (exists? js/performance.memory)
      (test-helper/start-and-destroy-db
       (fn []
         (safe-gc!)
         (let [before-mb (get-heap-size-mb)
               graph-data (generate-test-graph-data SMALL-GRAPH-PAGES
                                                     SMALL-GRAPH-BLOCKS-PER-PAGE)
               tx-data (flatten-graph-data graph-data)
               repo (state/get-current-repo)
               conn (conn/get-db repo false)
               _ (d/transact! conn tx-data)
               after-mb (get-heap-size-mb)
               delta-mb (when (and before-mb after-mb) (- after-mb before-mb))]
           (when delta-mb
             (record-benchmark! "memory" "heap-delta-small-graph" delta-mb "MB")
             (println (str "📊 Memory delta (small graph): " (.toFixed delta-mb 2) " MB")))))))))

;; ============================================================================
;; Result Formatting
;; ============================================================================

(defn format-results-markdown
  "Format benchmark results as markdown for documentation"
  [results]
  (let [grouped (group-by :category results)
        timestamp (js/Date.)]
    (str
     "# Performance Baseline (Before Sidecar Implementation)\n\n"
     "**Generated:** " (.toISOString timestamp) "\n\n"
     "**System:** Browser-based DataScript (no JVM sidecar)\n\n"
     "---\n\n"
     "## Summary\n\n"
     "This document captures baseline performance metrics before implementing the JVM sidecar.\n"
     "These numbers serve as the \"before\" comparison for measuring sidecar improvements.\n\n"
     "### Target Improvements (from sidecar plan)\n\n"
     "| Metric | Current Baseline | Target | Improvement |\n"
     "|--------|-----------------|--------|-------------|\n"
     "| Startup (2K pages) | 30-90 sec | < 5 sec | 6-18x faster |\n"
     "| Query latency (simple) | 50-200 ms | < 20 ms | 2.5-10x faster |\n"
     "| Query latency (complex) | 200-800 ms | < 100 ms | 2-8x faster |\n"
     "| Memory (2K pages) | 400-800 MB | < 300 MB | 25-60% less |\n"
     "| IPC round-trip | N/A | < 5 ms | new metric |\n\n"
     "---\n\n"
     "## Benchmark Results\n\n"
     (apply str
            (for [[category items] (sort-by first grouped)]
              (str "### " (string/capitalize category) "\n\n"
                   "| Metric | Value | Unit |\n"
                   "|--------|-------|------|\n"
                   (apply str
                          (for [{:keys [metric value unit]} (sort-by :metric items)]
                            (str "| " metric " | " (if (number? value)
                                                     (.toFixed value 2)
                                                     value) " | " unit " |\n")))
                   "\n")))
     "---\n\n"
     "## Test Configuration\n\n"
     "| Parameter | Value |\n"
     "|-----------|-------|\n"
     "| Small Graph | " SMALL-GRAPH-PAGES " pages × " SMALL-GRAPH-BLOCKS-PER-PAGE " blocks |\n"
     "| Medium Graph | " MEDIUM-GRAPH-PAGES " pages × " MEDIUM-GRAPH-BLOCKS-PER-PAGE " blocks |\n"
     "| Large Graph | " LARGE-GRAPH-PAGES " pages × " LARGE-GRAPH-BLOCKS-PER-PAGE " blocks |\n\n"
     "## Notes\n\n"
     "- All timings measured using `frontend.util/with-time` macro\n"
     "- Query benchmarks averaged over multiple iterations for stability\n"
     "- Memory measurements only available in environments with `performance.memory` API\n"
     "- Run benchmarks with: `bb benchmark:baseline`\n")))

(defn print-results-summary
  "Print a summary of benchmark results to console"
  []
  (let [results (get-benchmark-results)]
    (println "\n" (string/join "" (repeat 60 "=")) "\n")
    (println "📈 BENCHMARK RESULTS SUMMARY")
    (println (string/join "" (repeat 60 "=")) "\n")
    (doseq [[category items] (sort-by first (group-by :category results))]
      (println (str "📁 " (string/upper-case category)))
      (doseq [{:keys [metric value unit]} (sort-by :metric items)]
        (println (str "   " metric ": " (if (number? value)
                                          (.toFixed value 2)
                                          value) " " unit)))
      (println))
    (println (string/join "" (repeat 60 "=")))))
