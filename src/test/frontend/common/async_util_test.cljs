(ns frontend.common.async-util-test
  (:require [cljs.test :refer [is]]
            [frontend.common.async-util :as async-util]
            [frontend.test.helper :as test-helper :include-macros true :refer [deftest-async]]
            [promesa.core :as p]))

(deftest-async map-bounded-caps-in-flight-and-keeps-order-test
  (let [*active (atom 0)
        *max-active (atom 0)
        *started (atom [])
        items (range 20)
        f (fn [x]
            (swap! *started conj x)
            (swap! *max-active max (swap! *active inc))
            ;; uneven delays, so calls settle out of input order
            (p/let [_ (p/delay (mod (* x 7) 5))]
              (swap! *active dec)
              (* 10 x)))]
    (p/let [results (async-util/<map-bounded 3 f items)]
      (is (= (mapv #(* 10 %) items) results) "results are in input order")
      (is (= 3 @*max-active) "never more than 3 calls in flight, and the cap is used")
      (is (= (vec items) (sort @*started)) "every item is processed exactly once")
      (is (zero? @*active) "resolves only after every call has settled"))))

(deftest-async map-bounded-failure-does-not-stop-others-test
  (let [*called (atom #{})
        f (fn [x]
            (swap! *called conj x)
            (case x
              3 (p/rejected (ex-info "boom" {:x x}))
              5 (throw (ex-info "sync boom" {:x x}))
              (p/let [_ (p/delay 1)] x)))]
    (p/let [results (async-util/<map-bounded 2 f (range 10))]
      (is (= (set (range 10)) @*called) "items after the failures still run")
      (is (= [0 1 2 4 6 7 8 9]
             (vec (keep-indexed (fn [i v] (when-not (#{3 5} i) v)) results)))
          "the other results are unaffected and in order")
      (is (= "boom" (ex-message (nth results 3))) "a rejection's slot holds its error")
      (is (= "sync boom" (ex-message (nth results 5))) "a synchronous throw's slot holds its error"))))

(deftest-async map-bounded-edge-cases-test
  (p/let [empty-result (async-util/<map-bounded 4 inc [])
          nil-result (async-util/<map-bounded 4 inc nil)
          plain-values (async-util/<map-bounded 16 inc [1 2 3])
          zero-n (async-util/<map-bounded 0 inc [1 2])]
    (is (= [] empty-result) "empty input resolves to []")
    (is (= [] nil-result) "nil input resolves to []")
    (is (= [2 3 4] plain-values) "f may return plain values; n above the count is fine")
    (is (= [2 3] zero-n) "n below 1 runs one call at a time")))
