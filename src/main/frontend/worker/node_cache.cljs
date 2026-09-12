(ns frontend.worker.node-cache
  "Bounded, strongly held LRU of the decoded SQLite `kvs` rows that DataScript
  index nodes are restored from.

  persistent-sorted-set 0.1.2 keeps restored nodes only through js/WeakRef and
  has no cache of its own, so every GC drops them and the next walk restores
  them from SQLite again: a SELECT plus a transit decode, about 0.3 ms a node.
  This cache keeps the most recently restored rows, decoded, bounded by entry
  count and by approximate heap bytes. On a hit DataScript still builds a
  fresh node and fresh datoms from the row, but skips SQLite and transit.

  Correctness under address reuse: stores rewrite node rows in place (`on
  conflict(addr) do update`), so the cache must only ever mirror what SQLite
  holds for its handle.
  - `cached-storage` drops every written or deleted address before handing
    the batch to the writer, in the same synchronous step. Whether the write
    succeeds or throws, no cached row for those addresses survives it.
  - Code that changes kvs rows outside the storage (GC, rebuild, import) or
    closes the handle calls `clear-for!`.
  So a restore returns exactly what an uncached restore would read.

  There is one cache per SQLite handle (`cache-for`), shared by every storage
  over that handle, so graphs never see each other's rows even though
  DataScript's address counter is global."
  (:require [datascript.storage :as storage]))

(def max-entries
  "Most rows kept per SQLite handle. A search at 10k journals restores about
  1.4k nodes cold."
  4096)

(def max-bytes
  "Approximate heap budget per SQLite handle, as counted by `row-cost`."
  (* 64 1024 1024))

(def ^:private heap-bytes-per-char
  "Estimated heap of a decoded row per character of its stored text. A datom
  decodes to an [e a v tx] vector of about 90 bytes against 25-30 transit
  characters plus its value; values are shared with the datoms built from it."
  3)

(def ^:private entry-overhead-bytes
  "Map entry, entry array and the copy of :addresses made per restore."
  200)

(defn row-cost
  "Approximate heap bytes of a decoded row stored as `chars` characters."
  [chars]
  (+ entry-overhead-bytes (* heap-bytes-per-char (or chars 0))))

(defprotocol INodeCache
  (-hit [cache addr] "The row cached for addr, made most recent, or nil.")
  (-admit! [cache addr row cost]
    "Keep row under addr, then evict the least recent rows past the bounds.")
  (-invalidate! [cache addr] "Drop the row cached for addr, if any.")
  (-clear! [cache] "Drop every row.")
  (-stats [cache] "Counters and current size, as a map."))

;; entries: js/Map addr -> #js [row cost], iterated in insertion order, so the
;; first key is the least recently used one (a hit re-inserts its key).
(deftype NodeCache [^js entries entry-limit byte-limit
                    ^:mutable total-cost ^:mutable hits ^:mutable misses
                    ^:mutable evictions ^:mutable invalidations]
  INodeCache
  (-hit [_ addr]
    (let [e (.get entries addr)]
      (if (some? e)
        (do (.delete entries addr)
            (.set entries addr e)
            (set! hits (inc hits))
            (aget e 0))
        (do (set! misses (inc misses))
            nil))))

  (-admit! [_ addr row cost]
    ;; Only numeric addresses are cached; see -invalidate! for why.
    (when (number? addr)
      (when-some [old (.get entries addr)]
        (.delete entries addr)
        (set! total-cost (- total-cost (aget old 1))))
      (.set entries addr #js [row cost])
      (set! total-cost (+ total-cost cost))
      ;; Never evicts the row just admitted, even when it alone is over budget.
      (loop []
        (when (and (> (.-size entries) 1)
                   (or (> (.-size entries) entry-limit)
                       (> total-cost byte-limit)))
          (let [k (.-value (.next (.keys entries)))
                e (.get entries k)]
            (.delete entries k)
            (set! total-cost (- total-cost (aget e 1)))
            (set! evictions (inc evictions))
            (recur))))))

  (-invalidate! [this addr]
    (if (number? addr)
      (when-some [e (.get entries addr)]
        (.delete entries addr)
        (set! total-cost (- total-cost (aget e 1)))
        (set! invalidations (inc invalidations)))
      ;; A write under another representation of an address (a string or a
      ;; Long) could still alias a cached number: drop everything.
      (-clear! this)))

  (-clear! [_]
    (.clear entries)
    (set! total-cost 0))

  (-stats [_]
    {:entries (.-size entries)
     :bytes total-cost
     :hits hits
     :misses misses
     :evictions evictions
     :invalidations invalidations}))

(defn new-cache
  ([] (new-cache max-entries max-bytes))
  ([entry-limit byte-limit]
   (NodeCache. (js/Map.) entry-limit byte-limit 0 0 0 0 0)))

;; ---------------------------------------------------------------------------
;; One cache per SQLite handle

(defonce ^:private ^js caches (js/WeakMap.))

(defn cache-for
  "The cache of SQLite handle `db`, created on first use."
  [db]
  (or (.get caches db)
      (let [cache (new-cache)]
        (.set caches db cache)
        cache)))

(defn clear-for!
  "Drop every cached row of SQLite handle `db`. Call it after changing the
  handle's kvs rows other than through its storage, and when closing it."
  [db]
  (when-some [cache (when (some? db) (.get caches db))]
    (-clear! cache)))

(defn stats-for
  "Counters of the cache of SQLite handle `db`, nil when it has none."
  [db]
  (when-some [cache (when (some? db) (.get caches db))]
    (-stats cache)))

;; ---------------------------------------------------------------------------
;; DataScript storage with the cache in front

(defn- node-row?
  "Rows of index nodes and leaves. The root metadata (addr 0) and the tx tail
  (addr 1) are read once at open and are not worth a slot."
  [data]
  (and (map? data) (contains? data :keys)))

(defn- owned-row
  "A row the caller may keep. DataScript hands :addresses to the restored node
  as its mutable _addresses array, so a cached array is never handed out."
  [row]
  (let [addresses (:addresses row)]
    (if (array? addresses)
      (assoc row :addresses (.slice addresses))
      row)))

(defn cached-storage
  "A DataScript IStorage over kvs rows with `cache` in front.

  - `read-row`: (fn [addr]) -> [data chars] or nil when there is no row;
    `chars` is the length of the stored text and sizes the entry.
  - `write-rows!`: (fn [addr+data-seq delete-addrs]), the uncached -store.
  - `on-hit`: optional (fn []) called per cache hit, for perf counters."
  [cache {:keys [read-row write-rows! on-hit]}]
  (reify
    storage/IStorage
    (-store [_ addr+data-seq delete-addrs]
      (doseq [[addr _] addr+data-seq]
        (-invalidate! cache addr))
      (doseq [addr delete-addrs]
        (-invalidate! cache addr))
      (write-rows! addr+data-seq delete-addrs))

    (-restore [_ addr]
      (if-some [row (-hit cache addr)]
        (do (when on-hit (on-hit))
            (owned-row row))
        (when-some [[data chars] (read-row addr)]
          (when (node-row? data)
            (-admit! cache addr data (row-cost chars)))
          (owned-row data))))))
