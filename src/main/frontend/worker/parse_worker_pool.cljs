(ns frontend.worker.parse-worker-pool
  "Manages a pool of parse workers for parallel file parsing.

   CODEX: Includes worker caps, timeout handling, and single-load guard."
  (:require ["comlink" :as Comlink]
            [logseq.db :as ldb]
            [promesa.core :as p]))

;; =============================================================================
;; Constants
;; =============================================================================

;; Worker count strategy:
;; - Graph loading is a SHORT BURST (1-5 seconds), not continuous work
;; - Users want fast startup - use available CPU power
;; - But: More workers than cores = context switching overhead
;; - And: Each worker has ~10-20MB memory + serialization cost
;;
;; Sweet spot: Use physical core count, capped reasonably
;; - 4 cores → 4 workers (optimal)
;; - 8 cores → 8 workers (good)
;; - 16+ cores → 8 workers (diminishing returns past this)
(def ^:const MAX-WORKERS 8)        ; Beyond 8, overhead exceeds benefit
(def ^:const MIN-WORKERS 2)        ; At least 2 for any parallelism benefit
(def ^:const DEFAULT-WORKERS 4)    ; Fallback if hardwareConcurrency unavailable
(def ^:const WORKER-TIMEOUT-MS 30000)  ; 30 second timeout per chunk

;; Minimum files per worker to avoid over-chunking overhead
;; With 100 files and 10 min-per-worker: max 10 workers used
(def ^:const MIN-FILES-PER-WORKER 10)

;; =============================================================================
;; State
;; =============================================================================

(defonce *pool (atom nil))
;; CODEX: Guard to prevent overlapping parallel loads
(defonce *loading? (atom false))

;; =============================================================================
;; Worker Creation
;; =============================================================================

(defn- get-worker-path
  "Get worker JS path. Works in both browser and Electron.
   CODEX: Document that this path must be accessible in both contexts."
  []
  "/static/js/parse-worker.js")

(defn- create-worker
  "Create and wrap a single parse worker."
  []
  (try
    (let [worker (js/Worker. (get-worker-path))]
      {:raw worker
       :wrapped (Comlink/wrap worker)})
    (catch :default e
      (js/console.error "Failed to create parse worker:" e)
      nil)))

;; =============================================================================
;; Pool Management
;; =============================================================================

(defn pool-initialized?
  "Check if the worker pool is initialized with at least one worker.
   CODEX: Returns false if size is 0 to prevent division-by-zero."
  []
  (and (some? @*pool) (pos? (:size @*pool))))

(defn pool-size
  "Get the current pool size, or 0 if not initialized."
  []
  (or (:size @*pool) 0))

(defn init-pool!
  "Initialize pool with N workers.

   Default: Uses ALL available cores (navigator.hardwareConcurrency).
   Graph loading is a short burst - use full CPU power for fast startup.

   CODEX: Caps at MAX-WORKERS (12), minimum MIN-WORKERS (2)."
  ([] (init-pool! (or js/navigator.hardwareConcurrency DEFAULT-WORKERS)))
  ([n]
   (when-not @*pool
     (let [;; Use all cores - it's just a short burst at startup
           worker-count (-> n
                            (max MIN-WORKERS)
                            (min MAX-WORKERS))
           workers (->> (repeatedly worker-count create-worker)
                        (filter some?)  ; Filter failed worker creations
                        vec)]
       (if (seq workers)
         (do
           (reset! *pool {:workers workers :size (count workers)})
           (println (str "Parse worker pool initialized with " (count workers) " workers"
                        " (device has " (or js/navigator.hardwareConcurrency "unknown") " cores)"))
           true)
         (do
           (js/console.error "Failed to initialize any parse workers")
           false))))))

(defn shutdown-pool!
  "Terminate all workers in the pool."
  []
  (when-let [{:keys [workers]} @*pool]
    (doseq [{:keys [raw]} workers]
      (when raw (.terminate raw)))
    (reset! *pool nil)
    (reset! *loading? false)
    (println "Parse worker pool shut down")))

;; =============================================================================
;; Timeout Handling
;; =============================================================================

(defn- with-timeout
  "Wrap a promise with a timeout."
  [p timeout-ms error-msg]
  (p/race [p
           (p/do
             (p/delay timeout-ms)
             (p/rejected (ex-info error-msg {:timeout timeout-ms})))]))

(defn replace-worker-at-index!
  "Terminate worker at index and replace with a fresh one.
   CODEX: Called after timeout to retire hung workers.
   Creates new worker FIRST - only terminates old if creation succeeds."
  [worker-idx]
  (when-let [{:keys [workers]} @*pool]
    (let [old-worker (get workers worker-idx)]
      ;; CODEX: Create new worker FIRST before terminating old
      ;; This prevents dead slots if creation fails
      (if-let [new-worker (create-worker)]
        (do
          ;; Swap in new worker atomically
          (swap! *pool update :workers assoc worker-idx new-worker)
          ;; Now safe to terminate old worker
          (when-let [{:keys [raw]} old-worker]
            (try
              (when raw (.terminate raw))
              (catch :default _ nil)))
          (js/console.warn (str "Replaced timed-out worker at index " worker-idx)))
        ;; Creation failed - keep old worker (maybe it will recover)
        (js/console.warn (str "Failed to create replacement worker at index " worker-idx
                              " - keeping existing worker"))))))

;; =============================================================================
;; Parallel Parsing
;; =============================================================================

(defn loading?
  "Check if a parallel load is currently in progress."
  []
  @*loading?)

(defn parse-files-parallel!
  "Distribute files across worker pool, return combined results in order.

   CODEX: Includes timeout, single-load guard, worker replacement, and proper error handling.

   Arguments:
   - files: Sequence of {:file/path ... :file/content ...}
   - opts: Options passed to parse-file-data

   Returns: Promise resolving to vector of parse results"
  [files opts]
  (cond
    (not @*pool)
    (p/rejected (ex-info "Worker pool not initialized" {}))

    ;; CODEX: Prevent overlapping loads
    @*loading?
    (p/rejected (ex-info "Parallel load already in progress" {:code :already-loading}))

    :else
    ;; CODEX: Wrap in try/catch to ensure *loading? reset on serialization errors
    (try
      (reset! *loading? true)
      (let [{:keys [workers size]} @*pool
            file-vec (vec files)
            file-count (count file-vec)
            ;; Smart worker count: don't use more workers than beneficial
            ;; - At least MIN-FILES-PER-WORKER files per worker
            ;; - Never more workers than we have in pool
            effective-workers (-> (js/Math.ceil (/ file-count MIN-FILES-PER-WORKER))
                                  (max 1)
                                  (min size))
            ;; Split files into chunks, one per effective worker
            chunk-size (max 1 (js/Math.ceil (/ file-count effective-workers)))
            chunks (partition-all chunk-size file-vec)
            ;; Pre-serialize opts once (can throw if non-transit-encodable)
            opts-transit (ldb/write-transit-str opts)
            _ (when (< effective-workers size)
                (println (str "Using " effective-workers " of " size " workers for " file-count " files")))]
        (-> (p/all
             (map-indexed
              (fn [chunk-idx chunk]
                (let [worker-idx (mod chunk-idx size)
                      {:keys [wrapped]} (nth workers worker-idx)
                      files-transit (ldb/write-transit-str (vec chunk))]
                  (-> (with-timeout
                        ;; ^js hint suppresses type inference warning for Comlink wrapped object
                        (.parseFilesBatch ^js wrapped files-transit opts-transit)
                        WORKER-TIMEOUT-MS
                        (str "Worker timeout on chunk " chunk-idx))
                      (p/then ldb/read-transit-str)
                      ;; CODEX: Tag results with chunk index for ordering verification
                      (p/then (fn [results]
                                (mapv #(assoc % :chunk-idx chunk-idx) results)))
                      (p/catch (fn [e]
                                 ;; CODEX: On timeout, replace the hung worker
                                 (when (and (ex-data e) (:timeout (ex-data e)))
                                   (replace-worker-at-index! worker-idx))
                                 ;; Return error results for entire chunk
                                 (mapv (fn [f]
                                         {:status :error
                                          :file-path (:file/path f)
                                          :error (str e)
                                          :chunk-idx chunk-idx})
                                       chunk))))))
              chunks))
            (p/then (fn [results]
                      ;; Flatten results, maintaining order
                      (vec (apply concat results))))
            (p/finally (fn [] (reset! *loading? false)))))
      (catch :default e
        ;; CODEX: Reset loading flag on synchronous errors (e.g., serialization failure)
        (reset! *loading? false)
        (p/rejected e)))))
