(ns frontend.sidecar.routing
  "Operation routing for hybrid worker/sidecar architecture.

   This module routes database operations to the appropriate backend:
   - Web worker: File operations (mldoc parsing), vector search, RTC
   - Sidecar: Queries, outliner operations, datoms

   The routing is based on operation type:
   - worker-only-ops: Must go to web worker (require mldoc or browser APIs)
   - sidecar-preferred-ops: Prefer sidecar when available (lazy loading)
   - Everything else: Goes to web worker by default

   Usage:
   ```clojure
   ;; In browser.cljs, after starting both workers:
   (routing/set-workers! web-worker-fn sidecar-worker-fn)

   ;; In state.cljs, <invoke-db-worker uses routing:
   (routing/<invoke op args)
   ```"
  (:require [lambdaisland.glogi :as log]
            [promesa.core :as p]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private *web-worker (atom nil))
(defonce ^:private *sidecar-worker (atom nil))
(defonce ^:private *sidecar-ready? (atom false))

;; Debug logging enabled during development
;; TODO: Set to false by default before release
;; Toggle in browser console: frontend.sidecar.routing.enable_debug_BANG_() / disable_debug_BANG_()
(defonce ^:private *debug-routing* (atom true))

(defn enable-debug! [] (reset! *debug-routing* true))
(defn disable-debug! [] (reset! *debug-routing* false))

;; Track per-repo sync completion status
;; Key: repo URL, Value: true when initial sync is complete
(defonce ^:private *sync-complete-repos (atom #{}))

;; =============================================================================
;; Sync State Management
;; =============================================================================

(defn mark-sync-complete!
  "Mark initial sync as complete for a repo.
   Called by initial_sync after all datoms are transferred to sidecar."
  [repo]
  (log/info :routing-sync-complete {:repo repo})
  (swap! *sync-complete-repos conj repo))

(defn mark-sync-incomplete!
  "Mark sync as incomplete for a repo.
   Called when a graph is closed or needs re-sync."
  [repo]
  (log/info :routing-sync-incomplete {:repo repo})
  (swap! *sync-complete-repos disj repo))

(defn sync-complete?
  "Check if initial sync is complete for a repo.
   Returns true only after sync-graph-to-sidecar! has finished."
  [repo]
  (contains? @*sync-complete-repos repo))

(defn clear-all-sync-state!
  "Clear all sync state. Used during testing and shutdown."
  []
  (reset! *sync-complete-repos #{}))

;; =============================================================================
;; Operation Classification
;; =============================================================================

(def worker-only-ops
  "Operations that MUST go to web worker (require mldoc or browser APIs)"
  #{:thread-api/reset-file
    :thread-api/gc-graph
    :thread-api/export-db
    :thread-api/import-db
    :thread-api/fix-broken-graph
    ;; Vec search (uses browser WebGPU)
    :thread-api/vec-search-embedding-model-info
    :thread-api/vec-search-init-embedding-model
    :thread-api/vec-search-load-model
    :thread-api/vec-search-embedding-graph
    :thread-api/vec-search-search
    :thread-api/vec-search-cancel-indexing
    :thread-api/vec-search-update-index-info
    :thread-api/vec-upsert-blocks
    :thread-api/vec-delete-blocks
    :thread-api/vec-search-blocks
    ;; RTC (WebSocket in browser)
    :thread-api/rtc-start
    :thread-api/rtc-stop
    :thread-api/rtc-sync-graph!
    :thread-api/rtc-status
    ;; Mobile logs
    :thread-api/write-log
    :thread-api/mobile-get-logs})

;; Operations that benefit from sidecar's lazy loading and persistent storage.
;; These ops are routed to sidecar ONLY when:
;; 1. Sidecar is connected and ready
;; 2. Initial sync for the repo is complete (data exists in sidecar)
;;
;; CRITICAL: Graph management ops (create-or-open-db, list-db, db-exists)
;; MUST go to web worker so it can set up its local DataScript database
;; for file parsing. If these go to sidecar only, the web worker's DB
;; is never initialized and file parsing fails silently.
(def sidecar-preferred-ops
  "Operations that PREFER sidecar when available (for lazy loading performance).
   Only routed to sidecar after initial sync completes for the repo."
  #{;; Query operations benefit from sidecar lazy loading
    :thread-api/q
    :thread-api/pull
    :thread-api/pull-many
    :thread-api/datoms
    ;; Write operations for single source of truth
    :thread-api/transact
    :thread-api/apply-outliner-ops})

(defn route-operation
  "Determine which backend to use for an operation.
   Returns :worker, :sidecar, or :worker (fallback when sidecar unavailable).

   Arguments:
   - qkw: Operation keyword (e.g., :thread-api/q)
   - sidecar-available?: Whether sidecar is connected and ready
   - repo: (optional) Repository URL for sync-complete checking"
  ([qkw sidecar-available?]
   (route-operation qkw sidecar-available? nil))
  ([qkw sidecar-available? repo]
   (cond
     ;; Worker-only ops always go to worker
     (contains? worker-only-ops qkw)
     :worker

     ;; Sidecar-preferred ops go to sidecar when:
     ;; 1. Sidecar is available
     ;; 2. Initial sync is complete for this repo (or no repo specified)
     (and sidecar-available?
          (contains? sidecar-preferred-ops qkw)
          (or (nil? repo) (sync-complete? repo)))
     :sidecar

     ;; Default: use worker
     :else
     :worker)))

;; =============================================================================
;; Worker Management
;; =============================================================================

(defn set-web-worker!
  "Set the web worker function."
  [worker-fn]
  (log/info :routing-set-web-worker {:worker-fn? (some? worker-fn)})
  (reset! *web-worker worker-fn))

(defn set-sidecar-worker!
  "Set the sidecar worker function and mark as ready."
  [worker-fn]
  (log/info :routing-set-sidecar-worker {:worker-fn? (some? worker-fn)})
  (reset! *sidecar-worker worker-fn)
  (reset! *sidecar-ready? true))

(defn clear-sidecar-worker!
  "Clear the sidecar worker (e.g., on disconnect)."
  []
  (reset! *sidecar-worker nil)
  (reset! *sidecar-ready? false))

(defn sidecar-ready?
  "Check if sidecar is ready to receive requests."
  []
  @*sidecar-ready?)

(defn web-worker-ready?
  "Check if web worker is ready."
  []
  (some? @*web-worker))

;; =============================================================================
;; Invocation
;; =============================================================================

(defn <invoke
  "Invoke an operation on the appropriate backend.
   Routes based on operation type, sidecar availability, and sync status.

   qkw - Operation keyword (e.g., :thread-api/q)
   direct-pass? - Whether to pass args directly
   args - Operation arguments (first arg is typically repo URL)

   Returns a promise with the result."
  [qkw direct-pass? args]
  (let [;; First arg is typically the repo URL for most operations
        repo (first args)
        sidecar-available? (sidecar-ready?)
        target (route-operation qkw sidecar-available? repo)
        web-worker @*web-worker
        sidecar-worker @*sidecar-worker
        worker (case target
                 :sidecar sidecar-worker
                 :worker web-worker)]
    ;; Debug logging to track routing decisions for sidecar-preferred ops
    ;; Enable with: frontend.sidecar.routing.enable_debug_BANG_() in browser console
    (when (and @*debug-routing* (contains? sidecar-preferred-ops qkw))
      (if (= target :sidecar)
        (js/console.warn "[ROUTING->SIDECAR]" (pr-str {:op qkw :repo repo}))
        (js/console.warn "[ROUTING->WORKER]" (pr-str {:op qkw :repo repo
                                                       :reason (cond
                                                                 (not sidecar-available?) :sidecar-not-ready
                                                                 (not (sync-complete? repo)) :sync-incomplete
                                                                 :else :unknown)}))))
    (when (nil? worker)
      (log/error :routing-no-worker {:op qkw
                                      :target target
                                      :repo repo
                                      :web-worker? (some? web-worker)
                                      :sidecar-worker? (some? sidecar-worker)})
      (throw (ex-info "No worker available for operation"
                      {:op qkw :target target :sidecar-ready? sidecar-available?})))
    (log/debug :routing-invoke {:op qkw :target target :repo repo})
    (apply worker qkw direct-pass? args)))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn <invoke-worker
  "Invoke an operation on the web worker (bypass routing)."
  [qkw direct-pass? & args]
  (let [worker @*web-worker]
    (when (nil? worker)
      (throw (ex-info "Web worker not initialized" {:op qkw})))
    (apply worker qkw direct-pass? args)))

(defn <invoke-sidecar
  "Invoke an operation on the sidecar (bypass routing).
   Throws if sidecar not ready."
  [qkw direct-pass? & args]
  (let [worker @*sidecar-worker]
    (when (nil? worker)
      (throw (ex-info "Sidecar not ready" {:op qkw})))
    (apply worker qkw direct-pass? args)))
