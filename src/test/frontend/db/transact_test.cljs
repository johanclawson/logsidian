(ns frontend.db.transact-test
  (:require [cljs.test :refer [async deftest is testing]]
            [frontend.db.transact :as transact]
            [promesa.core :as p]))

(defn- <settlements
  "Calls worker-call with request-f and resolves, after a short wait, to every
   settlement of the returned promise as [:resolved value] or [:rejected error].
   A promise that never settles shows up as [] instead of hanging the test."
  [request-f]
  (let [*settled (atom [])]
    (-> (transact/worker-call request-f)
        (p/then (fn [v] (swap! *settled conj [:resolved v])))
        (p/catch (fn [e] (swap! *settled conj [:rejected e]))))
    (p/let [_ (p/delay 20)]
      @*settled)))

(deftest worker-call-settles-once-test
  (async done
    (-> (p/let [sync-throw (<settlements (fn [] (throw (js/Error. "worker gone"))))
                rejected (<settlements (fn [] (p/rejected (ex-info "request failed" {:k 1}))))
                rejected-error (<settlements (fn [] (p/rejected (js/Error. "plain error"))))
                ex-data-result (<settlements (fn [] (p/resolved {:ex-data {:type :boom}
                                                                 :ex-message "boom"})))
                success (<settlements (fn [] (p/resolved {:tx-data []})))
                nil-result (<settlements (fn [] (p/resolved nil)))]
          (testing "a synchronous throw while invoking the request rejects"
            (is (= 1 (count sync-throw)))
            (is (= :rejected (ffirst sync-throw)))
            (is (= "worker gone" (ex-message (second (first sync-throw))))))
          (testing "an asynchronous rejection rejects with its error"
            (is (= 1 (count rejected)))
            (is (= :rejected (ffirst rejected)))
            (is (= {:k 1} (ex-data (second (first rejected)))))
            (is (= [:rejected "plain error"]
                   (let [[kind e] (first rejected-error)] [kind (ex-message e)]))))
          (testing "a failure result from the worker (:ex-data) rejects with that result"
            (is (= [[:rejected {:ex-data {:type :boom} :ex-message "boom"}]] ex-data-result)))
          (testing "a success resolves once with the result"
            (is (= [[:resolved {:tx-data []}]] success))
            (is (= [[:resolved nil]] nil-result))))
        (p/catch (fn [e] (is false (str "unexpected: " e))))
        (p/finally (fn [] (done))))))
