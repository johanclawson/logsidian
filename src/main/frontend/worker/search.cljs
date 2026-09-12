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

(def schema-version
  "Version of the search db's tables and triggers, kept in search_meta under
  \"schema\". 1 (or no row): the triggers find a blocks_fts row by its id
  column, which FTS5 can only do with a full scan (69 ms per row at 10k).
  2: every blocks_fts row has the rowid of its blocks row and the triggers
  delete by rowid (an FTS5 rowid lookup). An index at another version is
  truncated and walked again (search-indexer/open-action, reason \"schema\").

  The FTS5 merge settings (fts-config) are not part of the version. FTS5
  persists them in blocks_fts_config and ensure-fts-config! writes any that
  differ on every open, so an existing version 2 index takes them without a
  walk: one an earlier step-4 build set to automerge=0 gets automerge 4 back
  on its next open (one config row). fts-config is FTS5's defaults, so a
  build without ensure-fts-config! that opens an index this build configured
  merges as stock FTS5 does. A step-4 build (automerge=0, only ever run in
  bench profiles) that opens it writes automerge=0 back, and this build
  restores it on the next open. Accepted instead of a schema bump, which
  would walk every index again."
  2)

(def fts-triggers
  "[name sql] of the triggers that keep blocks_fts in step with blocks. A
  blocks_fts row has the rowid of its blocks row, so delete and update find it
  by rowid. Only these triggers write blocks_fts, which keeps the two rowids
  equal. blocks has an implicit rowid (a TEXT primary key, not WITHOUT ROWID);
  SQLite may renumber such rowids on VACUUM, so the search db must never be
  VACUUMed (a truncate is its compaction). Should the rowids ever diverge,
  deletes and updates would silently hit the wrong blocks_fts rows; only a
  rowid collision makes the insert trigger fail (FTS5 rowids are unique), and
  that failed incremental sync marks the index dirty, which truncates and walks
  it."
  [["blocks_ad"
    "CREATE TRIGGER IF NOT EXISTS blocks_ad AFTER DELETE ON blocks
BEGIN
    DELETE FROM blocks_fts WHERE rowid = old.rowid;
END;"]
   ["blocks_ai"
    "CREATE TRIGGER IF NOT EXISTS blocks_ai AFTER INSERT ON blocks
BEGIN
    INSERT INTO blocks_fts (rowid, id, title, page)
    VALUES (new.rowid, new.id, new.title, new.page);
END;"]
   ["blocks_au"
    "CREATE TRIGGER IF NOT EXISTS blocks_au AFTER UPDATE ON blocks
BEGIN
    DELETE FROM blocks_fts WHERE rowid = old.rowid;
    INSERT INTO blocks_fts (rowid, id, title, page)
    VALUES (new.rowid, new.id, new.title, new.page);
END;"]])

(def ^:private trigger-markers
  "What each current trigger's SQL contains (lower case). The version 1
  triggers contain none of it."
  {"blocks_ad" ["old.rowid"]
   "blocks_ai" ["new.rowid"]
   "blocks_au" ["old.rowid" "new.rowid"]})

(defn current-triggers?
  "Whether rows, [name sql] of the triggers on blocks as sqlite_master holds
  them, are this version's fts-triggers (all present, all by rowid)."
  [rows]
  (let [by-name (into {} (keep (fn [[n s]] (when (string? s) [n (string/lower-case s)]))) rows)]
    (every? (fn [[trigger-name markers]]
              (when-let [s (get by-name trigger-name)]
                (every? #(string/includes? s %) markers)))
            trigger-markers)))

(defn- query-rows
  "Result rows of sql as vectors ([] when exec returns no row array)."
  [^Object db sql]
  (let [r (.exec db #js {:sql sql :rowMode "array"})]
    (if (array? r) (js->clj r) [])))

(defn triggers-current?
  "Whether db's blocks triggers are this version's (see current-triggers?).
  The schema row alone is not enough: a build without them truncating the
  index recreates its own triggers but leaves search_meta as it is."
  [^Object db]
  (current-triggers?
   (query-rows db "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'blocks'")))

(defn- search-tables-exist?
  "Whether blocks or blocks_fts exists (a db from an earlier open)."
  [^Object db]
  (seq (query-rows db "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('blocks', 'blocks_fts')")))

(defn- add-blocks-fts-triggers!
  "Table bindings of blocks tables and the blocks FTS virtual tables. IF NOT
  EXISTS: an existing db keeps the triggers it has until a truncate drops
  them (search-indexer/on-open! decides when)."
  [db]
  (doseq [[_ trigger] fts-triggers]
    (.exec db trigger)))

(defn- create-blocks-table!
  [db]
  ;; id -> block uuid, page -> page uuid. A rowid table (not WITHOUT ROWID):
  ;; its implicit rowid is the blocks_fts rowid (see fts-triggers).
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

(def fts-config
  "FTS5 merge settings of blocks_fts (ADR-003 step 4). These are FTS5's
  defaults, written out so they are explicit and so an index an earlier
  step-4 build set to automerge 0 gets them back (ensure-fts-config!).
  - automerge 4: incremental merging inside the committing statement. Its
    work is proportional to the writes and done in the writer's
    transaction: each time a commit takes the leaf write counter past a
    multiple of 64, FTS5 merges up to 64 x levels leaf pages, from the level
    with the most segments once it has 4. That keeps every level small, so
    crisismerge never triggers. Most small saves cross no 64-leaf boundary
    and merge nothing. Step 4 first ran automerge 0 and merged only from the
    maintenance tick; during a walk the tick fell behind (merge N stuck at
    1, 7% of the walk's WAL frames) and crisismerge ran inside commits
    instead, twice as a level-2 merge of the whole index (3.3 s and 5.4 s
    commits).
  - usermerge 4: the idle drain's merge steps (merge-step!) fold a level
    once it has 4 segments, the same fan-out.
  - crisismerge 16: a level that reaches 16 segments is merged whole inside
    the commit, and that cascades upward. The backstop, not the mechanism.
  The maintenance tick (search-indexer) keeps the checkpoints and the idle
  drain."
  {"automerge" 4 "crisismerge" 16 "usermerge" 4})

(defn- ensure-fts-config!
  "Write each fts-config key blocks_fts_config does not already hold, so a
  normal open writes nothing. FTS5 persists the values with the index; a new
  blocks_fts (truncate-table!) gets them inside the truncate's transaction."
  [^Object db]
  (let [have (into {} (query-rows db "SELECT k, v FROM blocks_fts_config WHERE k IN ('automerge', 'crisismerge', 'usermerge')"))]
    (doseq [[k v] fts-config]
      (when-not (= v (get have k))
        (.exec db #js {:sql "INSERT INTO blocks_fts(blocks_fts, rank) VALUES ($k, $v)"
                       :bind #js {:$k k :$v v}})))))

(declare set-meta!)

(defn create-tables-and-triggers!*
  "Create the search tables and triggers; throws on failure. A new db (neither
  blocks nor blocks_fts there yet, which includes the moment inside
  truncate-table! after its drop) is created at schema-version. An existing db
  keeps its tables, triggers and recorded version: search-indexer/on-open!
  migrates an old one. truncate-table! uses this directly, so a failed CREATE
  rolls its whole transaction back instead of recording a version over
  missing tables. Every call also applies fts-config (idempotent)."
  [db]
  (let [new-db? (not (search-tables-exist? db))]
    (create-blocks-table! db)
    (create-blocks-fts-table! db)
    (ensure-fts-config! db)
    (add-blocks-fts-triggers! db)
    (create-meta-table! db)
    (when new-db?
      (set-meta! db {:schema schema-version}))))

(defn create-tables-and-triggers!
  "Open a SQLite db for search index (create-tables-and-triggers!*, logging
  instead of throwing, so a broken search db doesn't stop the graph opening)."
  [db]
  (try
    (create-tables-and-triggers!* db)
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

(def upsert-sql
  "Insert or update every row of $rows (rows->json) in ONE statement.
  - One statement per commit keeps FTS5 at one new level-0 segment per
    commit. FTS5 flushes its pending terms into a new segment at every
    statement start (fts5SavepointMethod), so the per-row statements this
    replaces wrote one segment per row, and merging them cost the commit
    spikes.
  - FTS5 also flushes whenever a written rowid is not above the one before
    (sqlite3Fts5IndexBeginWrite), hence the ORDER BY: existing rows in blocks
    rowid order, then new rows in input order. New rows get rowids above
    every existing one. The update trigger deletes and reinserts a blocks_fts
    row at the same rowid, which FTS5 allows without a flush.
  - bench/sqlprobe-step4.py: 1 segment per mixed commit, 19-22 without the
    ORDER BY, 50 for 50 per-row statements.
  - WHERE true: SQLite's documented fix for the parse ambiguity of an
    INSERT ... SELECT with ON CONFLICT.
  - The DO UPDATE only runs when title or page differ (IS NOT: NULL-safe),
    so re-syncing an unchanged block writes nothing and fires no trigger.
  - Never INSERT OR REPLACE: it deletes and reinserts, firing the delete
    trigger and renumbering rowids."
  (str "INSERT INTO blocks (id, title, page)"
       " SELECT j.value ->> 0, j.value ->> 1, j.value ->> 2"
       " FROM json_each($rows) AS j LEFT JOIN blocks AS b ON b.id = j.value ->> 0"
       " WHERE true"
       " ORDER BY b.rowid IS NULL, b.rowid, j.key"
       " ON CONFLICT (id) DO UPDATE SET title = excluded.title, page = excluded.page"
       " WHERE blocks.title IS NOT excluded.title OR blocks.page IS NOT excluded.page"))

(def delete-sql
  "Delete the blocks rows of $ids (a JSON array of block uuid strings) in one
  statement; the delete trigger drops their blocks_fts rows. A DELETE with
  triggers visits its rowids in order, so this adds one FTS5 segment
  (bench/sqlprobe-step4.py), as the string-built IN list it replaces did. A
  sync-rows! commit with deletes and rows is two statements, so it adds two
  (bench/sqlwasm-probe.mjs)."
  "DELETE FROM blocks WHERE id IN (SELECT value FROM json_each($ids))")

(defn- valid-row?
  [id title page]
  (and (common-util/uuid-string? id)
       (common-util/uuid-string? page)
       (string? title)))

(def ^:private lone-surrogate
  "A UTF-16 surrogate without its pair: a high one not followed by a low one,
  or a low one not preceded by a high one."
  (js/RegExp. "[\\uD800-\\uDBFF](?![\\uDC00-\\uDFFF])|(?<![\\uD800-\\uDBFF])[\\uDC00-\\uDFFF]" "g"))

(defn replace-lone-surrogates
  "s with every lone surrogate replaced by U+FFFD, as
  String.prototype.toWellFormed does: well-formed's fallback for runtimes
  without it."
  [^js s]
  (.replace s lone-surrogate "\uFFFD"))

(defn- well-formed
  "s with lone surrogates replaced by U+FFFD: the bytes TextEncoder wrote when
  titles were bound one by one. JSON.stringify escapes a lone surrogate
  instead, and SQLite's JSON decoder would store it as invalid UTF-8 (\"a\\uD800b\"
  as 61 ED A0 80 62 instead of 61 EF BF BD 62)."
  [^js s]
  (if (.-toWellFormed s) (.toWellFormed s) (replace-lone-surrogates s)))

(defn rows->json
  "The $rows parameter of upsert-sql: {:json \"[[id title page] ...]\" :n rows},
  or nil when no row is left. rows are {:id :title :page} maps.
  Each row is validated first, then the rows are deduplicated by id, the
  last valid row of an id winning, at the position of its first. Validating
  after deduplicating would let a later bad row of an id replace a good one
  and then be skipped. A bad row (id or page not a uuid string, title not a
  string) is skipped with one warning per batch, never thrown, so it can't
  roll back its batch or pin a walk's cursor."
  [rows]
  (let [by-id (js/Map.)
        *bad (volatile! nil)]
    (doseq [{:keys [id title page]} rows]
      (if (valid-row? id title page)
        (.set by-id id #js [id (well-formed title) page])
        (vswap! *bad (fn [b] (if b (update b :n inc) {:n 1 :id id :page page})))))
    (when-let [{:keys [n id page]} @*bad]
      (js/console.warn "search: skipped" n "row(s) with a bad id, page or title; the first:" id page))
    (when (pos? (.-size by-id))
      {:json (js/JSON.stringify (js/Array.from (.values by-id)))
       :n (.-size by-id)})))

(defn- upsert-rows!
  "upsert-sql for rows ({:id :title :page} maps, see rows->json) on tx. No
  statement when no row is valid."
  [^Object tx rows]
  (when-let [{:keys [json]} (rows->json rows)]
    (.exec tx #js {:sql upsert-sql :bind #js {:$rows json}})))

(def ^:private upsert-chunk-rows
  "Rows per statement in upsert-blocks!, whose caller can send a whole graph."
  512)

(defn upsert-blocks!
  "thread-api/search-upsert-blocks (the UI-driven path): blocks is a JS array
  of {id title page} objects. upsert-sql per chunk of upsert-chunk-rows, all
  in one transaction, so no one JSON parameter holds a whole graph."
  [^Object db blocks]
  (.transaction db (fn [tx]
                     (doseq [chunk (partition-all upsert-chunk-rows blocks)]
                       (upsert-rows! tx (map (fn [^js item]
                                               {:id (.-id item) :title (.-title item) :page (.-page item)})
                                             chunk))))))

(defn delete-blocks!
  "Delete the blocks (and blocks_fts) rows of ids, block uuids or their
  strings, in one statement (delete-sql). No statement for no ids."
  [^Object db ids]
  (when (seq ids)
    (.exec db #js {:sql delete-sql
                   :bind #js {:$ids (js/JSON.stringify (into-array (map str ids)))}})))

;; Index state, one search_meta row per key. v is a TEXT column, so every value
;; comes back as a string: rows->meta parses the integer keys on read.
(def ^:private meta-keys
  {:state "blocks_state"             ; "complete" | "building"
   :cursor "blocks_cursor"           ; entity id the walk has indexed up to
   :gen "blocks_gen"                 ; bumped by every truncate
   :indexed-tx "blocks_indexed_tx"   ; max-tx of the last tx reflected in the index
   :dirty? "blocks_dirty"            ; an incremental sync failed: rebuild
   :schema "schema"})                ; schema-version of the tables and triggers

(def ^:private int-meta-keys #{:cursor :gen :indexed-tx :schema})

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
  "Persisted index state: {:state :cursor :gen :indexed-tx :dirty? :schema},
  integers parsed."
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
  the committed cursor. The rows go in one statement (upsert-sql)."
  [^Object db rows meta]
  (.transaction db (fn [tx]
                     (upsert-rows! tx rows)
                     (set-meta! tx meta))))

(defn sync-rows!
  "Incremental path: delete + upsert of one DataScript tx, plus its watermark,
  in one SQLite transaction: one delete statement, one upsert statement, the
  meta rows. Each is its own exec, because oo1 binds only the first statement
  of an exec that has parameters."
  [^Object db remove-ids rows meta]
  (.transaction db (fn [tx]
                     (delete-blocks! tx remove-ids)
                     (upsert-rows! tx rows)
                     (when meta
                       (set-meta! tx meta)))))

;; ---------------------------------------------------------------------------
;; Maintenance statements, run by search-indexer's maintenance tick between
;; tasks, never inside a sync or walk commit.

(defn total-changes
  "sqlite3_total_changes of db: rows written since it opened, FTS5 shadow
  tables included. The tick reads it to see that writes happened, and how
  much a merge step did."
  [^js db]
  (.changes db true))

(defn merge-step!
  "One bounded FTS5 merge step: up to n pages of output, into a level that has
  at least usermerge segments (fts-config). Returns the total_changes delta:
  below 2 means nothing was left to merge (FTS5's 'merge' contract; an idle
  step changes 1 row). n must be positive: a negative n merges the whole index
  down to one segment."
  [^js db n]
  (let [c0 (total-changes db)]
    (.exec db #js {:sql "INSERT INTO blocks_fts(blocks_fts, rank) VALUES ('merge', $n)"
                   :bind #js {:$n n}})
    (- (total-changes db) c0)))

(defn checkpoint!
  "PRAGMA wal_checkpoint(PASSIVE): [busy log checkpointed], log being the
  frames in the WAL and checkpointed how many of them are in the db file now
  (earlier backfill included). Complete when busy is 0 and log equals
  checkpointed; a partial result is not an error. It copies every frame not
  in the db file yet: SQLite 3.50.3 has no way to bound a checkpoint's work.
  Inside a transaction on db it fails with SQLITE_LOCKED."
  [^js db]
  (first (query-rows db "PRAGMA wal_checkpoint(PASSIVE)")))

(defn restart-wal!
  "One small write right after a complete checkpoint. The first write after
  one restarts the WAL: it syncs the new WAL header (an OPFS flush, even under
  synchronous=NORMAL) and, as the first commit of the new WAL, applies
  journal_size_limit (a truncate). This way the tick pays for both, not the
  next save or walk slice. The row is not index state: rows->meta ignores
  its key."
  [^js db]
  (.exec db #js {:sql "INSERT INTO search_meta (k, v) VALUES ('wal_restart', $v) ON CONFLICT (k) DO UPDATE SET v = excluded.v"
                 :bind #js {:$v (str (js/Date.now))}}))

(defn cache-writes
  "SQLITE_DBSTATUS_CACHE_WRITE of db (sqlite3_db_status, never reset): pages
  its pager has written since it opened. In WAL mode each is a WAL frame,
  whoever wrote it: syncs, walks, FTS5 merges, orphan deletes, meta and
  restart rows. Checkpoint copies are not counted. A bench probe (3.46,
  same pager path) saw the delta equal the frames appended, and 0 for a
  checkpoint or for a merge step with nothing to merge. It counts writes, not
  the WAL's size: a page written twice in one WAL counts twice. sqlite3: the
  sqlite-wasm module (worker-state/*sqlite). nil when it or the call is not
  available."
  [^js sqlite3 ^js db]
  (when sqlite3
    (try
      (let [^js capi (.-capi sqlite3)
            ^js wasm (.-wasm sqlite3)
            ^js pstack (.-pstack wasm)
            op (.-SQLITE_DBSTATUS_CACHE_WRITE capi)
            ptr (.-pointer db)]
        (when (and (number? op) ptr)
          (let [pos (.-pointer pstack)]
            (try
              ;; two ints out: the current value, and a highwater mark this
              ;; op leaves 0
              (let [out (.alloc pstack 8)
                    rc (.sqlite3_db_status capi ptr op out (+ out 4) 0)]
                (when (zero? rc) (.peek32 wasm out)))
              (finally (.restore pstack pos))))))
      (catch :default _e nil))))

(defn txn-open?
  "Whether db's connection has a transaction open on main: sqlite3_txn_state
  is not SQLITE_TXN_NONE. That is a BEGIN nobody ended, or a statement
  stepped and not reset, which holds a read (autocommit alone does not rule
  that out). A checkpoint then fails with SQLITE_LOCKED, and a write joins
  that transaction. sqlite3: the sqlite-wasm module. nil when it or the call
  is not available."
  [^js sqlite3 ^js db]
  (when sqlite3
    (try
      (let [^js capi (.-capi sqlite3)
            none (.-SQLITE_TXN_NONE capi)
            ptr (.-pointer db)
            s (when (and (number? none) ptr) (.sqlite3_txn_state capi ptr "main"))]
        ;; -1: no such schema, which main always is
        (when (and (number? s) (>= s 0)) (not= s none)))
      (catch :default _e nil))))

(defn- varint
  "SQLite varint at i of bytes (7 bits a byte, big-endian, the 9th byte all
  8): [value next-i]. Multiplies instead of shifting, so values above 2^31
  stay right."
  [^js bytes i]
  (loop [n 0 v 0]
    (let [b (aget bytes (+ i n))]
      (cond
        (= n 8) [(+ (* v 256) b) (+ i 9)]
        (< b 128) [(+ (* v 128) b) (+ i n 1)]
        :else (recur (inc n) (+ (* v 128) (bit-and b 127)))))))

(defn structure-level0-segments
  "Segments on level 0 of an FTS5 structure record (fts5StructureDecode, as
  bench/sqlprobe-step4.py decodes it): a 4-byte cookie, an optional 4-byte V2
  marker, varints nLevel, nSegment and nWriteCounter, then per level nMerge
  and its segment count. 0 for a record without levels."
  [^js bytes]
  (let [v2? (and (>= (.-length bytes) 8)
                 (= 0xff (aget bytes 4)) (= 0 (aget bytes 5))
                 (= 0 (aget bytes 6)) (= 1 (aget bytes 7)))
        [n-level i] (varint bytes (if v2? 8 4))
        [_n-segment i] (varint bytes i)
        [_write-counter i] (varint bytes i)]
    (if (pos? n-level)
      (let [[_n-merge i] (varint bytes i)]
        (first (varint bytes i)))
      0)))

(defn level0-segments
  "Level-0 segments of blocks_fts now, or nil when the structure record can't
  be read. Commits add them (one per commit); only merges remove them: the
  tick's merge steps, automerge inside a commit and crisismerge. So a
  level-0 count that went down between two ticks without a merge step in
  between is an automerge fold (expected, roughly one per 64 leaf pages
  written), a crisismerge, or a truncate: the maint line's l0-drops counts
  them all, and an l0-max near crisismerge (16) tells a crisis apart. Never
  throws: it only feeds the maint line."
  [^js db]
  (try
    (let [b (ffirst (query-rows db "SELECT block FROM blocks_fts_data WHERE id = 10"))]
      (if (some? b) (structure-level0-segments b) 0))
    (catch :default _e nil)))

;; repo -> #{block uuid string}: search db rows whose block DataScript does not
;; have (search-blocks found them). The maintenance tick deletes them.
(defonce ^:private *orphan-ids (atom {}))
;; Queue cap per repo, so a repo without a tick (tests, a failed tick) can't
;; grow it without bound. One search queues at most its SQL limit.
(def ^:private max-orphan-ids 1000)

(defn queue-orphans!
  "Queue ids (block uuid strings) for deletion from repo's search db. Rows
  without an entity appear when the search db keeps commits the main db lost
  (power loss with both on synchronous=NORMAL). A re-parse does not replace
  them, since a file-graph block without id:: gets a new uuid. They are hidden
  at query time, but only after the SQL limit, so they take result slots
  until deleted."
  [repo ids]
  (when (seq ids)
    (swap! *orphan-ids update repo
           (fn [s]
             (let [s (or s #{})]
               (into s (take (max 0 (- max-orphan-ids (count s)))) ids))))))

(defn take-orphans!
  "Remove and return repo's queued orphan ids."
  [repo]
  (let [[old _] (swap-vals! *orphan-ids dissoc repo)]
    (get old repo)))

(defn still-orphans
  "The ids (block uuid strings) db has no entity for. The tick checks again
  before deleting: a tx since the search may have recreated the block (undo),
  and its sync wrote the row back."
  [db ids]
  (remove (fn [id] (d/entity db [:block/uuid (uuid id)])) ids))

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
            ;; SQL rows whose block DataScript does not have are dropped below
            ;; (no entity), but only after the SQL limit, so they take result
            ;; slots: queue them for the maintenance tick to delete
            _ (queue-orphans! repo (still-orphans @conn (->> (concat matched-result non-match-result)
                                                             (keep :id)
                                                             (filter string?))))
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
  "Drop and recreate the blocks tables and their triggers, at schema-version.
  Records state building / cursor 0 / gen + 1 / schema (plus extra-meta) in
  the same SQLite transaction, so a quit after a truncate resumes the walk
  instead of leaving an empty index that looks complete, and the recorded
  version always describes the triggers that exist. This is also the schema
  migration: rows from an older version are dropped, never re-used."
  ([db] (truncate-table! db nil))
  ([^Object db extra-meta]
   (let [gen (inc (or (:gen (get-meta db)) 0))]
     (.transaction db (fn [tx]
                        (drop-tables-and-triggers! tx)
                        ;; throws on failure: the transaction rolls back and no
                        ;; version is recorded over missing tables
                        (create-tables-and-triggers!* tx)
                        (set-meta! tx (merge extra-meta
                                             {:state "building" :cursor 0 :gen gen :dirty? false
                                              :schema schema-version}))))
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
