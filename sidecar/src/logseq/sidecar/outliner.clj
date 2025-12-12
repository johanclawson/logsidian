(ns logseq.sidecar.outliner
  "Outliner operations for block manipulation.

   This module implements the core outliner operations needed for editing:
   - save-block: Update block content
   - insert-blocks: Insert new blocks
   - delete-blocks: Delete blocks
   - move-blocks: Move blocks to new location
   - indent-outdent-blocks: Change block nesting level

   These operations mirror the CLJS outliner/core.cljs but are simplified
   for the JVM sidecar context. The main process handles file persistence;
   sidecar handles the DataScript operations.

   Usage:
   ```clojure
   (apply-ops! conn [[op args] [op args] ...] opts)
   ```"
  (:require [clojure.tools.logging :as log]
            [datascript.core :as d]))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn- time-ms
  "Current time in milliseconds."
  []
  (System/currentTimeMillis))

(defn- block-with-timestamps
  "Add created-at and updated-at timestamps to a block."
  [block]
  (let [now (time-ms)]
    (cond-> (assoc block :block/updated-at now)
      (nil? (:block/created-at block))
      (assoc :block/created-at now))))

(defn- get-block
  "Get a block entity by ID (db/id or lookup ref)."
  [db block-id]
  (cond
    (integer? block-id) (d/entity db block-id)
    (vector? block-id) (d/entity db block-id)
    (uuid? block-id) (d/entity db [:block/uuid block-id])
    :else nil))

(defn- get-page-id
  "Get the page ID for a block or page entity.
   If the entity is a page itself, returns its ID.
   If it's a block, returns its :block/page ID."
  [db block-id]
  (when-let [entity (get-block db block-id)]
    (if (= "page" (:block/type entity))
      (:db/id entity)
      (or (:db/id (:block/page entity))
          ;; Fallback: entity itself might be the page
          (:db/id entity)))))

(defn- get-children
  "Get direct children of a block, ordered by :block/order or :block/left."
  [db parent-id]
  (->> (d/q '[:find ?c ?order
              :in $ ?parent
              :where
              [?c :block/parent ?parent]
              [(get-else $ ?c :block/order "") ?order]]
            db parent-id)
       (sort-by second)
       (map first)
       (map #(d/entity db %))))

(defn- get-last-child
  "Get the last child of a block (rightmost in tree)."
  [db parent-id]
  (last (get-children db parent-id)))

;; =============================================================================
;; Block Operations
;; =============================================================================

(defn save-block!
  "Update a block's content and properties.

   Arguments:
   - conn: DataScript connection
   - block: Map with :db/id or :block/uuid and updated fields
   - opts: Options (currently unused)

   Returns: Transaction result"
  [conn block _opts]
  (let [db @conn
        block-id (or (:db/id block)
                     (when-let [uuid (:block/uuid block)]
                       (:db/id (d/entity db [:block/uuid uuid]))))
        existing (when block-id (d/entity db block-id))]
    (when existing
      (let [tx-data [(-> block
                         (assoc :db/id block-id)
                         block-with-timestamps)]]
        (log/debug "save-block!" {:block-id block-id})
        (d/transact! conn tx-data)))))

(defn insert-blocks!
  "Insert one or more blocks after a target block.

   Arguments:
   - conn: DataScript connection
   - blocks: Vector of block maps to insert
   - target-block-id: ID of block to insert after (db/id or lookup ref)
   - opts: Options map with:
     - :sibling? - Insert as sibling (true) or child (false, default)

   Returns: Map with :inserted-blocks containing the new block entities"
  [conn blocks target-block-id opts]
  (let [db @conn
        target (get-block db target-block-id)]
    (when (and target (seq blocks))
      (let [sibling? (:sibling? opts true)
            parent-id (if sibling?
                        (:db/id (:block/parent target))
                        (:db/id target))
            page-id (:db/id (or (:block/page target) target))
            ;; Ensure each block has a UUID, preserve existing UUIDs
            blocks-with-ids (mapv (fn [block]
                                    (let [uuid (or (:block/uuid block) (random-uuid))]
                                      (-> block
                                          (assoc :block/uuid uuid
                                                 :block/parent {:db/id parent-id}
                                                 :block/page {:db/id page-id})
                                          block-with-timestamps)))
                                  blocks)
            tx-data blocks-with-ids]
        (log/debug "insert-blocks!" {:count (count blocks) :target target-block-id :parent parent-id})
        (let [result (d/transact! conn tx-data)
              uuids (map :block/uuid blocks-with-ids)]
          {:inserted-blocks (mapv (fn [uuid]
                                    (d/entity @conn [:block/uuid uuid]))
                                  uuids)})))))

(defn delete-blocks!
  "Delete one or more blocks and optionally their children.

   Arguments:
   - conn: DataScript connection
   - block-ids: Vector of block IDs to delete
   - opts: Options map with:
     - :children? - Also delete children (default: true)

   Returns: Transaction result"
  [conn block-ids opts]
  (let [db @conn
        delete-children? (:children? opts true)
        ;; Collect all blocks to delete
        blocks-to-delete (if delete-children?
                           ;; Recursively collect all descendants
                           (loop [to-process (set block-ids)
                                  collected #{}]
                             (if (empty? to-process)
                               collected
                               (let [children (->> to-process
                                                   (mapcat (fn [id]
                                                             (map :db/id (get-children db id))))
                                                   set)]
                                 (recur children (into collected to-process)))))
                           (set block-ids))
        tx-data (mapv (fn [id] [:db/retractEntity id]) blocks-to-delete)]
    (when (seq tx-data)
      (log/debug "delete-blocks!" {:count (count blocks-to-delete)})
      (d/transact! conn tx-data))))

(defn move-blocks!
  "Move blocks to a new location.

   Arguments:
   - conn: DataScript connection
   - block-ids: Vector of block IDs to move
   - target-block-id: ID of target block
   - opts: Options map with:
     - :sibling? - Move as sibling (true) or child (false)
     - :before? - Move before target (true) or after (false, default)

   Returns: Transaction result"
  [conn block-ids target-block-id opts]
  (let [db @conn
        target (get-block db target-block-id)
        sibling? (:sibling? opts true)]
    (when (and target (seq block-ids))
      (let [new-parent-id (if sibling?
                            (:db/id (:block/parent target))
                            (:db/id target))
            new-page-id (:db/id (or (:block/page target) target))
            tx-data (mapv (fn [id]
                            (-> {:db/id id
                                 :block/parent {:db/id new-parent-id}
                                 :block/page {:db/id new-page-id}}
                                block-with-timestamps))
                          block-ids)]
        (log/debug "move-blocks!" {:count (count block-ids) :target target-block-id})
        (d/transact! conn tx-data)))))

(defn indent-outdent-blocks!
  "Indent or outdent blocks (change their parent).

   Indent: Move block to be child of previous sibling
   Outdent: Move block to be sibling of current parent

   Arguments:
   - conn: DataScript connection
   - block-ids: Vector of block IDs
   - indent?: true to indent, false to outdent
   - opts: Options (currently unused)

   Returns: Transaction result or nil if operation not possible"
  [conn block-ids indent? _opts]
  (let [db @conn]
    (when (seq block-ids)
      (let [first-block (get-block db (first block-ids))
            current-parent (:block/parent first-block)]
        (if indent?
          ;; Indent: Find previous sibling and make it the new parent
          (let [siblings (get-children db (:db/id current-parent))
                block-idx (.indexOf (mapv :db/id siblings) (first block-ids))
                prev-sibling (when (pos? block-idx)
                               (nth siblings (dec block-idx)))]
            (when prev-sibling
              (let [tx-data (mapv (fn [id]
                                    (-> {:db/id id
                                         :block/parent {:db/id (:db/id prev-sibling)}}
                                        block-with-timestamps))
                                  block-ids)]
                (log/debug "indent-blocks!" {:count (count block-ids)})
                (d/transact! conn tx-data))))
          ;; Outdent: Move to be sibling of current parent
          (let [grandparent (:block/parent current-parent)]
            (when grandparent
              (let [tx-data (mapv (fn [id]
                                    (-> {:db/id id
                                         :block/parent {:db/id (:db/id grandparent)}}
                                        block-with-timestamps))
                                  block-ids)]
                (log/debug "outdent-blocks!" {:count (count block-ids)})
                (d/transact! conn tx-data)))))))))

(defn move-blocks-up-down!
  "Move blocks up or down among siblings.

   Arguments:
   - conn: DataScript connection
   - block-ids: Vector of block IDs to move
   - up?: true to move up, false to move down

   Returns: Transaction result or nil if operation not possible"
  [conn block-ids up?]
  (let [db @conn]
    (when (seq block-ids)
      (let [first-block (get-block db (first block-ids))
            parent (:block/parent first-block)
            siblings (get-children db (:db/id parent))
            sibling-ids (mapv :db/id siblings)
            ;; Find the index of the block being moved
            block-idx (.indexOf sibling-ids (first block-ids))]
        (when (>= block-idx 0)
          (if up?
            ;; Move up: swap orders with previous sibling
            (when (pos? block-idx)
              (let [prev-sibling (nth siblings (dec block-idx))
                    current-block (nth siblings block-idx)
                    prev-order (:block/order prev-sibling)
                    current-order (:block/order current-block)
                    ;; Simply swap the orders
                    tx-data [{:db/id (:db/id prev-sibling)
                              :block/order current-order}
                             {:db/id (first block-ids)
                              :block/order prev-order}]]
                (log/debug "move-blocks-up!" {:count (count block-ids)})
                (d/transact! conn tx-data)))
            ;; Move down: swap orders with next sibling
            (when (< block-idx (dec (count siblings)))
              (let [next-sibling (nth siblings (inc block-idx))
                    current-block (nth siblings block-idx)
                    next-order (:block/order next-sibling)
                    current-order (:block/order current-block)
                    ;; Simply swap the orders
                    tx-data [{:db/id (:db/id next-sibling)
                              :block/order current-order}
                             {:db/id (first block-ids)
                              :block/order next-order}]]
                (log/debug "move-blocks-down!" {:count (count block-ids)})
                (d/transact! conn tx-data)))))))))

;; =============================================================================
;; Page Operations
;; =============================================================================

(defn create-page!
  "Create a new page.

   Arguments:
   - conn: DataScript connection
   - title: Page title (will be normalized to lowercase for :block/name)
   - opts: Options map with:
     - :uuid - Specific UUID to use (optional, generates random if not provided)
     - :properties - Initial properties map (optional)
     - :format - Page format, e.g., :markdown (optional)

   Returns: Map with page data (db/id, uuid, name, title, type, etc.)
           Note: Returns plain map instead of Entity for Transit serialization."
  [conn title opts]
  (let [uuid (or (:uuid opts) (random-uuid))
        name (clojure.string/lower-case title)
        now (time-ms)
        page-data {:block/uuid uuid
                   :block/name name
                   :block/title title
                   :block/type "page"
                   :block/format (or (:format opts) :markdown)
                   :block/created-at now
                   :block/updated-at now}]
    (log/debug "create-page!" {:title title :uuid uuid})
    (d/transact! conn [page-data])
    ;; Look up the entity to get the db/id (Entity can't be serialized through Transit)
    (let [entity (d/entity @conn [:block/uuid uuid])]
      (assoc page-data :db/id (:db/id entity)))))

(defn rename-page!
  "Rename a page.

   Arguments:
   - conn: DataScript connection
   - page-uuid: UUID of the page to rename
   - new-title: New title for the page

   Returns: Transaction result"
  [conn page-uuid new-title]
  (let [db @conn
        page (d/entity db [:block/uuid page-uuid])]
    (when page
      (let [new-name (clojure.string/lower-case new-title)
            tx-data [{:db/id (:db/id page)
                      :block/name new-name
                      :block/title new-title
                      :block/updated-at (time-ms)}]]
        (log/debug "rename-page!" {:uuid page-uuid :new-title new-title})
        (d/transact! conn tx-data)))))

(defn delete-page!
  "Delete a page and all its blocks.

   Arguments:
   - conn: DataScript connection
   - page-uuid: UUID of the page to delete

   Returns: Transaction result"
  [conn page-uuid]
  (let [db @conn
        page (d/entity db [:block/uuid page-uuid])]
    (when page
      (let [page-id (:db/id page)
            ;; Find all blocks belonging to this page
            block-ids (->> (d/q '[:find ?b
                                  :in $ ?page-id
                                  :where [?b :block/page ?page-id]]
                                db page-id)
                           (map first))
            ;; Retract page and all its blocks
            tx-data (into [[:db/retractEntity page-id]]
                          (map (fn [id] [:db/retractEntity id]) block-ids))]
        (log/debug "delete-page!" {:uuid page-uuid :block-count (count block-ids)})
        (d/transact! conn tx-data)))))

;; =============================================================================
;; Batch Import
;; =============================================================================

(defn- normalize-block-tree
  "Normalize a block from the import tree format to DataScript transaction format.

   Input format (from Logseq EDN export):
   {:uuid UUID
    :content \"Block content\"
    :properties {}
    :format :markdown
    :children [...]}

   Returns a vector of transaction maps for the block and all descendants."
  [block parent-id page-id order-prefix]
  (let [uuid (or (:uuid block) (random-uuid))
        now (time-ms)
        ;; Build the block entity
        block-entity (cond-> {:block/uuid uuid
                              :block/parent {:db/id parent-id}
                              :block/page {:db/id page-id}
                              :block/created-at now
                              :block/updated-at now}
                       (:content block)
                       (assoc :block/content (:content block))

                       (:title block)
                       (assoc :block/title (:title block))

                       (:properties block)
                       (assoc :block/properties (:properties block))

                       (:format block)
                       (assoc :block/format (:format block))

                       order-prefix
                       (assoc :block/order order-prefix))
        ;; Recursively process children
        children (:children block)
        child-txs (when (seq children)
                    (mapcat (fn [idx child]
                              ;; Use alphabetic ordering: "a", "b", ..., "aa", "ab", ...
                              (let [child-order (str order-prefix (char (+ (int \a) (mod idx 26))))]
                                (normalize-block-tree child
                                                      [:block/uuid uuid]
                                                      page-id
                                                      child-order)))
                            (range)
                            children))]
    (cons block-entity child-txs)))

(defn- import-page-tree!
  "Import a single page with its block tree.

   Input format:
   {:uuid UUID
    :title \"Page Title\"
    :type \"page\" or \"whiteboard\"
    :properties {}
    :format :markdown
    :children [...blocks...]}

   Returns the created page entity."
  [conn page-tree]
  (let [uuid (or (:uuid page-tree) (random-uuid))
        title (:title page-tree)
        name (when title (clojure.string/lower-case title))
        now (time-ms)
        page-type (or (:type page-tree) "page")
        ;; Create page entity
        page-entity (cond-> {:block/uuid uuid
                             :block/type page-type
                             :block/created-at now
                             :block/updated-at now}
                      name
                      (assoc :block/name name)

                      title
                      (assoc :block/title title)

                      (:properties page-tree)
                      (assoc :block/properties (:properties page-tree))

                      (:format page-tree)
                      (assoc :block/format (:format page-tree)))
        ;; First, transact the page to get its db/id
        _ (d/transact! conn [page-entity])
        page-id (:db/id (d/entity @conn [:block/uuid uuid]))
        ;; Process children blocks
        children (:children page-tree)
        child-txs (when (seq children)
                    (mapcat (fn [idx child]
                              (let [order (str (char (+ (int \a) (mod idx 26))))]
                                (normalize-block-tree child
                                                      page-id
                                                      page-id
                                                      order)))
                            (range)
                            children))]
    ;; Transact all child blocks
    (when (seq child-txs)
      (d/transact! conn (vec child-txs)))
    (log/debug "import-page-tree!" {:title title :uuid uuid :block-count (count child-txs)})
    (d/entity @conn [:block/uuid uuid])))

(defn batch-import-edn!
  "Batch import pages and blocks from EDN format.

   This is the JVM equivalent of frontend.handler.import/import-from-edn!

   Arguments:
   - conn: DataScript connection
   - data: EDN data map with :blocks key containing page trees
   - opts: Options map with:
     - :translate-fn - Optional function to transform each page tree before import

   Input format:
   {:blocks [{:uuid UUID
              :title \"Page Title\"
              :type \"page\"
              :properties {}
              :format :markdown
              :children [{:uuid UUID
                          :content \"Block content\"
                          :children [...]}]}
             ...]}

   Returns: Map with :imported-pages (vector of created page entities)
            and :page-count, :block-count statistics"
  [conn data opts]
  (let [translate-fn (or (:translate-fn opts) identity)
        pages (mapv translate-fn (:blocks data))
        *block-count (atom 0)
        imported-pages (doall
                        (for [page-tree pages]
                          (let [page (import-page-tree! conn page-tree)
                                children-count (count (tree-seq map? :children page-tree))]
                            (swap! *block-count + children-count)
                            page)))]
    (log/info "batch-import-edn!" {:page-count (count pages) :total-blocks @*block-count})
    {:imported-pages (vec imported-pages)
     :page-count (count pages)
     :block-count @*block-count}))

;; =============================================================================
;; Page Tree Export (for file serialization)
;; =============================================================================

(defn get-page-tree
  "Get a page and its blocks as a tree structure for file serialization.

   This function returns the page data in a format suitable for converting
   back to markdown by the main process.

   Arguments:
   - db: DataScript db value (not connection)
   - page-id: Page db/id

   Returns a map with:
   - :page - Page entity attributes (uuid, name, title, type, format, etc.)
   - :blocks - Vector of block trees (each with :content, :children, etc.)
   - nil if page not found

   Example return:
   {:page {:block/uuid #uuid \"...\"
           :block/name \"my-page\"
           :block/title \"My Page\"
           :block/type \"page\"
           :block/format :markdown}
    :blocks [{:block/uuid #uuid \"...\"
              :block/content \"First block\"
              :block/properties {}
              :children [{:block/uuid #uuid \"...\"
                          :block/content \"Child block\"
                          :children []}]}]}"
  [db page-id]
  (when-let [page (d/entity db page-id)]
    (let [;; Extract page attributes
          page-attrs (select-keys page [:block/uuid :block/name :block/title
                                         :block/type :block/format :block/properties
                                         :block/created-at :block/updated-at
                                         :block/journal-day])
          ;; Get direct children of page (top-level blocks)
          children (get-children db page-id)

          ;; Build block tree recursively
          build-tree (fn build-tree [block]
                       (let [block-attrs (select-keys block
                                                      [:block/uuid :block/content :block/title
                                                       :block/properties :block/order
                                                       :block/collapsed? :block/marker
                                                       :block/priority :block/scheduled
                                                       :block/deadline :block/created-at
                                                       :block/updated-at])
                             block-children (get-children db (:db/id block))]
                         (if (seq block-children)
                           (assoc block-attrs :children (mapv build-tree block-children))
                           block-attrs)))

          ;; Build tree for each top-level block
          block-trees (mapv build-tree children)]

      {:page page-attrs
       :blocks block-trees})))

(defn get-pages-for-file-sync
  "Get multiple pages with their block trees for file synchronization.

   Arguments:
   - db: DataScript db value
   - page-ids: Vector of page db/ids

   Returns vector of page trees (see get-page-tree for format).
   Skips any page-ids that don't exist."
  [db page-ids]
  (->> page-ids
       (map #(get-page-tree db %))
       (remove nil?)
       vec))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn apply-ops!
  "Apply a batch of outliner operations.

   Arguments:
   - conn: DataScript connection
   - ops: Vector of [op-keyword args-vector] tuples
   - opts: Global options for all operations

   Supported operations:
   Block operations:
   - [:save-block [block opts]]
   - [:insert-blocks [blocks target-id opts]]
   - [:delete-blocks [block-ids opts]]
   - [:move-blocks [block-ids target-id opts]]
   - [:move-blocks-up-down [block-ids up?]]
   - [:indent-outdent-blocks [block-ids indent? opts]]

   Page operations:
   - [:create-page [title opts]]
   - [:rename-page [page-uuid new-title]]
   - [:delete-page [page-uuid]]

   Import operations:
   - [:batch-import-edn [data opts]]

   Other:
   - [:transact [tx-data tx-meta]]

   Returns: Map with:
   - :result - Result from the last operation
   - :affected-pages - Set of page db/ids that were modified (for file sync)"
  [conn ops opts]
  (let [*result (atom nil)
        *affected-pages (atom #{})]
    (doseq [[op args] ops]
      ;; Coerce op to keyword - frontend may send symbols through Transit
      (let [op-kw (if (keyword? op) op (keyword (name op)))]
        (log/debug "apply-op" {:op op :op-type (type op) :op-kw op-kw :args-count (count args)})
        (try
          (case op-kw
          ;; Block operations
          :save-block
          (let [[block op-opts] args
                db @conn
                ;; Track affected page before operation
                block-id (or (:db/id block)
                             (when-let [uuid (:block/uuid block)]
                               (:db/id (d/entity db [:block/uuid uuid]))))
                page-id (when block-id (get-page-id db block-id))]
            (save-block! conn block (merge opts op-opts))
            (when page-id
              (swap! *affected-pages conj page-id)))

          :insert-blocks
          (let [[blocks target-id op-opts] args
                db @conn
                page-id (get-page-id db target-id)
                result (insert-blocks! conn blocks target-id (merge opts op-opts))]
            (reset! *result result)
            (when page-id
              (swap! *affected-pages conj page-id)))

          :delete-blocks
          (let [[block-ids op-opts] args
                db @conn
                ;; Track all pages that have blocks being deleted
                page-ids (->> block-ids
                              (map #(get-page-id db %))
                              (remove nil?)
                              set)]
            (delete-blocks! conn block-ids (merge opts op-opts))
            (swap! *affected-pages into page-ids))

          :move-blocks
          (let [[block-ids target-id op-opts] args
                db @conn
                ;; Track source pages (blocks being moved from)
                source-page-ids (->> block-ids
                                     (map #(get-page-id db %))
                                     (remove nil?)
                                     set)
                ;; Track target page (blocks being moved to)
                target-page-id (get-page-id db target-id)]
            (move-blocks! conn block-ids target-id (merge opts op-opts))
            (swap! *affected-pages into source-page-ids)
            (when target-page-id
              (swap! *affected-pages conj target-page-id)))

          :move-blocks-up-down
          (let [[block-ids up?] args
                db @conn
                page-ids (->> block-ids
                              (map #(get-page-id db %))
                              (remove nil?)
                              set)]
            (move-blocks-up-down! conn block-ids up?)
            (swap! *affected-pages into page-ids))

          :indent-outdent-blocks
          (let [[block-ids indent? op-opts] args
                db @conn
                page-ids (->> block-ids
                              (map #(get-page-id db %))
                              (remove nil?)
                              set)]
            (indent-outdent-blocks! conn block-ids indent? (merge opts op-opts))
            (swap! *affected-pages into page-ids))

          ;; Page operations
          :create-page
          (let [[title op-opts] args
                result (create-page! conn title (merge opts op-opts))]
            (reset! *result result)
            ;; New page is affected
            (when-let [page-id (:db/id result)]
              (swap! *affected-pages conj page-id)))

          :rename-page
          (let [[page-uuid new-title] args
                db @conn
                page (d/entity db [:block/uuid page-uuid])]
            (rename-page! conn page-uuid new-title)
            (when-let [page-id (:db/id page)]
              (swap! *affected-pages conj page-id)))

          :delete-page
          (let [[page-uuid] args
                db @conn
                page (d/entity db [:block/uuid page-uuid])]
            ;; Track page being deleted before deletion
            (when-let [page-id (:db/id page)]
              (swap! *affected-pages conj page-id))
            (delete-page! conn page-uuid))

          ;; Import operations
          :batch-import-edn
          (let [[data op-opts] args
                result (batch-import-edn! conn data (merge opts op-opts))]
            (reset! *result result)
            ;; Track all imported pages
            (let [page-ids (->> (:imported-pages result)
                                (map :db/id)
                                (remove nil?)
                                set)]
              (swap! *affected-pages into page-ids)))

          ;; Raw transaction
          :transact
          (let [[tx-data _tx-meta] args
                ;; For raw transactions, we can't easily determine affected pages
                ;; The caller should track this themselves or use specific ops
                _ (d/transact! conn tx-data)]
            nil)

          ;; Unknown operation - log and skip
          (log/warn "Unknown outliner operation:" op-kw "(original:" op ")"))
        (catch Exception e
          (log/error e "Error in outliner operation:" op-kw)
          (throw e)))))
    {:result @*result
     :affected-pages (vec @*affected-pages)}))
