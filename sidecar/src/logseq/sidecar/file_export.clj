(ns logseq.sidecar.file-export
  "Convert page tree to markdown file content.

   This module provides the JVM-side equivalent of
   logseq.cli.common.file/tree->file-content for file serialization.

   The format matches Logseq's file format:
   - Each block is prefixed with '-' and indentation
   - Nested blocks have additional indentation
   - Properties are serialized inline

   Usage:
   ```clojure
   (require '[logseq.sidecar.file-export :as export])

   (let [page-tree (outliner/get-page-tree db page-id)]
     (export/page-tree->markdown page-tree))
   ```"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:const default-indent "  ")  ; Two spaces per level

;; =============================================================================
;; Content Formatting
;; =============================================================================

(defn- indent-content
  "Indent multi-line content to match block indentation."
  [content indent-str]
  (let [lines (str/split-lines content)]
    (str/join (str "\n" indent-str) lines)))

(defn- format-properties
  "Format block properties as inline property syntax.
   Returns property string to append to content, or nil if no properties."
  [properties]
  (when (and (map? properties) (seq properties))
    ;; Filter out internal/computed properties
    (let [visible-props (dissoc properties
                                :id :ls-type :logseq.property/built-in?
                                :block/uuid)]
      (when (seq visible-props)
        (->> visible-props
             (map (fn [[k v]]
                    (str (name k) ":: " (pr-str v))))
             (str/join "\n"))))))

(defn- block->line
  "Convert a single block to a markdown line with proper indentation.

   Arguments:
   - block: Block map with :block/content and optionally :block/properties
   - level: Indentation level (1-based, where 1 is top-level)
   - opts: Options map with :indent-str (default: two spaces)

   Returns: String representing the block line(s)"
  [block level opts]
  (let [indent-str (or (:indent-str opts) default-indent)
        prefix-indent (apply str (repeat (dec level) indent-str))
        content-indent (str prefix-indent indent-str)
        content (or (:block/content block) (:block/title block) "")
        content (str/trim content)
        ;; Format properties if present
        props-str (format-properties (:block/properties block))
        ;; Indent multi-line content
        indented-content (indent-content content content-indent)
        ;; Combine with bullet
        line (str prefix-indent "- " indented-content)]
    (if props-str
      (str line "\n" content-indent props-str)
      line)))

;; =============================================================================
;; Tree Traversal
;; =============================================================================

(defn- blocks->lines
  "Convert a vector of blocks (with children) to markdown lines.

   Arguments:
   - blocks: Vector of block maps, each may have :children
   - level: Current indentation level
   - opts: Options for formatting

   Returns: Vector of strings (lines)"
  [blocks level opts]
  (reduce
   (fn [lines block]
     (let [block-line (block->line block level opts)
           children (:children block)
           child-lines (when (seq children)
                         (blocks->lines children (inc level) opts))]
       (cond-> (conj lines block-line)
         (seq child-lines) (into child-lines))))
   []
   blocks))

;; =============================================================================
;; Main API
;; =============================================================================

(defn page-tree->markdown
  "Convert a page tree (from outliner/get-page-tree) to markdown content.

   Arguments:
   - page-tree: Map with :page and :blocks from get-page-tree
   - opts: Options map with:
     - :indent-str - Indentation string (default: two spaces)
     - :include-page-properties? - Include page properties (default: true)

   Returns: String of markdown content ready for file write

   Example:
   ```clojure
   (let [tree (outliner/get-page-tree db page-id)]
     (page-tree->markdown tree {}))
   ;; => \"- Block 1\\n  - Child block\\n- Block 2\"
   ```"
  [page-tree opts]
  (let [{:keys [page blocks]} page-tree
        ;; Page properties (if any)
        page-props (when (:include-page-properties? opts true)
                     (format-properties (:block/properties page)))
        ;; Convert blocks to lines
        block-lines (blocks->lines blocks 1 opts)]
    (str
     (when page-props
       (str page-props "\n\n"))
     (str/join "\n" block-lines))))

(defn pages->file-writes
  "Convert multiple page trees to file write specifications.

   Arguments:
   - page-trees: Vector of page trees from get-pages-for-file-sync
   - graph-dir: Base directory of the graph
   - opts: Options for markdown conversion

   Returns: Vector of [file-path content] tuples ready for writing

   Example:
   ```clojure
   (let [trees (outliner/get-pages-for-file-sync db page-ids)]
     (pages->file-writes trees \"/path/to/graph\" {}))
   ;; => [[\"pages/test.md\" \"- Block content\"]
   ;;     [\"pages/other.md\" \"- Other content\"]]
   ```"
  [page-trees graph-dir opts]
  (for [tree page-trees
        :let [page (:page tree)
              page-name (:block/name page)
              format (or (:block/format page) :markdown)
              ;; Determine file path based on page type
              journal-day (:block/journal-day page)
              sub-dir (if journal-day "journals" "pages")
              ext (if (= format :org) "org" "md")
              ;; Sanitize filename
              filename (-> (or (:block/title page) page-name "untitled")
                           (str/replace #"[\\/:*?\"<>|]" "_"))
              file-path (str sub-dir "/" filename "." ext)
              content (page-tree->markdown tree opts)]
        :when (and page-name (seq content))]
    [file-path content]))

(defn export-affected-pages
  "Export affected pages after an outliner operation.

   This is the main entry point for file sync after operations.

   Arguments:
   - db: DataScript database
   - page-ids: Vector of affected page db/ids
   - graph-dir: Base directory of the graph
   - opts: Options for export

   Returns: Vector of [file-path content] tuples

   Usage in apply-ops handler:
   ```clojure
   (let [result (outliner/apply-ops! conn ops opts)
         page-ids (:affected-pages result)]
     (when (seq page-ids)
       (let [trees (outliner/get-pages-for-file-sync @conn page-ids)
             files (export/export-affected-pages @conn page-ids graph-dir {})]
         ;; Send file writes to main process
         ...)))
   ```"
  [db page-ids graph-dir opts]
  (let [;; Import outliner dynamically to avoid circular dependency
        get-pages-for-file-sync (requiring-resolve 'logseq.sidecar.outliner/get-pages-for-file-sync)
        page-trees (get-pages-for-file-sync db page-ids)]
    (log/debug "export-affected-pages" {:page-count (count page-trees)})
    (pages->file-writes page-trees graph-dir opts)))
