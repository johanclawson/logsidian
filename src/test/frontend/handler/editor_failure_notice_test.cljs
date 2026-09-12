(ns frontend.handler.editor-failure-notice-test
  (:require [cljs.test :refer [is]]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.notification :as notification]
            [frontend.test.helper :as test-helper :include-macros true :refer [deftest-async]]
            [frontend.util :as util]
            [promesa.core :as p]))

(defn- <with-notices
  "Runs (f), which returns a promise, with notification/show! recording into
   the atom it is passed, and restores show! once the promise settles."
  [f]
  (let [original notification/show!
        *notices (atom [])]
    (set! notification/show! (fn [& args] (swap! *notices conj args) nil))
    (-> (f *notices)
        (p/finally (fn [& _] (set! notification/show! original))))))

(deftest-async with-failure-notice-test
  (<with-notices
   (fn [*notices]
     (p/let [ok (editor-handler/<with-failure-notice! "Doing it" #(p/resolved :done))
             _ (is (= :done ok))
             _ (is (= [] @*notices) "a success shows nothing")
             rejected (editor-handler/<with-failure-notice!
                       "Doing it" #(p/rejected {:ex-data {:type :boom} :ex-message "worker failed"}))
             _ (is (nil? rejected) "a rejection resolves to nil, no unhandled rejection")
             _ (is (= 1 (count @*notices)))
             _ (is (= [:error] (rest (last @*notices))))
             _ (is (= "Doing it failed: worker failed" (ffirst @*notices)))
             thrown (editor-handler/<with-failure-notice! "Doing it" #(throw (js/Error. "worker gone")))]
       (is (nil? thrown) "a synchronous throw too")
       (is (= "Doing it failed: worker gone" (first (last @*notices))))))))

(deftest-async copy-block-ref-copies-only-persisted-ids-test
  (let [originals [editor-handler/set-blocks-id! editor-handler/save-current-block! util/copy-to-clipboard!]
        *copied (atom [])
        *fail? (atom false)]
    (set! editor-handler/save-current-block! (fn [& _] nil))
    (set! editor-handler/set-blocks-id! (fn [_ids]
                                          (if @*fail?
                                            (p/rejected (js/Error. "worker gone"))
                                            (p/resolved nil))))
    (set! util/copy-to-clipboard! (fn [text & _] (swap! *copied conj text)))
    (-> (<with-notices
         (fn [*notices]
           (p/do!
            (reset! *fail? true)
            (editor-handler/copy-block-ref! #uuid "11111111-1111-1111-1111-111111111111")
            (is (= [] @*copied) "nothing is copied when the id could not be persisted")
            (is (= 1 (count @*notices)) "the user is told")

            (reset! *fail? false)
            (editor-handler/copy-block-ref! #uuid "11111111-1111-1111-1111-111111111111")
            (is (= ["11111111-1111-1111-1111-111111111111"] @*copied)))))
        (p/finally (fn [& _]
                     (let [[set-blocks-id! save-current-block! copy!] originals]
                       (set! editor-handler/set-blocks-id! set-blocks-id!)
                       (set! editor-handler/save-current-block! save-current-block!)
                       (set! util/copy-to-clipboard! copy!)))))))
