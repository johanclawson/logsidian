(ns logseq.sidecar.extract
  "Pure CLJ extraction of pages and blocks from mldoc AST.

   This module receives pre-parsed AST from the worker (via V8 mldoc)
   and extracts pages/blocks for DataScript transaction.

   Key differences from graph-parser/extract.cljc:
   - Pure Clojure (JVM), no ClojureScript dependencies
   - Receives AST directly, doesn't call mldoc parser
   - Simplified extraction focused on core entity creation

   Usage:
   (extract-from-ast \"pages/test.md\" ast {:format :markdown})
   ;; => {:pages [...] :blocks [...]}"
  (:require [clojure.string :as string]
            [clojure.walk :as walk]))

;; =============================================================================
;; Utilities
;; =============================================================================

(defn- page-name-sanity-lc
  "Convert page name to lowercase, sanitized form"
  [s]
  (when s
    (-> s
        string/trim
        string/lower-case)))

(defn- path->file-name
  "Extract filename from path"
  [path]
  (when path
    (if (string/includes? path "/")
      (last (string/split path #"/"))
      path)))

(defn- path->page-name
  "Extract page name from file path"
  [path]
  (when-let [file-name (path->file-name path)]
    (let [without-ext (if (string/includes? file-name ".")
                        (first (string/split file-name #"\."))
                        file-name)]
      (page-name-sanity-lc without-ext))))

(defn- squuid
  "Generate a sequential UUID"
  []
  (java.util.UUID/randomUUID))

;; =============================================================================
;; AST Node Accessors
;; =============================================================================

(defn- ast-block-type
  "Get the type of an AST block (first element)"
  [ast-block]
  (when (vector? ast-block)
    (first ast-block)))

(defn- ast-block-data
  "Get the data of an AST block (second element)"
  [ast-block]
  (when (vector? ast-block)
    (second ast-block)))

(defn- properties-block?
  "Check if AST block is a Properties block"
  [ast-block]
  (contains? #{"Properties" "Property_Drawer"} (ast-block-type ast-block)))

(defn- heading-block?
  "Check if AST block is a Heading block"
  [ast-block]
  (= "Heading" (ast-block-type ast-block)))

;; =============================================================================
;; Property Extraction
;; =============================================================================

(defn- extract-properties
  "Extract properties from a Properties AST block.
   Returns map of property keyword -> value"
  [ast-block]
  (when (properties-block? ast-block)
    (let [props-list (ast-block-data ast-block)]
      (when (sequential? props-list)
        (->> props-list
             (map (fn [[k v _mldoc-ast]]
                    (let [k-str (if (keyword? k)
                                  (name k)
                                  (str k))]
                      [(keyword (string/lower-case k-str)) v])))
             (into {}))))))

;; =============================================================================
;; Reference Extraction
;; =============================================================================

(defn- extract-page-ref
  "Extract page reference from Link node"
  [link-data]
  (when-let [url (:url link-data)]
    (when (and (vector? url) (= "Page_ref" (first url)))
      (second url))))

(defn- extract-block-ref
  "Extract block reference ID"
  [ast-node]
  (when (and (vector? ast-node) (= "Block_reference" (first ast-node)))
    (second ast-node)))

(defn- walk-ast-for-refs
  "Walk AST and collect page/block references"
  [ast]
  (let [page-refs (atom [])
        block-refs (atom [])]
    (walk/postwalk
     (fn [node]
       (cond
         ;; Link with Page_ref
         (and (map? node) (:url node))
         (when-let [ref (extract-page-ref node)]
           (swap! page-refs conj {:block/name (page-name-sanity-lc ref)
                                  :block/title ref}))

         ;; Block_reference
         (and (vector? node) (= "Block_reference" (first node)))
         (when-let [ref-id (second node)]
           (swap! block-refs conj ref-id)))
       node)
     ast)
    {:page-refs @page-refs
     :block-refs @block-refs}))

;; =============================================================================
;; Title Extraction
;; =============================================================================

(defn- extract-plain-text
  "Extract plain text from title AST"
  [title-ast]
  (->> title-ast
       (map (fn [[type content]]
              (case type
                "Plain" content
                "Code" content
                "Link" (or (:full_text content) "")
                "")))
       (string/join "")))

(defn- extract-heading-title
  "Extract title from Heading block"
  [heading-data]
  (when-let [title-ast (:title heading-data)]
    (extract-plain-text title-ast)))

;; =============================================================================
;; Block Extraction
;; =============================================================================

(defn- extract-block
  "Extract a single block from AST node"
  [ast-node pos-meta page-name format]
  (let [block-type (ast-block-type ast-node)
        block-data (ast-block-data ast-node)]
    (when (heading-block? ast-node)
      (let [title (extract-heading-title block-data)
            {:keys [page-refs block-refs]} (walk-ast-for-refs ast-node)
            refs (concat page-refs
                         (map (fn [id] [:block/uuid id]) block-refs))]
        (cond-> {:block/uuid (squuid)
                 :block/title (or title "")
                 :block/page [:block/name page-name]
                 :block/format format}
          (seq refs)
          (assoc :block/refs (vec refs))

          (:level block-data)
          (assoc :block/level (:level block-data)))))))

(defn- extract-list-items
  "Extract blocks from List items recursively"
  [list-data page-name format]
  (let [items (:items list-data)]
    (when (sequential? items)
      (->> items
           (mapcat (fn [item]
                     (let [item-data (if (vector? item) (first item) item)
                           content (:content item-data)
                           nested-items (:items item-data)
                           ;; Extract title from paragraph content
                           title (when (sequential? content)
                                   (let [[content-type content-data] (first content)]
                                     (when (= "Paragraph" content-type)
                                       (extract-plain-text content-data))))
                           {:keys [page-refs block-refs]} (walk-ast-for-refs content)
                           refs (concat page-refs
                                        (map (fn [id] [:block/uuid id]) block-refs))
                           block (cond-> {:block/uuid (squuid)
                                          :block/title (or title "")
                                          :block/page [:block/name page-name]
                                          :block/format format}
                                   (seq refs)
                                   (assoc :block/refs (vec refs)))
                           nested-blocks (when (seq nested-items)
                                           (extract-list-items {:items nested-items} page-name format))]
                       (cons block nested-blocks))))
           (remove nil?)
           vec))))

(defn- extract-blocks-from-ast
  "Extract all blocks from AST"
  [ast page-name format]
  (when (sequential? ast)
    (->> ast
         (mapcat (fn [[ast-node pos-meta]]
                   (let [block-type (ast-block-type ast-node)]
                     (cond
                       ;; Skip properties block
                       (properties-block? ast-node)
                       nil

                       ;; Heading blocks
                       (heading-block? ast-node)
                       [(extract-block ast-node pos-meta page-name format)]

                       ;; List blocks
                       (= "List" block-type)
                       (extract-list-items (ast-block-data ast-node) page-name format)

                       :else
                       nil))))
         (remove nil?)
         vec)))

;; =============================================================================
;; Page Extraction
;; =============================================================================

(defn- get-page-name-from-ast
  "Get page name from AST (title property) or file path"
  [file-path ast]
  (let [first-block (ffirst ast)
        ;; Try to get title from properties
        properties (when (properties-block? first-block)
                     (extract-properties first-block))
        title-from-props (:title properties)]
    (if title-from-props
      (page-name-sanity-lc title-from-props)
      (path->page-name file-path))))

(defn- get-page-title-from-ast
  "Get page title (display form) from AST or file"
  [file-path ast]
  (let [first-block (ffirst ast)
        properties (when (properties-block? first-block)
                     (extract-properties first-block))
        title-from-props (:title properties)]
    (or title-from-props
        (when-let [file-name (path->file-name file-path)]
          (if (string/includes? file-name ".")
            (first (string/split file-name #"\."))
            file-name)))))

(defn- build-page
  "Build page entity from file path and AST"
  [file-path ast format]
  (let [page-name (get-page-name-from-ast file-path ast)
        page-title (get-page-title-from-ast file-path ast)
        first-block (ffirst ast)
        properties (when (properties-block? first-block)
                     (extract-properties first-block))]
    (cond-> {:block/name page-name
             :block/title (or page-title page-name)
             :block/uuid (squuid)
             :block/file {:file/path file-path}
             :block/format format}
      (seq properties)
      (assoc :block/properties properties))))

(defn- collect-ref-pages
  "Collect all referenced pages from blocks"
  [blocks]
  (->> blocks
       (mapcat :block/refs)
       (filter map?)
       (filter :block/name)
       (map (fn [ref]
              (assoc ref :block/uuid (squuid))))
       (distinct)
       vec))

;; =============================================================================
;; Main Extraction Function
;; =============================================================================

(defn extract-from-ast
  "Extract pages and blocks from mldoc AST.

   Arguments:
   - file-path: Path to the source file
   - ast: Mldoc AST as EDN (vector of [ast-node pos-meta] tuples)
   - options: Map with:
     - :format - :markdown or :org

   Returns:
   {:pages [...] :blocks [...]}

   Pages include:
   - Main page (from file)
   - Reference pages (from [[links]] in blocks)

   Blocks include:
   - All extracted blocks with UUIDs, refs, and page reference"
  [file-path ast {:keys [format] :or {format :markdown}}]
  (if (or (nil? ast) (empty? ast))
    {:pages [] :blocks []}
    (let [page-name (get-page-name-from-ast file-path ast)
          main-page (build-page file-path ast format)
          blocks (extract-blocks-from-ast ast page-name format)
          ref-pages (collect-ref-pages blocks)
          all-pages (into [main-page] ref-pages)]
      {:pages all-pages
       :blocks blocks})))

;; =============================================================================
;; REPL
;; =============================================================================

(comment
  ;; Test simple extraction
  (def test-ast
    [[["Heading" {:level 1 :title [["Plain" "Hello World"]]}]
      {:start_pos 0 :end_pos 13}]])

  (extract-from-ast "pages/test.md" test-ast {:format :markdown})

  ;; Test with properties
  (def props-ast
    [[["Properties" [["title" "My Page" nil]]]
      {:start_pos 0 :end_pos 20}]
     [["Heading" {:level 1 :title [["Plain" "Content"]]}]
      {:start_pos 21 :end_pos 35}]])

  (extract-from-ast "pages/test.md" props-ast {:format :markdown})
  )
