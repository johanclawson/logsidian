(ns frontend.worker.search-schema-test
  "Search db schema version 2: a blocks_fts row has the rowid of its blocks
  row, so the triggers delete by rowid; an unchanged upsert writes nothing;
  an index at another version is truncated and walked again. Pure parts and
  a fake db only (no sqlite under node)."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as string]
            [frontend.worker.search :as search]
            [frontend.worker.search-indexer :as search-indexer]
            [goog.object :as gobj]))

(def ^:private v1-triggers
  "The version 1 triggers as sqlite_master holds them (IF NOT EXISTS dropped)."
  [["blocks_ad" "CREATE TRIGGER blocks_ad AFTER DELETE ON blocks
                  BEGIN
                      DELETE from blocks_fts where id = old.id;
                  END"]
   ["blocks_ai" "CREATE TRIGGER blocks_ai AFTER INSERT ON blocks
                  BEGIN
                      INSERT INTO blocks_fts (id, title, page)
                      VALUES (new.id, new.title, new.page);
                  END"]
   ["blocks_au" "CREATE TRIGGER blocks_au AFTER UPDATE ON blocks
                  BEGIN
                      DELETE from blocks_fts where id = old.id;
                      INSERT INTO blocks_fts (id, title, page)
                      VALUES (new.id, new.title, new.page);
                  END"]])

(defn- squash
  "Lower case, runs of whitespace as one space."
  [s]
  (-> s string/lower-case (string/replace #"\s+" " ")))

(defn- fake-db
  "Stands in for a sqlite-wasm oo1 DB and keeps what the schema code reads
  back: search_meta (values as TEXT), which of blocks/blocks_fts exist, the
  trigger SQL (as sqlite_master stores it: IF NOT EXISTS dropped; an
  existing trigger is kept) and blocks_fts_config. Dropping blocks drops its
  triggers, as in SQLite, and dropping blocks_fts its config. Every
  statement is logged."
  [{:keys [tables triggers meta fts-config]}]
  (let [store (atom {:tables (set tables)
                     :triggers (into {} triggers)
                     :meta (or meta {})
                     :fts-config (or fts-config {})
                     :log []
                     :transactions 0})
        db #js {}]
    (set! (.-exec db)
          (fn [arg]
            (let [sql (if (string? arg) arg (gobj/get arg "sql"))
                  bind (when-not (string? arg) (js->clj (gobj/get arg "bind")))]
              (swap! store update :log conj {:sql sql :bind bind})
              (cond
                (string/includes? sql "type = 'table'")
                (clj->js (mapv vector (sort (:tables @store))))

                (string/includes? sql "type = 'trigger'")
                (clj->js (mapv (fn [[k v]] [k v]) (:triggers @store)))

                (string/starts-with? sql "SELECT k, v FROM search_meta")
                (clj->js (mapv (fn [[k v]] [k v]) (:meta @store)))

                (string/starts-with? sql "INSERT INTO search_meta")
                (do (swap! store assoc-in [:meta (get bind "$k")] (str (get bind "$v"))) db)

                (string/starts-with? sql "SELECT k, v FROM blocks_fts_config")
                (clj->js (mapv (fn [[k v]] [k v]) (:fts-config @store)))

                (string/starts-with? sql "INSERT INTO blocks_fts(blocks_fts, rank)")
                (do (swap! store assoc-in [:fts-config (get bind "$k")] (get bind "$v")) db)

                (string/starts-with? sql "CREATE TABLE IF NOT EXISTS blocks")
                (do (swap! store update :tables conj "blocks") db)

                (string/starts-with? sql "CREATE VIRTUAL TABLE IF NOT EXISTS blocks_fts")
                (do (swap! store update :tables conj "blocks_fts") db)

                (string/starts-with? sql "CREATE TRIGGER IF NOT EXISTS")
                (let [trigger-name (second (re-find #"^CREATE TRIGGER IF NOT EXISTS (\w+)" sql))]
                  (swap! store update :triggers
                         (fn [m] (if (contains? m trigger-name)
                                   m
                                   (assoc m trigger-name (string/replace sql "IF NOT EXISTS " "")))))
                  db)

                (string/includes? sql "DROP TABLE IF EXISTS blocks;")
                (do (swap! store assoc :tables #{} :triggers {} :fts-config {}) db)

                :else db))))
    (set! (.-transaction db) (fn [f]
                               (swap! store update :transactions inc)
                               (f db)))
    {:db db :store store}))

(defn- meta-writes
  [log]
  (->> log
       (filter #(string/starts-with? (:sql %) "INSERT INTO search_meta"))
       (mapv (fn [{:keys [bind]}] [(get bind "$k") (get bind "$v")]))))

(defn- facts
  [db stored-max-tx]
  {:stored-max-tx stored-max-tx
   :triggers-current? (search/triggers-current? db)})

(def ^:private schema-walk {:action :walk :truncate? true :cursor 0 :reason "schema"})

;; ---------------------------------------------------------------------------
;; SQL

(deftest trigger-sql-test
  (let [sqls (into {} search/fts-triggers)]
    (is (= #{"blocks_ad" "blocks_ai" "blocks_au"} (set (keys sqls))))
    (testing "no trigger looks a blocks_fts row up by its id column (a full FTS5 scan)"
      (doseq [[trigger-name sql] sqls]
        (is (not (re-find #"where id\s*=" (squash sql))) trigger-name)))
    (testing "each trigger is on blocks, for its event"
      (is (string/includes? (squash (sqls "blocks_ad")) "after delete on blocks"))
      (is (string/includes? (squash (sqls "blocks_ai")) "after insert on blocks"))
      (is (string/includes? (squash (sqls "blocks_au")) "after update on blocks")))
    (testing "delete by the rowid of the blocks row"
      (doseq [trigger-name ["blocks_ad" "blocks_au"]]
        (is (string/includes? (squash (sqls trigger-name))
                              "delete from blocks_fts where rowid = old.rowid;")
            trigger-name)))
    (testing "insert with the rowid of the blocks row"
      (doseq [trigger-name ["blocks_ai" "blocks_au"]]
        (is (string/includes? (squash (sqls trigger-name))
                              (str "insert into blocks_fts (rowid, id, title, page)"
                                   " values (new.rowid, new.id, new.title, new.page);"))
            trigger-name)))
    (testing "the update trigger deletes the old row before it inserts the new one"
      (let [s (squash (sqls "blocks_au"))]
        (is (< (string/index-of s "delete from blocks_fts")
               (string/index-of s "insert into blocks_fts")))))
    (testing "the ad trigger only deletes"
      (is (not (string/includes? (squash (sqls "blocks_ad")) "insert"))))))

(deftest current-triggers-test
  (let [as-stored (fn [rows] (mapv (fn [[n s]] [n (string/replace s "IF NOT EXISTS " "")]) rows))]
    (is (true? (search/current-triggers? (as-stored search/fts-triggers)))
        "as sqlite_master stores them")
    (is (true? (search/current-triggers? search/fts-triggers)))
    (is (false? (search/current-triggers? v1-triggers)) "version 1: by id")
    (is (false? (search/current-triggers? [])) "no trigger")
    (is (false? (search/current-triggers? (butlast search/fts-triggers))) "one missing")
    (is (false? (search/current-triggers? (conj (vec (rest search/fts-triggers)) (first v1-triggers))))
        "one old")
    (is (false? (search/current-triggers? [["blocks_ad" nil] ["blocks_ai" nil] ["blocks_au" nil]])))))

(deftest upsert-sql-test
  (let [s (squash search/upsert-sql)
        at #(string/index-of s %)]
    (is (string/starts-with? s (str "insert into blocks (id, title, page)"
                                    " select j.value ->> 0, j.value ->> 1, j.value ->> 2"
                                    " from json_each($rows) as j"))
        "every row of the $rows JSON in one statement")
    (testing "rows are written in target rowid order (FTS5 flushes a segment when a rowid goes back)"
      (is (string/includes? s "left join blocks as b on b.id = j.value ->> 0")
          "an existing row's rowid")
      (is (string/includes? s "order by b.rowid is null, b.rowid, j.key")
          "existing rows by rowid first, then new rows in input order"))
    (testing "where true, then order by, then on conflict (the INSERT ... SELECT upsert parse rule)"
      (is (< (at " where true ") (at " order by ") (at " on conflict (id) "))))
    (is (string/includes? s (str "on conflict (id) do update set title = excluded.title, page = excluded.page"
                                 " where blocks.title is not excluded.title"
                                 " or blocks.page is not excluded.page"))
        "an unchanged row is not updated, so no trigger fires")
    (is (not (string/includes? s "or replace")) "no delete + reinsert")))

(deftest delete-sql-test
  (is (= "delete from blocks where id in (select value from json_each($ids))"
         (squash search/delete-sql))))

(defn- json-bind
  [entry k]
  (js->clj (js/JSON.parse (get-in entry [:bind k]))))

(deftest upsert-statement-test
  (let [{:keys [db store]} (fake-db {})
        id (str (random-uuid))
        id2 (str (random-uuid))]
    (search/commit-batch! db [{:id id :title "t" :page id}
                              {:id "bad" :title "x" :page id}
                              {:id id2 :title "u" :page id}]
                          {:cursor 1})
    (let [upserts (filterv #(string/starts-with? (:sql %) "INSERT INTO blocks ") (:log @store))]
      (is (= [search/upsert-sql] (mapv :sql upserts)) "one statement per commit")
      (is (= [[id "t" id] [id2 "u" id]] (json-bind (first upserts) "$rows"))
          "the bad row is skipped; the good ones keep their order"))
    (testing "no valid row: no upsert statement, the meta still commits"
      (let [n (count (:log @store))]
        (search/commit-batch! db [{:id "bad" :title "x" :page id}] {:cursor 2})
        (is (not-any? #(string/starts-with? (:sql %) "INSERT INTO blocks ") (drop n (:log @store))))
        (is (= 2 (:cursor (search/get-meta db))))))))

(deftest sync-statements-test
  (let [{:keys [db store]} (fake-db {})
        [a b c] (repeatedly 3 #(str (random-uuid)))]
    (search/sync-rows! db #{a b} [{:id c :title "first" :page c}
                                  {:id c :title "last" :page c}]
                       {:indexed-tx 7})
    (let [log (:log @store)
          kinds (keep (fn [{:keys [sql]}]
                        (cond (string/starts-with? sql "DELETE FROM blocks") :delete
                              (string/starts-with? sql "INSERT INTO blocks ") :upsert
                              (string/starts-with? sql "INSERT INTO search_meta") :meta))
                      log)]
      (is (= [:delete :upsert :meta] kinds) "one delete, one upsert, then the watermark")
      (is (= 1 (:transactions @store)) "in one transaction")
      (is (= #{a b} (set (json-bind (first (filter #(= search/delete-sql (:sql %)) log)) "$ids")))
          "every id in one $ids parameter")
      (is (= [[c "last" c]] (json-bind (first (filter #(= search/upsert-sql (:sql %)) log)) "$rows"))
          "one row per id, the last one winning"))
    (testing "nothing removed or added: only the watermark"
      (let [n (count (:log @store))]
        (search/sync-rows! db #{} [] {:indexed-tx 8})
        (is (every? #(string/starts-with? (:sql %) "INSERT INTO search_meta") (drop n (:log @store))))))))

(deftest fts-config-test
  (is (= {"automerge" 0 "crisismerge" 16 "usermerge" 4} search/fts-config))
  (let [{:keys [db store]} (fake-db {})
        fts-writes (fn [log] (filterv #(string/starts-with? (:sql %) "INSERT INTO blocks_fts(blocks_fts, rank)") log))]
    (search/create-tables-and-triggers! db)
    (testing "a new db gets the FTS5 merge settings"
      (is (= search/fts-config (:fts-config @store)))
      (is (= 3 (count (fts-writes (:log @store))))))
    (testing "an open that finds them writes nothing"
      (let [n (count (:log @store))]
        (search/create-tables-and-triggers! db)
        (is (empty? (fts-writes (drop n (:log @store)))))))
    (testing "only a differing key is written"
      (swap! store assoc-in [:fts-config "automerge"] 4)
      (let [n (count (:log @store))]
        (search/create-tables-and-triggers! db)
        (is (= [{"$k" "automerge" "$v" 0}] (mapv :bind (fts-writes (drop n (:log @store))))))))
    (testing "a truncate recreates blocks_fts and sets them inside its transaction"
      (let [n (:transactions @store)]
        (search/truncate-table! db)
        (is (= (inc n) (:transactions @store)))
        (is (= search/fts-config (:fts-config @store)))))))

;; ---------------------------------------------------------------------------
;; Version decision

(deftest open-action-schema-test
  (let [v search/schema-version
        current {:stored-max-tx 10 :triggers-current? true}]
    (is (= 2 v))
    (testing "an index without a schema row is migrated"
      (is (= schema-walk (search-indexer/open-action {:state "complete" :indexed-tx 10} current)))
      (is (= schema-walk (search-indexer/open-action {} {:blocks-empty? false :has-files? true}))
          "a legacy index from before search_meta, with rows")
      (is (= schema-walk (search-indexer/open-action {} {:blocks-empty? true :has-files? false}))
          "an empty one too: its truncate is cheap and records the version"))
    (testing "older and newer versions are migrated"
      (is (= schema-walk (search-indexer/open-action {:state "complete" :indexed-tx 10 :schema 1} current)))
      (is (= schema-walk (search-indexer/open-action {:state "complete" :indexed-tx 10 :schema (inc v)}
                                                     current))))
    (testing "the current version with current triggers goes on to the other rules"
      (is (= {:action :trust}
             (search-indexer/open-action {:state "complete" :indexed-tx 10 :schema v} current)))
      (is (= {:action :trust}
             (search-indexer/open-action {:state "complete" :indexed-tx 10 :schema v} {:stored-max-tx 10}))
          "without a trigger fact the version decides"))
    (testing "a version row over old triggers is migrated"
      (is (= schema-walk
             (search-indexer/open-action {:state "complete" :indexed-tx 10 :schema v}
                                         (assoc current :triggers-current? false)))))
    (testing "an old index is never resumed or trusted: the schema rule comes first"
      (is (= schema-walk
             (search-indexer/open-action {:state "building" :cursor 77 :indexed-tx 10 :schema 1} current))
          "a walk interrupted under the old triggers")
      (is (= schema-walk
             (search-indexer/open-action {:state "complete" :dirty? true :indexed-tx 10} current)))
      (is (= schema-walk
             (search-indexer/open-action {:state "complete" :indexed-tx 9} current))))
    (testing "at the current version a quit mid-walk resumes from the cursor"
      (is (= {:action :walk :truncate? false :cursor 77 :reason "resume"}
             (search-indexer/open-action {:state "building" :cursor 77 :indexed-tx 10 :schema v} current))))
    (testing "the version as read back from search_meta (TEXT)"
      (is (= {:action :trust}
             (search-indexer/open-action (search/rows->meta [["blocks_state" "complete"]
                                                             ["blocks_indexed_tx" "10"]
                                                             ["schema" (str v)]])
                                         current)))
      (is (= schema-walk
             (search-indexer/open-action (search/rows->meta [["blocks_state" "complete"]
                                                             ["blocks_indexed_tx" "10"]
                                                             ["schema" "1"]])
                                         current))))))

;; ---------------------------------------------------------------------------
;; Create, open and migrate, on the fake db

(deftest new-db-schema-test
  (let [{:keys [db store]} (fake-db {})]
    (search/create-tables-and-triggers! db)
    (testing "a brand-new db is created at the current version"
      (is (= [["schema" (str search/schema-version)]] (meta-writes (:log @store))))
      (is (= search/schema-version (:schema (search/get-meta db))))
      (is (true? (search/triggers-current? db)))
      (is (= {:action :trust}
             (search-indexer/open-action (search/get-meta db)
                                         (assoc (facts db nil) :blocks-empty? true :has-files? false)))
          "a new graph is not walked")
      (is (= {:action :walk :truncate? false :cursor 0 :reason "empty"}
             (search-indexer/open-action (search/get-meta db)
                                         (assoc (facts db nil) :blocks-empty? true :has-files? true)))
          "a new search db for a parsed graph is walked, without a truncate"))
    (testing "a reopen writes no version again"
      (let [n (count (:log @store))]
        (search/create-tables-and-triggers! db)
        (is (empty? (meta-writes (drop n (:log @store)))))))))

(deftest old-db-migration-test
  (let [{:keys [db store]} (fake-db {:tables ["blocks" "blocks_fts"]
                                     :triggers v1-triggers
                                     :meta {"blocks_state" "complete"
                                            "blocks_cursor" "500"
                                            "blocks_gen" "3"
                                            "blocks_indexed_tx" "10"}})]
    (search/create-tables-and-triggers! db)
    (testing "opening keeps the old triggers and records no version"
      (is (= (into {} v1-triggers) (:triggers @store)) "CREATE TRIGGER IF NOT EXISTS")
      (is (empty? (meta-writes (:log @store))))
      (is (nil? (:schema (search/get-meta db))))
      (is (false? (search/triggers-current? db))))
    (testing "the open decision is a truncating walk"
      (is (= schema-walk (search-indexer/open-action (search/get-meta db) (facts db 10)))))
    (testing "a quit after on-open! persisted its decision, before the truncate: migrated again"
      ;; what on-open! writes for a truncating walk
      (search/set-meta-tx! db {:state "building" :dirty? true :cursor 0})
      (is (= schema-walk (search-indexer/open-action (search/get-meta db) (facts db 10)))))
    (testing "the truncate recreates the triggers and records the version in one transaction"
      (let [n (:transactions @store)
            gen (search/truncate-table! db {:indexed-tx 10})]
        (is (= (inc n) (:transactions @store)))
        (is (= 4 gen))
        (is (true? (search/triggers-current? db)))
        (is (= (into {} (map (fn [[k s]] [k (string/replace s "IF NOT EXISTS " "")])) search/fts-triggers)
               (:triggers @store)))
        (is (= {:state "building" :cursor 0 :gen 4 :indexed-tx 10 :dirty? false
                :schema search/schema-version}
               (search/get-meta db)))))
    (testing "a quit mid-walk resumes: the index is building at the new version"
      (search/set-meta-tx! db {:cursor 250})
      (is (= {:action :walk :truncate? false :cursor 250 :reason "resume"}
             (search-indexer/open-action (search/get-meta db) (facts db 10)))))
    (testing "a finished walk is trusted"
      (search/set-meta-tx! db {:state "complete"})
      (is (= {:action :trust} (search-indexer/open-action (search/get-meta db) (facts db 10)))))))

(deftest version-row-over-old-triggers-test
  ;; A build without versions truncated the index: its own (version 1)
  ;; triggers, but search_meta, which a truncate keeps, still says 2.
  (let [{:keys [db]} (fake-db {:tables ["blocks" "blocks_fts"]
                               :triggers v1-triggers
                               :meta {"schema" (str search/schema-version)
                                      "blocks_state" "complete"
                                      "blocks_indexed_tx" "10"}})]
    (search/create-tables-and-triggers! db)
    (is (= schema-walk (search-indexer/open-action (search/get-meta db) (facts db 10))))))
