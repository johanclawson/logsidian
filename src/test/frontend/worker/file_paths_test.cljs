(ns frontend.worker.file-paths-test
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [frontend.util :as util]
            [frontend.worker.file-paths :as file-paths]
            [logseq.common.config :as common-config]
            [logseq.db.file-based.schema :as file-schema]))

(def ^:private repo "file-paths-test-repo")

(def ^:private paths
  ["journals/a.md" "pages/B.MD" "pages/c.org" "pages/d.markdown"
   "logseq/config.edn" "logseq/custom.css" "whiteboards/w.edn"
   "pages/README" "a.b/c"])

(defn- new-conn
  []
  (let [conn (d/create-conn file-schema/schema)]
    (d/transact! conn (map (fn [p] {:file/path p :file/content ""}) paths))
    conn))

(defn- ui-non-mldoc
  "The filter frontend.search/file-search applies on the UI side."
  [paths]
  (let [mldoc-exts (set (map name common-config/mldoc-support-formats))]
    (remove (fn [file] (mldoc-exts (util/get-file-ext file))) paths)))

(defn- file-eid
  [conn path]
  (:db/id (d/entity @conn [:file/path path])))

(deftest mldoc-path?-parity-test
  (testing "worker predicate matches the UI's util/get-file-ext filter"
    (is (= (set (ui-non-mldoc paths))
           (set (remove file-paths/mldoc-path? paths))))
    (is (= #{"logseq/config.edn" "logseq/custom.css" "whiteboards/w.edn" "pages/README" "a.b/c"}
           (set (remove file-paths/mldoc-path? paths))))
    (is (false? (file-paths/mldoc-path? nil)))))

(deftest all-paths-test
  (let [conn (new-conn)]
    (is (= (count paths) (count (file-paths/all-paths @conn))))
    (is (= (set paths) (set (file-paths/all-paths @conn))))))

(deftest non-mldoc-paths-test
  (file-paths/forget! repo)
  (let [conn (new-conn)
        v1 (file-paths/non-mldoc-paths repo conn)]
    (testing "parity with the UI filter"
      (is (= (set (ui-non-mldoc paths)) (set v1))))

    (testing "cached"
      (is (identical? v1 (file-paths/non-mldoc-paths repo conn))))

    (testing "adding or retracting an md file keeps the cache"
      (d/transact! conn [{:file/path "pages/new.md" :file/content ""}])
      (is (identical? v1 (file-paths/non-mldoc-paths repo conn)))
      (d/transact! conn [[:db/retractEntity (file-eid conn "pages/new.md")]])
      (is (identical? v1 (file-paths/non-mldoc-paths repo conn))))

    (testing "identical-value upsert emits no :file/path datom and keeps the cache"
      (d/transact! conn [{:file/path "logseq/config.edn" :file/content "x"}])
      (is (identical? v1 (file-paths/non-mldoc-paths repo conn))))

    (testing "adding a non-mldoc file rebuilds"
      (d/transact! conn [{:file/path "whiteboards/x.edn" :file/content ""}])
      (let [v2 (file-paths/non-mldoc-paths repo conn)]
        (is (not (identical? v1 v2)))
        (is (contains? (set v2) "whiteboards/x.edn"))))

    (testing "retracting a non-mldoc file rebuilds"
      (d/transact! conn [[:db/retractEntity (file-eid conn "whiteboards/x.edn")]])
      (is (not (contains? (set (file-paths/non-mldoc-paths repo conn)) "whiteboards/x.edn"))))

    (testing "renaming a non-mldoc path rebuilds"
      (d/transact! conn [{:db/id (file-eid conn "logseq/custom.css") :file/path "logseq/custom2.css"}])
      (let [v (set (file-paths/non-mldoc-paths repo conn))]
        (is (contains? v "logseq/custom2.css"))
        (is (not (contains? v "logseq/custom.css")))))

    (testing "reset-conn! invalidates, with and without :reset-conn! tx-meta"
      (let [v3 (file-paths/non-mldoc-paths repo conn)]
        (d/reset-conn! conn @(new-conn) {:reset-conn! true})
        (let [v4 (file-paths/non-mldoc-paths repo conn)]
          (is (not (identical? v3 v4)))
          (is (= (set (ui-non-mldoc paths)) (set v4)))
          (d/reset-conn! conn @conn)
          (is (not (identical? v4 (file-paths/non-mldoc-paths repo conn)))))))

    (testing "a new conn for the same repo rebuilds"
      (let [v5 (file-paths/non-mldoc-paths repo conn)
            conn2 (d/create-conn file-schema/schema)]
        (d/transact! conn2 [{:file/path "logseq/only.edn"}])
        (is (not (identical? v5 (file-paths/non-mldoc-paths repo conn2))))
        (is (= ["logseq/only.edn"] (file-paths/non-mldoc-paths repo conn2)))
        (testing "a transact on the old conn does not drop the new conn's cache"
          (let [v6 (file-paths/non-mldoc-paths repo conn2)]
            (d/transact! conn [{:file/path "whiteboards/y.edn"}])
            (is (identical? v6 (file-paths/non-mldoc-paths repo conn2)))))))

    (testing "forget! drops the cache"
      (let [conn3 (new-conn)
            v7 (file-paths/non-mldoc-paths repo conn3)]
        (file-paths/forget! repo)
        (is (not (identical? v7 (file-paths/non-mldoc-paths repo conn3))))))
    (file-paths/forget! repo)))

(deftest invalidate?-test
  (is (true? (file-paths/invalidate? {:tx-meta {:reset-conn! true} :tempids {}})))
  (is (true? (file-paths/invalidate? {:tx-data [] :tempids nil})))
  (is (false? (file-paths/invalidate? {:tx-data [(d/datom 1 :file/path "pages/a.md")] :tempids {}})))
  (is (true? (file-paths/invalidate? {:tx-data [(d/datom 1 :file/path "logseq/custom.css")] :tempids {}})))
  (is (false? (file-paths/invalidate? {:tx-data [(d/datom 1 :file/content "x")] :tempids {}}))))
