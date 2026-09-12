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

(defn- json-param
  [bind k]
  (js->clj (js/JSON.parse (gobj/get bind k))))

(defn- fake-sdb
  "Stands in for a sqlite-wasm oo1 DB, for the statements the index uses.
  search_meta values are kept as strings, the way its TEXT column hands them
  back. Rows arrive as the $rows JSON of search/upsert-sql and deletes as the
  $ids JSON of search/delete-sql; FTS5 commands (INSERT INTO blocks_fts(...))
  set :fts-config. No timers: this db has no changes(), so
  search-indexer/start-maint! ignores it."
  []
  (let [store (atom {:meta {} :blocks {} :fts-config {} :transactions 0})
        db #js {}]
    (set! (.-exec db)
          (fn [arg]
            (let [sql (if (string? arg) arg (gobj/get arg "sql"))
                  bind (when-not (string? arg) (gobj/get arg "bind"))
                  b #(gobj/get bind %)]
              (cond
                ;; table/trigger probes: this fake keeps no schema objects
                (string/includes? sql "sqlite_master")
                #js []

                (string/starts-with? sql "INSERT INTO search_meta")
                (do (swap! store assoc-in [:meta (b "$k")] (str (b "$v"))) db)

                (string/starts-with? sql "SELECT k, v FROM search_meta")
                (clj->js (mapv (fn [[k v]] [k v]) (:meta @store)))

                ;; before the INSERT INTO blocks branch: an FTS5 command is
                ;; not a blocks row
                (string/starts-with? sql "INSERT INTO blocks_fts(blocks_fts, rank)")
                (do (swap! store assoc-in [:fts-config (b "$k")] (b "$v")) db)

                (string/starts-with? sql "SELECT k, v FROM blocks_fts_config")
                (clj->js (mapv (fn [[k v]] [k v]) (:fts-config @store)))

                (string/starts-with? sql "INSERT INTO blocks ")
                (do (swap! store update :blocks into
                           (map (fn [[id title page]] [id {:title title :page page}]))
                           (json-param bind "$rows"))
                    db)

                (string/starts-with? sql "SELECT 1 FROM blocks")
                (clj->js (if (seq (:blocks @store)) [[1]] []))

                (string/starts-with? sql "DELETE FROM blocks WHERE id IN")
                (do (swap! store update :blocks #(apply dissoc % (json-param bind "$ids"))) db)

                (string/includes? sql "DROP TABLE IF EXISTS blocks;")
                (do (swap! store assoc :blocks {} :fts-config {}) db)

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
  (let [m {:state "building" :cursor 123 :gen 2 :indexed-tx 536871000 :dirty? true :schema 2}]
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

(deftest rows-json-validates-before-dedupe-test
  (let [id (str (random-uuid))
        id2 (str (random-uuid))
        page (str (random-uuid))
        rows (fn [xs] (some-> (search/rows->json xs) :json js/JSON.parse js->clj))]
    (testing "a later bad row of an id does not replace the good one"
      (is (= [[id "good" page]]
             (rows [{:id id :title "good" :page page}
                    {:id id :title "bad page" :page "not-a-uuid"}
                    {:id id :title 42 :page page}]))))
    (testing "the last valid row of an id wins, at the position of its first"
      (is (= [[id "b" page] [id2 "x" page]]
             (rows [{:id id :title "a" :page page}
                    {:id id2 :title "x" :page page}
                    {:id id :title "b" :page page}])))
      (is (= 2 (:n (search/rows->json [{:id id :title "a" :page page}
                                       {:id id2 :title "x" :page page}
                                       {:id id :title "b" :page page}])))))
    (testing "bad ids, pages and titles are skipped"
      (is (= [[id2 "ok" page]]
             (rows [{:id "not-a-uuid" :title "t" :page page}
                    {:id id :title nil :page page}
                    {:id id2 :title "ok" :page page}]))))
    (testing "no valid row: nil, so no statement runs"
      (is (nil? (search/rows->json [{:id "bad" :title "t" :page page}])))
      (is (nil? (search/rows->json []))))
    (testing "quotes, backslashes and non-ASCII survive the JSON parameter"
      (let [t "a \"quote\" \\ back\\slash, möte 😀"]
        (is (= [[id t page]] (rows [{:id id :title t :page page}])))))
    (when (.-toWellFormed "")
      (testing "a lone surrogate becomes U+FFFD, the bytes TextEncoder wrote before"
        (is (= [[id "a\uFFFDb" page]] (rows [{:id id :title "a\uD800b" :page page}])))))))

(deftest delete-blocks-by-ids-test
  (let [{:keys [db store]} (fake-sdb)
        page (str (random-uuid))
        [a b c] (repeatedly 3 #(str (random-uuid)))]
    (search/commit-batch! db (mapv (fn [id] {:id id :title id :page page}) [a b c]) {:cursor 1})
    (is (= #{a b c} (set (keys (:blocks @store)))))
    (search/delete-blocks! db #{a c})
    (is (= #{b} (set (keys (:blocks @store)))) "every id in the one $ids parameter")
    (search/delete-blocks! db [(uuid b)])
    (is (empty? (:blocks @store)) "uuids are sent as their strings")
    (let [n (:transactions @store)]
      (search/sync-rows! db #{} [{:id a :title "t" :page page}] {:indexed-tx 3})
      (is (= {a {:title "t" :page page}} (:blocks @store)) "no ids: no delete")
      (is (= (inc n) (:transactions @store))))))

(deftest orphan-queue-test
  (let [conn (d/create-conn file-schema/schema)
        live (random-uuid)
        gone (str (random-uuid))]
    (d/transact! conn [{:block/uuid live :block/name "live" :block/title "Live"}])
    (search/take-orphans! repo)
    (search/queue-orphans! repo [gone (str live)])
    (search/queue-orphans! repo [gone])
    (let [ids (search/take-orphans! repo)]
      (is (= #{gone (str live)} ids) "queued once per id")
      (is (nil? (search/take-orphans! repo)) "taking empties the queue")
      (is (= [gone] (vec (search/still-orphans @conn ids)))
          "a block that exists again (e.g. undo) is not deleted"))
    (testing "the queue is capped"
      (search/queue-orphans! repo (repeatedly 5000 #(str (random-uuid))))
      (is (= 1000 (count (search/take-orphans! repo)))))))

(deftest maint-action-test
  (let [act search-indexer/maint-action
        soft search-indexer/maint-checkpoint-soft-pages
        age search-indexer/maint-checkpoint-max-age-ms
        idle {:wrote? false :quiet-ms 5000 :pending-pages 0 :budget-pages 0 :age-ms 0
              :held? false :merge? false :busy? false}
        writing (assoc idle :wrote? true :quiet-ms 0 :pending-pages 10 :budget-pages 10 :age-ms 250)]
    (is (= {:ckpt nil :merge? false :idle? true} (act idle)) "nothing to do")
    (is (= {:ckpt nil :merge? false :idle? false} (act writing))
        "a write on the last tick is no reason to checkpoint")
    (testing "no checkpoint without pending pages, however old, big or quiet"
      (is (nil? (:ckpt (act (assoc idle :budget-pages (* 2 soft) :age-ms 5000)))))
      (is (nil? (:ckpt (act (assoc writing :pending-pages 0 :budget-pages 2 :age-ms 5000))))
          "the restart row alone is not pending"))
    (testing "budget: pages since the last complete checkpoint"
      (is (nil? (:ckpt (act (assoc writing :pending-pages 500 :budget-pages (dec soft))))))
      (is (= :budget (:ckpt (act (assoc writing :pending-pages 510 :budget-pages soft)))))
      (is (= :budget (:ckpt (act (assoc writing :pending-pages 510 :budget-pages soft :age-ms age))))
          "the budget is named first when both are due"))
    (testing "age: the oldest application write not checkpointed"
      (is (nil? (:ckpt (act (assoc writing :age-ms (- age 250))))))
      (is (= :age (:ckpt (act (assoc writing :age-ms age)))))
      (is (= :age (:ckpt (act (assoc writing :age-ms age :busy? true)))) "busy or not"))
    (testing "final: pages left once the idle drain has no merge work"
      (is (= :final (:ckpt (act (assoc idle :pending-pages 40 :budget-pages 42)))))
      (is (nil? (:ckpt (act (assoc idle :pending-pages 40 :merge? true)))) "not while the drain has work")
      (is (nil? (:ckpt (act (assoc idle :pending-pages 40 :busy? true)))) "not while the user waits")
      (is (nil? (:ckpt (act (assoc idle :pending-pages 40 :quiet-ms 750)))) "not before the quiet period"))
    (testing "held after a partial or failed checkpoint"
      (is (nil? (:ckpt (act (assoc writing :age-ms 3000 :budget-pages (* 2 soft) :held? true))))))
    (testing "a checkpoint and a merge both due: only the checkpoint"
      (is (= {:ckpt :age :merge? false :idle? false} (act (assoc writing :age-ms age :merge? true))))
      (is (= {:ckpt :budget :merge? false :idle? true}
             (act (assoc idle :pending-pages soft :budget-pages soft :merge? true)))))
    (testing "merges: a step whenever work may be left; idle? paces the next"
      (is (= {:ckpt nil :merge? true :idle? true} (act (assoc idle :merge? true))))
      (is (= {:ckpt nil :merge? true :idle? false} (act (assoc idle :merge? true :busy? true))))
      (is (= {:ckpt nil :merge? true :idle? false} (act (assoc writing :merge? true))))
      (is (= {:ckpt nil :merge? true :idle? false} (act (assoc idle :merge? true :quiet-ms 750)))))))

(deftest maint-checkpoint-then-merge-test
  (let [st {:tc 3 :ckpt-pages 0 :done-pages 0 :write-t 0 :app-t 0 :merge? true
            :merge-n 8 :merge-fast 0}
        [st1 m1] (search-indexer/maint-observe st 1000 3 40 0)
        a1 (search-indexer/maint-action (assoc m1 :busy? true))
        st2 (search-indexer/maint-settle st1 1000 {:tc 4 :pages 42 :ckpt :complete :ckpt-pages 40
                                                   :merge-left? (:merge? st1)})
        [_ m2] (search-indexer/maint-observe st2 1250 4 42 0)
        a2 (search-indexer/maint-action (assoc m2 :busy? true))]
    (is (= {:ckpt :age :merge? false :idle? false} a1) "this tick: the checkpoint")
    (is (true? (:merge? st2)) "merge work still pending")
    (is (= 0 (:pending-pages m2)) "the restart row is not pending")
    (is (= 2 (:budget-pages m2)) "but counts toward the next budget")
    (is (= {:ckpt nil :merge? true :idle? false} a2) "the next tick: the merge")))

(deftest maint-settle-test
  (let [st {:tc 5 :ckpt-pages 100 :done-pages 102 :write-t 0 :app-t 250 :merge? true}]
    (testing "complete: a new budget, nothing pending, the restart row included"
      (let [s (search-indexer/maint-settle st 1250 {:tc 9 :pages 162 :ckpt :complete :ckpt-pages 160
                                                    :merge-left? true})]
        (is (= [160 162 nil 9 true] ((juxt :ckpt-pages :done-pages :app-t :tc :merge?) s)))))
    (testing "no checkpoint: only the tick's own total_changes moves"
      (let [s (search-indexer/maint-settle st 1250 {:tc 6 :pages 170 :merge-left? false})]
        (is (= [100 102 250 6 false] ((juxt :ckpt-pages :done-pages :app-t :tc :merge?) s)))
        (is (= 68 (:pending-pages (second (search-indexer/maint-observe s 1500 6 170 0))))
            "the tick's merge pages are pending")))
    (testing "partial or failed: everything stays pending, the next try after a hold"
      (doseq [o [:partial :failed]]
        (let [s (search-indexer/maint-settle st 1250 {:tc 5 :pages 160 :ckpt o :ckpt-pages 160
                                                      :merge-left? true})
              seen (fn [t] (second (search-indexer/maint-observe s t 5 160 0)))]
          (is (= [100 102 250] ((juxt :ckpt-pages :done-pages :app-t) s)) (str o))
          (is (:held? (seen 1500)) (str o))
          (is (nil? (:ckpt (search-indexer/maint-action (assoc (seen 1500) :busy? false)))) (str o))
          (is (= :age (:ckpt (search-indexer/maint-action
                              (assoc (seen (+ 1250 search-indexer/maint-checkpoint-max-age-ms)) :busy? false))))
              (str o " retried once the hold is over")))))))

(deftest maint-merge-n-test
  (let [f search-indexer/maint-merge-n
        g search-indexer/maint-merge-grow-after
        st {:merge-n 8 :merge-fast 0}
        grow (fn [n k] (reduce (fn [s _] (merge s (f s 1 true))) {:merge-n n :merge-fast 0} (range k)))]
    (is (= {:merge-n 4 :merge-fast 0} (f st 12 true)) "halved after a step over 10 ms")
    (is (= {:merge-n 4 :merge-fast 0} (f (assoc st :merge-fast 3) 12 false)))
    (is (= 1 (:merge-n (f {:merge-n 1 :merge-fast 0} 50 true))) "never below 1")
    (is (= 1 (:merge-n (f {:merge-n 3 :merge-fast 0} 50 true))))
    (is (= {:merge-n 8 :merge-fast 1} (f st 1 true)) "one fast step: counted")
    (is (= {:merge-n 8 :merge-fast 0} (f (assoc st :merge-fast 3) 5 true)) "a step in between: counted again")
    (is (= {:merge-n 8 :merge-fast 0} (f (assoc st :merge-fast 3) 1 false))
        "a step that found no work never grows N")
    (is (= 8 (:merge-n (grow 8 (dec g)))))
    (is (= 16 (:merge-n (grow 8 g))) "doubled after maint-merge-grow-after fast steps with work")
    (is (= search-indexer/maint-merge-n-max (:merge-n (grow 8 (* 10 g)))) "capped")))

(deftest maint-next-ms-test
  (let [f search-indexer/maint-next-ms
        d search-indexer/maint-idle-step-delay-ms
        tick search-indexer/maint-tick-ms]
    (is (= d (f {:merge? true} 5000 true false)) "idle with merge work: the next step soon")
    (is (= d (f {:merge? false} 5000 true true)) "idle, drain done, pages pending: the final checkpoint soon")
    (is (= tick (f {:merge? false} 5000 true false)) "idle with nothing left: disarmed")
    (is (= tick (f {:merge? true} 5000 false false)) "busy: one step per tick")
    (is (= tick (f {:merge? false :held-until 6000} 5000 true true)) "a held checkpoint does not spin")))

(defn- simulate-maint
  "Runs maint-observe, maint-action, maint-settle, maint-merge-n and
  maint-next-ms the way maint-tick! does, one timer task at a time, on a
  model of the search db. writes: sorted [ms pages] application commits.
  opts:
  :busy?           a thread-api call before every task
  :merge-per-write FTS5 merge work (pages) each commit leaves; a step of N
                   takes up to N of it, writes that many pages and takes
                   0.5 + 0.15 ms a page; with none left it changes 1 row
  :outcomes        checkpoint index -> :complete, :partial or :failed
  A complete checkpoint writes a 2-page restart row. No orphans. Returns
  :tasks ([t ckpt step?] per task), :ckpts (reasons), :max-wait (longest an
  application write waited for a complete checkpoint), :max-pending (pages
  pending at a checkpoint), :merged, :pending (pages left) and the final :st."
  [writes end-ms & [{:keys [busy? merge-per-write outcomes]
                     :or {busy? false merge-per-write 0 outcomes (constantly :complete)}}]]
  (let [writes (vec writes)
        all-pages (reduce + 0 (map second writes))]
    (loop [t search-indexer/maint-tick-ms
           st {:tc 0 :ckpt-pages 0 :done-pages 0 :write-t 0 :merge? false
               :merge-n search-indexer/maint-merge-n-initial :merge-fast 0}
           own {:pages 0 :tc 0 :merged 0 :ckpt-n 0 :ckpt-w 0}
           out {:tasks [] :ckpts [] :max-wait 0 :max-pending 0}]
      (if (> t end-ms)
        (assoc out :st st :merged (:merged own)
               :pending (- (+ all-pages (:pages own)) (:done-pages st)))
        (let [seen-w (take-while #(<= (first %) t) writes)
              n-w (count seen-w)
              pages (+ (reduce + 0 (map second seen-w)) (:pages own))
              [st1 seen] (search-indexer/maint-observe st t (+ n-w (:tc own)) pages 0)
              {:keys [ckpt merge? idle?]} (search-indexer/maint-action (assoc seen :busy? busy?))
              outcome (when ckpt (outcomes (:ckpt-n own)))
              restart (if (= :complete outcome) 2 0)
              step (when merge? (min (- (* merge-per-write n-w) (:merged own)) (:merge-n st1)))
              own' (cond-> (-> own
                               (update :pages + restart (or step 0))
                               (update :tc + (if (pos? restart) 1 0)
                                       (cond (nil? step) 0 (pos? step) (max 2 step) :else 1))
                               (update :merged + (or step 0)))
                     ckpt (update :ckpt-n inc)
                     (= :complete outcome) (assoc :ckpt-w n-w))
              pages' (+ pages restart (or step 0))
              st2 (cond-> (search-indexer/maint-settle
                           st1 t {:tc (+ n-w (:tc own')) :pages pages' :ckpt outcome
                                  :ckpt-pages pages
                                  :merge-left? (if merge? (pos? step) (:merge? st1))})
                    merge? (merge (search-indexer/maint-merge-n st1 (+ 0.5 (* 0.15 step)) (pos? step))))
              wait (if (and (= :complete outcome) (> n-w (:ckpt-w own)))
                     (- t (first (nth writes (:ckpt-w own))))
                     0)]
          (recur (+ t (search-indexer/maint-next-ms st2 t idle? (> pages' (:done-pages st2))))
                 st2
                 own'
                 (cond-> (-> out
                             (update :tasks conj [t ckpt merge?])
                             (update :max-wait max wait))
                   ckpt (-> (update :ckpts conj ckpt)
                            (update :max-pending max (:pending-pages seen))))))))))

(deftest maint-checkpoints-any-write-pattern-test
  (let [tick search-indexer/maint-tick-ms
        age search-indexer/maint-checkpoint-max-age-ms
        soft search-indexer/maint-checkpoint-soft-pages
        every (fn [p pages] (map (fn [t] [t pages]) (range 100 30000 p)))]
    (testing "a streak of small saves: a checkpoint per second or so, not per tick"
      (let [{:keys [ckpts max-wait pending]} (simulate-maint (every 100 6) 32000)]
        (is (<= 20 (count ckpts) 26) (pr-str (frequencies ckpts)))
        (is (every? #{:age} ckpts))
        (is (<= max-wait (+ age tick)))
        (is (zero? pending) "every write checkpointed in the end")))
    (testing "a heavy streak: the page budget"
      (let [{:keys [ckpts max-pending]} (simulate-maint (every 50 40) 30000)]
        ;; 200 pages a tick: a budget checkpoint every third tick
        (is (< 30 (count (filter #{:budget} ckpts)) 45) (pr-str (frequencies ckpts)))
        (is (<= max-pending (+ soft 200)))))
    (testing "sparse saves (never a streak, never a quiet second): each waits about a second"
      (doseq [p [550 600 700 900]
              busy? [false true]]
        (let [{:keys [ckpts max-wait pending]} (simulate-maint (every p 6) 32000 {:busy? busy?})]
          (is (<= max-wait (+ age tick)) (str "a save every " p " ms, busy " busy?))
          (is (<= (count ckpts) 26) (str "a save every " p " ms, busy " busy?))
          (is (zero? pending) (str "a save every " p " ms, busy " busy?)))))
    (testing "one save: a single checkpoint about a second later"
      (let [{:keys [ckpts max-wait]} (simulate-maint [[100 6]] 5000)]
        (is (= [:age] ckpts))
        (is (<= age max-wait (+ age tick)))))
    (testing "nothing written: no checkpoint, a tick every maint-tick-ms"
      (let [{:keys [ckpts tasks]} (simulate-maint [] 10000)]
        (is (empty? ckpts))
        (is (every? #{tick} (map - (rest (map first tasks)) (map first tasks))))))))

(deftest maint-idle-drain-test
  (let [delay-ms search-indexer/maint-idle-step-delay-ms
        writes (map (fn [t] [t 6]) (range 100 2000 100))
        {:keys [tasks ckpts merged pending]} (simulate-maint writes 10000 {:merge-per-write 40})
        final-t (some (fn [[t ckpt]] (when (= :final ckpt) t)) tasks)
        ;; idle tasks before the final checkpoint: steps, and a budget
        ;; checkpoint when the drain's pages reach it (a task of its own)
        drain (filter (fn [[t]] (< (+ 1900 search-indexer/maint-quiet-ms) t final-t)) tasks)
        drain-ts (map first drain)]
    (is (= (* 19 40) merged) "all merge work done")
    (is (zero? pending) "the final checkpoint took the merges' pages")
    (is (= 1 (count (filter #{:final} ckpts))))
    (is (every? (fn [[_ ckpt step?]] (not (and ckpt step?))) tasks) "never a checkpoint and a step in one task")
    (is (< 10 (count (filter #(nth % 2) drain))) "the drain ran")
    (is (every? #{delay-ms} (map - (rest drain-ts) drain-ts)) "one task every maint-idle-step-delay-ms")
    (is (= delay-ms (- final-t (last drain-ts))) "the final checkpoint in the next task")
    (let [after (filter #(> (first %) final-t) tasks)]
      (is (every? (fn [[_ ckpt step?]] (and (nil? ckpt) (not step?))) after) "then disarmed")
      (is (every? #{search-indexer/maint-tick-ms}
                  (map - (map first after) (cons final-t (map first after))))))))

(deftest maint-retries-partial-checkpoint-test
  (doseq [o [:partial :failed]]
    (let [{:keys [tasks ckpts pending st]}
          (simulate-maint [[100 6]] 6000 {:outcomes #(if (zero? %) o :complete)})
          [t1 t2] (keep (fn [[t ckpt]] (when ckpt t)) tasks)]
      (is (= [:age :age] ckpts) (str o ": tried again once, not in a loop"))
      (is (>= (- t2 t1) search-indexer/maint-checkpoint-max-age-ms) (str o ": after the hold"))
      (is (zero? pending) (str o))
      (is (nil? (:app-t st)) (str o)))))

(deftest maint-inert-without-sqlite-test
  (let [{:keys [db]} (fake-sdb)]
    (is (nil? (search-indexer/start-maint! repo db)) "a fake db has no changes(): no tick")
    (is (nil? (search-indexer/start-maint! repo nil)))
    (is (false? (search-indexer/maint-running? repo)) "no timer left behind")
    (search-indexer/close! repo)
    (is (false? (search-indexer/maint-running? repo)))))

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
      (is (= {:state "building" :cursor 0 :gen 4 :indexed-tx 7 :dirty? false
              :schema search/schema-version}
             (search/get-meta db))
          "a truncate also records the schema version of the triggers it recreated"))
    (testing "set-meta-tx! writes a multi-key state change in one transaction"
      (let [n (:transactions @store)]
        (search/set-meta-tx! db {:state "building" :dirty? true :cursor 0})
        (is (= (inc n) (:transactions @store)))
        (is (= {:state "building" :cursor 0 :gen 4 :indexed-tx 7 :dirty? true
                :schema search/schema-version}
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
  ;; An index at the current schema version; the version rule itself is in
  ;; frontend.worker.search-schema-test.
  (let [open (fn [m facts]
               (search-indexer/open-action (assoc m :schema search/schema-version)
                                           (assoc facts :triggers-current? true)))]
    (testing "no state row"
      (is (= {:action :trust}
             (open {} {:blocks-empty? true :has-files? false}))
          "a new graph: every tx is indexed as it happens")
      (is (= {:action :walk :truncate? false :cursor 0 :reason "empty"}
             (open {} {:blocks-empty? true :has-files? true}))
          "an empty index on a parsed graph is healed")
      (is (= {:action :trust}
             (open {} {:blocks-empty? false :has-files? true}))
          "rows but no state row is not walked"))
    (testing "dirty"
      (is (= {:action :walk :truncate? true :cursor 0 :reason "dirty"}
             (open {:state "complete" :dirty? true :indexed-tx 10}
                   {:stored-max-tx 10}))))
    (testing "watermark"
      (is (= {:action :trust}
             (open {:state "complete" :indexed-tx 10} {:stored-max-tx 10})))
      (is (= {:action :trust}
             (open {:state "complete" :indexed-tx 12} {:stored-max-tx 10})))
      (is (= {:action :walk :truncate? true :cursor 0 :reason "gap"}
             (open {:state "complete" :indexed-tx 9} {:stored-max-tx 10}))
          "a stored tx the index never saw")
      (is (= "gap" (:reason (open {:state "complete"} {:stored-max-tx 10})))))
    (testing "building"
      (is (= {:action :walk :truncate? false :cursor 77 :reason "resume"}
             (open {:state "building" :cursor 77 :indexed-tx 10}
                   {:stored-max-tx 10})))
      (is (= "gap" (:reason (open {:state "building" :cursor 77 :indexed-tx 9}
                                  {:stored-max-tx 10})))
          "a gap below the cursor can't be resumed"))
    (testing "state read back from search_meta (TEXT)"
      (let [m (search/rows->meta [["blocks_state" "building"]
                                  ["blocks_cursor" "77"]
                                  ["blocks_indexed_tx" "10"]
                                  ["schema" (str search/schema-version)]])]
        (is (= search/schema-version (:schema m)) "parsed as an integer")
        (is (= 77 (:cursor (search-indexer/open-action m {:stored-max-tx 10}))))))))

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
