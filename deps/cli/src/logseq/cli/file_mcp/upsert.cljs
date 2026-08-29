(ns logseq.cli.file-mcp.upsert
  "Upsert operations for file-based graphs - writes markdown files.

   Supported operations:
   - add page: Creates a new markdown file in pages/ or journals/
   - add block: Adds a block to an existing page
   - edit block: Updates the content of an existing block

   Unsupported operations (compared to DB graphs):
   - edit page (would require renaming files and updating all references)
   - add/edit tags (complex reference management)
   - add/edit properties (need property serialization)
   - block positioning (insert before/after specific block)
   - block deletion"
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.cli.common.file :as cli-file]
            [logseq.db :as ldb]))

;; =============================================================================
;; Operation Validation
;; =============================================================================

(def ^:private supported-operations
  "Operations supported for file-based graphs"
  #{["add" "page"]
    ["add" "block"]
    ["edit" "block"]})

(defn validate-operations
  "Validates that all operations are supported for file graphs.
   Throws an exception if any unsupported operation is found."
  [operations]
  (doseq [{:keys [operation entityType]} operations]
    (when-not (contains? supported-operations [operation entityType])
      (throw (ex-info (str "Operation '" operation " " entityType "' not supported for file graphs. "
                           "Supported: add page, add block, edit block")
                      {:operation operation :entityType entityType})))))

;; =============================================================================
;; File Path Helpers
;; =============================================================================

(defn- get-pages-dir [graph-dir]
  (path/join graph-dir "pages"))

(defn- get-journals-dir [graph-dir]
  (path/join graph-dir "journals"))

(defn- journal-title?
  "Check if title looks like a journal date (YYYY-MM-DD format)"
  [title]
  (boolean (re-matches #"\d{4}-\d{2}-\d{2}" title)))

(defn- sanitize-filename
  "Sanitize a page title to be used as a filename.
   Replaces characters that are invalid in filenames."
  [title]
  (-> title
      (string/replace #"[<>:\"/\\|?*]" "_")
      (string/replace #"\s+" " ")
      string/trim))

(defn- get-page-file-path
  "Get the markdown file path for a page entity.
   First checks if page has a :block/file association, otherwise constructs path."
  [db page graph-dir]
  (if-let [file-entity (:block/file page)]
    ;; Use existing file path if available
    (let [file-path (:file/path file-entity)]
      (if (path/isAbsolute file-path)
        file-path
        (path/join graph-dir file-path)))
    ;; Construct path from page name
    (let [title (or (:block/title page) (:block/original-name page) (:block/name page))
          is-journal (ldb/journal? page)
          dir (if is-journal (get-journals-dir graph-dir) (get-pages-dir graph-dir))
          file-name (str (sanitize-filename
                           (if is-journal
                             (string/replace title "-" "_")
                             title))
                         ".md")]
      (path/join dir file-name))))

;; =============================================================================
;; Block Tree to Markdown Serialization
;; =============================================================================

(defn- save-page-to-file
  "Serialize a page's blocks to markdown and write to disk.
   Returns the file path that was written."
  [db page graph-dir]
  (let [file-path (get-page-file-path db page graph-dir)
        ;; Ensure parent directory exists
        _ (fs/mkdirSync (path/dirname file-path) #js {:recursive true})
        ;; Build content using the existing block->content function
        content (cli-file/block->content
                  "file_graph" db (:block/uuid page)
                  {:init-level 0}
                  {:export-bullet-indentation "  "})]
    (fs/writeFileSync file-path content "utf8")
    file-path))

;; =============================================================================
;; Add Page Operation
;; =============================================================================

(defn- add-page
  "Create a new markdown file for a page.
   Data should contain :title for the page name.
   Returns {:file-path path :title title :uuid uuid}"
  [conn graph-dir {:keys [data]}]
  (let [title (:title data)
        _ (when (string/blank? title)
            (throw (ex-info "Page title is required" {:data data})))
        ;; Check if page already exists
        db @conn
        existing-page (ldb/get-page db title)]
    (when existing-page
      (throw (ex-info (str "Page '" title "' already exists")
                      {:title title :uuid (str (:block/uuid existing-page))})))
    (let [is-journal (journal-title? title)
          dir (if is-journal (get-journals-dir graph-dir) (get-pages-dir graph-dir))
          file-name (str (sanitize-filename
                           (if is-journal
                             (string/replace title "-" "_")
                             title))
                         ".md")
          file-path (path/join dir file-name)
          page-uuid (random-uuid)]
      ;; Create directory if needed
      (fs/mkdirSync dir #js {:recursive true})
      ;; Create empty page file
      (fs/writeFileSync file-path "" "utf8")
      ;; Transact page to DataScript
      (d/transact! conn [{:block/uuid page-uuid
                          :block/name (string/lower-case title)
                          :block/title title
                          :block/original-name title
                          :block/type (if is-journal "journal" "page")
                          :block/format :markdown}])
      {:file-path file-path
       :title title
       :uuid (str page-uuid)})))

;; =============================================================================
;; Add Block Operation
;; =============================================================================

(defn- get-page-for-block
  "Resolve the page entity for a block operation.
   page-id can be a UUID string or page name."
  [db page-id]
  (let [page (cond
               ;; Try parsing as UUID first
               (and (string? page-id) (parse-uuid page-id))
               (d/entity db [:block/uuid (parse-uuid page-id)])
               ;; Otherwise look up by name
               :else
               (ldb/get-page db page-id))]
    (when-not page
      (throw (ex-info (str "Page not found: " page-id) {:page-id page-id})))
    page))

(defn- get-next-block-order
  "Get the next order value for a new block under a parent.
   Returns a string that sorts after all existing children."
  [db parent-id]
  (let [children (->> (d/datoms db :avet :block/parent parent-id)
                      (map #(d/entity db (:e %)))
                      (map :block/order)
                      (filter some?))]
    (if (empty? children)
      "a0"
      (let [max-order (apply max-key count children)]
        (str max-order "0")))))

(defn- add-block
  "Add a new block to an existing page.
   Data should contain:
   - :title - the block content
   - :page-id - UUID string or page name
   Returns {:block-uuid uuid :file-path path}"
  [conn graph-dir {:keys [data]}]
  (let [title (:title data)
        page-id (:page-id data)
        _ (when (string/blank? title)
            (throw (ex-info "Block title is required" {:data data})))
        _ (when (nil? page-id)
            (throw (ex-info "page-id is required" {:data data})))
        db @conn
        page (get-page-for-block db page-id)
        block-uuid (random-uuid)
        order (get-next-block-order db (:db/id page))
        tx-data [{:block/uuid block-uuid
                  :block/title title
                  :block/page {:db/id (:db/id page)}
                  :block/parent {:db/id (:db/id page)}
                  :block/order order
                  :block/format :markdown}]]
    ;; Transact new block
    (d/transact! conn tx-data)
    ;; Save page to file
    (let [file-path (save-page-to-file @conn page graph-dir)]
      {:block-uuid (str block-uuid)
       :file-path file-path})))

;; =============================================================================
;; Edit Block Operation
;; =============================================================================

(defn- edit-block
  "Update an existing block's content.
   id should be the block UUID.
   Data should contain :title with the new content.
   Returns {:block-uuid uuid :file-path path}"
  [conn graph-dir {:keys [id data]}]
  (let [_ (when (nil? id)
            (throw (ex-info "Block id is required for edit operation" {})))
        _ (when (string/blank? (:title data))
            (throw (ex-info "Block title is required" {:data data})))
        block-uuid (if (uuid? id) id (parse-uuid id))
        _ (when-not block-uuid
            (throw (ex-info (str "Invalid block UUID: " id) {:id id})))
        db @conn
        block (d/entity db [:block/uuid block-uuid])]
    (when-not block
      (throw (ex-info (str "Block not found: " id) {:id id})))
    (let [page (:block/page block)]
      ;; Update block in DataScript
      (d/transact! conn [{:db/id (:db/id block)
                          :block/title (:title data)}])
      ;; Save page to file
      (let [file-path (save-page-to-file @conn page graph-dir)]
        {:block-uuid (str block-uuid)
         :file-path file-path}))))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn upsert-nodes
  "Execute upsert operations on a file graph.

   Arguments:
   - conn: DataScript connection
   - graph-dir: Path to the graph directory
   - operations: Vector of operation maps, each with:
     - :operation - 'add' or 'edit'
     - :entityType - 'page' or 'block'
     - :id - (for edit) the entity UUID
     - :data - operation-specific data
   - opts: Options map
     - :dry-run - if true, only validate without making changes

   Returns summary string."
  [conn graph-dir operations {:keys [dry-run]}]
  (validate-operations operations)
  (let [results (when-not dry-run
                  (doall
                    (for [{:keys [operation entityType] :as op} operations]
                      (case [operation entityType]
                        ["add" "page"] (add-page conn graph-dir op)
                        ["add" "block"] (add-block conn graph-dir op)
                        ["edit" "block"] (edit-block conn graph-dir op)))))]
    {:message (str (when dry-run "Dry run: ")
                   "Processed " (count operations) " operation(s)")
     :results (when-not dry-run results)}))
