(ns frontend.fs.watcher-handler-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.fs.watcher-handler :as watcher-handler]))

(deftest plan-id-repairs-test
  (let [journal {:ref-id "a" :path "journals/2026_09_12.md"}
        page {:ref-id "b" :path "pages/project.md"}
        no-file {:ref-id "c" :path nil}
        repairs [journal page no-file]]
    (testing "a clean run repairs every block whose file is known"
      (is (= {:run [journal page] :defer [no-file]}
             (watcher-handler/plan-id-repairs repairs {:failed-paths #{}}))))
    (testing "a repair waits when its file failed in the run: the db may lack that file's offline edits"
      (is (= {:run [journal] :defer [page no-file]}
             (watcher-handler/plan-id-repairs repairs {:failed-paths #{"pages/project.md"}}))))
    (testing "a repair without a known file always waits, a clean run included (review finding 3)"
      (is (= {:run [] :defer [no-file]}
             (watcher-handler/plan-id-repairs [no-file] {:failed-paths #{} :clean? true})))
      (is (= {:run [journal page] :defer [no-file]}
             (watcher-handler/plan-id-repairs repairs {:failed-paths #{"pages/other.md"}
                                                       :clean? false}))))
    (testing "nil failed-paths blocks nothing with a known file"
      (is (= {:run [journal page] :defer [no-file]}
             (watcher-handler/plan-id-repairs repairs {}))))
    (testing "no repairs"
      (is (= {:run [] :defer []}
             (watcher-handler/plan-id-repairs [] {:failed-paths #{}}))))))

(deftest reconcile-file-bound-test
  (testing "eager fan-out by default: every file at once"
    (is (= 555 (watcher-handler/reconcile-file-bound 555 false)))
    (is (= 10000 (watcher-handler/reconcile-file-bound 10000 false))))
  (testing "the H2 experiment flag caps the file phase at 16"
    (is (= 16 (watcher-handler/reconcile-file-bound 555 true)))
    (is (= 5 (watcher-handler/reconcile-file-bound 5 true))))
  (testing "never below 1, so <map-bounded always makes progress"
    (is (= 1 (watcher-handler/reconcile-file-bound 0 false)))
    (is (= 1 (watcher-handler/reconcile-file-bound 0 true))))
  (testing "the flag is off unless set (the node test build sets no localStorage flag)"
    (is (= 555 (watcher-handler/reconcile-file-bound 555)))))
