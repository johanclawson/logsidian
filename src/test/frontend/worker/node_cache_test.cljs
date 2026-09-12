(ns frontend.worker.node-cache-test
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [datascript.storage :as storage]
            [frontend.worker.node-cache :as node-cache]))

(defn- row [v] {:keys [[1 :a v 1]]})

(defn- cached-addrs
  "The candidates the cache holds. Probes with -hit, which reorders entries
  and counts hits and misses, so call it after the assertions it would skew."
  [cache candidates]
  (filterv #(some? (node-cache/-hit cache %)) candidates))

(deftest bound-by-count
  (let [cache (node-cache/new-cache 3 1e9)]
    (doseq [a (range 1 6)]
      (node-cache/-admit! cache a (row a) 1))
    (is (= 3 (:entries (node-cache/-stats cache))))
    (is (= 2 (:evictions (node-cache/-stats cache))))
    (is (= [3 4 5] (cached-addrs cache (range 1 6))))))

(deftest least-recently-used-goes-first
  (let [cache (node-cache/new-cache 3 1e9)]
    (doseq [a [1 2 3]]
      (node-cache/-admit! cache a (row a) 1))
    (testing "a hit makes the row most recent"
      (is (= (row 1) (node-cache/-hit cache 1))))
    (node-cache/-admit! cache 4 (row 4) 1)
    (is (= [1 3 4] (cached-addrs cache [1 2 3 4])))))

(deftest bound-by-bytes
  (let [cache (node-cache/new-cache 100 10)]
    (doseq [a [1 2 3]]
      (node-cache/-admit! cache a (row a) 4))
    (is (= 8 (:bytes (node-cache/-stats cache))))
    (is (= [2 3] (cached-addrs cache [1 2 3])))
    (testing "an over-budget row is still kept, alone"
      (node-cache/-admit! cache 9 (row 9) 50)
      (is (= {:entries 1 :bytes 50}
             (select-keys (node-cache/-stats cache) [:entries :bytes]))))))

(deftest readmit-replaces
  (let [cache (node-cache/new-cache 10 1e9)]
    (node-cache/-admit! cache 1 (row :old) 4)
    (node-cache/-admit! cache 1 (row :new) 6)
    (is (= {:entries 1 :bytes 6}
           (select-keys (node-cache/-stats cache) [:entries :bytes])))
    (is (= (row :new) (node-cache/-hit cache 1)))))

(deftest invalidate-and-clear
  (let [cache (node-cache/new-cache 10 1e9)]
    (doseq [a [1 2 3]]
      (node-cache/-admit! cache a (row a) 5))
    (node-cache/-invalidate! cache 2)
    (is (nil? (node-cache/-hit cache 2)))
    (is (= {:entries 2 :bytes 10 :invalidations 1}
           (select-keys (node-cache/-stats cache) [:entries :bytes :invalidations])))
    (testing "a non-numeric address drops everything"
      (node-cache/-invalidate! cache "1")
      (is (= 0 (:entries (node-cache/-stats cache)))))
    (node-cache/-admit! cache 1 (row 1) 5)
    (node-cache/-clear! cache)
    (is (= {:entries 0 :bytes 0}
           (select-keys (node-cache/-stats cache) [:entries :bytes])))
    (is (nil? (node-cache/-hit cache 1)))))

(deftest one-cache-per-handle
  (let [db1 #js {}
        db2 #js {}]
    (is (identical? (node-cache/cache-for db1) (node-cache/cache-for db1)))
    (is (not (identical? (node-cache/cache-for db1) (node-cache/cache-for db2))))
    (node-cache/-admit! (node-cache/cache-for db1) 7 (row 7) 1)
    (node-cache/clear-for! db2)
    (is (= 1 (:entries (node-cache/stats-for db1))))
    (node-cache/clear-for! db1)
    (is (= 0 (:entries (node-cache/stats-for db1))))
    (is (nil? (node-cache/stats-for #js {})))
    (is (nil? (node-cache/clear-for! nil)))))

;; ---------------------------------------------------------------------------
;; cached-storage over an in-memory stand-in for the kvs table

(defn- serialized
  "What a round trip through SQLite would give back: no shared arrays."
  [data]
  (if (and (map? data) (array? (:addresses data)))
    (update data :addresses #(.slice %))
    data))

(defn- fake-kvs
  "An in-memory kvs table and a cached storage over it. Counts reads."
  ([] (fake-kvs (node-cache/new-cache)))
  ([cache]
   (let [rows (atom {})
         reads (atom 0)
         hits (atom 0)
         fail? (atom false)
         s (node-cache/cached-storage
            cache
            {:read-row (fn [addr]
                         (swap! reads inc)
                         (when-some [data (get @rows addr)]
                           [(serialized data) 100]))
             :write-rows! (fn [addr+data-seq _delete-addrs]
                            (when @fail? (throw (js/Error. "disk full")))
                            (swap! rows into (map (fn [[a data]] [a (serialized data)]))
                                   addr+data-seq))
             :on-hit #(swap! hits inc)})]
     {:storage s :cache cache :rows rows :reads reads :hits hits :fail? fail?})))

(deftest restore-after-store-returns-the-new-row
  (let [{s :storage :keys [reads hits]} (fake-kvs)]
    (storage/-store s [[5 (row "old")]] nil)
    (is (= (row "old") (storage/-restore s 5)))
    (is (= (row "old") (storage/-restore s 5)))
    (is (= 1 @reads) "second restore is a hit")
    (is (= 1 @hits))
    (testing "the address is rewritten in place"
      (storage/-store s [[5 (row "new")]] nil)
      (is (= (row "new") (storage/-restore s 5)))
      (is (= 2 @reads)))))

(deftest deleted-addresses-are-dropped
  (let [{s :storage :keys [reads]} (fake-kvs)]
    (storage/-store s [[5 (row 5)]] nil)
    (storage/-restore s 5)
    (storage/-store s [[6 (row 6)]] [5])
    (storage/-restore s 5)
    (is (= 2 @reads))))

(deftest failed-write-leaves-no-stale-row
  (let [{s :storage :keys [reads fail?]} (fake-kvs)]
    (storage/-store s [[5 (row "old")]] nil)
    (storage/-restore s 5)
    (reset! fail? true)
    (is (thrown? js/Error (storage/-store s [[5 (row "new")]] nil)))
    (testing "the row is read again, and SQLite still has the old one"
      (is (= (row "old") (storage/-restore s 5)))
      (is (= 2 @reads)))))

(deftest only-node-rows-are-cached
  (let [{s :storage :keys [reads cache]} (fake-kvs)]
    (storage/-store s [[0 {:schema {} :eavt 10}] [1 [[1 :a 1 1]]]] nil)
    (storage/-restore s 0)
    (storage/-restore s 1)
    (storage/-restore s 99)
    (is (= 0 (:entries (node-cache/-stats cache))))
    (is (= 3 @reads))))

(deftest restored-addresses-are-not-shared
  (let [{s :storage} (fake-kvs)]
    (storage/-store s [[5 {:keys [] :addresses #js [7 8]}]] nil)
    (aset (:addresses (storage/-restore s 5)) 0 99)
    (is (= [7 8] (vec (:addresses (storage/-restore s 5)))) "hit")
    (aset (:addresses (storage/-restore s 5)) 1 99)
    (is (= [7 8] (vec (:addresses (storage/-restore s 5)))))))

(defn- eav-set [db]
  (set (map (juxt :e :a :v) (d/datoms db :eavt))))

(deftest datascript-restore-after-in-place-rewrites
  ;; Enough datoms for a multi-level tree. The second conn rewrites leaves of
  ;; a tree whose rows are cached; DataScript keeps their addresses, so a
  ;; cache that missed a store would restore old leaf contents here.
  (let [{s :storage cache :cache} (fake-kvs)
        schema {:name {}}
        conn1 (d/create-conn schema {:storage s})
        _ (d/transact! conn1 (for [i (range 1 2001)] {:db/id i :name (str "v1-" i)}))
        _ (d/store @conn1)
        conn2 (d/restore-conn s)
        _ (is (= (eav-set @conn1) (eav-set @conn2)))
        hits0 (:hits (node-cache/-stats cache))
        _ (d/transact! conn2 (for [i (range 1 2001 7)] [:db/add i :name (str "v2-" i)]))
        _ (d/store @conn2)
        conn3 (d/restore-conn s)]
    (is (= (eav-set @conn2) (eav-set @conn3)))
    (is (contains? (eav-set @conn3) [8 :name "v2-8"]))
    (is (pos? (:invalidations (node-cache/-stats cache)))
        "stores rewrote addresses that were cached")
    (is (> (:hits (node-cache/-stats cache)) hits0)
        "the last restore was served partly from the cache")))
