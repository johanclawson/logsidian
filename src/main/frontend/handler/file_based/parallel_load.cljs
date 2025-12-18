(ns frontend.handler.file-based.parallel-load
  "Parallel graph loading implementation.
   Uses batch parsing with conflict detection and serialized transactions.

   Phase 4 of the parallel loading TDD plan:
   - Step 13: Commit executor with batching
   - Step 14: Integration entry points"
  (:require [clojure.core.async :as async]
            [datascript.core :as d]
            [frontend.db.transact-queue :as tq]
            [frontend.state :as state]
            [logseq.db :as ldb]
            [logseq.graph-parser :as graph-parser]
            [logseq.graph-parser.batch :as batch]
            [promesa.core :as p]))

;; =============================================================================
;; Step 13: Commit Executor with Batching
;; =============================================================================

(defn- parse-file-isolated
  "Parse a single file with error isolation.
   Returns wrapped result with :ok or :error status."
  [db file opts]
  (try
    (let [result (graph-parser/parse-file-data
                  (:file/path file)
                  (:file/content file)
                  {:db db
                   :extract-options opts
                   :ctime (:ctime (:stat file))
                   :mtime (:mtime (:stat file))})]
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

   Note: 'Parallel' here means concurrent I/O, not parallel CPU.
   JavaScript is single-threaded, but this allows I/O to overlap.
   Results are returned in the same order as input files to ensure
   deterministic conflict resolution (first file wins in deduplication)."
  [db files opts]
  (let [total (count files)
        ;; Use vector of fixed size to preserve order
        results (atom (vec (repeat total nil)))
        counter (atom 0)]
    (p/create
     (fn [resolve _reject]
       (if (zero? total)
         (resolve [])
         (doseq [[idx file] (map-indexed vector files)]
           (let [result (parse-file-isolated db file opts)
                 n (swap! counter inc)]
             ;; Store at original index to preserve order
             (swap! results assoc idx result)
             (report-progress! (:file/path file) n total)
             (when (= n total)
               (resolve @results)))))))))

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
   1. Parses all files with error isolation
   2. Validates for UUID and page conflicts
   3. Commits in batches if validation passes

   Note: Validation is performed against a DB snapshot taken before parsing.
   Concurrent modifications between parse and commit are not re-validated."
  [conn files opts]
  (let [db @conn
        supported-files (graph-parser/filter-files files)
        total (count supported-files)]
    (state/set-parsing-state! {:total total :graph-loading? true})
    (-> (parse-files-parallel db supported-files (:extract-options opts))
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

(comment
  ;; Example usage:
  (load-graph-parallel!
   conn
   [{:file/path "page1.md" :file/content "- Block 1"}
    {:file/path "page2.md" :file/content "- Block 2"}]
   {:batch-size 25
    :extract-options {:verbose false}}))
