(ns frontend.worker.search-indexer-test
  (:require [cljs.test :refer [async deftest is testing]]
            [clojure.string :as string]
            [datascript.core :as d]
            [frontend.worker.db-listener :as db-listener]
            [frontend.worker.search :as search]
            [frontend.worker.search-indexer :as search-indexer]
            [frontend.worker.state :as worker-state]
            [goog.object :as gobj]
            [logseq.db.file-based.schema :as file-schema]
            [promesa.core :as p]))

(def ^:private repo "search-indexer-test-repo")

(defn- fake-sdb
  "Stands in for a sqlite-wasm oo1 DB, for the statements the index uses.
  search_meta values are kept as strings, the way its TEXT column hands them
  back."
  []
  (let [store (atom {:meta {} :blocks {} :transactions 0})
        db #js {}]
    (set! (.-exec db)
          (fn [arg]
            (let [sql (if (string? arg) arg (gobj/get arg "sql"))
                  bind (when-not (string? arg) (gobj/get arg "bind"))
                  b #(gobj/get bind %)]
              (cond
                (string/starts-with? sql "INSERT INTO search_meta")
                (do (swap! store assoc-in [:meta (b "$k")] (str (b "$v"))) db)

                (string/starts-with? sql "SELECT k, v FROM search_meta")
                (clj->js (mapv (fn [[k v]] [k v]) (:meta @store)))

                (string/starts-with? sql "INSERT INTO blocks")
                (do (swap! store assoc-in [:blocks (b "$id")] {:title (b "$title") :page (b "$page")}) db)

                (string/starts-with? sql "SELECT 1 FROM blocks")
                (clj->js (if (seq (:blocks @store)) [[1]] []))

                (string/starts-with? sql "DELETE from blocks WHERE id IN")
                (do (swap! store update :blocks #(apply dissoc % (map second (re-seq #"'([^']+)'" sql)))) db)

                (string/includes? sql "DROP TABLE IF EXISTS blocks;")
                (do (swap! store assoc :blocks {}) db)

                :else db))))
    (set! (.-transaction db) (fn [f]
                               (swap! store update :transactions inc)
                               (f db)))
    {:db db :store store}))

(def ^:private hidden-uuid (random-uuid))
(def ^:private blank-uuid (random-uuid))

(defn- new-conn
  "4 pages with 3 blocks each, a hidden page and a block with a blank title."
  []
  (let [conn (d/create-conn file-schema/schema)
        pages (mapv (fn [i] {:block/uuid (random-uuid)
                             :block/name (str "page " i)
                             :block/title (str "Page " i)})
                    (range 4))]
    (d/transact! conn (conj pages {:block/uuid hidden-uuid
                                   :block/name "hidden"
                                   :block/title "Hidden"
                                   :logseq.property/hide? true}))
    (d/transact! conn (conj (vec (for [p pages
                                       j (range 3)]
                                   {:block/uuid (random-uuid)
                                    :block/title (str "block " j " of " (:block/title p))
                                    :block/page [:block/uuid (:block/uuid p)]
                                    :block/parent [:block/uuid (:block/uuid p)]}))
                            {:block/uuid blank-uuid
                             :block/title ""
                             :block/page [:block/uuid (:block/uuid (first pages))]
                             :block/parent [:block/uuid (:block/uuid (first pages))]}))
    conn))

(defn- expected-rows
  "id -> row of a full index, computed without the walk."
  [db]
  (->> (d/datoms db :aevt :block/uuid)
       (map #(d/entity db (:e %)))
       (remove :logseq.property/hide?)
       (keep search/block->index)
       (map (juxt :id identity))
       (into {})))

(defn- walk-all
  [db opts]
  (loop [cursor 0
         batches []]
    (let [batch (search/index-batch db cursor opts)
          batches (conj batches batch)]
      (if (:done? batch)
        batches
        (recur (:last-e batch) batches)))))

(deftest index-batch-test
  (let [db @(new-conn)
        expected (expected-rows db)
        uuid-count (count (d/datoms db :aevt :block/uuid))]
    (is (seq expected))
    (testing "batches of at most 3 cover every entity exactly once"
      (let [batches (walk-all db {:max-items 3 :max-chars 1e9 :deadline js/Infinity})
            rows (mapcat :rows batches)
            visited (filter #(pos? (:n %)) batches)]
        (is (every? #(<= (:n %) 3) batches))
        (is (= uuid-count (reduce + (map :n batches))) "each :block/uuid datom visited once")
        (is (= (count rows) (count (set (map :id rows)))) "no row twice")
        (is (= expected (into {} (map (juxt :id identity)) rows)))
        (is (apply < (map :last-e visited)) "the cursor strictly increases")
        (is (not (contains? (set (map :id rows)) (str hidden-uuid))) "hidden page skipped")
        (is (not (contains? (set (map :id rows)) (str blank-uuid))) "blank block skipped")))
    (testing "a deadline already passed still consumes one datom per call"
      (let [batches (walk-all db {:max-items 1000 :max-chars 1e9 :deadline 0})]
        (is (every? #(= 1 (:n %)) (butlast batches)))
        (is (= uuid-count (reduce + (map :n batches))))
        (is (= expected (into {} (map (juxt :id identity)) (mapcat :rows batches))))))
    (testing "the char cap ends a batch"
      (is (= 1 (:n (search/index-batch db 0 {:max-items 1000 :max-chars 1 :deadline js/Infinity})))))))

(deftest meta-round-trip-test
  (let [m {:state "building" :cursor 123 :gen 2 :indexed-tx 536871000 :dirty? true}]
    (is (every? string? (map second (search/meta->kvs m))) "values go to a TEXT column")
    (is (= m (search/rows->meta (search/meta->kvs m))) "integers come back as integers")
    (is (= {:dirty? false} (search/rows->meta [["blocks_dirty" "0"]])))
    (is (= {:cursor nil :gen nil} (search/rows->meta [["blocks_cursor" "x"] ["blocks_gen" nil]])))
    (is (= {} (search/rows->meta [["unknown" "1"]])))
    (is (empty? (search/meta->kvs {:cursor nil :unknown 1})))))

(deftest resume-from-persisted-cursor-test
  (let [db @(new-conn)
        {a :db a-store :store} (fake-sdb)
        {b :db b-store :store} (fake-sdb)
        opts {:max-items 3 :max-chars 1e9 :deadline js/Infinity}
        slice! (fn [sdb]
                 ;; like the indexer: the cursor comes back from search_meta
                 (let [cursor (or (:cursor (search/get-meta sdb)) 0)
                       {:keys [rows last-e done?]} (search/index-batch db cursor opts)]
                   (search/commit-batch! sdb rows {:cursor last-e
                                                   :state (if done? "complete" "building")})
                   done?))]
    (testing "interrupted after two slices"
      (slice! a)
      (slice! a)
      (let [m (search/get-meta a)]
        (is (string? (get-in @a-store [:meta "blocks_cursor"])) "stored as TEXT")
        (is (= "building" (:state m)))
        (is (integer? (:cursor m)) "parsed on read")
        (is (pos? (:cursor m)))))
    (testing "resuming from the persisted cursor gives the same index as one run"
      (loop [] (when-not (slice! a) (recur)))
      (loop [] (when-not (slice! b) (recur)))
      (is (= "complete" (:state (search/get-meta a))))
      (is (= (:blocks @b-store) (:blocks @a-store)))
      (is (= (set (keys (expected-rows db))) (set (keys (:blocks @a-store))))))))

(deftest commit-batch-skips-bad-rows-test
  (let [{:keys [db store]} (fake-sdb)
        id (str (random-uuid))
        page (str (random-uuid))]
    (search/commit-batch! db [{:id id :title "ok" :page page}
                              {:id (str (random-uuid)) :title "bad page" :page "not-a-uuid"}
                              {:id "not-a-uuid" :title "bad id" :page page}]
                          {:cursor 5 :state "building"})
    (is (= {id {:title "ok" :page page}} (:blocks @store)))
    (is (= 5 (:cursor (search/get-meta db))) "progress still committed")
    (is (= 1 (:transactions @store)) "rows and progress in one transaction")
    (testing "sync-rows! deletes, upserts and writes the watermark together"
      (let [id2 (str (random-uuid))]
        (search/sync-rows! db #{id} [{:id id2 :title "new" :page page}
                                     {:id id2 :title "x" :page "bad"}]
                           {:indexed-tx 9})
        (is (= {id2 {:title "new" :page page}} (:blocks @store)))
        (is (= 9 (:indexed-tx (search/get-meta db))))
        (is (= 2 (:transactions @store)))))))

(deftest truncate-table-records-state-test
  (let [{:keys [db store]} (fake-sdb)
        id (str (random-uuid))]
    (search/commit-batch! db [{:id id :title "a" :page id}] {:state "complete" :cursor 42 :gen 3})
    (is (false? (search/blocks-empty? db)))
    (let [n (:transactions @store)
          gen (search/truncate-table! db {:indexed-tx 7})]
      (is (= 4 gen) "generation bumped")
      (is (= (inc n) (:transactions @store)) "drop, create and state in one transaction")
      (is (search/blocks-empty? db))
      (is (= {:state "building" :cursor 0 :gen 4 :indexed-tx 7 :dirty? false}
             (search/get-meta db))))
    (testing "set-meta-tx! writes a multi-key state change in one transaction"
      (let [n (:transactions @store)]
        (search/set-meta-tx! db {:state "building" :dirty? true :cursor 0})
        (is (= (inc n) (:transactions @store)))
        (is (= {:state "building" :cursor 0 :gen 4 :indexed-tx 7 :dirty? true}
               (search/get-meta db)))))))

(deftest slice-action-test
  (let [conn (d/create-conn {})
        job {:token 3 :gen 2 :conn conn}
        current {:token 3 :gen 2 :dirty? false :conn conn :sdb #js {}}]
    (is (= :continue (search-indexer/slice-action job current)))
    (is (= :stop (search-indexer/slice-action job (assoc current :token 4))) "cancelled or superseded")
    (is (= :stop (search-indexer/slice-action job (assoc current :conn nil))) "graph closed")
    (is (= :stop (search-indexer/slice-action job (assoc current :sdb nil))) "search db closed")
    (is (= :stop (search-indexer/slice-action job (assoc current :conn (d/create-conn {}))))
        "graph reopened: a new conn")
    (is (= :truncate-restart (search-indexer/slice-action job (assoc current :dirty? true)))
        "an incremental sync failed mid-walk")
    (is (= :restart (search-indexer/slice-action job (assoc current :gen 3)))
        "truncated under the walk: new generation")
    (is (= :stop (search-indexer/slice-action job (assoc current :token 4 :gen 3 :dirty? true)))
        "cancel wins")))

(deftest open-action-test
  (testing "no state row"
    (is (= {:action :trust}
           (search-indexer/open-action {} {:blocks-empty? true :has-files? false}))
        "a new graph: every tx is indexed as it happens")
    (is (= {:action :walk :truncate? false :cursor 0 :reason "empty"}
           (search-indexer/open-action {} {:blocks-empty? true :has-files? true}))
        "an empty index on a parsed graph is healed")
    (is (= {:action :trust}
           (search-indexer/open-action {} {:blocks-empty? false :has-files? true}))
        "a legacy index with rows is not walked"))
  (testing "dirty"
    (is (= {:action :walk :truncate? true :cursor 0 :reason "dirty"}
           (search-indexer/open-action {:state "complete" :dirty? true :indexed-tx 10}
                                       {:stored-max-tx 10}))))
  (testing "watermark"
    (is (= {:action :trust}
           (search-indexer/open-action {:state "complete" :indexed-tx 10} {:stored-max-tx 10})))
    (is (= {:action :trust}
           (search-indexer/open-action {:state "complete" :indexed-tx 12} {:stored-max-tx 10})))
    (is (= {:action :walk :truncate? true :cursor 0 :reason "gap"}
           (search-indexer/open-action {:state "complete" :indexed-tx 9} {:stored-max-tx 10}))
        "a stored tx the index never saw")
    (is (= "gap" (:reason (search-indexer/open-action {:state "complete"} {:stored-max-tx 10})))))
  (testing "building"
    (is (= {:action :walk :truncate? false :cursor 77 :reason "resume"}
           (search-indexer/open-action {:state "building" :cursor 77 :indexed-tx 10}
                                       {:stored-max-tx 10})))
    (is (= "gap" (:reason (search-indexer/open-action {:state "building" :cursor 77 :indexed-tx 9}
                                                      {:stored-max-tx 10})))
        "a gap below the cursor can't be resumed"))
  (testing "state read back from search_meta (TEXT)"
    (let [m (search/rows->meta [["blocks_state" "building"]
                                ["blocks_cursor" "77"]
                                ["blocks_indexed_tx" "10"]])]
      (is (= 77 (:cursor (search-indexer/open-action m {:stored-max-tx 10})))))))

(deftest next-batch-size-test
  (is (= 64 (search-indexer/next-batch-size 64 8 8)))
  (is (= 128 (search-indexer/next-batch-size 64 4 8)))
  (is (= 8 (search-indexer/next-batch-size 1 100 8)) "floor")
  (is (= 1024 (search-indexer/next-batch-size 1000 1 25)) "cap")
  (is (= 400 (search-indexer/next-batch-size 5 0 8)) "0 ms does not divide by zero"))

(deftest sync-tx-test
  (let [{:keys [db store]} (fake-sdb)
        conn (d/create-conn file-schema/schema)
        page-uuid (random-uuid)
        id (str page-uuid)]
    (swap! worker-state/*sqlite-conns assoc repo {:search db})
    (try
      (testing "a first-open (from-disk, new-graph) tx is indexed at once"
        (let [report (d/transact! conn [{:block/uuid page-uuid :block/name "foo" :block/title "Foo"}]
                                  {:from-disk? true :new-graph? true})]
          (search-indexer/sync-tx! repo report)
          (is (contains? (:blocks @store) id))
          (is (= (:max-tx (:db-after report)) (:indexed-tx (search/get-meta db)))
              "the watermark commits with the rows")))
      (testing "a watcher (from-disk) edit is indexed"
        (let [report (d/transact! conn [{:block/uuid page-uuid :block/title "Bar"}] {:from-disk? true})]
          (search-indexer/sync-tx! repo report)
          (is (= (:title (search/block->index (d/entity @conn [:block/uuid page-uuid])))
                 (get-in @store [:blocks id :title])))
          (is (= (:max-tx (:db-after report)) (:indexed-tx (search/get-meta db))))))
      (testing "a reset-conn! report is not synced row by row"
        (let [before (:blocks @store)]
          (search-indexer/sync-tx! repo {:tx-meta {:reset-conn! true}
                                         :db-after @conn
                                         :tx-data (d/datoms @conn :eavt)})
          ;; drop the deferred truncate + walk
          (search-indexer/cancel! repo)
          (is (= before (:blocks @store)))))
      (testing "an untagged reset-conn! report (fix-broken-graph) is not synced row by row either"
        (let [before (:blocks @store)
              reset-conn (d/create-conn file-schema/schema)
              *report (atom nil)]
          (d/listen! reset-conn ::capture #(reset! *report %))
          (d/reset-conn! reset-conn (d/db-with @reset-conn [{:block/uuid (random-uuid)
                                                             :block/name "new"
                                                             :block/title "New"}]))
          (is (some? @*report))
          (is (nil? (:tempids @*report)) "reset-conn! builds its report without :tempids")
          (search-indexer/sync-tx! repo @*report)
          (search-indexer/cancel! repo)
          (is (= before (:blocks @store)))))
      (finally
        (search-indexer/close! repo)
        (swap! worker-state/*sqlite-conns dissoc repo)))))

(deftest db-listener-sync-test
  (let [{:keys [db store]} (fake-sdb)
        conn (d/create-conn file-schema/schema)
        page-uuid (random-uuid)
        id (str page-uuid)
        watermark #(:indexed-tx (search/get-meta db))]
    (swap! worker-state/*sqlite-conns assoc repo {:search db})
    (db-listener/listen-db-changes! repo conn :handler-keys [:sync-db-to-main-thread])
    (try
      (testing "a first-open (from-disk, new-graph) tx is indexed through the listener"
        (let [report (d/transact! conn [{:block/uuid page-uuid :block/name "foo" :block/title "Foo"}]
                                  {:from-disk? true :new-graph? true})]
          (is (contains? (:blocks @store) id))
          (is (= (:max-tx (:db-after report)) (watermark)) "the watermark commits with the rows")))
      (testing "a watcher (from-disk) edit is indexed through the listener"
        (let [report (d/transact! conn [{:block/uuid page-uuid :block/title "Bar"}] {:from-disk? true})]
          (is (= (:title (search/block->index (d/entity @conn [:block/uuid page-uuid])))
                 (get-in @store [:blocks id :title])))
          (is (= (:max-tx (:db-after report)) (watermark)))))
      (testing "a tx the pipeline returns no result for is still synced"
        (let [block-uuid (random-uuid)
              report (d/transact! conn [{:block/uuid block-uuid
                                         :block/title "refs only"
                                         :block/page [:block/uuid page-uuid]
                                         :block/parent [:block/uuid page-uuid]}]
                                  {:transact-new-graph-refs? true})]
          (is (contains? (:blocks @store) (str block-uuid)))
          (is (= (:max-tx (:db-after report)) (watermark))
              "the watermark advances, so the next open sees no gap")))
      (finally
        (d/unlisten! conn :frontend.worker.db-listener/listen-db-changes!)
        (search-indexer/close! repo)
        (swap! worker-state/*sqlite-conns dissoc repo)))))

(deftest walk-with-txs-between-slices-test
  (let [{:keys [db store]} (fake-sdb)
        conn (new-conn)
        opts {:max-items 3 :max-chars 1e9 :deadline js/Infinity}
        slice! (fn [cursor]
                 (let [{:keys [rows last-e done?]} (search/index-batch @conn cursor opts)]
                   (search/commit-batch! db rows {:cursor last-e
                                                  :state (if done? "complete" "building")})
                   {:cursor last-e :done? done?}))
        sync! (fn [tx-data] (search-indexer/sync-tx! repo (d/transact! conn tx-data)))
        titled-block-eids (fn [db']
                            (->> (d/datoms db' :aevt :block/page)
                                 (map :e)
                                 (remove #(string/blank? (:block/title (d/entity db' %))))
                                 sort))
        first-block (first (titled-block-eids @conn))]
    (swap! worker-state/*sqlite-conns assoc repo {:search db})
    (try
      (let [cursor (loop [c 0]
                     (let [{:keys [cursor]} (slice! c)]
                       (if (>= cursor first-block) cursor (recur cursor))))
            eids (titled-block-eids @conn)
            behind (d/entity @conn (last (filter #(<= % cursor) eids)))
            ahead-e (last eids)
            ahead-uuid (:block/uuid (d/entity @conn ahead-e))
            page-e (:db/id (:block/page behind))
            new-uuid (random-uuid)]
        (is (> ahead-e cursor) "a block is still ahead of the cursor")
        (sync! [{:db/id (:db/id behind) :block/title "edited behind the cursor"}])
        (sync! [[:db/retractEntity ahead-e]])
        (sync! [{:block/uuid new-uuid
                 :block/title "created mid-walk"
                 :block/page page-e
                 :block/parent page-e}])
        (loop [c cursor]
          (let [{:keys [cursor done?]} (slice! c)]
            (when-not done? (recur cursor))))
        (is (= (into {} (map (fn [[id row]] [id (dissoc row :id)])) (expected-rows @conn))
               (:blocks @store))
            "the final rows equal a full index of the final db")
        (is (contains? (:blocks @store) (str new-uuid)))
        (is (not (contains? (:blocks @store) (str ahead-uuid))))
        (is (= (:title (search/block->index (d/entity @conn (:db/id behind))))
               (get-in @store [:blocks (str (:block/uuid behind)) :title]))))
      (finally
        (search-indexer/close! repo)
        (swap! worker-state/*sqlite-conns dissoc repo)))))

(deftest cancel-stops-a-running-walk-test
  (async done
    (let [{:keys [db store]} (fake-sdb)
          conn (d/create-conn file-schema/schema)
          page-uuid (random-uuid)
          cleanup! (fn []
                     (search-indexer/close! repo)
                     (swap! worker-state/*sqlite-conns dissoc repo)
                     (swap! worker-state/*datascript-conns dissoc repo))]
      (d/transact! conn [{:block/uuid page-uuid :block/name "big" :block/title "Big"}])
      (d/transact! conn (vec (for [i (range 300)]
                               {:block/uuid (random-uuid)
                                :block/title (str "block " i)
                                :block/page [:block/uuid page-uuid]
                                :block/parent [:block/uuid page-uuid]})))
      (swap! worker-state/*sqlite-conns assoc repo {:search db})
      (swap! worker-state/*datascript-conns assoc repo conn)
      (let [result (search-indexer/start! repo {:force? true})
            ;; the first slice (at most 64 entities) ran inside start!
            rows-after-first-slice (count (:blocks @store))]
        (search-indexer/cancel! repo)
        (-> result
            (p/then (fn [r]
                      (is (= {:cancelled true} r))
                      (is (pos? rows-after-first-slice))
                      (is (<= rows-after-first-slice 64))
                      (is (= rows-after-first-slice (count (:blocks @store)))
                          "no slice runs after cancel!")
                      (is (not (:running? (search-indexer/status repo))))))
            (p/catch (fn [e] (is false (str e))))
            (p/finally (fn [_ _]
                         (cleanup!)
                         (done))))))))

(deftest large-graph-test
  (let [conn (d/create-conn file-schema/schema)
        page (fn [i] {:block/uuid (random-uuid) :block/name (str "p" i) :block/title (str "P" i)})]
    (d/transact! conn (mapv page (range search/fuzzy-page-limit)))
    (is (false? (search/large-graph? @conn)) "exactly the limit")
    (d/transact! conn [(page search/fuzzy-page-limit)])
    (is (true? (search/large-graph? @conn)) "one page over the limit")))

(deftest fuzzy-page-batch-test
  (let [db @(new-conn)
        pages (->> (d/datoms db :avet :block/name)
                   (map #(d/entity db (:e %)))
                   (remove :logseq.property/hide?))
        docs (loop [cursor 0
                    acc []]
               (let [batch (search/fuzzy-page-batch db cursor {:max-items 2 :deadline js/Infinity})
                     acc (into acc (:docs batch))]
                 (if (:done? batch) acc (recur (:last-e batch) acc))))]
    (is (= 4 (count pages)))
    (is (not-any? :block/type pages) "file-graph pages without :block/type")
    (is (= (set (map (comp str :block/uuid) pages)) (set (map :id docs)))
        "every non-hidden :block/name entity, as the old synchronous build")
    (is (not (contains? (set (map :id docs)) (str hidden-uuid))) "hidden page skipped")))

(deftest close-drops-the-fuse-index-test
  (swap! search/fuzzy-search-indices assoc repo #js {})
  (swap! search/fuzzy-builds assoc repo {:cursor 0 :token -1})
  (search-indexer/close! repo)
  (is (nil? (get @search/fuzzy-search-indices repo)) "a reopen rebuilds it from the new conn")
  (is (nil? (get @search/fuzzy-builds repo))))
