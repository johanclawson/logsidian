(ns frontend.fs.watcher-handler-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.fs.watcher-handler :as watcher-handler]))

(deftest plan-id-repairs-test
  (let [journal {:ref-id "a" :path "journals/2026_09_12.md"}
        page {:ref-id "b" :path "pages/project.md"}
        no-file {:ref-id "c" :path nil}
        repairs [journal page no-file]]
    (testing "a clean run repairs every block"
      (is (= {:run repairs :defer []}
             (watcher-handler/plan-id-repairs repairs {:failed-paths #{} :clean? true}))))
    (testing "a repair waits when its file failed in the run: the db may lack that file's offline edits"
      (is (= {:run [journal] :defer [page no-file]}
             (watcher-handler/plan-id-repairs repairs {:failed-paths #{"pages/project.md"}
                                                       :clean? false}))))
    (testing "a repair without a known file waits whenever the run was not clean"
      (is (= {:run [journal page] :defer [no-file]}
             (watcher-handler/plan-id-repairs repairs {:failed-paths #{"pages/other.md"}
                                                       :clean? false}))))
    (testing "nil failed-paths blocks nothing"
      (is (= {:run repairs :defer []}
             (watcher-handler/plan-id-repairs repairs {:clean? true}))))
    (testing "no repairs"
      (is (= {:run [] :defer []}
             (watcher-handler/plan-id-repairs [] {:failed-paths #{} :clean? true}))))))
