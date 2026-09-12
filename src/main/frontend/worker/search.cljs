(ns frontend.worker.search
  "Full-text and fuzzy search"
  (:require ["fuse.js" :as Fuse]
            [cljs-bean.core :as bean]
            [clojure.set :as set]
            [clojure.string :as string]
            [datascript.core :as d]
            [frontend.common.search-fuzzy :as fuzzy]
            [frontend.worker.embedding :as embedding]
            [goog.object :as gobj]
            [logseq.common.config :as common-config]
            [logseq.common.util :as common-util]
            [logseq.common.util.namespace :as ns-util]
            [logseq.db :as ldb]
            [logseq.db.frontend.content :as db-content]
            [logseq.db.sqlite.util :as sqlite-util]
            [logseq.graph-parser.text :as text]
            [missionary.core :as m]))

(def fuse (aget Fuse "default"))

;; TODO: use sqlite for fuzzy search
;; maybe https://github.com/nalgeon/sqlean/blob/main/docs/fuzzy.md?
(defonce fuzzy-search-indices (atom {}))

;; Configuration for re-ranking
(def config
  {:keyword-weight 0.9
   :semantic-weight 0.1})

(defn- log-score
  [score]
  (if (> score 2)
    (js/Math.log score)
    score))

;; Normalize scores to [0, 1] range using min-max normalization
(defn normalize-score [score min-score max-score]
  (if (= min-score max-score)
    0.0
    (let [normalized (/ (log-score (- score min-score))
                        (log-score (- max-score min-score)))]
      (max 0.0 (min 1.0 normalized)))))

(defn- add-blocks-fts-triggers!
  "Table bindings of blocks tables and the blocks FTS virtual tables"
  [db]
  (let [triggers [;; delete
                  "CREATE TRIGGER IF NOT EXISTS blocks_ad AFTER DELETE ON blocks
                  BEGIN
                      DELETE from blocks_fts where id = old.id;
                  END;"
                  ;; insert
                  "CREATE TRIGGER IF NOT EXISTS blocks_ai AFTER INSERT ON blocks
                  BEGIN
                      INSERT INTO blocks_fts (id, title, page)
                      VALUES (new.id, new.title, new.page);
                  END;"
                  ;; update
                  "CREATE TRIGGER IF NOT EXISTS blocks_au AFTER UPDATE ON blocks
                  BEGIN
                      DELETE from blocks_fts where id = old.id;
                      INSERT INTO blocks_fts (id, title, page)
                      VALUES (new.id, new.title, new.page);
                  END;"]]
    (doseq [trigger triggers]
      (.exec db trigger))))

(defn- create-blocks-table!
  [db]
  ;; id -> block uuid, page -> page uuid
  (.exec db "CREATE TABLE IF NOT EXISTS blocks (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        page TEXT)"))

(defn- create-blocks-fts-table!
  [db]
  ;; The trigram tokenizer extends FTS5 to support substring matching in general, instead of the usual token matching. When using the trigram tokenizer, a query or phrase token may match any sequence of characters within a row, not just a complete token.
  ;; Check https://www.sqlite.org/fts5.html#the_experimental_trigram_tokenizer.
  (.exec db "CREATE VIRTUAL TABLE IF NOT EXISTS blocks_fts USING fts5(id, title, page, tokenize=\"trigram\")"))

(defn- create-meta-table!
  [db]
  ;; Index state (see get-meta). Not dropped by drop-tables-and-triggers!.
  (.exec db "CREATE TABLE IF NOT EXISTS search_meta (k TEXT PRIMARY KEY, v TEXT)"))

(defn create-tables-and-triggers!
  "Open a SQLite db for search index"
  [db]
  (try
    (create-blocks-table! db)
    (create-blocks-fts-table! db)
    (add-blocks-fts-triggers! db)
    (create-meta-table! db)
    (catch :default e
      (prn "Failed to create tables and triggers")
      (js/console.error e)
      ;; FIXME:
      ;; (try
      ;;   ;; unlink db
      ;;   (catch :default e
      ;;     (js/console.error "cannot unlink search db:" e)))
      )))

(defn drop-tables-and-triggers!
  [db]
  (.exec db "
DROP TABLE IF EXISTS blocks;
DROP TABLE IF EXISTS blocks_fts;
DROP TRIGGER IF EXISTS blocks_ad;
DROP TRIGGER IF EXISTS blocks_ai;
DROP TRIGGER IF EXISTS blocks_au;
"))

(defn- clj-list->sql
  "Turn clojure list into SQL list
   '(1 2 3 4)
   ->
   \"('1','2','3','4')\""
  [ids]
  (str "(" (->> (map (fn [id] (str "'" id "'")) ids)
                (string/join ", ")) ")"))

(defn- upsert-row!
  "Upsert one blocks row. A row with a bad id, page or title is skipped (and
  logged) instead of throwing, so it can't roll back the rest of its batch or
  pin a walk's cursor. Returns true when the row was written."
  [^Object tx id title page]
  (if (and (common-util/uuid-string? id)
           (common-util/uuid-string? page)
           (string? title))
    (do
      (.exec tx #js {:sql "INSERT INTO blocks (id, title, page) VALUES ($id, $title, $page) ON CONFLICT (id) DO UPDATE SET (title, page) = ($title, $page)"
                     :bind #js {:$id id
                                :$title title
                                :$page page}})
      true)
    (do
      (js/console.warn "search: skipped a row with a bad id, page or title" id page)
      false)))

(defn upsert-blocks!
  [^Object db blocks]
  (.transaction db (fn [tx]
                     (doseq [item blocks]
                       (upsert-row! tx (.-id item) (.-title item) (.-page item))))))

(defn delete-blocks!
  [db ids]
  (let [sql (str "DELETE from blocks WHERE id IN " (clj-list->sql ids))]
    (.exec db sql)))

;; Index state, one search_meta row per key. v is a TEXT column, so every value
;; comes back as a string: rows->meta parses the integer keys on read.
(def ^:private meta-keys
  {:state "blocks_state"             ; "complete" | "building"
   :cursor "blocks_cursor"           ; entity id the walk has indexed up to
   :gen "blocks_gen"                 ; bumped by every truncate
   :indexed-tx "blocks_indexed_tx"   ; max-tx of the last tx reflected in the index
   :dirty? "blocks_dirty"})          ; an incremental sync failed: rebuild

(def ^:private int-meta-keys #{:cursor :gen :indexed-tx})

(defn meta->kvs
  "search_meta [k v] rows for the state map m. nil values are skipped."
  [m]
  (keep (fn [[k v]]
          (when-let [col (get meta-keys k)]
            (when (some? v)
              [col (if (boolean? v) (if v "1" "0") (str v))])))
        m))

(defn- parse-int
  [v]
  (let [n (cond (number? v) v
                (string? v) (js/parseInt v 10)
                :else js/NaN)]
    (when (js/isFinite n)
      (js/Math.trunc n))))

(defn rows->meta
  "State map from search_meta [k v] rows; the inverse of meta->kvs."
  [rows]
  (let [col->k (set/map-invert meta-keys)]
    (reduce (fn [m [col v]]
              (if-let [k (get col->k col)]
                (assoc m k (cond
                             (contains? int-meta-keys k) (parse-int v)
                             (= k :dirty?) (contains? #{"1" 1 "true"} v)
                             :else v))
                m))
            {}
            rows)))

(defn set-meta!
  [^Object db m]
  (doseq [[k v] (meta->kvs m)]
    (.exec db #js {:sql "INSERT INTO search_meta (k, v) VALUES ($k, $v) ON CONFLICT (k) DO UPDATE SET v = excluded.v"
                   :bind #js {:$k k :$v v}})))

(defn set-meta-tx!
  "set-meta! in one SQLite transaction: a multi-key state change is atomic and
  costs one commit."
  [^Object db m]
  (.transaction db (fn [tx] (set-meta! tx m))))

(defn get-meta
  "Persisted index state: {:state :cursor :gen :indexed-tx :dirty?}, integers parsed."
  [^Object db]
  (rows->meta (bean/->clj (.exec db #js {:sql "SELECT k, v FROM search_meta"
                                         :rowMode "array"}))))

(defn blocks-empty?
  [^Object db]
  (empty? (bean/->clj (.exec db #js {:sql "SELECT 1 FROM blocks LIMIT 1"
                                     :rowMode "array"}))))

(defn commit-batch!
  "One walk slice: rows ({:id :title :page} maps) and the walk's progress (a
  state map for set-meta!) in ONE SQLite transaction, so a quit resumes from
  the committed cursor."
  [^Object db rows meta]
  (.transaction db (fn [tx]
                     (doseq [{:keys [id title page]} rows]
                       (upsert-row! tx id title page))
                     (set-meta! tx meta))))

(defn sync-rows!
  "Incremental path: delete + upsert of one DataScript tx, plus its watermark,
  in one SQLite transaction."
  [^Object db remove-ids rows meta]
  (.transaction db (fn [tx]
                     (when (seq remove-ids)
                       (delete-blocks! tx remove-ids))
                     (doseq [{:keys [id title page]} rows]
                       (upsert-row! tx id title page))
                     (when meta
                       (set-meta! tx meta)))))

(defonce max-snippet-length 250)

(defn- snippet-by
  [content length]
  (str (subs content 0 length) (when (> (count content) max-snippet-length) "...")))

(defn- get-snippet-result
  [snippet]
  (let [;; Cut snippet to limited size chars for non-matched results
        flag-highlight "$pfts_2lqh>$ "
        snippet (if (string/includes? snippet flag-highlight)
                  snippet
                  (snippet-by snippet max-snippet-length))]
    snippet))

(defn- get-match-input
  [q]
  (let [match-input (-> q
                        (string/replace " and " " AND ")
                        (string/replace " & " " AND ")
                        (string/replace " or " " OR ")
                        (string/replace " | " " OR ")
                        (string/replace " not " " NOT "))]
    (cond
      (and (re-find #"[^\w\s]" q)
           (or (not (some #(string/includes? match-input %) ["AND" "OR" "NOT"]))
               (string/includes? q "/")))            ; punctuations
      (str "\"" match-input "\"*")
      (not= q match-input)
      (string/replace match-input "," "")
      :else
      match-input)))

(defn- search-blocks-aux
  [db sql q input page limit enable-snippet?]
  (try
    (let [namespace? (ns-util/namespace-page? q)
          last-part (when namespace?
                      (some-> (text/get-namespace-last-part q)
                              get-match-input))
          bind (cond
                 (and namespace? page)
                 [page input last-part limit]
                 page
                 [page input limit]
                 namespace?
                 [input last-part limit]
                 :else
                 [input limit])
          result (.exec db (bean/->js
                            {:sql sql
                             :bind bind
                             :rowMode "array"}))
          blocks (bean/->clj result)]
      (keep (fn [block]
              (let [[id page title _rank snippet] (if enable-snippet?
                                                    (update block 4 get-snippet-result)
                                                    block)]
                (when title
                  {:id id
                   :keyword-score (fuzzy/score q title)
                   :page page
                   :title title
                   :snippet snippet}))) blocks))
    (catch :default e
      (prn :debug "Search blocks failed: ")
      (js/console.error e))))

(defn exact-matched?
  "Check if two strings points toward same search result"
  [q match]
  (when (and (string? q) (string? match))
    (boolean
     (reduce
      (fn [coll char']
        (let [coll' (drop-while #(not= char' %) coll)]
          (if (seq coll')
            (rest coll')
            (reduced false))))
      (seq (fuzzy/search-normalize match true))
      (seq (fuzzy/search-normalize q true))))))

(defn- hidden-entity?
  [entity]
  (or (ldb/hidden? entity)
      (let [page (:block/page entity)]
        (and (ldb/hidden? page)
             (not= (:block/title page) common-config/quick-add-page-name)))))

(defn- page-or-object?
  [entity]
  (and (or (ldb/page? entity) (ldb/object? entity))
       (not (hidden-entity? entity))))

(defn get-all-fuzzy-supported-blocks
  "Only pages and objects are supported now."
  [db]
  (let [page-ids (->> (d/datoms db :avet :block/name)
                      (map :e))
        object-ids (when (ldb/db-based-graph? db)
                     (->> (d/datoms db :avet :block/tags)
                          (map :e)))
        blocks (->> (distinct (concat page-ids object-ids))
                    (map #(d/entity db %)))]
    (remove hidden-entity? blocks)))

(defn- sanitize
  [content]
  (some-> content
          (fuzzy/search-normalize true)))

(defn block->index
  "Convert a block to the index for searching"
  [{:block/keys [uuid page title] :as block}]
  (when-not (or
             (ldb/closed-value? block)
             (and (string? title) (> (count title) 10000))
             (string/blank? title))        ; empty page or block
    (try
      (let [title (cond->
                   (-> block
                       (update :block/title ldb/get-title-with-parents)
                       db-content/recur-replace-uuid-in-block-title)
                    (ldb/journal? block)
                    (str " " (:block/journal-day block)))]
        (when uuid
          {:id (str uuid)
           :page (str (or (:block/uuid page) uuid))
           :title (if (page-or-object? block) title (sanitize title))}))
      (catch :default e
        (prn "Error: failed to run block->index on block " (:db/id block))
        (js/console.error e)))))

(defn- fuse-options
  []
  (clj->js {:keys ["title"]
            :shouldSort true
            :tokenize true
            :distance 1024
            :threshold 0.5 ;; search for 50% match from the start
            :minMatchCharLength 1}))

(def fuzzy-page-limit
  "Above this many pages search-blocks never queries Fuse (too slow), so the
  page index is not built either."
  2500)

(defn large-graph?
  "More than fuzzy-page-limit pages. Walks at most fuzzy-page-limit + 1
  :block/name datoms instead of counting all of them."
  [db]
  (> (count (take (inc fuzzy-page-limit) (d/datoms db :avet :block/name)))
     fuzzy-page-limit))

;; repo -> {:indice Fuse :cursor eid :token n} while a sliced page-index build
;; (frontend.worker.search-indexer/ensure-fuse!) is in progress
(defonce fuzzy-builds (atom {}))

(defn new-fuzzy-indice
  []
  (fuse. #js [] (fuse-options)))

(defn add-fuzzy-docs!
  [^js indice docs]
  (doseq [doc docs]
    (.add indice (bean/->js doc))))

(defn forget-fuzzy!
  "Drop the Fuse page index of repo and any build in progress; the next search
  starts a new sliced build."
  [repo]
  (swap! fuzzy-builds dissoc repo)
  (swap! fuzzy-search-indices dissoc repo))

(defn build-fuzzy-search-indice
  "Build a block title indice from scratch, synchronously. Only DB graphs use
  this; file graphs build it in slices (frontend.worker.search-indexer/ensure-fuse!).
   Incremental page title indice is implemented in frontend.search.sync-search-indice!"
  [repo db]
  (let [blocks (->> (get-all-fuzzy-supported-blocks db)
                    (keep block->index)
                    (bean/->js))
        indice (fuse. blocks (fuse-options))]
    (swap! fuzzy-search-indices assoc repo indice)
    indice))

(defn fuzzy-search
  "Return a list of blocks (pages && tagged blocks) that match the query. Takes the following
  options:
   * :limit - Number of result to limit search results. Defaults to 100
  For file graphs this uses only an index that is already built, and returns
  nil otherwise: page titles still match through blocks_fts meanwhile."
  [repo db q {:keys [limit]
              :or {limit 100}}]
  (when repo
    (let [q (fuzzy/search-normalize q true)
          q (fuzzy/clean-str q)
          q (if (= \# (first q)) (subs q 1) q)]
      (when-not (string/blank? q)
        (when-let [indice (or (get @fuzzy-search-indices repo)
                              (when (ldb/db-based-graph? db)
                                (build-fuzzy-search-indice repo db)))]
          (let [result (->> (.search indice q (clj->js {:limit limit}))
                            (bean/->clj))]
            (->> (map :item result)
                 (filter (fn [{:keys [title]}]
                           (exact-matched? q title))))))))))

;; Combine and re-rank results
(defn combine-results
  [db keyword-results semantic-results]
  (let [;; Extract score ranges for normalization
        keyword-scores (map :keyword-score keyword-results)
        k-min (if (seq keyword-scores) (apply min keyword-scores) 0.0)
        k-max (if (seq keyword-scores) (apply max keyword-scores) 1.0)
        all-ids (set/union (set (map :id keyword-results))
                           (set (map :id semantic-results)))
        merged (map (fn [id]
                      (let [block (when id (d/entity db [:block/uuid (uuid id)]))
                            k-result (first (filter #(= (:id %) id) keyword-results))
                            s-result (first (filter #(= (:id %) id) semantic-results))
                            result (merge s-result k-result)
                            page? (ldb/page? block)
                            keyword-score (if page? (+ (:keyword-score k-result) 2) (:keyword-score k-result))
                            k-score (or keyword-score 0.0)
                            s-score (or (:semantic-score s-result) 0.0)
                            norm-k-score (normalize-score k-score k-min k-max)
                            ;; Weighted combination
                            combined-score (+ (* (:keyword-weight config)
                                                 norm-k-score)
                                              (* (:semantic-weight config) s-score)
                                              (cond
                                                (ldb/page? block)
                                                0.02
                                                (:block/tags block)
                                                0.01
                                                :else
                                                0))]
                        (merge result
                               {:combined-score combined-score
                                :keyword-score k-score
                                :semantic-score s-score})))
                    all-ids)
        sorted-result (sort-by :combined-score #(compare %2 %1) merged)]
    sorted-result))

(defn search-blocks
  "Options:
   * :page - the page to specifically search on
   * :limit - Number of result to limit search results. Defaults to 100
   * :dev? - Allow all nodes to be seen for development. Defaults to false
   * :built-in?  - Whether to return public built-in nodes for db graphs. Defaults to false"
  [repo conn search-db q {:keys [limit page enable-snippet? built-in? dev? page-only? library-page-search?]
                          :as option
                          :or {enable-snippet? true}}]
  (m/sp
    (when-not (string/blank? q)
      (let [match-input (get-match-input q)
            large? (large-graph? @conn)
            non-match-input (when (<= (count q) 2)
                              (str "%" (string/replace q #"\s+" "%") "%"))
            limit  (or limit 100)
            ;; https://www.sqlite.org/fts5.html#the_highlight_function
            ;; the 2nd column in blocks_fts (content)
            ;; pfts_2lqh is a key for retrieval
            ;; highlight and snippet only works for some matching with high rank
            snippet-aux "snippet(blocks_fts, 1, '$pfts_2lqh>$', '$<pfts_2lqh$', '...', 256)"
            select (if enable-snippet?
                     (str "select id, page, title, rank, " snippet-aux " from blocks_fts where ")
                     "select id, page, title, rank from blocks_fts where ")
            pg-sql (if page "page = ? and" "")
            match-sql (if (ns-util/namespace-page? q)
                        (str select pg-sql " title match ? or title match ? order by rank limit ?")
                        (str select pg-sql " title match ? order by rank limit ?"))
            non-match-sql (str select pg-sql " title like ? limit ?")
            matched-result (when-not page-only?
                             (search-blocks-aux search-db match-sql q match-input page limit enable-snippet?))
            non-match-result (when (and (not page-only?) non-match-input)
                               (->> (search-blocks-aux search-db non-match-sql q non-match-input page limit enable-snippet?)
                                    (map (fn [result]
                                           (assoc result :keyword-score (fuzzy/score q (:title result)))))))
            ;; fuzzy is too slow for large graphs
            fuzzy-result (when-not (or page large?)
                           (->> (fuzzy-search repo @conn q option)
                                (map (fn [result]
                                       (assoc result :keyword-score (fuzzy/score q (:title result)))))))
            semantic-search-result* (m/? (embedding/task--search repo q 10))
            semantic-search-result (->> semantic-search-result*
                                        (map (fn [{:keys [block distance]}]
                                               (let [page-id (when-let [id (:block/uuid (:block/page block))] (str id))]
                                                 (cond->
                                                  {:id (str (:block/uuid block))
                                                   :title (:block/title block)
                                                   :semantic-score (/ 1.0 (+ 1.0 distance))}
                                                   page-id
                                                   (assoc :page page-id))))))
            ;; _ (doseq [item (concat fuzzy-result matched-result)]
            ;;     (prn :debug :keyword-search-result item))
            ;; _ (doseq [item semantic-search-result]
            ;;     (prn :debug :semantic-search-item item))
            combined-result (combine-results @conn (concat fuzzy-result matched-result non-match-result) semantic-search-result)
            result (->> combined-result
                        (common-util/distinct-by :id)
                        (keep (fn [result]
                                (let [{:keys [id page title snippet]} result
                                      block-id (uuid id)]
                                  (when-let [block (d/entity @conn [:block/uuid block-id])]
                                    (when-not (or
                                               ;; remove pages that already have parents
                                               (and library-page-search?
                                                    (or (ldb/page-in-library? @conn block)
                                                        (not (ldb/internal-page? block))))
                                               ;; remove non-page blocks when asking for pages only
                                               (and page-only? (not (ldb/page? block))))
                                      (when (if dev?
                                              true
                                              (if built-in?
                                                (or (not (ldb/built-in? block))
                                                    (not (ldb/private-built-in-page? block))
                                                    (ldb/class? block))
                                                (or (not (ldb/built-in? block))
                                                    (ldb/class? block))))
                                        {:db/id (:db/id block)
                                         :block/uuid (:block/uuid block)
                                         :block/title (or snippet title)
                                         :block.temp/original-title (:block/title block)
                                         :block/page (or
                                                      (:block/uuid (:block/page block))
                                                      (when page
                                                        (if (common-util/uuid-string? page)
                                                          (uuid page)
                                                          nil)))
                                         :block/parent (:db/id (:block/parent block))
                                         :block/tags (seq (map :db/id (:block/tags block)))
                                         :logseq.property/icon (:logseq.property/icon block)
                                         :page? (ldb/page? block)
                                         :alias (some-> (first (:block/_alias block))
                                                        (select-keys [:block/uuid :block/title]))})))))))]
        (common-util/distinct-by :block/uuid result)))))

(defn truncate-table!
  "Drop and recreate the blocks tables. Records state building / cursor 0 /
  gen + 1 (plus extra-meta) in the same SQLite transaction, so a quit after a
  truncate resumes the walk instead of leaving an empty index that looks
  complete. Returns the new gen."
  ([db] (truncate-table! db nil))
  ([^Object db extra-meta]
   (let [gen (inc (or (:gen (get-meta db)) 0))]
     (.transaction db (fn [tx]
                        (drop-tables-and-triggers! tx)
                        (create-tables-and-triggers! tx)
                        (set-meta! tx (merge extra-meta
                                             {:state "building" :cursor 0 :gen gen :dirty? false}))))
     gen)))

(defn index-batch
  "One slice of the full walk: AEVT :block/uuid from entity id (inc after-e),
  in entity-id order, on the db value given. Stops at max-items entities,
  max-chars of title, or the deadline (performance.now ms), but always
  consumes at least one datom. Returns {:rows [{:id :title :page}] :last-e
  :n (entities visited) :done?}."
  [db after-e {:keys [max-items max-chars deadline]}]
  (loop [ds (d/seek-datoms db :aevt :block/uuid (inc after-e))
         rows (transient [])
         n 0
         chars 0
         last-e after-e]
    (let [dt (first ds)]
      (cond
        (or (nil? dt) (not= :block/uuid (:a dt)))
        {:rows (persistent! rows) :last-e last-e :n n :done? true}

        (and (pos? n)
             (or (>= n max-items)
                 (>= chars max-chars)
                 (>= (js/performance.now) deadline)))
        {:rows (persistent! rows) :last-e last-e :n n :done? false}

        :else
        (let [e (:e dt)
              ent (d/entity db e)
              row (when-not (hidden-entity? ent) (block->index ent))]
          (recur (rest ds)
                 (cond-> rows row (conj! row))
                 (inc n)
                 (+ chars (count (:title row)))
                 e))))))

(defn fuzzy-page-batch
  "One slice of the Fuse page-index build: AEVT :block/name from entity id
  (inc after-e). Same contract as index-batch; returns {:docs :last-e :n :done?}.
  Takes every non-hidden :block/name entity, like get-all-fuzzy-supported-blocks
  (a file-graph page without :block/type included)."
  [db after-e {:keys [max-items deadline]}]
  (loop [ds (d/seek-datoms db :aevt :block/name (inc after-e))
         docs (transient [])
         n 0
         last-e after-e]
    (let [dt (first ds)]
      (cond
        (or (nil? dt) (not= :block/name (:a dt)))
        {:docs (persistent! docs) :last-e last-e :n n :done? true}

        (and (pos? n)
             (or (>= n max-items)
                 (>= (js/performance.now) deadline)))
        {:docs (persistent! docs) :last-e last-e :n n :done? false}

        :else
        (let [e (:e dt)
              ent (d/entity db e)
              doc (when-not (hidden-entity? ent) (block->index ent))]
          (recur (rest ds) (cond-> docs doc (conj! doc)) (inc n) e))))))

(defn- get-blocks-from-datoms-impl
  [repo {:keys [db-after db-before]} datoms]
  (when (seq datoms)
    (let [blocks-to-add-set (->> (filter :added datoms)
                                 (map :e)
                                 (set))
          blocks-to-remove-set (->> (remove :added datoms)
                                    (filter #(= :block/uuid (:a %)))
                                    (map :e)
                                    (set))
          blocks-to-add-set' (if (and (sqlite-util/db-based-graph? repo) (seq blocks-to-add-set))
                               (->> blocks-to-add-set
                                    (mapcat (fn [id] (map :db/id (:block/_refs (d/entity db-after id)))))
                                    (concat blocks-to-add-set)
                                    set)
                               blocks-to-add-set)]
      {:blocks-to-remove     (->>
                              (keep #(d/entity db-before %) blocks-to-remove-set))
       :blocks-to-add        (->>
                              (keep #(d/entity db-after %) blocks-to-add-set')
                              (remove hidden-entity?))})))

(defn- get-affected-blocks
  [repo tx-report]
  (let [data (:tx-data tx-report)
        datoms (filter
                (fn [datom]
                  ;; Capture any direct change on page display title, page ref or block content
                  (contains? #{:block/uuid :block/name :block/title :block/properties} (:a datom)))
                data)]
    (when (seq datoms)
      (get-blocks-from-datoms-impl repo tx-report datoms))))

(defn sync-search-indice
  [repo tx-report]
  (let [{:keys [blocks-to-add blocks-to-remove]} (get-affected-blocks repo tx-report)]
    ;; update page title indice
    (let [fuzzy-blocks-to-add (filter page-or-object? blocks-to-add)
          fuzzy-blocks-to-remove (filter page-or-object? blocks-to-remove)
          apply! (fn [^js indice to-remove to-add]
                   (doseq [page-entity to-remove]
                     (.remove indice (fn [page] (= (str (:block/uuid page-entity)) (gobj/get page "id")))))
                   (doseq [page to-add]
                     (.remove indice (fn [p] (= (str (:block/uuid page)) (gobj/get p "id"))))
                     (when-let [doc (block->index page)]
                       (.add indice (bean/->js doc)))))]
      (when (or (seq fuzzy-blocks-to-add) (seq fuzzy-blocks-to-remove))
        (swap! fuzzy-search-indices update repo
               (fn [indice]
                 (when indice
                   (apply! indice fuzzy-blocks-to-remove fuzzy-blocks-to-add)
                   indice)))
        ;; A sliced build in progress reads pages above its cursor later, from
        ;; the then-current db; changes at or below the cursor are applied here.
        (when-let [{:keys [indice cursor]} (get @fuzzy-builds repo)]
          (let [walked? #(<= (:db/id %) cursor)]
            (apply! indice
                    (filter walked? fuzzy-blocks-to-remove)
                    (filter walked? fuzzy-blocks-to-add))))))

    ;; update block indice
    (when (or (seq blocks-to-add) (seq blocks-to-remove))
      (let [blocks-to-add' (keep block->index blocks-to-add)
            blocks-to-remove (set (concat (map (comp str :block/uuid) blocks-to-remove)
                                          (->>
                                           (set/difference
                                            (set (map :block/uuid blocks-to-add))
                                            (set (map :block/uuid blocks-to-add')))
                                           (map str))))]
        {:blocks-to-remove-set blocks-to-remove
         :blocks-to-add        blocks-to-add'}))))
