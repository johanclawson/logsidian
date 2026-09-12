(ns frontend.worker.file-test
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
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
