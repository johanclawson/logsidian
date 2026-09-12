(ns frontend.worker.file
  "Save pages to files for file-based graphs"
  (:require [cljs-time.coerce :as tc]
            [cljs-time.core :as t]
            [clojure.core.async :as async]
            [clojure.string :as string]
            [datascript.core :as d]
            [frontend.common.async-util :as async-util]
            [logseq.cli.common.file :as common-file]
            [frontend.common.file.util :as wfu]
            [frontend.worker-common.util :as worker-util]
            [frontend.worker.state :as worker-state]
            [goog.object :as gobj]
            [lambdaisland.glogi :as log]
            [logseq.common.date :as common-date]
            [logseq.common.path :as path]
            [logseq.db :as ldb]
            [logseq.db.file-based.entity-util :as file-entity-util]
            [logseq.outliner.tree :as otree]
            [malli.core :as m]))

(defonce *writes (atom {}))
(defonce *request-id (atom 0))

(defn- conj-page-write!
  [page-id]
  (let [request-id (swap! *request-id inc)]
    (swap! *writes assoc request-id page-id)
    request-id))

(defn dissoc-request!
  [request-id]
  (when-let [page-id (get @*writes request-id)]
    (let [old-page-request-ids (keep (fn [[r p]]
                                       (when (and (= p page-id) (<= r request-id))
                                         r)) @*writes)]
      (when (seq old-page-request-ids)
        (swap! *writes (fn [x] (apply dissoc x old-page-request-ids)))))))

(defn remove-writes-of-deleted-pages
  "writes (request id -> page db id) without the requests whose page is no
   longer in db. A deleted page is never saved, so nothing would acknowledge
   its requests. Requests of pages that exist stay: their writes are pending."
  [writes db]
  (into {} (filter (fn [[_ page-id]] (d/entity db page-id))) writes))

(defonce file-writes-chan
  (let [coercer (m/coercer [:catn
                            [:repo :string]
                            [:page-id :any]
                            [:outliner-op :any]
                            [:epoch :int]
                            [:request-id :int]])]
    (async/chan 10000 (map coercer))))

(def batch-write-interval 1000)

(def whiteboard-blocks-pull-keys-with-persisted-ids
  '[:block/properties
    :block/uuid
    :block/order
    :block/title
    :block/format
    :block/created-at
    :block/updated-at
    :block/collapsed?
    {:block/page      [:block/uuid]}
    {:block/parent    [:block/uuid]}])

(defn- cleanup-whiteboard-block
  [block]
  (if (get-in block [:block/properties :ls-type] false)
    (dissoc block
            :db/id
            :block/uuid ;; shape block uuid is read from properties
            :block/collapsed?
            :block/title
            :block/format
            :block/order
            :block/page
            :block/parent) ;; these are auto-generated for whiteboard shapes
    (dissoc block :db/id :block/page)))

(defn- transact-file-tx-if-not-exists!
  [conn page-block ok-handler context]
  (when (:block/name page-block)
    (let [format (name (get page-block :block/format (:preferred-format context)))
          date-formatter (:date-formatter context)
          title (string/capitalize (:block/name page-block))
          whiteboard-page? (file-entity-util/whiteboard? page-block)
          format (if whiteboard-page? "edn" format)
          journal-page? (common-date/valid-journal-title? title date-formatter)
          journal-title (common-date/normalize-journal-title title date-formatter)
          journal-page? (and journal-page? (not (string/blank? journal-title)))
          filename (if journal-page?
                     (common-date/date->file-name journal-title (:journal-file-name-format context))
                     (-> (or (:block/title page-block) (:block/name page-block))
                         wfu/file-name-sanity))
          sub-dir (cond
                    journal-page?    (:journals-directory context)
                    whiteboard-page? (:whiteboards-directory context)
                    :else            (:pages-directory context))
          ext (if (= format "markdown") "md" format)
          file-rpath (path/path-join sub-dir (str filename "." ext))
          file {:file/path file-rpath}
          tx [{:file/path file-rpath}
              {:block/name (:block/name page-block)
               :block/file file}]]
      (ldb/transact! conn tx)
      (when ok-handler (ok-handler)))))

(defn- remove-transit-ids [block] (dissoc block :db/id :block/file))

(defn send-proposal!
  "Posts a page's new file content to the renderer (:write-files), which writes
   it through the guarded writeFile against :base, and stamps that base.
   The base is the file's :file/content in the worker's db at serialization:
   the content the page's previous proposal left there (below), or the disk
   content a load or a reset from disk put there. Then :file/content becomes
   this proposal, so the next serialization of the page (in the same flush, a
   later one, or after a stall) takes it as its base. A reset from disk
   overwrites :file/content with the disk content: a proposal serialized
   before the reset carries a pre-reset base and is refused, one serialized
   after it carries the disk content and is written.
   The transact touches only the file entity: no page is updated, so no page
   save follows (pipeline/invoke-hooks-default), and it is the tx the
   renderer's alter-files used to send through db/set-file-content!."
  [repo conn request-id page-id file-path content]
  (let [base (:file/content (d/entity @conn [:file/path file-path]))]
    (wfu/post-message :write-files {:request-id request-id
                                    :page-id page-id
                                    :repo repo
                                    :files [[file-path content]]
                                    :base base})
    (ldb/transact! conn [{:file/path file-path :file/content content}] {:skip-refresh? true})))

(defn- save-tree-aux!
  [repo conn page-block tree blocks-just-deleted? context request-id]
  (let [db @conn
        page-block (d/pull db '[*] (:db/id page-block))
        init-level 1
        file-db-id (-> page-block :block/file :db/id)
        file-path (-> (d/entity db file-db-id) :file/path)
        result (if (and (string? file-path) (not-empty file-path))
                 (let [new-content (if (file-entity-util/whiteboard? page-block)
                                     (->
                                      (wfu/ugly-pr-str {:blocks tree
                                                        :pages (list (remove-transit-ids page-block))})
                                      (string/triml))
                                     (common-file/tree->file-content repo db tree {:init-level init-level} context))]
                   (when-not (and (string/blank? new-content) (not blocks-just-deleted?))
                     (send-proposal! repo conn request-id (:db/id page-block) file-path new-content)
                     :sent))
                 ;; In e2e tests, "card" page in db has no :file/path
                 (js/console.error "File path from page-block is not valid" page-block tree))]
    (when-not (= :sent result)          ; page may not exists now
      (dissoc-request! request-id))))

(defn save-tree!
  [repo conn page-block tree blocks-just-deleted? context request-id]
  {:pre [(map? page-block)]}
  (when repo
    (let [ok-handler #(save-tree-aux! repo conn page-block tree blocks-just-deleted? context request-id)
          file (or (:block/file page-block)
                   (when-let [page-id (:db/id (:block/page page-block))]
                     (:block/file (d/entity @conn page-id))))]
      (if file
        (ok-handler)
        (transact-file-tx-if-not-exists! conn page-block ok-handler context)))))

(defn do-write-file!
  [repo conn page-db-id outliner-op context request-id]
  (let [page-block (d/entity @conn page-db-id)
        page-db-id (:db/id page-block)
        whiteboard? (file-entity-util/whiteboard? page-block)
        blocks-count (ldb/get-page-blocks-count @conn page-db-id)
        blocks-just-deleted? (and (zero? blocks-count)
                                  (contains? #{:delete-blocks :move-blocks} outliner-op))]
    (if (or (>= blocks-count 1) blocks-just-deleted?)
      (if (and (or (> blocks-count 500) whiteboard?)
               (not (worker-state/tx-idle? repo {:diff 3000})))
        (async/put! file-writes-chan [repo page-db-id outliner-op (tc/to-long (t/now)) request-id])
        (let [blocks (if whiteboard?
                       (ldb/get-page-blocks @conn (:db/id page-block)
                                            {:pull-keys whiteboard-blocks-pull-keys-with-persisted-ids})
                       (:block/_page page-block))
              blocks (if whiteboard? (map cleanup-whiteboard-block blocks) blocks)]
          (if (and (= 1 (count blocks))
                   (string/blank? (:block/title (first blocks)))
                   (nil? (:block/file page-block))
                   (not whiteboard?))
            (dissoc-request! request-id)
            (let [tree-or-blocks (if whiteboard? blocks
                                     (otree/blocks->vec-tree repo @conn blocks (:db/id page-block)))]
              (if page-block
                (save-tree! repo conn page-block tree-or-blocks blocks-just-deleted? context request-id)
                (do
                  (js/console.error (str "can't find page id: " page-db-id))
                  (dissoc-request! request-id)))))))
      (dissoc-request! request-id))))

(defn coalesce-page-writes
  "The page writes of one flush, [repo page-id outliner-op epoch request-id]
   tuples in arrival order, as {:keep tuples :drop-ids request-ids}: one
   tuple per [repo page-id], the last one (its op decides
   blocks-just-deleted?, right for any op sequence), carrying the highest
   request id of its page so that acknowledging it clears the page's older
   requests (dissoc-request!), and the other request ids to drop.
   One serialization per page and flush means one proposal per page, so a
   write cannot be refused against the page's own first write. Deduping by
   [repo page-id outliner-op] let type-then-Enter (:save-block then
   :insert-blocks) through as two proposals with the same base."
  [pages]
  (let [pages (vec pages)
        page-key (fn [[repo page-id]] [repo page-id])
        groups (group-by page-key pages)
        ;; index of each page's last tuple, to keep arrival order
        last-index (reduce-kv (fn [m i tuple] (assoc m (page-key tuple) i)) {} pages)
        keep (->> last-index
                  (sort-by val)
                  (mapv (fn [[k i]]
                          (assoc (nth pages i) 4 (apply max (map last (get groups k)))))))
        keep-ids (set (map last keep))]
    {:keep keep
     :drop-ids (into #{} (comp (map last) (remove keep-ids)) pages)}))

(defn write-files!
  [conn pages context]
  (when (seq pages)
    (let [{:keys [keep drop-ids]} (coalesce-page-writes pages)]
      (doseq [id drop-ids]
        (dissoc-request! id))

      (doseq [[repo page-id outliner-op _time request-id] keep]
        (try (do-write-file! repo conn page-id outliner-op context request-id)
             (catch :default e
               (worker-util/post-message :notification
                                         [[:div
                                           [:p "Write file failed, please copy the changes to other editors in case of losing data."]
                                           "Error: " (str (gobj/get e "stack"))]
                                          :error])
               (log/error :file/write-file-error {:error e})
               (dissoc-request! request-id)))))))

(defn sync-to-file
  [repo page-id tx-meta]
  (when (and page-id
             (not (:created-from-journal-template? tx-meta))
             (not (:delete-files? tx-meta)))
    (let [request-id (conj-page-write! page-id)]
      (async/put! file-writes-chan [repo page-id (:outliner-op tx-meta) (tc/to-long (t/now)) request-id]))))

(defn <ratelimit-file-writes!
  [flush-fn]
  (async-util/<ratelimit file-writes-chan batch-write-interval
                         :filter-fn (fn [_] true)
                         :flush-fn flush-fn))
