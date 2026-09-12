(ns logseq.db.common.initial-data-test
  "This ns is the only one to test against file based datascript connections.
   These are useful integration tests"
  (:require ["fs" :as fs]
            ["path" :as node-path]
            [cljs.test :refer [deftest async use-fixtures is testing]]
            [datascript.core :as d]
            [logseq.common.util.date-time :as date-time-util]
            [logseq.db.common.initial-data :as common-initial-data]
            [logseq.db.common.sqlite-cli :as sqlite-cli]
            [logseq.db.sqlite.build :as sqlite-build]
            [logseq.db.sqlite.create-graph :as sqlite-create-graph]
            [logseq.db.test.helper :as db-test]))

(use-fixtures
  :each
 ;; Cleaning tmp/ before leaves last tmp/ after a test run for dev and debugging
  {:before
   #(async done
           (if (fs/existsSync "tmp")
             (fs/rm "tmp" #js {:recursive true} (fn [err]
                                                  (when err (js/console.log err))
                                                  (done)))
             (done)))})

(defn- create-graph-dir
  [dir db-name]
  (fs/mkdirSync (node-path/join dir db-name) #js {:recursive true}))

(deftest get-initial-data
  (testing "Fetches a defined block"
    (create-graph-dir "tmp/graphs" "test-db")

    (let [conn* (sqlite-cli/open-db! "tmp/graphs" "test-db")
          blocks [{:file/path "logseq/config.edn"
                   :file/content "{:foo :bar}"}]
          _ (d/transact! conn* blocks)
          ;; Simulate getting data from sqlite and restoring it for frontend
          {:keys [schema initial-data]} (common-initial-data/get-initial-data @conn*)
          conn (d/conn-from-datoms initial-data schema)]
      (is (= blocks
             (->> @conn
                  (d/q '[:find (pull ?b [:block/uuid :file/path :file/content]) :where [?b :file/content]])
                  (map first)))
          "Correct file with content is found"))))

(deftest restore-initial-data
  (create-graph-dir "tmp/graphs" "test-db")
  (let [conn* (sqlite-cli/open-db! "tmp/graphs" "test-db")
        _ (d/transact! conn* (sqlite-create-graph/build-db-initial-data "{}"))
        {:keys [init-tx]}
        (sqlite-build/build-blocks-tx
         {:pages-and-blocks
          [{:page {:block/title "page1"}
            :blocks [{:block/title "b1"}]}]})
        _ (d/transact! conn* init-tx)
          ;; Simulate getting data from sqlite and restoring it for frontend
        {:keys [schema initial-data]} (common-initial-data/get-initial-data @conn*)
        conn (d/conn-from-datoms initial-data schema)]
    (is (some? (db-test/find-page-by-title @conn "page1"))
        "Restores recently updated page")))

(defn- journal-day
  "journal-day int for today + n days, stepping from noon so DST stays on the day"
  [n]
  (let [noon (doto (js/Date.) (.setHours 12 0 0 0))]
    (date-time-util/date->int (js/Date. (+ (.getTime noon) (* n 86400000))))))

(deftest get-latest-journals
  (let [conn (d/create-conn {:block/name {:db/unique :db.unique/identity}
                             :block/journal-day {:db/index true}})
        ;; 5 days ago .. tomorrow, then a second page on today and a non-journal
        _ (d/transact! conn (concat
                             (for [n (range -5 2)]
                               {:block/name (str "day" n)
                                :block/type "journal"
                                :block/journal-day (journal-day n)})
                             [{:block/name "today-b"
                               :block/type "journal"
                               :block/journal-day (journal-day 0)}
                              {:block/name "not-journal"
                               :block/type "page"
                               :block/journal-day (journal-day -1)}]))
        db @conn
        id #(:db/id (d/entity db [:block/name %]))
        today (journal-day 0)
        ;; previous eager implementation, kept as the reference
        reference (->> (d/datoms db :avet :block/journal-day)
                       vec
                       rseq
                       (keep (fn [d]
                               (when (<= (:v d) today)
                                 (let [e (d/entity db (:e d))]
                                   (when (= "journal" (:block/type e))
                                     (:db/id e)))))))
        result (map :db/id (common-initial-data/get-latest-journals db))]
    (testing "Newest first, today included, future days and non-journals excluded"
      (is (= (map id ["today-b" "day0" "day-1" "day-2" "day-3" "day-4" "day-5"])
             result))
      (is (= reference result)))
    (testing "Callers bound the walk with take"
      (is (= (map id ["today-b" "day0" "day-1"])
             (map :db/id (take 3 (common-initial-data/get-latest-journals db))))))
    (testing ":after continues strictly after the cursor journal"
      (is (= (map id ["day0" "day-1" "day-2" "day-3" "day-4" "day-5"])
             (map :db/id (common-initial-data/get-latest-journals
                          db {:after [today (id "today-b")]}))))
      (is (= (map id ["day-2" "day-3" "day-4" "day-5"])
             (map :db/id (common-initial-data/get-latest-journals
                          db {:after [(journal-day -1) (id "day-1")]}))))
      (is (= (map id ["today-b" "day0" "day-1" "day-2" "day-3" "day-4" "day-5"])
             (map :db/id (common-initial-data/get-latest-journals
                          db {:after [(journal-day 1) (id "day1")]})))
          "A cursor past today skips future days"))
    (testing "Empty db"
      (is (empty? (common-initial-data/get-latest-journals
                   (d/empty-db {:block/journal-day {:db/index true}})))))))
