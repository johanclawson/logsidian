(ns logseq.graph-parser.reparse-identity-test
  "parse-file of a file that is already in the db, as the app's reset-file! does it"
  (:require [cljs.test :refer [deftest is]]
            [datascript.core :as d]
            [logseq.graph-parser :as graph-parser]
            [logseq.graph-parser.db :as gp-db]))

(defn- reparse!
  "Parses like frontend.worker.file.reset/reset-file!: the previous version's
  blocks are deleted or kept by get-blocks-to-delete, uuids are reused"
  [conn file-path content & {:keys [reuse-uuids?] :or {reuse-uuids? true}}]
  (graph-parser/parse-file conn file-path content
                           {:delete-blocks-fn (fn [page path retain-blocks]
                                                (graph-parser/get-blocks-to-delete @conn page path retain-blocks))
                            :extract-options {:verbose false :reuse-uuids? reuse-uuids?}}))

(defn- page-blocks
  [db page-name]
  (map #(d/entity db %)
       (d/q '[:find [?b ...] :in $ ?name :where [?p :block/name ?name] [?b :block/page ?p]]
            db page-name)))

(defn- by-title
  "The blocks of the page, by title (test contents have no duplicate titles)"
  [db page-name]
  (into {} (map (juxt :block/title identity)) (page-blocks db page-name)))

(deftest reparse-keeps-block-identity
  (let [conn (gp-db/start-conn)
        _ (reparse! conn "pages/foo.md" "- a [[bar]]\n- b\n  - b1\n- c\n- d")
        db-before @conn
        before (by-title db-before "foo")
        ;; link removed from a, b edited, c deleted, a child added to d
        {:keys [tx]} (reparse! conn "pages/foo.md" "- a\n- b edited\n  - b1\n- d\n  - new child")
        after (by-title @conn "foo")]
    (is (= #{"a" "b edited" "b1" "d" "new child"} (set (keys after))))
    (doseq [[old-title new-title] {"a [[bar]]" "a", "b" "b edited", "b1" "b1", "d" "d"}]
      (is (= (:block/uuid (before old-title)) (:block/uuid (after new-title))) (str old-title " keeps its uuid"))
      (is (= (:db/id (before old-title)) (:db/id (after new-title))) (str old-title " keeps its entity")))
    (is (= (:db/id (after "b edited")) (:db/id (:block/parent (after "b1")))) "b1 is still b's child")
    (is (= (:db/id (after "d")) (:db/id (:block/parent (after "new child")))))
    (is (contains? (set (map :block/name (:block/refs (before "a [[bar]]")))) "bar"))
    (is (not-any? #(= "bar" (:block/name %)) (:block/refs (after "a")))
        "the removed link is gone from the kept block's refs")
    (is (= [[:db.fn/retractEntity (:db/id (before "c"))]]
           (filter #(= :db.fn/retractEntity (first %)) tx))
        "only the deleted block is retracted")
    (let [attr-retracts (filter #(= :db.fn/retractAttribute (first %)) tx)]
      (is (some #{[:db.fn/retractAttribute (:db/id (before "a [[bar]]")) :block/refs]} attr-retracts))
      (is (every? (fn [[_ eid attr]] (some? (get (d/entity db-before eid) attr))) attr-retracts)
          "only attributes the old entity has are retracted"))))

(deftest reparse-clears-collapsed-when-the-file-drops-it
  (let [conn (gp-db/start-conn)
        _ (reparse! conn "pages/foo.md" "- a\ncollapsed:: true\n  - a1")
        a1 (d/entity @conn [:block/uuid (:block/uuid ((by-title @conn "foo") "a1"))])
        a-uuid (:block/uuid (:block/parent a1))
        _ (is (true? (:block/collapsed? (d/entity @conn [:block/uuid a-uuid]))))
        _ (reparse! conn "pages/foo.md" "- a\n  - a1")]
    (is (= a-uuid (:block/uuid ((by-title @conn "foo") "a"))) "a is kept, as an edit")
    (is (not (:block/collapsed? (d/entity @conn [:block/uuid a-uuid]))))))

(deftest external-append-keeps-every-prior-block
  (let [conn (gp-db/start-conn)
        path "journals/2026_09_12.md"
        _ (reparse! conn path "- call Bob\n- notes\n  - details")
        page-name (:block/name (first (:block/_file (d/entity @conn [:file/path path]))))
        before (by-title @conn page-name)
        {:keys [tx]} (reparse! conn path "- call Bob\n- notes\n  - details\n- agent: done")
        after (by-title @conn page-name)]
    (is (some? page-name))
    (doseq [title ["call Bob" "notes" "details"]]
      (is (= (:block/uuid (before title)) (:block/uuid (after title))) title)
      (is (= (:db/id (before title)) (:db/id (after title))) title))
    (is (contains? after "agent: done"))
    (is (empty? (filter #(= :db.fn/retractEntity (first %)) tx)) "nothing is retracted")))

(deftest reparse-after-a-rename
  (let [conn (gp-db/start-conn)
        _ (reparse! conn "pages/foo.md" "- a\n- b")
        before (by-title @conn "foo")
        _ (reparse! conn "pages/foo.md" "title:: zed\n\n- a\n- b")
        db-renamed @conn
        renamed (by-title db-renamed "zed")
        _ (reparse! conn "pages/foo.md" "title:: zed\n\n- a\n- b")
        after (by-title @conn "zed")]
    (is (some? (renamed "a")))
    (is (not= (:block/uuid (before "a")) (:block/uuid (renamed "a"))) "the rename itself reuses nothing")
    (is (not= (:block/uuid (before "b")) (:block/uuid (renamed "b"))))
    (is (empty? (page-blocks @conn "foo")) "the old page's blocks are gone")
    (is (= (:block/uuid (renamed "a")) (:block/uuid (after "a")))
        "the next re-parse reuses the renamed page's blocks, though the old page is still bound to the file")
    (is (= (count (page-blocks db-renamed "zed")) (count (page-blocks @conn "zed")))
        "and duplicates none")))

(deftest reparse-without-reuse-gives-fresh-uuids
  (let [conn (gp-db/start-conn)
        _ (reparse! conn "pages/foo.md" "- a" :reuse-uuids? false)
        before (by-title @conn "foo")
        _ (reparse! conn "pages/foo.md" "- a" :reuse-uuids? false)]
    (is (not= (:block/uuid (before "a")) (:block/uuid ((by-title @conn "foo") "a")))
        "callers that do not ask for it (initial load, import, cli) keep the old behaviour")))
