(ns frontend.common.cache-test
  (:require [cljs.cache :as cache]
            [cljs.test :refer [deftest is testing]]
            [frontend.common.cache :as common.cache]))

(defn- through-all
  "Runs `ks` through cache `c` in order, caching (f k) on a miss."
  [c f ks]
  (reduce (fn [c k] (cache/through f c k)) c ks))

(defn- cached-keys [c ks]
  (filterv #(cache/has? c %) ks))

(deftest lru-keeps-threshold
  (let [c (through-all (common.cache/lru-cache-factory {} :threshold 3) str (range 10))]
    (is (= 3 (count c)))
    (is (= [7 8 9] (cached-keys c (range 10))))
    (is (= "9" (cache/lookup c 9))))
  (testing "default threshold is 32, like cljs.cache"
    (is (= 32 (count (through-all (common.cache/lru-cache-factory {}) str (range 100)))))))

(deftest lru-evicts-least-recently-used
  (let [c (through-all (common.cache/lru-cache-factory {} :threshold 3) str
                       [:a :b :c
                        :a ;; hit: :b is now the least recently used
                        :d])]
    (is (= [:a :c :d] (cached-keys c [:a :b :c :d])))
    (let [c (through-all c str [:c :e])] ;; :a is least recently used
      (is (= [:c :d :e] (cached-keys c [:a :b :c :d :e])))))
  (testing "evict removes an entry and frees its slot"
    (let [c (-> (common.cache/lru-cache-factory {} :threshold 2)
                (through-all str [:a :b])
                (cache/evict :a)
                (through-all str [:c]))]
      (is (= [:b :c] (cached-keys c [:a :b :c])))
      (is (= 2 (count c))))))

(deftest lru-value-keys-and-nil-values
  (let [c (through-all (common.cache/lru-cache-factory {} :threshold 4)
                       (constantly nil)
                       [[:markdown "a"]])]
    (is (cache/has? c [:markdown (str "a")]) "equal (not identical) keys hit")
    (is (nil? (cache/lookup c [:markdown "a"])) "nil values are cached")
    (is (= ::none (cache/lookup c [:markdown "b"] ::none)))))

(deftest cache-fn-with-lru
  (let [calls (atom [])
        *cache (volatile! (common.cache/lru-cache-factory {} :threshold 2))
        f (common.cache/cache-fn
           *cache
           (fn [format content] [[format content] [format content]])
           (fn [format content]
             (swap! calls conj [format content])
             (when-not (= content "nil") (str (name format) ":" content))))]
    (is (= "markdown:a" (f :markdown "a")))
    (is (= "markdown:a" (f :markdown "a")))
    (is (nil? (f :markdown "nil")))
    (is (nil? (f :markdown "nil")))
    (is (= [[:markdown "a"] [:markdown "nil"]] @calls) "hits don't call f")
    (is (= "org:a" (f :org "a")) "a third key evicts the least recently used")
    (is (= "markdown:a" (f :markdown "a")))
    (is (= [[:markdown "a"] [:markdown "nil"] [:org "a"] [:markdown "a"]] @calls))))

(deftest lru-matches-cljs-cache-lru
  (testing "same entries and values as cljs.cache's LRU over a pseudo-random sequence of through/evict"
    (let [domain (vec (concat (range 10) (map (fn [i] [:markdown (str "c" i)]) (range 10))))
          ;; Park-Miller LCG: deterministic and exact in doubles
          *seed (atom 42)
          rand-int! (fn [n] (mod (swap! *seed #(mod (* % 16807) 2147483647)) n))
          snapshot (fn [c] [(count c) (mapv #(cache/lookup c % ::none) domain)])]
      (is (nil?
           (loop [i 0
                  expected (cache/lru-cache-factory {} :threshold 5)
                  actual (common.cache/lru-cache-factory {} :threshold 5)]
             (when (< i 3000)
               (let [k (nth domain (rand-int! (count domain)))
                     evict? (zero? (rand-int! 8))
                     value-fn (fn [x] [x i])
                     expected (if evict? (cache/evict expected k) (cache/through value-fn expected k))
                     actual (if evict? (cache/evict actual k) (cache/through value-fn actual k))]
                 (if (= (snapshot expected) (snapshot actual))
                   (recur (inc i) expected actual)
                   {:step i :key k :evict? evict?
                    :expected (snapshot expected) :actual (snapshot actual)})))))))))
