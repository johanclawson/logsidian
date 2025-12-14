(ns frontend.sidecar.file-sync
  "File sync module for keeping sidecar in sync with file changes.

   When files change on disk (detected by file watcher):
   1. Frontend parses the file and updates its DataScript DB
   2. This module syncs the affected page's datoms to sidecar
   3. Sidecar DB stays in sync with frontend

   This complements initial-sync (full graph sync on open) by providing
   incremental sync for ongoing file changes.

   Usage:
   ```clojure
   ;; After a file is parsed and DB updated:
   (file-sync/sync-file-change! repo path)

   ;; For file deletions:
   (file-sync/sync-file-delete! repo page-name)
   ```"
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [frontend.db :as db]
            [frontend.db.file-based.model :as file-model]
            [frontend.sidecar.core :as sidecar]
            [frontend.state :as state]
            [lambdaisland.glogi :as log]
            [promesa.core :as p]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:const SYNC_DELAY_MS
  "Delay before syncing to allow DB to settle after parse.
   This ensures all transactions from parsing are complete."
  50)

;; =============================================================================
;; Datom Extraction for Page
;; =============================================================================

(defn- get-page-entity-ids
  "Get all entity IDs associated with a page (page + blocks).
   Returns a set of entity IDs."
  [db page-name]
  (let [;; Get page entity
        page-id (d/q '[:find ?e .
                       :in $ ?name
                       :where [?e :block/name ?name]]
                     db (string/lower-case page-name))
        ;; Get all blocks belonging to this page
        block-ids (when page-id
                    (d/q '[:find [?b ...]
                           :in $ ?page-id
                           :where [?b :block/page ?page-id]]
                         db page-id))]
    (when page-id
      (into #{page-id} block-ids))))

(defn- extract-entity-datoms
  "Extract all datoms for a set of entity IDs.
   Returns vector of [e a v tx added?] vectors."
  [db entity-ids]
  (let [datoms (d/datoms db :eavt)]
    (->> datoms
         (filter #(contains? entity-ids (:e %)))
         (mapv (fn [datom]
                 [(:e datom) (:a datom) (:v datom) (:tx datom) true])))))

(defn extract-page-datoms
  "Extract all datoms for a specific page and its blocks.
   Returns vector of [e a v tx added?] vectors, or nil if page not found."
  [db page-name]
  (when-let [entity-ids (get-page-entity-ids db page-name)]
    (extract-entity-datoms db entity-ids)))

;; =============================================================================
;; Sidecar Sync Operations
;; =============================================================================

(defn- sidecar-enabled?
  "Check if sidecar is enabled and ready for sync."
  []
  (or (sidecar/sidecar-enabled?)
      (sidecar/websocket-sidecar-enabled?)))

(defn sync-page-to-sidecar!
  "Sync a specific page's datoms to the sidecar.

   This is used after a file is parsed to update the sidecar's view
   of that page. It sends all datoms for the page (replacing any
   previous data for that page).

   Arguments:
   - repo: Repository URL
   - page-name: Name of the page to sync

   Returns a promise that resolves when sync is complete."
  [repo page-name]
  (when (sidecar-enabled?)
    (let [conn (db/get-db repo false)]
      (if-not conn
        (do
          (log/warn :file-sync-no-db {:repo repo :page page-name})
          (p/resolved nil))
        (let [db @conn
              datoms (extract-page-datoms db page-name)]
          (if (empty? datoms)
            (do
              (log/debug :file-sync-no-datoms {:repo repo :page page-name})
              (p/resolved nil))
            (do
              (log/info :file-sync-page
                        {:repo repo :page page-name :datom-count (count datoms)})
              (-> (state/<invoke-db-worker :thread-api/sync-datoms
                                           repo (vec datoms) {:full-sync? false
                                                               :page-sync? true
                                                               :page-name page-name})
                  (p/then (fn [_]
                            (log/debug :file-sync-page-complete
                                       {:repo repo :page page-name})))
                  (p/catch (fn [err]
                             (log/error :file-sync-page-failed
                                        {:repo repo :page page-name :error err})))))))))))

(defn sync-page-delete-to-sidecar!
  "Notify sidecar that a page has been deleted.

   Arguments:
   - repo: Repository URL
   - page-name: Name of the deleted page

   Returns a promise."
  [repo page-name]
  (when (sidecar-enabled?)
    (log/info :file-sync-delete {:repo repo :page page-name})
    (-> (state/<invoke-db-worker :thread-api/delete-page
                                 repo page-name {})
        (p/catch (fn [err]
                   (log/error :file-sync-delete-failed
                              {:repo repo :page page-name :error err}))))))

;; =============================================================================
;; File Change Handlers
;; =============================================================================

(defn sync-file-change!
  "Sync a file change to the sidecar.

   Called after a file has been parsed and the frontend DB updated.
   Extracts the page's datoms and sends them to sidecar.

   Arguments:
   - repo: Repository URL
   - file-path: Path of the changed file

   Returns a promise."
  [repo file-path]
  (when (sidecar-enabled?)
    ;; Small delay to let DB transactions settle
    (-> (p/delay SYNC_DELAY_MS)
        (p/then
         (fn [_]
           (if-let [page-name (file-model/get-file-page file-path)]
             (sync-page-to-sidecar! repo page-name)
             (do
               (log/debug :file-sync-no-page {:repo repo :path file-path})
               (p/resolved nil))))))))

(defn sync-file-delete!
  "Sync a file deletion to the sidecar.

   Called when a file is deleted from disk.

   Arguments:
   - repo: Repository URL
   - page-name: Name of the deleted page (already known before deletion)

   Returns a promise."
  [repo page-name]
  (when (and (sidecar-enabled?) page-name)
    (sync-page-delete-to-sidecar! repo page-name)))

;; =============================================================================
;; Batch Sync (for multiple file changes)
;; =============================================================================

(defn sync-files-change!
  "Sync multiple file changes to the sidecar.

   Used when multiple files change at once (e.g., during initial load
   or when syncing from remote).

   Arguments:
   - repo: Repository URL
   - file-paths: Collection of file paths that changed

   Returns a promise that resolves when all syncs complete."
  [repo file-paths]
  (when (and (sidecar-enabled?) (seq file-paths))
    (log/info :file-sync-batch {:repo repo :file-count (count file-paths)})
    ;; Delay to let all DB transactions settle
    (-> (p/delay (* 2 SYNC_DELAY_MS))
        (p/then
         (fn [_]
           (let [page-names (->> file-paths
                                 (keep file-model/get-file-page)
                                 (distinct))]
             (if (empty? page-names)
               (p/resolved nil)
               (p/all (map #(sync-page-to-sidecar! repo %) page-names)))))))))
