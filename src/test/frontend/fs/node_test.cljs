(ns frontend.fs.node-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.fs.node :as fs-node]))

(deftest guard-expected-test
  (testing "a caller passing :old-content gets a guarded write against it"
    (is (= "- a\n" (fs-node/guard-expected {:old-content "- a\n"})))
    (is (= "" (fs-node/guard-expected {:old-content ""}))
        "an existing empty file is compared, not taken for a new file"))
  (testing "a nil :old-content is a new file, which must not exist yet"
    (is (= {:absent true} (fs-node/guard-expected {:old-content nil}))))
  (testing "legacy, unguarded writes"
    (is (nil? (fs-node/guard-expected {})) "no :old-content")
    (is (nil? (fs-node/guard-expected nil)) "no options")
    (is (nil? (fs-node/guard-expected {:skip-compare? true :old-content "- a\n"}))
        ":skip-compare? wins over :old-content")
    (is (nil? (fs-node/guard-expected {:old-content 42})))))
