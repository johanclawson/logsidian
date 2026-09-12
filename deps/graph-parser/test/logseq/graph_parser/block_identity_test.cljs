(ns logseq.graph-parser.block-identity-test
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [logseq.db.file-based.schema :as file-schema]
            [logseq.graph-parser.block-identity :as block-identity]))

(defn- old-blocks
  "[title level] specs as page-blocks-for-identity returns blocks"
  [specs]
  (mapv (fn [[title level]] {:uuid (random-uuid) :title title :level level :has-id? false}) specs))

(defn- new-blocks
  "[title raw-level] specs as gp-block/extract-blocks returns blocks"
  [specs]
  (mapv (fn [[title level]] {:block/uuid (random-uuid) :block/title title :block/level level}) specs))

(defn- origins
  "For each result block, the index of the old block whose uuid it got, else :fresh"
  [old result]
  (let [index (zipmap (map :uuid old) (range))]
    (mapv #(get index (:block/uuid %) :fresh) result)))

(defn- reuse
  [old-specs new-specs]
  (let [old (old-blocks old-specs)
        result (block-identity/reuse-block-uuids old (new-blocks new-specs))]
    (is (apply distinct? (map :block/uuid result)) "no uuid is given out twice")
    (origins old result)))

(deftest unchanged-file
  (is (= [0 1 2 3] (reuse [["a" 1] ["b" 2] ["b" 2] ["c" 1]]
                          [["a" 1] ["b" 2] ["b" 2] ["c" 1]])))
  (testing "only the indentation changed: the keys are equal, matched one to one"
    (is (= [0 1] (reuse [["a" 1] ["b" 2]] [["a" 1] ["b" 1]])))))

(deftest one-block-edited
  (is (= [0 1 2] (reuse [["a" 1] ["b" 1] ["c" 1]] [["a" 1] ["b typed" 1] ["c" 1]]))
      "the unique neighbours anchor the gap, rule 5 pairs the edited block")
  (is (= [0 1 2] (reuse [["a" 1] ["b" 2] ["c" 1]] [["a" 1] ["b typed" 3] ["c" 1]]))
      "raw level 3 right under a level-1 block is tree level 2, as with-parent-and-order builds it")
  (is (= [0 :fresh 2] (reuse [["a" 1] ["b" 2] ["c" 1]] [["a" 1] ["b typed" 1] ["c" 1]]))
      "an edit that also changes the tree level is a new block"))

(deftest insert-at-top
  (is (= [:fresh 0 1 2] (reuse [["a" 1] ["b" 1] ["c" 1]]
                               [["new" 1] ["a" 1] ["b" 1] ["c" 1]]))))

(deftest block-moved-with-children
  (is (= [0 4 1 2 3] (reuse [["a" 1] ["b" 1] ["b1" 2] ["b2" 2] ["c" 1]]
                            [["a" 1] ["c" 1] ["b" 1] ["b1" 2] ["b2" 2]]))
      "c moved up: an anchor outside the increasing run keeps its uuid too")
  (testing "moved and one child edited in the same save"
    (is (= [0 4 1 2 3] (reuse [["a" 1] ["b" 1] ["b1" 2] ["b2" 2] ["c" 1]]
                              [["a" 1] ["c" 1] ["b" 1] ["b1 typed" 2] ["b2" 2]])))))

(deftest duplicates
  (testing "swapped duplicates: first come, first served, no uuid twice"
    (is (= [1 0 3 2] (reuse [["x" 1] ["y" 1] ["x" 1] ["y" 1]]
                            [["y" 1] ["x" 1] ["y" 1] ["x" 1]]))))
  (testing "a duplicate moved past a unique block leaves its gap"
    (is (= [0 1 :fresh 2] (reuse [["a" 1] ["TODO" 1] ["b" 1] ["TODO" 1]]
                                 [["a" 1] ["TODO" 1] ["TODO" 1] ["b" 1]]))))
  (testing "duplicates plus one edited"
    (is (= [0 1 2 3] (reuse [["a" 1] ["TODO" 1] ["TODO" 1] ["c" 1]]
                            [["a" 1] ["TODO" 1] ["TODO call" 1] ["c" 1]])))
    (is (= [0 2 1 3] (reuse [["a" 1] ["TODO" 1] ["TODO" 1] ["c" 1]]
                            [["a" 1] ["TODO call" 1] ["TODO" 1] ["c" 1]]))
        "editing the first of two equal blocks swaps their uuids: equal content is matched first")))

(deftest explicit-ids-win
  (testing "an id:: block whose uuid an old id-less block has"
    (let [x (random-uuid)
          old [{:uuid x :title "a" :level 1 :has-id? false}
               {:uuid (random-uuid) :title "b" :level 1 :has-id? false}]
          parsed [{:block/uuid (random-uuid) :block/title "a" :block/level 1}
               {:block/uuid x :block/title (str "b\nid:: " x) :block/level 1
                :block/properties {:id (str x)}}]
          result (block-identity/reuse-block-uuids old parsed)]
      (is (= x (:block/uuid (second result))) "the id:: block keeps its uuid")
      (is (not= x (:block/uuid (first result))) "the old id-less block with that uuid is out of the pool")
      (is (apply distinct? (map :block/uuid result)))))
  (testing "an old block with an id property that no new block claims is not reused"
    (let [old [{:uuid (random-uuid) :title "a" :level 1 :has-id? true}]
          parsed [{:block/uuid (random-uuid) :block/title "a" :block/level 1}]]
      (is (= parsed (block-identity/reuse-block-uuids old parsed)))))
  (testing "id blocks take no part in the fast path"
    (let [x (random-uuid)
          y (random-uuid)
          old [{:uuid x :title (str "a\nid:: " x) :level 1 :has-id? true}
               {:uuid y :title "b" :level 1 :has-id? false}]
          parsed [{:block/uuid x :block/title (str "a\nid:: " x) :block/level 1
                :block/properties {:id (str x)}}
               {:block/uuid (random-uuid) :block/title "b" :block/level 1}]]
      (is (= [x y] (map :block/uuid (block-identity/reuse-block-uuids old parsed)))))))

(deftest external-append-to-a-journal
  (is (= [0 1 2 3 4 :fresh :fresh]
         (reuse [["" 1] ["TODO call" 1] ["notes" 1] ["TODO call" 2] ["" 1]]
                [["" 1] ["TODO call" 1] ["notes" 1] ["TODO call" 2] ["" 1]
                 ["agent: done" 1] ["agent: details" 2]]))))

(def ^:private b1-id #uuid "6f0e3a4c-0d8e-4b53-9b1a-2f6f1c1a0b01")

(defn- page-db
  "A file graph db with page foo bound to pages/foo.md: b (b1 with an id), a"
  []
  (let [conn (d/create-conn file-schema/schema)]
    (d/transact! conn [{:db/id -1 :file/path "pages/foo.md"}
                       {:db/id -2 :block/name "foo" :block/title "foo" :block/uuid (random-uuid) :block/file -1}
                       {:db/id -3 :block/uuid (random-uuid) :block/title "a"
                        :block/page -2 :block/parent -2 :block/order "a1"}
                       {:db/id -4 :block/uuid (random-uuid) :block/title "b"
                        :block/page -2 :block/parent -2 :block/order "a0"}
                       {:db/id -5 :block/uuid b1-id :block/title (str "b1\nid:: " b1-id)
                        :block/properties {:id (str b1-id)}
                        :block/page -2 :block/parent -4 :block/order "a0"}])
    @conn))

(deftest page-blocks-for-identity-test
  (let [db (page-db)]
    (is (= [["b" 1 false] [(str "b1\nid:: " b1-id) 2 true] ["a" 1 false]]
           (map (juxt :title :level :has-id?)
                (block-identity/page-blocks-for-identity db "pages/foo.md" "foo")))
        "pre-order by :block/order, levels from the tree")
    (is (nil? (block-identity/page-blocks-for-identity db "pages/foo.md" "zed"))
        "the file's page was renamed (title::): nothing to reuse")
    (is (nil? (block-identity/page-blocks-for-identity db "pages/none.md" "none"))
        "no page bound to the file yet")
    (let [parsed (new-blocks [["a" 1]])]
      (is (= parsed (block-identity/reuse-block-uuids nil parsed)) "no old blocks: unchanged"))))
