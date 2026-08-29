(ns logseq.cli.file-mcp.tools
  "MCP tool implementations for file-based graphs.
   Unlike DB graphs that use SQLite, file graphs parse markdown files directly
   into DataScript using logseq.graph-parser.cli/parse-graph."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [logseq.cli.file-mcp.upsert :as file-upsert]
            [logseq.db :as ldb]))

;; Hidden pages that should be filtered from list results
(def ^:private hidden-page-names
  #{"favorites" "contents" "card" "todo" "doing"
    "done" "later" "now" "waiting" "cancelled"})

(defn list-pages
  "List all pages in a file-based graph.
   Options:
     :expand - When true, includes created-at and updated-at timestamps"
  [db {:keys [expand]}]
  (->> (d/datoms db :avet :block/name)
       (map #(d/entity db (:e %)))
       (remove #(contains? hidden-page-names (:block/name %)))
       (map (fn [e]
              (cond-> {:block/name (:block/name e)
                       :block/title (or (:block/title e) (:block/original-name e))
                       :block/uuid (str (:block/uuid e))}
                expand (merge {:block/created-at (:block/created-at e)
                               :block/updated-at (:block/updated-at e)}))))))

(defn- build-block-tree
  "Build a hierarchical tree of blocks for a page.
   Returns blocks with :block/children nested structure."
  [db page-id]
  (let [blocks (->> (d/datoms db :avet :block/page page-id)
                    (map #(d/entity db (:e %)))
                    (map #(hash-map :db/id (:db/id %)
                                    :block/uuid (str (:block/uuid %))
                                    :block/title (or (:block/title %) (:block/content %))
                                    :block/parent (when-let [p (:block/parent %)]
                                                    (:db/id p))
                                    :block/properties (:block/properties %)
                                    :block/marker (:block/marker %))))
        by-parent (group-by :block/parent blocks)]
    (letfn [(tree [pid]
              (->> (get by-parent pid [])
                   (map #(let [children (tree (:db/id %))]
                           (cond-> (dissoc % :block/parent :db/id)
                             (seq children) (assoc :block/children children))))))]
      (tree page-id))))

(defn get-page-data
  "Get page data including the page entity and its block tree.
   Returns nil if page not found."
  [db page-name-or-uuid]
  (when-let [page (ldb/get-page db page-name-or-uuid)]
    {:entity {:block/name (:block/name page)
              :block/title (or (:block/title page) (:block/original-name page))
              :block/uuid (str (:block/uuid page))}
     :blocks (build-block-tree db (:db/id page))}))

(defn search-blocks
  "Search for blocks containing the given term (case-insensitive).
   Options:
     :limit - Maximum number of results (default 100)"
  [db term {:keys [limit] :or {limit 100}}]
  (let [term-lower (string/lower-case term)]
    (->> (d/datoms db :aevt :block/title)
         (filter #(and (string? (:v %))
                       (string/includes?
                         (string/lower-case (:v %)) term-lower)))
         (take limit)
         (map (fn [datom]
                (let [e (d/entity db (:e datom))
                      p (:block/page e)]
                  {:block/uuid (str (:block/uuid e))
                   :block/title (:block/title e)
                   :block/page {:block/name (:block/name p)}}))))))

(defn list-tags
  "List all tags used in the graph.
   Tags in file-based graphs are pages referenced via :block/tags.
   Options:
     :expand - When true, includes usage count"
  [db {:keys [expand]}]
  (->> (d/datoms db :aevt :block/tags)
       (mapcat #(let [v (:v %)] (if (coll? v) v [v])))
       distinct
       (map #(d/entity db %))
       (filter some?)
       (map (fn [e]
              (cond-> {:block/name (:block/name e)
                       :block/title (or (:block/title e) (:block/original-name e))
                       :block/uuid (str (:block/uuid e))}
                expand (assoc :usage-count
                              (count (d/datoms db :avet :block/tags (:db/id e)))))))))

(defn list-properties
  "List all properties used in the graph.
   Properties in file-based graphs are stored in :block/properties maps.
   Options:
     :expand - When true, includes usage count"
  [db {:keys [expand]}]
  (let [prop-counts (->> (d/datoms db :aevt :block/properties)
                         (mapcat #(when (map? (:v %)) (keys (:v %))))
                         frequencies)]
    (->> prop-counts
         (remove (fn [[k _]] (#{:id :heading} k)))
         (map (fn [[k cnt]]
                (cond-> {:name (name k) :keyword k}
                  expand (assoc :usage-count cnt)))))))

;; MCP Response Wrappers
;; =====================
(defn mcp-success
  "Wrap data in MCP success response format"
  [data]
  #js {:content #js [#js {:type "text" :text (js/JSON.stringify (clj->js data))}]})

(defn mcp-error
  "Create MCP error response"
  [msg]
  #js {:content #js [#js {:type "text" :text msg}]})

(defn mcp-list-pages
  "MCP wrapper for list-pages"
  [conn args]
  (mcp-success (list-pages @conn {:expand (aget args "expand")})))

(defn mcp-get-page
  "MCP wrapper for get-page-data"
  [conn args]
  (if-let [result (get-page-data @conn (aget args "pageName"))]
    (mcp-success result)
    (mcp-error "Page not found")))

(defn mcp-search-blocks
  "MCP wrapper for search-blocks"
  [conn args]
  (mcp-success (search-blocks @conn (aget args "searchTerm")
                              {:limit (or (aget args "limit") 100)})))

(defn mcp-list-tags
  "MCP wrapper for list-tags"
  [conn args]
  (mcp-success (list-tags @conn {:expand (aget args "expand")})))

(defn mcp-list-properties
  "MCP wrapper for list-properties"
  [conn args]
  (mcp-success (list-properties @conn {:expand (aget args "expand")})))

(defn mcp-upsert-nodes
  "MCP wrapper for file graph upsert-nodes.
   Arguments:
   - conn: DataScript connection
   - graph-dir: Path to the graph directory
   - args: MCP arguments object with:
     - operations: Array of operation objects (or JSON string)
     - dry-run: Optional boolean for validation-only mode"
  [conn graph-dir args]
  (try
    (let [operations (-> (if (string? (.-operations args))
                           (js/JSON.parse (.-operations args))
                           (.-operations args))
                         (js->clj :keywordize-keys true))
          result (file-upsert/upsert-nodes conn graph-dir operations
                                           {:dry-run (.-dry-run args)})]
      (mcp-success result))
    (catch :default e
      (mcp-error (str "Error: " (ex-message e))))))
