(ns electron.backup-file-test
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as node-path]
            [cljs.test :refer [deftest is testing]]
            [electron.backup-file :as backup-file]))

(defn- text [p] (.toString (fs/readFileSync p)))

(defn- files-under
  "Every file below dir, as paths relative to dir, sorted."
  [dir]
  (letfn [(walk [d]
            (mapcat (fn [^js entry]
                      (let [p (node-path/join d (.-name entry))]
                        (cond
                          (.isDirectory entry) (walk p)
                          (.isFile entry) [(node-path/relative dir p)]
                          :else [])))
                    (fs/readdirSync d #js {:withFileTypes true})))]
    (if (fs/existsSync dir)
      (vec (sort (walk dir)))
      [])))

(defn- with-graph-dir
  [f]
  (let [dir (fs/mkdtempSync (node-path/join (os/tmpdir) "backup-file-"))]
    (try
      (f dir)
      (finally
        (fs/rmSync dir #js {:recursive true :force true})))))

(deftest conflict-copy-location-test
  (with-graph-dir
    (fn [repo]
      (let [copy (backup-file/create-conflict-copy! repo "pages/foo.md" ".md" "- proposed\n")]
        (testing "the copy lives in the conflict tree, under the page's path"
          (is (= (node-path/join repo "logseq/bak/conflicts/pages/foo")
                 (node-path/dirname copy)))
          (is (.endsWith copy ".Desktop.md"))
          (is (= "- proposed\n" (text copy))))
        (testing "not in the ordinary backup directory of the page"
          (is (= [] (files-under (node-path/join repo "logseq/bak/pages/foo")))))))))

(deftest conflict-copy-never-replaces-test
  (with-graph-dir
    (fn [repo]
      (let [now (js/Date. "2026-09-12T10:00:00.000Z")
            *suffixes (atom ["same" "same" "other"])
            next-suffix #(let [s (first @*suffixes)] (swap! *suffixes rest) s)
            first-copy (backup-file/create-conflict-copy! repo "pages/foo.md" ".md" "- first\n"
                                                           :now (constantly now) :suffix-fn next-suffix)
            second-copy (backup-file/create-conflict-copy! repo "pages/foo.md" ".md" "- second\n"
                                                            :now (constantly now) :suffix-fn next-suffix)]
        (testing "a name collision in the same millisecond retries with a new suffix"
          (is (not= first-copy second-copy))
          (is (.includes second-copy ".other.")))
        (testing "the earlier copy keeps its content"
          (is (= "- first\n" (text first-copy)))
          (is (= "- second\n" (text second-copy))))
        (testing "only collisions retry; they stop after a bound"
          (is (thrown? js/Error
                       (backup-file/create-conflict-copy! repo "pages/foo.md" ".md" "- third\n"
                                                          :now (constantly now)
                                                          :suffix-fn (constantly "same")))))
        (is (= 2 (count (files-under (node-path/join repo "logseq/bak/conflicts")))))))))

(deftest ordinary-backups-never-prune-conflict-copies-test
  (with-graph-dir
    (fn [repo]
      (let [copies (doall
                    (for [i (range 8)]
                      (backup-file/create-conflict-copy! repo "pages/foo.md" ".md" (str "- conflict " i "\n"))))
            other-dir-copies (doall
                              (for [d ["a" "b" "c" "d" "e" "f" "g"]]
                                (backup-file/create-conflict-copy! repo (str d "/x.md") ".md" "- x\n")))]
        (testing "many ordinary backups of the same page leave its conflict copies"
          (dotimes [i 8]
            (backup-file/backup-file repo :backup-dir "pages/foo.md" ".md" (str "- backup " i "\n")))
          (is (every? #(fs/existsSync %) copies)))
        (testing "an ordinary backup whose directory is the conflict tree (a root file conflicts.md) prunes nothing there"
          (dotimes [i 8]
            (backup-file/backup-file repo :backup-dir "conflicts.md" ".md" (str "- c " i "\n")))
          (is (every? #(fs/existsSync %) (concat copies other-dir-copies))))))))

(deftest ordinary-pruning-keeps-directories-test
  (with-graph-dir
    (fn [repo]
      (testing "the backups of a root file pages.md do not prune the backup directories of pages/*"
        (let [page-backups (doall
                            (for [i (range 8)]
                              (backup-file/backup-file repo :backup-dir (str "pages/p" i ".md") ".md" "- p\n")))]
          (dotimes [i 8]
            (backup-file/backup-file repo :backup-dir "pages.md" ".md" (str "- root " i "\n")))
          (is (every? #(fs/existsSync %) page-backups))
          (is (<= (->> (fs/readdirSync (node-path/join repo "logseq/bak/pages") #js {:withFileTypes true})
                       (filter #(.isFile ^js %))
                       count)
                  6)
              "files still keep only the latest 6"))))))

(deftest in-conflict-dir-test
  (let [repo "/g"]
    (is (backup-file/in-conflict-dir? repo "/g/logseq/bak/conflicts"))
    (is (backup-file/in-conflict-dir? repo "/g/logseq/bak/conflicts/pages/foo"))
    (is (backup-file/in-conflict-dir? repo "/g/logseq/bak/conflicts/..x"))
    (is (not (backup-file/in-conflict-dir? repo "/g/logseq/bak/pages/foo")))
    (is (not (backup-file/in-conflict-dir? repo "/g/logseq/bak/conflictsx")))
    (is (not (backup-file/in-conflict-dir? repo "/g/logseq/bak")))))
