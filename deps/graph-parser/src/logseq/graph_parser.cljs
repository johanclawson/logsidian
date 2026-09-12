(ns logseq.graph-parser
  "For file graphs, provides main ns to parse graph from source files.
   Used by logseq app to parse graph and then save to the given database connection"
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.common.config :as common-config]
            [logseq.common.util :as common-util]
            [logseq.db :as ldb]
            [logseq.db.file-based.schema :as file-schema]
            [logseq.graph-parser.extract :as extract]))

(def ^:private retained-block-retract-attributes
  "Attributes cleared on a block entity that a re-parse keeps (its uuid is
  among the new blocks') before the new block map is upserted onto it. The
  map's cardinality-one attributes overwrite the old values, but these are
  either cardinality-many (the map's values would be added to the old ones, so
  a removed link would stay in :block/refs) or absent from the map when the
  file no longer has them (:block/collapsed? is only set when true)."
  (conj file-schema/retract-attributes :block/refs :block/collapsed?))

(defn- retract-blocks-tx
  "`blocks` are entities. A block whose uuid is in `retain-uuids` keeps its
  entity and loses the attributes above that it has; any other is retracted."
  [blocks retain-uuids]
  (mapcat (fn [{uuid' :block/uuid eid :db/id :as block}]
            (if (and uuid' (contains? retain-uuids uuid'))
              (keep (fn [attr]
                      (when (some? (get block attr))
                        [:db.fn/retractAttribute eid attr]))
                    retained-block-retract-attributes)
              (when eid [[:db.fn/retractEntity eid]])))
          blocks))

(defn- get-file-page
  "Copy of file-model/get-file-page. Too basic to couple to main app"
  [db file-path]
  (ffirst
   (d/q
    '[:find ?page
      :in $ ?path
      :where
      [?file :file/path ?path]
      [?page :block/file ?file]]
    db
    file-path)))

(defn get-blocks-to-delete
  "Returns the transactional operations to retract blocks belonging to the
  given page name and file path. This function is required when a file is being
  parsed from disk; before saving the parsed, blocks from the previous version
  of that file need to be retracted.

  The 'Page' parsed from the new file version is passed separately from the
  file-path, as the page name can be set via properties in the file, and thus
  can change between versions. If it has changed, existing blocks for both the
  old and new page name will be retracted.

  Blocks are by default fully cleared via retractEntity. However, a collection
  of block UUIDs to retain can be passed, and any blocks with matching uuids
  will instead have their attributes cleared individually via
  'retractAttribute'. This will preserve block references to the retained
  UUIDs."
  [db file-page file-path retain-uuid-blocks]
  (let [existing-file-page (get-file-page db file-path)
        pages-to-clear (distinct (filter some? [existing-file-page (:db/id file-page)]))
        blocks (mapcat (fn [page-id]
                         (:block/_page (d/entity db page-id)))
                       pages-to-clear)
        retain-uuids (set (keep :block/uuid retain-uuid-blocks))]
    (retract-blocks-tx (distinct blocks) retain-uuids)))

(defn- remove-nil-vals
  "Remove keys with nil values from a map. DataScript rejects nil values."
  [m]
  (into {} (remove (fn [[_ v]] (nil? v)) m)))

(defn parse-file-data
  "Parse file content and return parsed data WITHOUT committing to database.
   This is the pure, deterministic parsing function that can be tested in isolation.

   Options:
   * :db - Database value (not conn) for looking up existing entities
   * :uuid-fn - Function to generate new UUIDs (for deterministic testing)
   * :now-fn - Function to get current timestamp (for deterministic testing)
   * :extract-options - Options map to pass to extract/extract
   * :ctime - Optional creation time for file entity
   * :mtime - Optional modification time for file entity

   Returns:
   {:pages [...] :blocks [...] :refs [...] :file-entity {...} :primary-page <map> :ast [...]}"
  [file-path content {:keys [db uuid-fn now-fn extract-options ctime mtime]
                      :or {uuid-fn d/squuid
                           now-fn #(js/Date.)}}]
  (let [format (common-util/get-format file-path)
        ;; Build file entity - shared between empty and non-empty paths
        file-entity (cond-> {:file/path file-path
                             :file/content content
                             :file/created-at (or ctime (now-fn))}
                      mtime
                      (assoc :file/last-modified-at mtime))]
    (if (string/blank? content)
      ;; Empty content - return empty result
      {:pages []
       :blocks []
       :refs []
       :file-entity file-entity
       :primary-page nil
       :ast []}
      ;; Parse content
      (let [extract-options' (merge {:block-pattern (common-config/get-block-pattern format)
                                     :date-formatter "MMM do, yyyy"
                                     :uri-encoded? false
                                     :filename-format :legacy
                                     :uuid-fn uuid-fn}
                                    extract-options
                                    {:db db})
            {:keys [pages blocks ast refs]
             :or {pages []
                  blocks []
                  ast []
                  refs []}}
            (cond (contains? common-config/mldoc-support-formats format)
                  (extract/extract file-path content extract-options')

                  (common-config/whiteboard? file-path)
                  (extract/extract-whiteboard-edn file-path content extract-options')

                  :else nil)
            ;; Store primary page BEFORE merging ref pages (for delete-blocks-fn)
            primary-page (first pages)
            ;; Add ref pages and deduplicate
            pages (extract/with-ref-pages pages blocks uuid-fn)
            ;; Clean nil values from pages and blocks (DataScript rejects nil values)
            pages (map remove-nil-vals pages)
            blocks (map remove-nil-vals blocks)]
        {:pages (vec pages)
         :blocks (vec blocks)
         :refs (vec refs)
         :file-entity file-entity
         :primary-page primary-page
         :ast ast}))))

(defn build-file-tx
  "Build transaction data from parsed file data.
   This is the transaction builder that takes output from parse-file-data
   and prepares it for DataScript transact.

   Arguments:
   - parsed-data: Result from parse-file-data
   - delete-blocks: Optional block retraction operations from delete-blocks-fn

   Returns transaction vector ready for d/transact!"
  [{:keys [pages blocks refs file-entity]} delete-blocks]
  (let [file-content [{:file/path (:file/path file-entity)}]
        block-ids (map (fn [block] {:block/uuid (:block/uuid block)}) blocks)
        block-refs-ids (->> (mapcat :block/refs blocks)
                            (filter (fn [ref] (and (vector? ref)
                                                   (= :block/uuid (first ref)))))
                            (map (fn [ref] {:block/uuid (second ref)}))
                            (seq))
        ;; To prevent "unique constraint" on datascript
        block-ids (set/union (set block-ids) (set block-refs-ids))
        pages-index (map #(select-keys % [:block/name]) pages)]
    ;; Order matters for DataScript unique constraints
    (vec (concat file-content refs pages-index delete-blocks pages block-ids blocks [file-entity]))))

(defn parse-file
  "Parse file and save parsed data to the given db. Main parse fn used by logseq app.
Options available:

  * :delete-blocks-fn - Optional fn which is called with the new page, file and existing block uuids
  which may be referenced elsewhere. Used to delete the existing blocks before saving the new ones.
   Implemented in file-common-handler/validate-and-get-blocks-to-delete for IoC
  * :extract-options - Options map to pass to extract/extract
  * :timings - Optional atom. When given, phase durations in ms are assoc'd onto it:
  :parse-ms (parse-file-data), :delete-ms (delete-blocks-fn), :build-tx-ms and
  :transact-ms (ldb/transact!, including the storage write)"
  ([conn file-path content] (parse-file conn file-path content {}))
  ([conn file-path content {:keys [delete-blocks-fn extract-options ctime mtime timings]
                            :or {delete-blocks-fn (constantly [])}
                            :as options}]
   ;; Parse file content (pure, no side effects)
   (let [t0 (when timings (js/performance.now))
         parsed (parse-file-data file-path content
                                 {:db @conn
                                  :extract-options extract-options
                                  :ctime ctime
                                  :mtime mtime})
         ;; Check if file already exists for created-at logic
         existing-file-entity (d/entity @conn [:file/path file-path])
         ;; Update file entity with created-at if needed
         parsed (if (and (not ctime) existing-file-entity)
                  ;; Keep existing created-at, don't overwrite
                  (update parsed :file-entity dissoc :file/created-at)
                  parsed)
         t1 (when timings (js/performance.now))
         ;; Get blocks to delete (use primary-page, not first of merged pages)
         delete-blocks (delete-blocks-fn
                        (:primary-page parsed)
                        file-path
                        (map (fn [block] {:block/uuid (:block/uuid block)}) (:blocks parsed)))
         t2 (when timings (js/performance.now))
         ;; Build transaction
         tx (build-file-tx parsed delete-blocks)
         t3 (when timings (js/performance.now))]
     ;; Commit to database
     (ldb/transact! conn tx (select-keys options [:new-graph? :from-disk?]))
     (when timings
       (swap! timings assoc
              :parse-ms (- t1 t0)
              :delete-ms (- t2 t1)
              :build-tx-ms (- t3 t2)
              :transact-ms (- (js/performance.now) t3)))
     {:tx tx
      :ast (:ast parsed)})))

(defn filter-files
  "Filters files in preparation for parsing. Only includes files that are
  supported by parser"
  [files]
  (let [support-files (filter
                       (fn [file]
                         (let [format (common-util/get-format (:file/path file))]
                           (contains? (set/union #{:edn :css} common-config/mldoc-support-formats) format)))
                       files)
        support-files (sort-by :file/path support-files)
        {journals true non-journals false} (group-by (fn [file] (string/includes? (:file/path file) "journals/")) support-files)
        {built-in true others false} (group-by (fn [file]
                                                 (or (string/includes? (:file/path file) "contents.")
                                                     (string/includes? (:file/path file) ".edn")
                                                     (string/includes? (:file/path file) "custom.css"))) non-journals)]
    (concat (reverse journals) built-in others)))
