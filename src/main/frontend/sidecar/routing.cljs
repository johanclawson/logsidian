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

;; TEMPORARY: Sidecar routing is disabled during initial development.
;; The issue is that the sidecar DB is empty during initial graph loading
;; (files are parsed by web worker, sync-datoms hasn't run yet).
;; Queries to an empty sidecar return nil, causing blank content.
;;
;; TODO: Re-enable sidecar routing once initial sync is reliable.
;; This requires ensuring sync-graph-to-sidecar! completes before
;; the UI starts querying.
;;
;; CRITICAL: Graph management ops (create-or-open-db, list-db, db-exists)
;; MUST go to web worker so it can set up its local DataScript database
;; for file parsing. If these go to sidecar only, the web worker's DB
;; is never initialized and file parsing fails silently.
;;
;; For now, all operations go to the web worker (single-worker mode).
(def sidecar-preferred-ops
  "Operations that PREFER sidecar when available (for lazy loading performance).
   CURRENTLY DISABLED - all operations go to web worker."
  #{;; ALL OPERATIONS DISABLED for now.
    ;; The hybrid architecture requires careful orchestration:
    ;; 1. Web worker must create its graph first (create-or-open-db)
    ;; 2. Files are parsed by web worker (mldoc)
    ;; 3. Only THEN can data be synced to sidecar (sync-datoms)
    ;; 4. Queries can only go to sidecar after sync is complete
    ;;
    ;; Since this orchestration isn't implemented yet, all operations
    ;; go to the web worker for reliability.
    ;;
    ;; TODO: Implement proper orchestration in initial_sync.cljs:
    ;; - After graph/added event completes
    ;; - Call create-or-open-db in sidecar
    ;; - Then sync all datoms
    ;; - Then re-enable query routing
    })

(defn route-operation
  "Determine which backend to use for an operation.
   Returns :worker, :sidecar, or :worker (fallback when sidecar unavailable)."
  [qkw sidecar-available?]
  (cond
    ;; Worker-only ops always go to worker
    (contains? worker-only-ops qkw)
    :worker

    ;; Sidecar-preferred ops go to sidecar when available
    (and sidecar-available? (contains? sidecar-preferred-ops qkw))
    :sidecar

    ;; Default: use worker
    :else
    :worker))

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
   Routes based on operation type and sidecar availability.

   qkw - Operation keyword (e.g., :thread-api/q)
   direct-pass? - Whether to pass args directly
   args - Operation arguments

   Returns a promise with the result."
  [qkw direct-pass? args]
  (let [sidecar-available? (sidecar-ready?)
        target (route-operation qkw sidecar-available?)
        web-worker @*web-worker
        sidecar-worker @*sidecar-worker
        worker (case target
                 :sidecar sidecar-worker
                 :worker web-worker)]
    ;; Debug logging to track routing decisions
    (when (= qkw :thread-api/reset-file)
      (log/info :routing-reset-file {:op qkw
                                      :target target
                                      :web-worker? (some? web-worker)
                                      :sidecar-worker? (some? sidecar-worker)}))
    (when (nil? worker)
      (log/error :routing-no-worker {:op qkw
                                      :target target
                                      :web-worker? (some? web-worker)
                                      :sidecar-worker? (some? sidecar-worker)})
      (throw (ex-info "No worker available for operation"
                      {:op qkw :target target :sidecar-ready? sidecar-available?})))
    (log/debug :routing-invoke {:op qkw :target target})
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
