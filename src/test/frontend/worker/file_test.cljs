(ns frontend.worker.file-test
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [frontend.common.file.util :as wfu]
            [frontend.worker.file :as worker-file]
            [logseq.db.file-based.schema :as file-schema]))

(deftest remove-writes-of-deleted-pages-test
  (let [conn (d/create-conn file-schema/schema)
        {:keys [tempids]} (d/transact! conn [{:db/id "live" :block/uuid (random-uuid) :block/name "live"}
                                             {:db/id "gone" :block/uuid (random-uuid) :block/name "gone"}])
        live (get tempids "live")
        gone (get tempids "gone")
        _ (d/transact! conn [[:db/retractEntity gone]])
        writes {1 live 2 gone 3 live}]
    (testing "requests of pages that exist stay: their writes are pending"
      (is (= {1 live 3 live} (worker-file/remove-writes-of-deleted-pages writes @conn))))
    (testing "requests of deleted pages go"
      (is (= {} (worker-file/remove-writes-of-deleted-pages {2 gone} @conn))))
    (testing "nothing pending"
      (is (= {} (worker-file/remove-writes-of-deleted-pages {} @conn))))))

(deftest coalesce-page-writes-test
  (testing "type then Enter in one flush: one write per page, the last op, the highest request id"
    (is (= {:keep [["repo" 10 :insert-blocks 2 2]]
            :drop-ids #{1}}
           (worker-file/coalesce-page-writes [["repo" 10 :save-block 1 1]
                                              ["repo" 10 :insert-blocks 2 2]]))))
  (testing "pages keep the arrival order of their last write; other pages are untouched"
    (is (= {:keep [["repo" 20 :save-block 3 3]
                   ["repo" 10 :delete-blocks 4 4]]
            :drop-ids #{1 2}}
           (worker-file/coalesce-page-writes [["repo" 10 :save-block 1 1]
                                              ["repo" 10 :save-block 2 2]
                                              ["repo" 20 :save-block 3 3]
                                              ["repo" 10 :delete-blocks 4 4]]))))
  (testing "a write put back into the channel (busy page) with an older id keeps the page's highest id"
    (is (= {:keep [["repo" 10 :save-block 9 7]]
            :drop-ids #{5}}
           (worker-file/coalesce-page-writes [["repo" 10 :save-block 8 7]
                                              ["repo" 10 :save-block 9 5]]))))
  (testing "the same page id in two repos is two pages"
    (is (= {:keep [["a" 10 :save-block 1 1] ["b" 10 :save-block 2 2]]
            :drop-ids #{}}
           (worker-file/coalesce-page-writes [["a" 10 :save-block 1 1]
                                              ["b" 10 :save-block 2 2]]))))
  (testing "nothing"
    (is (= {:keep [] :drop-ids #{}} (worker-file/coalesce-page-writes [])))))

(defn- page-conn
  "A file graph conn with pages/a.md bound to page a, the file holding
   content (no :file/content when nil)."
  [content]
  (let [conn (d/create-conn file-schema/schema)]
    (d/transact! conn [(cond-> {:file/path "pages/a.md"}
                         (some? content) (assoc :file/content content))])
    (d/transact! conn [{:block/name "a"
                        :block/title "a"
                        :block/uuid (random-uuid)
                        :block/file [:file/path "pages/a.md"]}])
    conn))

(defn- save-page!
  "Serializes page a as having had all its blocks deleted (an empty tree),
   the smallest save-tree-aux! call that needs no export context, and
   returns the :write-files messages posted."
  [conn request-id]
  (let [*posted (atom [])
        page (d/entity @conn [:block/name "a"])]
    (with-redefs [wfu/post-message (fn [type data & _] (swap! *posted conj [type data]))]
      (#'worker-file/save-tree-aux! "repo" conn page [] true nil request-id))
    @*posted))

(deftest save-tree-stamps-the-base-test
  (testing "the proposal is posted with the file's content as its base, and becomes :file/content"
    (let [conn (page-conn "- A")
          [[type data]] (save-page! conn 1)]
      (is (= :write-files type))
      (is (= "- A" (:base data)) "base: :file/content before the call")
      (is (= [["pages/a.md" ""]] (:files data)))
      (is (= 1 (:request-id data)))
      (is (= "" (:file/content (d/entity @conn [:file/path "pages/a.md"])))
          ":file/content is the proposal, the next serialization's base")))
  (testing "the next serialization takes the previous proposal as its base"
    (let [conn (page-conn "- A")
          _ (save-page! conn 1)
          [[_ data]] (save-page! conn 2)]
      (is (= "" (:base data)))))
  (testing "a reset from disk in between gives the next proposal the disk content as base"
    (let [conn (page-conn "- A")
          _ (save-page! conn 1)
          _ (d/transact! conn [{:file/path "pages/a.md" :file/content "- E"}])
          [[_ data]] (save-page! conn 2)]
      (is (= "- E" (:base data)))))
  (testing "a file without content yet: nil base, i.e. a new file"
    (let [conn (page-conn nil)
          [[_ data]] (save-page! conn 1)]
      (is (contains? data :base))
      (is (nil? (:base data))))))
