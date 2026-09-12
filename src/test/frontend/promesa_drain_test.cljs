(ns frontend.promesa-drain-test
  "Regression test for the promesa 11.0.678 drain bug patched in
  src/main/promesa/impl/promise.js: a handler that throws must not reject
  unrelated promise chains that share the same drain (every p/let starts
  from the shared resolved-nil promise, so chains started in one tick do)."
  (:require [cljs.test :refer [deftest is async]]
            [promesa.core :as p]))

(deftest a-throwing-chain-does-not-reject-its-neighbours
  (async done
    (let [;; three chains started in the same tick: the middle one throws
          ;; synchronously in its first step
          a (p/let [_ nil] :a)
          b (p/let [_ nil] (throw (ex-info "only b fails" {:chain :b})))
          c (p/let [_ nil] :c)
          settle (fn [pr] (-> pr
                              (p/then (fn [v] {:ok v}))
                              (p/catch (fn [e] {:error (ex-message e)}))))]
      (-> (p/all [(settle a) (settle b) (settle c)])
          (p/then (fn [[ra rb rc]]
                    (is (= {:ok :a} ra) "a chain before the throwing one resolves")
                    (is (= {:error "only b fails"} rb) "the throwing chain gets its own error")
                    (is (= {:ok :c} rc) "a chain after the throwing one is not rejected with b's error")))
          (p/catch (fn [e] (is false (str "unexpected: " (ex-message e)))))
          (p/finally (fn [& _] (done)))))))

(deftest a-catch-after-a-sync-throw-catches
  (async done
    (let [caught (-> (p/do (throw (ex-info "sync throw" {})))
                     (p/catch (fn [e] (ex-message e))))
          neighbour (p/let [_ nil] :neighbour)]
      (-> (p/all [caught neighbour])
          (p/then (fn [[c n]]
                    (is (= "sync throw" c) "p/catch catches a synchronous throw inside p/do")
                    (is (= :neighbour n))))
          (p/catch (fn [e] (is false (str "unexpected: " (ex-message e)))))
          (p/finally (fn [& _] (done)))))))
