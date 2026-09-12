(ns frontend.fs.node-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.fs.node :as fs-node]
            [frontend.state :as state]
            [frontend.test.helper :as test-helper :include-macros true :refer [deftest-async]]
            [goog.object :as gobj]
            [promesa.core :as p]))

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

(defn- <with-ipc
  "Runs (f), which returns a promise, with electron.ipc/ipc answering each
   action from responses (a fn of the action and its args, returning a value
   or a promise) and state/pub-event! recording; resolves to
   {:result .. :calls [[action & args] ..] :events [..]} and restores both."
  [responses f]
  (let [original-ipc ipc/ipc
        original-pub-event! state/pub-event!
        *calls (atom [])
        *events (atom [])]
    (set! ipc/ipc (fn [action & args]
                    (swap! *calls conj (into [action] args))
                    (p/promise (apply responses action args))))
    (set! state/pub-event! (fn [event] (swap! *events conj event) nil))
    (-> (p/let [result (f)]
          {:result result :calls @*calls :events @*events})
        (p/finally (fn [& _]
                     (set! ipc/ipc original-ipc)
                     (set! state/pub-event! original-pub-event!))))))

(def ^:private copy-path "/g/logseq/bak/conflicts/pages/a/2026-09-12T10_00_00.000Z.ab12.Desktop.md")

(defn- notices [events]
  (keep (fn [[kind payload]] (when (= :notification/show kind) (:content payload))) events))

(deftest-async io-error-saves-a-conflict-copy-test
  (p/let [{:keys [result calls events]}
          (<with-ipc (fn [action & _]
                       (case action
                         "writeFile" #js {:result "io-error" :error "ENOSPC: no space left on device" :code "ENOSPC"}
                         "backupConflictFile" copy-path
                         nil))
                     #(#'fs-node/write-file-impl! "repo" "/g" "pages/a.md" "- proposal\n"
                                                  {:old-content "- a\n"} nil))]
    (is (= "io-error" (gobj/get result "result")))
    (is (= copy-path (gobj/get result "copy")))
    (is (= ["backupConflictFile" "/g" "pages/a.md" "- proposal\n"] (second calls))
        "the proposal is saved as a conflict copy")
    (is (some #(string/includes? % copy-path) (notices events)) "the error notice names the copy")
    (is (not-any? #(= :file/reparse-from-disk (first %)) events) "no reparse: the db keeps the proposal")))

(deftest-async io-error-with-a-failed-copy-test
  (p/let [{:keys [result events]}
          (<with-ipc (fn [action & _]
                       (case action
                         "writeFile" #js {:result "io-error" :error "EIO: i/o error"}
                         "backupConflictFile" (p/rejected (js/Error. "EACCES: permission denied"))
                         nil))
                     #(#'fs-node/write-file-impl! "repo" "/g" "pages/a.md" "- proposal\n"
                                                  {:old-content "- a\n"} nil))]
    (is (= "io-error" (gobj/get result "result")))
    (is (nil? (gobj/get result "copy")))
    (is (some #(string/includes? % "failed too") (notices events)))))
