(ns frontend.sidecar.initial-sync
  "Initial sync module for populating the sidecar with graph data.

   When a graph is opened, this module:
   1. Collects all datoms from the frontend database
   2. Batches them for efficient transfer
   3. Sends to sidecar via sync-datoms
   4. Signals routing module that sync is complete

   This ensures the sidecar has the same data as the frontend after
   parsing is complete, and enables query routing to sidecar.

   Usage:
   ```clojure
   ;; After graph parsing is complete:
   (initial-sync/sync-graph-to-sidecar! repo-url)
   ```"
  (:require [datascript.core :as d]
            [frontend.db :as db]
            [frontend.sidecar.routing :as routing]
            [frontend.state :as state]
            [lambdaisland.glogi :as log]
            [promesa.core :as p]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:const BATCH_SIZE
  "Number of datoms to send per batch.
   Larger batches are more efficient but may cause memory pressure."
  5000)

;; =============================================================================
;; Datom Extraction (public for testing)
;; =============================================================================

(defn datom->vec
  "Convert a DataScript datom to a vector for sync.
   Format: [entity-id attr value tx added?]
   Note: Datoms from d/datoms don't have :added, so we hardcode true."
  [datom]
  [(:e datom) (:a datom) (:v datom) (:tx datom) true])

(defn extract-datoms
  "Extract all datoms from a DataScript database.
   Returns a vector of [e a v tx added?] vectors."
  [db]
  (let [datoms (d/datoms db :eavt)]
    (mapv datom->vec datoms)))

(defn batch-datoms
  "Split datoms into batches of given size (default: BATCH_SIZE)."
  ([datoms] (batch-datoms datoms BATCH_SIZE))
  ([datoms batch-size]
   (partition-all batch-size datoms)))

;; =============================================================================
;; Sidecar Sync
;; =============================================================================

(defn- send-datom-batch!
  "Send a batch of datoms to the sidecar.
   Uses direct sidecar invoke (bypasses routing) since sync
   must always go to sidecar, never to web worker.
   Returns a promise."
  [repo batch opts]
  (routing/<invoke-sidecar :thread-api/sync-datoms false
                           repo (vec batch) opts))

(defn sync-graph-to-sidecar!
  "Sync all graph data to the sidecar.

   This function:
   1. Marks sync as incomplete (to prevent routing during sync)
   2. Creates/opens the graph in sidecar
   3. Extracts all datoms from the frontend DB
   4. Sends datoms in batches to sidecar
   5. Marks sync as complete (enables query routing to sidecar)

   Arguments:
   - repo: Repository URL (graph identifier)
   - opts: Options map with:
     - :on-progress - Callback (fn [current total]) for progress updates
     - :storage-path - Path for sidecar storage (default: :memory:)

   Returns a promise that resolves when sync is complete."
  ([repo] (sync-graph-to-sidecar! repo {}))
  ([repo {:keys [on-progress storage-path] :or {storage-path ":memory:"}}]
   ;; Mark sync as incomplete during transfer to prevent query routing
   (routing/mark-sync-incomplete! repo)
   (log/info :initial-sync-starting {:repo repo})
   (let [conn (db/get-db repo false)]
     (if-not conn
       (do
         (log/error :initial-sync-no-db {:repo repo})
         (p/rejected (ex-info "No database found for repo" {:repo repo})))
       (let [db @conn
             datoms (extract-datoms db)
             batches (batch-datoms datoms)
             total-batches (count batches)
             total-datoms (count datoms)]
         (log/info :initial-sync-extracted
                   {:repo repo
                    :datom-count total-datoms
                    :batch-count total-batches})
         (->
          (p/let [;; Create/open graph in sidecar with storage
                  ;; Uses direct sidecar invoke (bypasses routing)
                  _ (routing/<invoke-sidecar :thread-api/create-or-open-db false
                                             repo {:storage-path storage-path
                                                   :ref-type :soft})]
            ;; Send first batch with full-sync flag
            (if (empty? batches)
              (do
                (log/info :initial-sync-empty {:repo repo})
                ;; Even with empty sync, mark as complete
                (routing/mark-sync-complete! repo)
                {:datom-count 0 :batch-count 0})
              (p/loop [remaining-batches batches
                       batch-num 1]
                (when on-progress
                  (on-progress batch-num total-batches))
                (let [batch (first remaining-batches)
                      is-first? (= batch-num 1)
                      opts (if is-first?
                             {:full-sync? true}
                             {:full-sync? false})]
                  (p/let [_ (send-datom-batch! repo batch opts)]
                    (if-let [next-batches (next remaining-batches)]
                      (p/recur next-batches (inc batch-num))
                      ;; Done - signal routing module that queries can go to sidecar
                      (do
                        (routing/mark-sync-complete! repo)
                        (log/info :initial-sync-complete
                                  {:repo repo
                                   :datom-count total-datoms
                                   :batch-count total-batches
                                   :routing-enabled? true})
                        {:datom-count total-datoms
                         :batch-count total-batches})))))))
          (p/catch (fn [err]
                     (log/error :initial-sync-failed {:repo repo :error err})
                     ;; Keep sync incomplete on failure so queries go to worker
                     (throw err)))))))))

;; =============================================================================
;; Incremental Sync
;; =============================================================================

(defn sync-datoms-to-sidecar!
  "Send specific datoms to the sidecar (for incremental sync).

   This is used after individual file changes to keep the sidecar
   in sync with the frontend.

   Arguments:
   - repo: Repository URL
   - datoms: Vector of datoms to sync
   - opts: Options (passed to sync-datoms)

   Returns a promise."
  [repo datoms opts]
  (log/debug :incremental-sync {:repo repo :datom-count (count datoms)})
  (send-datom-batch! repo datoms (assoc opts :full-sync? false)))

;; =============================================================================
;; Event Integration
;; =============================================================================

(defn register-sync-on-graph-added!
  "Register a handler to sync to sidecar when a graph is added.

   This should be called during app initialization if sidecar is enabled.
   The handler listens for [:graph/added repo opts] events."
  []
  (state/pub-event!
   [:register-global-listener
    {:event :graph/added
     :handler (fn [[_ repo _opts]]
                (log/info :graph-added-sync-trigger {:repo repo})
                (sync-graph-to-sidecar! repo {}))}]))

;; =============================================================================
;; File Change Sync
;; =============================================================================

(defn tx-report->datom-vecs
  "Convert a DataScript tx-report to sync datom vectors.
   Handles both additions and retractions."
  [tx-report]
  (let [tx-datoms (:tx-data tx-report)]
    (mapv (fn [datom]
            [(:e datom) (:a datom) (:v datom) (:tx datom) (:added datom)])
          tx-datoms)))

(defn sync-tx-report!
  "Sync a transaction report to the sidecar.

   Called after a transaction is applied to the frontend DB
   to keep the sidecar in sync.

   Arguments:
   - repo: Repository URL
   - tx-report: DataScript transaction report

   Returns a promise."
  [repo tx-report]
  (let [datoms (tx-report->datom-vecs tx-report)]
    (when (seq datoms)
      (sync-datoms-to-sidecar! repo datoms {}))))
