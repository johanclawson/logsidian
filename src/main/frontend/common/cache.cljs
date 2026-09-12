(ns frontend.common.cache
  "Utils about cache"
  (:require [cljs.cache :as cache]))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
;; (def *profile (volatile! {}))

(defn cache-fn
  "Return a cached version of `f`.
  cache-key&f-args-fn: return [<cache-key> <args-list-to-f>]"
  [*cache cache-key&f-args-fn f]
  (fn [& args]
    (let [[cache-k f-args] (apply cache-key&f-args-fn args)
          through-value-fn #(apply f f-args)
          ;; hit? (cache/has? @*cache cache-k)
          ;; _ (vswap! *profile update-in [[*cache (.-limit ^js @*cache)] (if hit? :hit :miss)] inc)
          ;; _ (prn (if hit? :hit :miss) cache-k)
          cache (vreset! *cache (cache/through through-value-fn @*cache cache-k))]
      (cache/lookup cache cache-k))))

;; LRU cache backed by JS Maps.
;;
;; cljs.cache/lru-cache-factory seeds its usage queue (a priority-map) with
;; `threshold` placeholder entries when it is created, which for a 5000-entry
;; cache created at namespace load cost ~177 ms of the renderer boot, and every
;; hit/miss then updates a persistent priority-map. This one allocates nothing
;; up front and hit/miss/evict are O(1):
;; - `index` maps (hash key) to a JS array of entries, compared with `=`, so
;;   keys have the same value semantics as in a cljs map;
;; - `order` holds the entries in JS Map insertion order, least recently used
;;   first; a hit or miss re-inserts the entry at the end.
;;
;; Unlike cljs.cache caches it is mutable: hit/miss/evict update the cache in
;; place and return it. That is all `cache-fn` needs (it keeps the returned
;; cache in its volatile). Eviction behaves like cljs.cache's LRUCache: a miss
;; on a full cache (threshold entries) evicts the entry that was least recently
;; hit or missed.

(deftype LruEntry [k h ^:mutable v])

(defn- lru-find
  "The entry for `item` (whose hash is `h`) in `index`, or nil."
  [^js index item h]
  (when-let [bucket (.get index h)]
    (loop [i 0]
      (when (< i (alength bucket))
        (let [^LruEntry e (aget bucket i)]
          (if (= item (.-k e))
            e
            (recur (inc i))))))))

(defn- lru-remove!
  [^js index ^js order ^LruEntry e]
  (let [h (.-h e)
        ^js bucket (.get index h)]
    (.splice bucket (.indexOf bucket e) 1)
    (when (zero? (alength bucket))
      (.delete index h))
    (.delete order e)))

(defn- lru-touch!
  "Makes `e` the most recently used entry."
  [^js order e]
  (.delete order e)
  (.set order e true))

(deftype MapLRUCache [^js index ^js order limit]
  cache/CacheProtocol
  (lookup [this item]
    (cache/lookup this item nil))
  (lookup [_ item not-found]
    (if-let [^LruEntry e (lru-find index item (hash item))]
      (.-v e)
      not-found))
  (has? [_ item]
    (some? (lru-find index item (hash item))))
  (hit [this item]
    (when-let [e (lru-find index item (hash item))]
      (lru-touch! order e))
    this)
  (miss [this item result]
    (let [h (hash item)]
      (if-let [^LruEntry e (lru-find index item h)]
        (do (set! (.-v e) result)
            (lru-touch! order e))
        (do
          (when (>= (.-size order) limit)
            ;; the first entry in insertion order is the least recently used
            (lru-remove! index order (.-value ^js (.next ^js (.keys order)))))
          (let [e (LruEntry. item h result)]
            (if-let [^js bucket (.get index h)]
              (.push bucket e)
              (.set index h #js [e]))
            (.set order e true)))))
    this)
  (evict [this item]
    (when-let [e (lru-find index item (hash item))]
      (lru-remove! index order e))
    this)
  (seed [_ base]
    (reduce-kv (fn [c k v] (cache/miss c k v))
               (MapLRUCache. (js/Map.) (js/Map.) limit)
               base))

  ICounted
  (-count [_]
    (.-size order)))

(defn lru-cache-factory
  "Same arguments as cljs.cache/lru-cache-factory, returns a `MapLRUCache` (see
  above) holding at most `threshold` entries (default 32). The entries of
  `base` are added in its iteration order (the most recently used last)."
  [base & {threshold :threshold :or {threshold 32}}]
  {:pre [(number? threshold) (< 0 threshold)
         (map? base)]}
  (cache/seed (MapLRUCache. nil nil threshold) base))
