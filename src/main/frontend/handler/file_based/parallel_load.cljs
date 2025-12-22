(ns frontend.handler.file-based.parallel-load
  "Parallel graph loading implementation.
   Uses batch parsing with conflict detection and serialized transactions.

   Phase 4 of the parallel loading TDD plan:
   - Step 13: Commit executor with batching
   - Step 14: Integration entry points

   Phase 5 additions:
   - Step 18: Web Worker integration for true CPU parallelism"
  (:require [clojure.core.async :as async]
            [datascript.core :as d]
            [frontend.db.transact-queue :as tq]
            [frontend.state :as state]
            [frontend.worker.parse-worker-pool :as pool]
            [logseq.db :as ldb]
            [logseq.graph-parser :as graph-parser]
            [logseq.graph-parser.batch :as batch]
            [promesa.core :as p]))

;; =============================================================================
;; Step 13: Commit Executor with Batching
;; =============================================================================

(defn- parse-file-isolated
  "Parse a single file with error isolation.
   Returns wrapped result with :ok or :error status.
   Strips :ast from result for memory efficiency (matches worker behavior)."
  [db file opts]
  (try
    (let [result (-> (graph-parser/parse-file-data
                      (:file/path file)
                      (:file/content file)
                      {:db db
                       :extract-options opts
                       :ctime (:ctime (:stat file))
                       :mtime (:mtime (:stat file))})
                     ;; Strip AST to reduce memory usage (parity with worker path)
                     (dissoc :ast))]
      (batch/wrap-parse-result (:file/path file) result))
    (catch :default e
      (batch/wrap-parse-error (:file/path file) e))))

(defn- report-progress!
  "Report parsing progress to state."
  [file-path finished total]
  (state/set-parsing-state!
   (fn [m]
     (-> m
         (assoc :current-parsing-file file-path)
         (assoc :finished finished)
         (assoc :total total)))))

(defn parse-files-parallel
  "Parse multiple files with error isolation.
   Returns promise resolving to sequence of parse results IN ORIGINAL ORDER.

   Arguments:
   - db: Database snapshot
   - files: Sequence of file maps with :file/path and :file/content
   - opts: Extract options

   Note: This is a sequential operation on the main thread. The 'parallel'
   in the name refers to its role in the overall parallel loading strategy
   (workers provide true parallelism when available). Results are returned
   in the same order as input files to ensure deterministic conflict
   resolution (first file wins in deduplication)."
  [db files opts]
  (p/resolved
   (let [total (count files)]
     (if (zero? total)
       []
       (doall
        (map-indexed
         (fn [idx file]
           (let [result (parse-file-isolated db file opts)]
             (report-progress! (:file/path file) (inc idx) total)
             result))
         files))))))

(defn execute-load-plan!
  "Execute a validated load plan by committing batches to the database.

   Arguments:
   - conn: DataScript connection
   - plan: Result from batch/plan-graph-load with :status :ok
   - opts: Options map with :batch-size (default 50)

   Returns promise resolving to:
   {:status :ok :committed n}
   or
   {:status :error :error e :committed n}"
  [conn plan opts]
  (if (not= :ok (:status plan))
    (p/resolved {:status :error
                 :error "Cannot execute plan with errors"
                 :committed 0})
    (let [batch-size (or (:batch-size opts) 50)
          parsed-files (:parsed-files plan)
          batches (batch/create-batches parsed-files batch-size)
          committed (atom 0)]
      (p/loop [remaining batches]
        (if (empty? remaining)
          {:status :ok :committed @committed}
          (let [batch (first remaining)
                tx-data (batch/build-batch-tx batch [])]
            (-> (tq/enqueue-transact! conn tx-data {:from-disk? true})
                (p/then (fn [_]
                          (swap! committed + (count batch))
                          (p/recur (rest remaining))))
                (p/catch (fn [e]
                           {:status :error
                            :error e
                            :committed @committed})))))))))

;; =============================================================================
;; Step 18: Web Worker Integration (Phase 5)
;; =============================================================================

(def ^:const MIN-FILES-FOR-WORKERS
  "Minimum file count to use worker pool. For small batches,
   worker overhead (creation, serialization) exceeds benefits."
  10)

(defn use-worker-pool?
  "Determine if worker pool should be used.
   Uses workers for larger batches when pool is available and not busy.
   CODEX: Also checks loading state to prevent concurrent attempts."
  [file-count]
  (and (pool/pool-initialized?)
       (not (pool/loading?))
       (> file-count MIN-FILES-FOR-WORKERS)))

(defn parse-files-with-workers
  "Parse files using worker pool, returning results compatible with batch.cljc.

   Arguments:
   - db: Database snapshot (passed to workers via opts)
   - files: Sequence of file maps with :file/path, :file/content, and optionally :stat
   - opts: Extract options

   Returns: Promise resolving to sequence of wrapped parse results

   Note: Files should include :stat {:ctime ... :mtime ...} for timestamp metadata.
   Worker receives both files (with stat) and opts, mirroring main-thread path.
   CODEX: Reports progress after worker parsing completes."
  [db files opts]
  (let [total (count files)
        ;; Include stat/timestamp data with each file for parity with main-thread path
        files-with-stat (mapv (fn [f]
                                (select-keys f [:file/path :file/content :stat]))
                              files)]
    (-> (pool/parse-files-parallel!
         files-with-stat
         {:db db
          :extract-options opts})
        (p/then (fn [results]
                  ;; CODEX: Report progress after worker parsing completes
                  (report-progress! "workers-complete" total total)
                  ;; Convert worker results to batch.cljc format
                  (mapv (fn [r]
                          (if (= :ok (:status r))
                            (batch/wrap-parse-result (:file-path r) (:result r))
                            ;; Preserve stack trace in ex-data for debuggability
                            (batch/wrap-parse-error
                             (:file-path r)
                             (ex-info (or (:error r) "Unknown worker error")
                                      {:stack (:stack r)
                                       :message (:message r)}))))
                        results)))
        (p/catch (fn [e]
                   ;; Check if this is an "already loading" guard rejection
                   ;; If so, do NOT fall back - that would defeat the guard's purpose
                   (if (= :already-loading (:code (ex-data e)))
                     (p/rejected e)
                     ;; Other failures (worker creation, timeout, etc.) - fall back to main thread
                     (do
                       (js/console.warn "Worker pool failed, falling back to main thread:" e)
                       (parse-files-parallel db files opts))))))))

;; =============================================================================
;; Step 14: Integration Entry Points
;; =============================================================================

(defn- clear-parsing-state!
  "Clear all parsing state after load completes."
  []
  (state/set-parsing-state!
   (fn [_]
     {:graph-loading? false
      :total 0
      :finished 0
      :current-parsing-file nil})))

(defn load-graph-parallel!
  "Main entry point for parallel graph loading.
   Parses all files, validates for conflicts, then commits in batches.

   IMPORTANT: This function is designed for INITIAL graph loading only.
   It does NOT handle delete operations for existing blocks/pages.
   For reloads or incremental updates, use the standard sequential path.

   Arguments:
   - conn: DataScript connection (should be fresh/empty for initial load)
   - files: Sequence of file maps
   - opts: Options map with :batch-size (default 50)

   Returns promise resolving to:
   {:status :ok :files-loaded n}
   or
   {:status :error :parse-errors [...] :conflicts [...]}

   This function:
   1. Parses all files with error isolation (using Web Workers when available)
   2. Validates for UUID and page conflicts
   3. Commits in batches if validation passes

   Note: Validation is performed against a DB snapshot taken before parsing.
   Concurrent modifications between parse and commit are not re-validated.

   Phase 5: When worker pool is initialized and file count exceeds threshold,
   parsing is distributed across Web Workers for true CPU parallelism."
  [conn files opts]
  (let [db @conn
        supported-files (graph-parser/filter-files files)
        total (count supported-files)
        use-workers? (use-worker-pool? total)]
    (state/set-parsing-state! {:total total :graph-loading? true})
    (when use-workers?
      (println (str "Using worker pool (" (pool/pool-size) " workers) for " total " files")))
    ;; Choose parsing strategy based on worker pool availability
    (-> (if use-workers?
          (parse-files-with-workers db supported-files (:extract-options opts))
          (parse-files-parallel db supported-files (:extract-options opts)))
        (p/then
         (fn [parse-results]
           (let [plan (batch/plan-graph-load db parse-results)]
             (if (= :ok (:status plan))
               ;; All good - execute the plan
               (-> (execute-load-plan! conn plan opts)
                   (p/then (fn [result]
                             (if (= :ok (:status result))
                               {:status :ok
                                :files-loaded (:committed result)}
                               result))))
               ;; Validation failed - return errors
               (p/resolved
                {:status :error
                 :parse-errors (:parse-errors plan)
                 :conflicts (:conflicts plan)
                 :files-parsed (count (:parsed-files plan))})))))
        (p/catch
         (fn [e]
           {:status :error
            :error e
            :message (str e)}))
        (p/finally clear-parsing-state!))))

(defn parallel-loading-enabled?
  "Check if parallel loading is enabled via feature flag.
   Default: disabled (use sequential loading)."
  []
  ;; Feature flag check - can be wired to state/get-config or env var
  (some-> (state/get-config)
          :features
          :parallel-loading))

;; =============================================================================
;; Worker Pool Lifecycle (Phase 5)
;; =============================================================================

(defn init-worker-pool!
  "Initialize the parse worker pool for parallel loading.
   Should be called during app startup when parallel loading is enabled.

   Arguments:
   - n: Number of workers (optional, defaults to navigator.hardwareConcurrency)

   Returns: true if pool initialized, false if failed"
  ([]
   (pool/init-pool!))
  ([n]
   (pool/init-pool! n)))

(defn shutdown-worker-pool!
  "Shutdown the parse worker pool.
   Should be called during app shutdown."
  []
  (pool/shutdown-pool!))

(defn worker-pool-status
  "Get current worker pool status.
   Returns map with :initialized?, :size, :loading?"
  []
  {:initialized? (pool/pool-initialized?)
   :size (pool/pool-size)
   :loading? (pool/loading?)})

(comment
  ;; Example usage:

  ;; Initialize worker pool during app startup
  (init-worker-pool!)      ; Uses navigator.hardwareConcurrency
  (init-worker-pool! 4)    ; Or specify worker count

  ;; Check status
  (worker-pool-status)
  ;; => {:initialized? true :size 4 :loading? false}

  ;; Load graph (automatically uses workers if available)
  (load-graph-parallel!
   conn
   [{:file/path "page1.md" :file/content "- Block 1"}
    {:file/path "page2.md" :file/content "- Block 2"}]
   {:batch-size 25
    :extract-options {:verbose false}})

  ;; Shutdown when app closes
  (shutdown-worker-pool!))
