(ns frontend.handler.file-based.file-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.fs :as fs]
            [frontend.handler.file-based.file :as file-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.test.helper :as test-helper :include-macros true :refer [deftest-async]]
            [promesa.core :as p]))

(deftest apply-written-content-test
  (testing "content read from disk applies"
    (is (true? (file-handler/apply-written-content? true nil)))
    (is (true? (file-handler/apply-written-content? true #js {:result "mismatch"}))))
  (testing "a guarded write applies only when written"
    (is (true? (file-handler/apply-written-content? false #js {:result "written" :mtime 1})))
    (is (false? (file-handler/apply-written-content? false #js {:result "mismatch" :disk "present"})))
    (is (false? (file-handler/apply-written-content? false #js {:result "exists"})))
    (is (false? (file-handler/apply-written-content? false #js {:result "io-error" :error "ENOSPC"}))))
  (testing "a write that reports no outcome (skip-compare?, browser backends) applies, as before"
    (is (true? (file-handler/apply-written-content? false nil)))
    (is (true? (file-handler/apply-written-content? false #js {:size 3 :mtime 1})))))

(deftest-async alter-file-applies-custom-css-only-when-written-test
  (let [originals [db/get-file db/set-file-content! fs/write-plain-text-file!
                   ui-handler/add-style-if-exists! config/get-repo-dir]
        restore! (fn [& _]
                   (let [[get-file set-file-content! write! add-style! get-repo-dir] originals]
                     (set! db/get-file get-file)
                     (set! db/set-file-content! set-file-content!)
                     (set! fs/write-plain-text-file! write!)
                     (set! ui-handler/add-style-if-exists! add-style!)
                     (set! config/get-repo-dir get-repo-dir)))
        *db-contents (atom [])
        *styles (atom 0)
        *write-result (atom nil)
        *write-opts (atom nil)]
    (set! db/get-file (constantly "old css"))
    (set! db/set-file-content! (fn [_repo _path content & _]
                                 (swap! *db-contents conj content)
                                 (p/resolved nil)))
    (set! fs/write-plain-text-file! (fn [_repo _dir _path _content opts]
                                      (reset! *write-opts opts)
                                      (p/resolved @*write-result)))
    (set! ui-handler/add-style-if-exists! (fn [] (swap! *styles inc)))
    (set! config/get-repo-dir (constantly "/tmp/graph"))
    (-> (p/do!
         (reset! *write-result #js {:result "mismatch" :disk "present"})
         (file-handler/alter-file "repo" "logseq/custom.css" "refused css" {:reset? false})
         (is (= "old css" (:old-content @*write-opts)) "a guarded write against the content before the change")
         (is (= ["refused css"] @*db-contents) "a refused write's content is not transacted a second time")
         (is (= 0 @*styles) "and not loaded as the app's style; the reparse from disk applies the disk's")

         (reset! *db-contents [])
         (reset! *write-result #js {:result "io-error" :error "ENOSPC"})
         (file-handler/alter-file "repo" "logseq/custom.css" "unsaved css" {:reset? false})
         (is (= ["unsaved css"] @*db-contents))
         (is (= 0 @*styles) "an io-error applies nothing either")

         (reset! *db-contents [])
         (reset! *write-result #js {:result "written" :mtime 1})
         (file-handler/alter-file "repo" "logseq/custom.css" "new css" {:reset? false})
         (is (= ["new css" "new css"] @*db-contents))
         (is (= 1 @*styles) "a written file is applied"))
        (p/finally restore!))))
