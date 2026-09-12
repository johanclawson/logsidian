(ns frontend.fs.watcher-handler-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.db.async :as db-async]
            [frontend.fs :as fs]
            [frontend.handler.file-based.file :as file-handler]
            [frontend.fs.watcher-handler :as watcher-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.page :as page-handler]
            [frontend.test.helper :as test-helper :include-macros true :refer [deftest-async]]
            [promesa.core :as p]))

(defn- probe-of
  "A probe for <await-presence resolving to states in turn, and its call count."
  [states]
  (let [*calls (atom 0)
        *states (atom states)]
    [*calls (fn []
              (swap! *calls inc)
              (let [state (first @*states)]
                (swap! *states rest)
                (p/resolved state)))]))

(deftest-async await-presence-test
  (p/let [back (let [[calls probe] (probe-of [:missing :missing :present :missing])]
                 (p/let [state (watcher-handler/<await-presence probe [0 0 0 0])]
                   [state @calls]))
          gone (let [[calls probe] (probe-of (repeat :missing))]
                 (p/let [state (watcher-handler/<await-presence probe [0 0 0])]
                   [state @calls]))
          failed (-> (watcher-handler/<await-presence #(p/rejected (js/Error. "EACCES: permission denied")) [0 0])
                     (p/then (constantly :resolved))
                     (p/catch (constantly :rejected)))]
    (is (= [:present 3] back) "a file back after two misses is present, probed no further")
    (is (= [:missing 4] gone) "one probe, then one per wait")
    (is (= :rejected failed) "a failure other than absence rejects")))

(deftest-async reparse-from-disk-never-deletes-test
  (let [originals [fs/<path-state page-handler/<delete! fs/unlink! notification/show! config/get-repo-dir]
        restore! (fn [& _]
                   (let [[path-state delete unlink show get-repo-dir] originals]
                     (set! fs/<path-state path-state)
                     (set! page-handler/<delete! delete)
                     (set! fs/unlink! unlink)
                     (set! notification/show! show)
                     (set! config/get-repo-dir get-repo-dir)))
        *probes (atom 0)
        *deletions (atom [])
        *notices (atom [])]
    (set! config/get-repo-dir (constantly "/tmp/graph"))
    (set! page-handler/<delete! (fn [& args] (swap! *deletions conj [:delete-page args]) (p/resolved nil)))
    (set! fs/unlink! (fn [& args] (swap! *deletions conj [:unlink args]) (p/resolved nil)))
    (set! notification/show! (fn [& args] (swap! *notices conj args) nil))
    (-> (p/do!
         (set! fs/<path-state (fn [_ _] (swap! *probes inc) (p/resolved :missing)))
         (watcher-handler/<reparse-from-disk! "logseq_local_/tmp/graph" "pages/a.md" :waits-ms [0 0 0])
         (is (= 4 @*probes) "looked for the file again after each wait")
         (is (= [] @*deletions) "a file still missing deletes neither the page nor the path")
         (is (= 1 (count @*notices)) "and says the db was kept")

         (reset! *notices [])
         (set! fs/<path-state (fn [_ _] (p/rejected (js/Error. "EACCES: permission denied, stat '/tmp/graph/pages/a.md'"))))
         (watcher-handler/<reparse-from-disk! "logseq_local_/tmp/graph" "pages/a.md" :waits-ms [0])
         (is (= [] @*deletions) "a stat error is not absence and deletes nothing")
         (is (= 1 (count @*notices))))
        (p/finally restore!))))

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

(deftest-async reparse-after-refusal-keeps-edits-the-reset-replaces-test
  (let [originals [config/get-repo-dir fs/<path-state fs/stat fs/read-file db-async/<get-file
                   file-handler/alter-file ipc/ipc notification/show!]
        restore! (fn [& _]
                   (let [[get-repo-dir path-state stat read-file get-file alter-file ipc-f show] originals]
                     (set! config/get-repo-dir get-repo-dir)
                     (set! fs/<path-state path-state)
                     (set! fs/stat stat)
                     (set! fs/read-file read-file)
                     (set! db-async/<get-file get-file)
                     (set! file-handler/alter-file alter-file)
                     (set! ipc/ipc ipc-f)
                     (set! notification/show! show)))
        snapshot-copy "/tmp/graph/logseq/bak/conflicts/pages/a/snapshot.Desktop.md"
        refusal {:reason "pages/a.md was changed on disk since the app last read it"
                 :copy-path "/tmp/graph/logseq/bak/conflicts/pages/a/proposal.Desktop.md"
                 :proposal "- proposal\n"}
        *snapshot (atom nil)
        *alter-opts (atom nil)
        *copies (atom [])
        *notices (atom [])
        reparse! #(watcher-handler/<reparse-from-disk! "logseq_local_/tmp/graph" "pages/a.md"
                                                       :waits-ms [] :refusal refusal)]
    (set! config/get-repo-dir (constantly "/tmp/graph"))
    (set! fs/<path-state (fn [_ _] (p/resolved :present)))
    (set! fs/stat (fn [& _] (p/resolved {:mtime 1 :ctime 1})))
    (set! fs/read-file (fn [& _] (p/resolved "- external\n")))
    (set! db-async/<get-file (fn [& _] (p/resolved "- proposal\n")))
    (set! file-handler/alter-file (fn [_repo _path _content opts]
                                    (reset! *alter-opts opts)
                                    (p/resolved {:tx [] :snapshot @*snapshot})))
    (set! ipc/ipc (fn [& args] (swap! *copies conj (vec args)) (p/resolved snapshot-copy)))
    (set! notification/show! (fn [& args] (swap! *notices conj args) nil))
    (-> (p/do!
         (reset! *snapshot "- proposal\n- typed meanwhile\n")
         (reparse!)
         (is (true? (:snapshot-before? @*alter-opts)) "the reset snapshots the page first")
         (is (true? (:from-disk? @*alter-opts)))
         (is (= [["backupConflictFile" "/tmp/graph" "pages/a.md" "- proposal\n- typed meanwhile\n"]] @*copies)
             "edits the reset replaces become a second conflict copy")
         (is (= 1 (count @*notices)) "one warning")
         (is (string/includes? (ffirst @*notices) (:copy-path refusal)))
         (is (string/includes? (ffirst @*notices) snapshot-copy) "naming both copies")

         (reset! *copies [])
         (reset! *notices [])
         (reset! *snapshot "- proposal\n")
         (reparse!)
         (is (= [] @*copies) "a snapshot equal to the refused proposal is not copied again")
         (is (not (string/includes? (ffirst @*notices) snapshot-copy)))

         (reset! *notices [])
         (reset! *snapshot "- external\n")
         (reparse!)
         (is (= [] @*copies) "nor one equal to the disk"))
        (p/finally restore!))))

(deftest refusal-notice-test
  (let [refusal {:reason "r" :copy-path "/c1.md" :proposal "p"}]
    (is (= "Your change was not saved: r. Your version was saved to /c1.md. The app now shows the file as it is on disk."
           (watcher-handler/refusal-notice refusal nil nil)))
    (is (string/includes? (watcher-handler/refusal-notice refusal "/c2.md" nil) "/c2.md"))
    (is (string/includes? (watcher-handler/refusal-notice refusal nil "gone") "nothing was deleted"))))
