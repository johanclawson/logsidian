(ns frontend.fs-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [frontend.test.node-fixtures :as node-fixtures]
            [frontend.test.helper :as test-helper :include-macros true :refer [deftest-async]]
            [frontend.test.node-helper :as test-node-helper]
            [frontend.fs :as fs]
            [promesa.core :as p]
            ["fs" :as fs-node]
            ["path" :as node-path]))

(use-fixtures :once node-fixtures/redef-get-fs)

(deftest-async create-if-not-exists-creates-correctly
  ;; dir needs to be an absolute path for fn to work correctly
  (let [dir (node-path/resolve (test-node-helper/create-tmp-dir))
        some-file (node-path/join dir "something.txt")]

    (->
     (p/do!
      (fs/create-if-not-exists nil dir some-file "NEW")
      (is (fs-node/existsSync some-file)
          "something.txt created correctly")
      (is (= "NEW"
             (str (fs-node/readFileSync some-file)))
          "something.txt has correct content"))

     (p/finally
      (fn []
        (fs-node/unlinkSync some-file)
        (fs-node/rmdirSync dir))))))

(deftest-async create-if-not-exists-does-not-create-correctly
  (let [dir (node-path/resolve (test-node-helper/create-tmp-dir))
        some-file (node-path/join dir "something.txt")]
    (fs-node/writeFileSync some-file "OLD")

    (->
     (p/do!
      (fs/create-if-not-exists nil dir some-file "NEW")
      (is (= "OLD" (str (fs-node/readFileSync some-file)))
          "something.txt has not been touched and old content still exists"))

     (p/finally
      (fn []
        (fs-node/unlinkSync some-file)
        (fs-node/rmdirSync dir))))))

(defn- error-with-code
  [message code]
  (let [e (js/Error. message)]
    (set! (.-code e) code)
    e))

(deftest enoent-error-test
  (testing "absence, however the stat error arrives"
    (is (fs/enoent-error? (js/Error. "ENOENT: no such file or directory, stat '/g/pages/a.md'")))
    (is (fs/enoent-error? (error-with-code "no such file" "ENOENT")))
    (is (fs/enoent-error? (js/Error. "Error invoking remote method 'main': Error: ENOENT: no such file or directory, stat '/g/a.md'")))
    (is (fs/enoent-error? (ex-info "stat failed" {:code "ENOENT"})))
    (is (fs/enoent-error? "Error: ENOENT: no such file or directory, stat '/g/a.md'")))
  (testing "every other failure is not absence"
    (is (not (fs/enoent-error? (js/Error. "EACCES: permission denied, stat '/g/pages/ENOENT.md'"))))
    (is (not (fs/enoent-error? (error-with-code "permission denied" "EACCES"))))
    (is (not (fs/enoent-error? (js/Error. "EIO: i/o error, stat '/g/a.md'"))))
    (is (not (fs/enoent-error? nil)))))

(deftest-async path-state-present-and-missing-test
  (let [dir (node-path/resolve (test-node-helper/create-tmp-dir))]
    (fs-node/writeFileSync (node-path/join dir "here.md") "- a\n")
    (p/let [present (fs/<path-state dir "here.md")
            missing (fs/<path-state dir "gone.md")]
      (is (= :present present))
      (is (= :missing missing)))))

(deftest-async path-state-rejects-other-errors-test
  (let [dir (node-path/resolve (test-node-helper/create-tmp-dir))
        locked (node-path/join dir "locked")]
    (if (or (= "win32" js/process.platform) (zero? (.getuid js/process)))
      (p/resolved nil)                  ; no EACCES from a stat here
      (do
        (fs-node/mkdirSync locked)
        (fs-node/writeFileSync (node-path/join locked "x.md") "- a\n")
        (fs-node/chmodSync locked 0)
        (-> (fs/<path-state locked "x.md")
            (p/then (fn [state] (is false (str "a permission error resolved to " state))))
            (p/catch (fn [e] (is (not (fs/enoent-error? e)) "rejects with the EACCES error")))
            (p/finally (fn [& _] (fs-node/chmodSync locked 448))))))))
