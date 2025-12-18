(ns logseq.graph-parser.batch
  "Batch utilities for parallel graph loading.
   Provides conflict detection and transaction building for batch operations."
  (:require [clojure.set :as set]
            [datascript.core :as d]
            [logseq.common.util :as common-util]))

;; =============================================================================
;; Step 4: Collectors for Parsed Output
;; =============================================================================

(defn collect-uuids
  "Collect all UUIDs from a parsed file result.
   Returns a vector of maps with UUID and source metadata.
   Uses vector (not set) to preserve duplicates for intra-file conflict detection.

   Example return value:
   [{:uuid #uuid\"...\" :source :block :file-path \"test.md\" :title \"Block content\"}
    {:uuid #uuid\"...\" :source :page :file-path \"test.md\" :name \"test\"}]"
  [{:keys [pages blocks file-entity]}]
  (let [file-path (:file/path file-entity)]
    (vec
     (concat
      ;; Page UUIDs
      (for [page pages
            :when (:block/uuid page)]
        {:uuid (:block/uuid page)
         :source :page
         :file-path file-path
         :name (:block/name page)})
      ;; Block UUIDs
      (for [block blocks
            :when (:block/uuid block)]
        {:uuid (:block/uuid block)
         :source :block
         :file-path file-path
         :title (:block/title block)})))))

(defn collect-page-titles
  "Collect all page titles from a parsed file result with canonical names.
   Uses the provided canonicalize-fn (defaults to page-name-sanity-lc).

   Example return value:
   [{:canonical \"test\" :raw \"Test\" :file-path \"test.md\" :source :primary}
    {:canonical \"other page\" :raw \"Other Page\" :file-path \"test.md\" :source :ref}]"
  ([parsed-file]
   (collect-page-titles parsed-file common-util/page-name-sanity-lc))
  ([{:keys [pages primary-page file-entity]} canonicalize-fn]
   (let [file-path (:file/path file-entity)
         primary-name (:block/name primary-page)]
     (for [page pages
           :let [raw-name (:block/title page)
                 canonical (or (:block/name page)
                               (canonicalize-fn raw-name))]
           :when canonical]
       {:canonical canonical
        :raw (or raw-name canonical)
        :file-path file-path
        :source (if (= canonical primary-name) :primary :ref)}))))

;; =============================================================================
;; Step 5 & 6: UUID Conflict Detection
;; =============================================================================

(defn- get-db-uuids
  "Get all block UUIDs from the database snapshot.
   Queries ALL entities with :block/uuid, not just pages."
  [db]
  (when db
    (->> (d/q '[:find ?uuid ?e
                :where
                [?e :block/uuid ?uuid]]
              db)
         (map (fn [[uuid eid]]
                {:uuid uuid :eid eid :source :db}))
         set)))

(defn detect-uuid-conflicts
  "Detect UUID conflicts across multiple parsed files AND against a DB snapshot.

   Arguments:
   - db: Database snapshot (optional, can be nil)
   - parsed-files: Sequence of parsed file results from parse-file-data

   Returns:
   {:ok true} if no conflicts
   {:conflicts [{:uuid ... :sources [...]}]} if conflicts found

   Each conflict entry shows which files/sources have the same UUID."
  [db parsed-files]
  (let [;; Collect UUIDs from all parsed files
        all-uuids (mapcat collect-uuids parsed-files)
        ;; Group by UUID
        uuid-groups (->> all-uuids
                         (group-by :uuid)
                         (filter (fn [[_ sources]] (> (count sources) 1))))
        ;; Also check against DB
        db-uuids (get-db-uuids db)
        db-uuid-set (set (map :uuid db-uuids))
        ;; Find conflicts with DB
        db-conflicts (for [uuid-entry all-uuids
                          :when (contains? db-uuid-set (:uuid uuid-entry))]
                       [(:uuid uuid-entry)
                        [(assoc uuid-entry :source-type :new-file)
                         {:uuid (:uuid uuid-entry)
                          :source :db
                          :source-type :existing}]])
        ;; Combine all conflicts
        all-conflicts (concat
                       (for [[uuid sources] uuid-groups]
                         {:uuid uuid
                          :sources (vec sources)
                          :conflict-type :cross-file})
                       (for [[uuid sources] db-conflicts]
                         {:uuid uuid
                          :sources (vec sources)
                          :conflict-type :db-collision}))]
    (if (seq all-conflicts)
      {:ok false
       :conflicts (vec all-conflicts)}
      {:ok true})))

(defn validate-no-uuid-conflicts!
  "Validate that parsed files have no UUID conflicts.
   Returns {:ok true :parsed-files ...} if valid.
   Returns {:ok false :errors [...]} with actionable error messages if conflicts found.

   This is the fail-fast validation step that prevents silent UUID regeneration."
  [db parsed-files]
  (let [result (detect-uuid-conflicts db parsed-files)]
    (if (:ok result)
      {:ok true
       :parsed-files parsed-files}
      {:ok false
       :errors (for [{:keys [uuid sources conflict-type]} (:conflicts result)]
                 {:type :uuid-conflict
                  :uuid uuid
                  :conflict-type conflict-type
                  :message (str "UUID " uuid " appears in multiple locations: "
                                (pr-str (map #(select-keys % [:file-path :source :name :title])
                                             sources)))
                  :sources sources})})))

;; =============================================================================
;; Step 7: Page Conflict Detection
;; =============================================================================

(defn- get-db-pages
  "Get all page names from the database snapshot.
   Makes :block/title optional since some pages may not have it."
  [db]
  (when db
    (->> (d/q '[:find ?name
                :where
                [?e :block/name ?name]]
              db)
         (map (fn [[name]]
                {:canonical name :source :db}))
         set)))

(defn detect-page-conflicts
  "Detect page conflicts (same canonical name, different sources) across files.

   Arguments:
   - db: Database snapshot (optional)
   - parsed-files: Sequence of parsed file results
   - canonicalize-fn: Function to canonicalize page names (optional)

   Returns:
   {:ok true} if no conflicts
   {:conflicts [{:canonical ... :sources [...]}]} if conflicts found

   Note: A page appearing in multiple files with the same canonical name is
   a conflict only if they come from different PRIMARY pages. Ref pages
   pointing to the same canonical name is expected behavior."
  ([db parsed-files]
   (detect-page-conflicts db parsed-files common-util/page-name-sanity-lc))
  ([db parsed-files canonicalize-fn]
   (let [;; Collect all page titles from all files (primary pages only for conflict detection)
         all-pages (->> parsed-files
                        (mapcat #(collect-page-titles % canonicalize-fn))
                        (filter #(= :primary (:source %))))
         ;; Group by canonical name
         page-groups (->> all-pages
                          (group-by :canonical)
                          (filter (fn [[_ sources]] (> (count sources) 1))))
         ;; Also check against DB for primary pages
         db-pages (get-db-pages db)
         db-canonical-set (set (map :canonical db-pages))
         ;; Find conflicts with DB (primary pages with same canonical name as existing)
         db-conflicts (for [page-entry all-pages
                           :when (contains? db-canonical-set (:canonical page-entry))]
                        [(:canonical page-entry)
                         [(assoc page-entry :source-type :new-file)
                          {:canonical (:canonical page-entry)
                           :source :db
                           :source-type :existing}]])
         ;; Combine conflicts
         all-conflicts (concat
                        (for [[canonical sources] page-groups]
                          {:canonical canonical
                           :sources (vec sources)
                           :conflict-type :cross-file})
                        (for [[canonical sources] db-conflicts]
                          {:canonical canonical
                           :sources (vec sources)
                           :conflict-type :db-collision}))]
     (if (seq all-conflicts)
       {:ok false
        :conflicts (vec all-conflicts)}
       {:ok true}))))

(defn validate-no-page-conflicts!
  "Validate that parsed files have no page name conflicts.
   Returns {:ok true :parsed-files ...} if valid.
   Returns {:ok false :errors [...]} with actionable error messages if conflicts found."
  [db parsed-files]
  (let [result (detect-page-conflicts db parsed-files)]
    (if (:ok result)
      {:ok true
       :parsed-files parsed-files}
      {:ok false
       :errors (for [{:keys [canonical sources conflict-type]} (:conflicts result)]
                 {:type :page-conflict
                  :canonical canonical
                  :conflict-type conflict-type
                  :message (str "Page name \"" canonical "\" has conflicting sources: "
                                (pr-str (map #(select-keys % [:file-path :raw :source])
                                             sources)))
                  :sources sources})})))

;; =============================================================================
;; Combined Validation
;; =============================================================================

(defn validate-batch
  "Validate a batch of parsed files for all conflict types.
   Returns {:ok true :parsed-files ...} if all validations pass.
   Returns {:ok false :errors [...]} if any validation fails."
  [db parsed-files]
  (let [uuid-result (validate-no-uuid-conflicts! db parsed-files)
        page-result (validate-no-page-conflicts! db parsed-files)]
    (if (and (:ok uuid-result) (:ok page-result))
      {:ok true
       :parsed-files parsed-files}
      {:ok false
       :errors (vec (concat (:errors uuid-result)
                            (:errors page-result)))})))
