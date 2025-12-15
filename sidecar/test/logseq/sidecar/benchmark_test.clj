(ns logseq.sidecar.benchmark-test
  "Performance benchmarks for sidecar DataScript operations.

   Run with: clj -M:bench

   Performance Targets:
   | Metric                  | Target    | Notes                          |
   |-------------------------|-----------|--------------------------------|
   | Graph load (10k blocks) | < 500ms   | vs ~9000ms in web worker       |
   | Simple query            | < 1ms     | entity lookup by unique attr   |
   | Backlinks query         | < 10ms    | find refs to a page            |
   | Block transaction       | < 5ms     | single block insert            |
   | Memory (10k blocks)     | < 100MB   | vs ~500MB in web worker        |
   | Memory (100k blocks)    | < 500MB   | web worker OOMs                |"
  (:require [clojure.test :refer [deftest testing is]]
            [datascript.core :as d]
            [logseq.sidecar.storage :as storage]
            [logseq.sidecar.server :as server])
  (:import [java.lang.management ManagementFactory]))

;; =============================================================================
;; Utilities
;; =============================================================================

(defn current-memory-mb
  "Get current heap memory usage in MB."
  []
  (let [runtime (Runtime/getRuntime)]
    (/ (- (.totalMemory runtime) (.freeMemory runtime)) 1024.0 1024.0)))

(defn force-gc!
  "Force garbage collection and wait for it to complete."
  []
  (System/gc)
  (Thread/sleep 100)
  (System/gc)
  (Thread/sleep 100))

(defn measure-time
  "Measure execution time of f, returns [result time-ms]."
  [f]
  (let [start (System/nanoTime)
        result (f)
        end (System/nanoTime)]
    [result (/ (- end start) 1000000.0)]))

(defn measure-time-ms
  "Measure execution time of f, returns time in ms."
  [f]
  (second (measure-time f)))

(defn average [coll]
  (if (empty? coll)
    0
    (/ (reduce + coll) (count coll))))

(defn benchmark
  "Run f multiple times and return statistics.
   Returns {:mean :min :max :std-dev :runs}."
  [f & {:keys [warmup-runs bench-runs] :or {warmup-runs 3 bench-runs 10}}]
  ;; Warmup
  (dotimes [_ warmup-runs] (f))
  ;; Benchmark runs
  (let [times (mapv (fn [_] (measure-time-ms f)) (range bench-runs))
        mean (average times)
        min-t (apply min times)
        max-t (apply max times)
        variance (average (map #(Math/pow (- % mean) 2) times))
        std-dev (Math/sqrt variance)]
    {:mean mean
     :min min-t
     :max max-t
     :std-dev std-dev
     :runs bench-runs}))

(defn print-benchmark
  "Print benchmark results in a readable format."
  [name results target-ms]
  (let [{:keys [mean min max std-dev]} results
        pass? (<= mean target-ms)]
    (println (format "\n=== %s ===" name))
    (println (format "  Mean:    %.2f ms" mean))
    (println (format "  Min:     %.2f ms" min))
    (println (format "  Max:     %.2f ms" max))
    (println (format "  Std Dev: %.2f ms" std-dev))
    (println (format "  Target:  < %d ms %s" target-ms (if pass? "✓ PASS" "✗ FAIL")))
    pass?))

;; =============================================================================
;; Test Data Generation
;; =============================================================================

(def ^:private logseq-schema
  "Minimal Logseq schema for benchmarking."
  {:block/uuid {:db/unique :db.unique/identity}
   :block/name {:db/unique :db.unique/identity}
   :block/original-name {}
   :block/type {}
   :block/format {}
   :block/content {}
   :block/parent {:db/valueType :db.type/ref}
   :block/left {:db/valueType :db.type/ref}
   :block/page {:db/valueType :db.type/ref}
   :block/refs {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :block/tags {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}})

(defn generate-page
  "Generate a page entity."
  [idx]
  {:block/uuid (random-uuid)
   :block/name (str "page-" idx)
   :block/original-name (str "Page " idx)
   :block/type "page"
   :block/format :markdown})

(defn generate-block
  "Generate a block entity with parent reference."
  [idx page-id parent-id]
  (let [block {:block/uuid (random-uuid)
               :block/content (str "Block content " idx " - Lorem ipsum dolor sit amet, consectetur adipiscing elit.")
               :block/page page-id
               :block/parent parent-id
               :block/format :markdown}]
    ;; Add some refs to create backlinks (every 10th block refs page-0)
    (if (zero? (mod idx 10))
      (assoc block :block/refs [[:block/name "page-0"]])
      block)))

(defn generate-graph-data
  "Generate test graph data with pages and blocks.
   Returns vector of tx-data."
  [num-pages blocks-per-page]
  (let [pages (mapv generate-page (range num-pages))]
    (concat
     pages
     (for [page-idx (range num-pages)
           block-idx (range blocks-per-page)]
       (generate-block
        (+ (* page-idx blocks-per-page) block-idx)
        [:block/name (str "page-" page-idx)]
        [:block/name (str "page-" page-idx)])))))

;; =============================================================================
;; Benchmarks
;; =============================================================================

(deftest ^:benchmark graph-load-benchmark
  (testing "Graph load performance (10k blocks)"
    (println "\n" (apply str (repeat 60 "=")))
    (println "BENCHMARK: Graph Load (10k blocks)")
    (println (apply str (repeat 60 "=")))

    (let [;; Generate 100 pages x 100 blocks = 10,000 blocks
          tx-data (generate-graph-data 100 100)
          _ (println (format "Generated %d entities" (count tx-data)))

          ;; Benchmark: Create conn and transact all data
          results (benchmark
                   (fn []
                     (let [storage (storage/create-sqlite-storage ":memory:")
                           conn (d/create-conn logseq-schema {:storage storage :ref-type :soft})]
                       (d/transact! conn tx-data)
                       ;; Return block count for verification
                       (count (d/datoms @conn :eavt))))
                   :warmup-runs 2
                   :bench-runs 5)]

      (print-benchmark "Graph Load (10k blocks)" results 500))))

(deftest ^:benchmark simple-query-benchmark
  (testing "Simple query performance"
    (println "\n" (apply str (repeat 60 "=")))
    (println "BENCHMARK: Simple Query (entity lookup)")
    (println (apply str (repeat 60 "=")))

    (let [;; Setup: Create graph with 10k blocks
          storage (storage/create-sqlite-storage ":memory:")
          conn (d/create-conn logseq-schema {:storage storage :ref-type :soft})
          tx-data (generate-graph-data 100 100)
          _ (d/transact! conn tx-data)
          _ (println (format "Setup complete: %d datoms" (count (d/datoms @conn :eavt))))

          ;; Benchmark: Entity lookup by unique attribute
          results (benchmark
                   (fn []
                     (d/entity @conn [:block/name "page-50"]))
                   :warmup-runs 100
                   :bench-runs 1000)]

      (print-benchmark "Simple Query (entity lookup)" results 1))))

(deftest ^:benchmark pattern-query-benchmark
  (testing "Pattern query performance"
    (println "\n" (apply str (repeat 60 "=")))
    (println "BENCHMARK: Pattern Query (find pages)")
    (println (apply str (repeat 60 "=")))

    (let [;; Setup
          storage (storage/create-sqlite-storage ":memory:")
          conn (d/create-conn logseq-schema {:storage storage :ref-type :soft})
          tx-data (generate-graph-data 100 100)
          _ (d/transact! conn tx-data)

          ;; Benchmark: Find all pages
          results (benchmark
                   (fn []
                     (d/q '[:find ?e ?name
                            :where
                            [?e :block/type "page"]
                            [?e :block/name ?name]]
                          @conn))
                   :warmup-runs 10
                   :bench-runs 100)]

      (print-benchmark "Pattern Query (find pages)" results 10))))

(deftest ^:benchmark backlinks-query-benchmark
  (testing "Backlinks query performance"
    (println "\n" (apply str (repeat 60 "=")))
    (println "BENCHMARK: Backlinks Query")
    (println (apply str (repeat 60 "=")))

    (let [;; Setup
          storage (storage/create-sqlite-storage ":memory:")
          conn (d/create-conn logseq-schema {:storage storage :ref-type :soft})
          tx-data (generate-graph-data 100 100)
          _ (d/transact! conn tx-data)

          ;; Get page-0's entity id
          page-0-eid (:db/id (d/entity @conn [:block/name "page-0"]))
          _ (println (format "Finding backlinks to page-0 (eid: %d)" page-0-eid))

          ;; Benchmark: Find all blocks that reference page-0
          results (benchmark
                   (fn []
                     (d/q '[:find ?e ?content
                            :in $ ?page
                            :where
                            [?e :block/refs ?page]
                            [?e :block/content ?content]]
                          @conn page-0-eid))
                   :warmup-runs 10
                   :bench-runs 100)]

      (print-benchmark "Backlinks Query" results 10))))

(deftest ^:benchmark transaction-benchmark
  (testing "Transaction performance"
    (println "\n" (apply str (repeat 60 "=")))
    (println "BENCHMARK: Single Block Transaction")
    (println (apply str (repeat 60 "=")))

    (let [;; Setup
          storage (storage/create-sqlite-storage ":memory:")
          conn (d/create-conn logseq-schema {:storage storage :ref-type :soft})

          ;; Create a page to add blocks to
          _ (d/transact! conn [(generate-page 0)])

          ;; Benchmark: Single block insert
          counter (atom 0)
          results (benchmark
                   (fn []
                     (let [idx (swap! counter inc)]
                       (d/transact! conn [{:block/uuid (random-uuid)
                                           :block/content (str "New block " idx)
                                           :block/page [:block/name "page-0"]
                                           :block/parent [:block/name "page-0"]}])))
                   :warmup-runs 100
                   :bench-runs 1000)]

      (print-benchmark "Single Block Transaction" results 5))))

(deftest ^:benchmark batch-transaction-benchmark
  (testing "Batch transaction performance"
    (println "\n" (apply str (repeat 60 "=")))
    (println "BENCHMARK: Batch Transaction (100 blocks)")
    (println (apply str (repeat 60 "=")))

    (let [;; Setup
          storage (storage/create-sqlite-storage ":memory:")
          conn (d/create-conn logseq-schema {:storage storage :ref-type :soft})
          _ (d/transact! conn [(generate-page 0)])

          ;; Benchmark: Batch insert of 100 blocks
          counter (atom 0)
          results (benchmark
                   (fn []
                     (let [batch-start (swap! counter + 100)]
                       (d/transact! conn
                                    (for [i (range 100)]
                                      {:block/uuid (random-uuid)
                                       :block/content (str "Batch block " (+ batch-start i))
                                       :block/page [:block/name "page-0"]
                                       :block/parent [:block/name "page-0"]}))))
                   :warmup-runs 10
                   :bench-runs 50)]

      (print-benchmark "Batch Transaction (100 blocks)" results 50))))

(deftest ^:benchmark memory-usage-benchmark
  (testing "Memory usage with 10k blocks"
    (println "\n" (apply str (repeat 60 "=")))
    (println "BENCHMARK: Memory Usage (10k blocks)")
    (println (apply str (repeat 60 "=")))

    (force-gc!)
    (let [baseline-mb (current-memory-mb)
          _ (println (format "Baseline memory: %.1f MB" baseline-mb))

          ;; Create graph with 10k blocks
          storage (storage/create-sqlite-storage ":memory:")
          conn (d/create-conn logseq-schema {:storage storage :ref-type :soft})
          tx-data (generate-graph-data 100 100)
          _ (d/transact! conn tx-data)

          ;; Force realization of lazy sequences
          datom-count (count (d/datoms @conn :eavt))
          _ (println (format "Datoms created: %d" datom-count))

          after-mb (current-memory-mb)
          used-mb (- after-mb baseline-mb)
          target-mb 100
          pass? (<= used-mb target-mb)]

      (println (format "\n=== Memory Usage (10k blocks) ==="))
      (println (format "  Baseline:  %.1f MB" baseline-mb))
      (println (format "  After:     %.1f MB" after-mb))
      (println (format "  Used:      %.1f MB" used-mb))
      (println (format "  Target:    < %d MB %s" target-mb (if pass? "✓ PASS" "✗ FAIL")))

      (is pass? (format "Memory usage %.1f MB exceeds target %d MB" used-mb target-mb)))))

(deftest ^:benchmark memory-usage-100k-benchmark
  (testing "Memory usage with 100k blocks"
    (println "\n" (apply str (repeat 60 "=")))
    (println "BENCHMARK: Memory Usage (100k blocks)")
    (println (apply str (repeat 60 "=")))

    (force-gc!)
    (let [baseline-mb (current-memory-mb)
          _ (println (format "Baseline memory: %.1f MB" baseline-mb))

          ;; Create graph with 100k blocks (1000 pages x 100 blocks)
          storage (storage/create-sqlite-storage ":memory:")
          conn (d/create-conn logseq-schema {:storage storage :ref-type :soft})

          ;; Transact in batches to avoid huge tx
          _ (println "Transacting 100k blocks in batches...")
          _ (do (doseq [_batch-idx (range 10)]
                  (let [tx-data (generate-graph-data 100 100)]
                    (d/transact! conn tx-data))
                  (print ".") (flush))
                (println " done"))

          datom-count (count (d/datoms @conn :eavt))
          _ (println (format "Datoms created: %d" datom-count))

          after-mb (current-memory-mb)
          used-mb (- after-mb baseline-mb)
          target-mb 500
          pass? (<= used-mb target-mb)]

      (println (format "\n=== Memory Usage (100k blocks) ==="))
      (println (format "  Baseline:  %.1f MB" baseline-mb))
      (println (format "  After:     %.1f MB" after-mb))
      (println (format "  Used:      %.1f MB" used-mb))
      (println (format "  Target:    < %d MB %s" target-mb (if pass? "✓ PASS" "✗ FAIL")))

      (is pass? (format "Memory usage %.1f MB exceeds target %d MB" used-mb target-mb)))))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main
  "Run all benchmarks."
  [& _args]
  (println "\n")
  (println (apply str (repeat 60 "=")))
  (println "        LOGSIDIAN SIDECAR PERFORMANCE BENCHMARKS")
  (println (apply str (repeat 60 "=")))
  (println)
  (println "Performance Targets:")
  (println "  - Graph load (10k blocks):  < 500ms")
  (println "  - Simple query:             < 1ms")
  (println "  - Pattern query:            < 10ms")
  (println "  - Backlinks query:          < 10ms")
  (println "  - Single transaction:       < 5ms")
  (println "  - Batch transaction (100):  < 50ms")
  (println "  - Memory (10k blocks):      < 100MB")
  (println "  - Memory (100k blocks):     < 500MB")
  (println)

  ;; Run benchmarks
  (graph-load-benchmark)
  (simple-query-benchmark)
  (pattern-query-benchmark)
  (backlinks-query-benchmark)
  (transaction-benchmark)
  (batch-transaction-benchmark)
  (memory-usage-benchmark)
  (memory-usage-100k-benchmark)

  (println "\n")
  (println (apply str (repeat 60 "=")))
  (println "        BENCHMARKS COMPLETE")
  (println (apply str (repeat 60 "="))))
