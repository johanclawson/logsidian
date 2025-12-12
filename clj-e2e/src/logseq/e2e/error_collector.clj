(ns logseq.e2e.error-collector
  "Console error collection and assertion utilities for E2E tests.

   Provides:
   - Error-level filtering of console logs
   - Assertion helper for 'no console errors'
   - Structured error report generation

   Usage in tests:
   ```clojure
   (use-fixtures :each fixtures/assert-no-console-errors)

   ;; Or manually:
   (testing \"my test\"
     (do-something)
     (error-collector/assert-no-console-errors!))
   ```"
  (:require [clojure.string :as string]
            [clojure.test :as t]
            [logseq.e2e.custom-report :as custom-report]))

;; =============================================================================
;; Console Error Filtering
;; =============================================================================

(def ^:dynamic *console-errors*
  "Atom containing console error entries collected during test execution.
   Each entry is a map with :text, :type, :timestamp keys."
  nil)

(defn- error-level?
  "Check if a console message text indicates an error.
   Handles both Playwright message types and text patterns."
  [text]
  (or (string/starts-with? (string/lower-case (str text)) "error")
      (string/includes? (string/lower-case (str text)) "uncaught")
      (string/includes? (string/lower-case (str text)) "exception")
      ;; Common CLJS error patterns
      (string/includes? (str text) "Error:")
      (string/includes? (str text) "TypeError:")
      (string/includes? (str text) "ReferenceError:")
      (string/includes? (str text) "SyntaxError:")))

(defn- ignorable-error?
  "Check if an error should be ignored (known benign errors)."
  [text]
  (let [text-lower (string/lower-case (str text))]
    (or
     ;; Known benign errors to ignore
     (string/includes? text-lower "favicon.ico")
     (string/includes? text-lower "failed to load resource")
     ;; React dev mode warnings
     (string/includes? text-lower "react does not recognize")
     ;; Web worker initialization
     (string/includes? text-lower "worker already initialized"))))

(defn filter-console-errors
  "Filter console logs to extract error-level messages.
   Returns vector of error maps {:text :timestamp}."
  [console-logs]
  (->> console-logs
       (filter #(and (error-level? %)
                     (not (ignorable-error? %))))
       (map (fn [text]
              {:text text
               :timestamp (System/currentTimeMillis)}))))

(defn get-console-errors
  "Get console errors from the current test's collected logs.
   Returns vector of error maps or empty vector if no errors."
  []
  (if-let [pw-page->logs (some-> custom-report/*pw-page->console-logs* deref)]
    (->> pw-page->logs
         vals
         (mapcat identity)
         filter-console-errors
         vec)
    []))

(defn get-all-console-logs
  "Get all console logs (not just errors) from the current test.
   Returns vector of log strings."
  []
  (if-let [pw-page->logs (some-> custom-report/*pw-page->console-logs* deref)]
    (->> pw-page->logs
         vals
         (mapcat identity)
         vec)
    []))

;; =============================================================================
;; Assertions
;; =============================================================================

(defn assert-no-console-errors!
  "Assert that no console errors were collected during the test.
   Fails the test with detailed error info if errors exist."
  []
  (let [errors (get-console-errors)]
    (when (seq errors)
      (let [error-summary (->> errors
                               (map :text)
                               (string/join "\n  - "))]
        (t/is (empty? errors)
              (str "Console errors detected:\n  - " error-summary))))))

(defn assert-no-errors-containing!
  "Assert that no console errors contain the given pattern.
   Useful for checking specific error types."
  [pattern]
  (let [errors (get-console-errors)
        matching (->> errors
                      (filter #(string/includes? (:text %) pattern)))]
    (when (seq matching)
      (let [error-summary (->> matching
                               (map :text)
                               (string/join "\n  - "))]
        (t/is (empty? matching)
              (str "Console errors matching '" pattern "':\n  - " error-summary))))))

;; =============================================================================
;; Test Fixture
;; =============================================================================

(defn wrap-assert-no-console-errors
  "Test fixture that asserts no console errors at end of test.

   Usage:
   ```clojure
   (use-fixtures :each error-collector/wrap-assert-no-console-errors)
   ```"
  [f]
  (f)
  (assert-no-console-errors!))

;; =============================================================================
;; Error Report Generation
;; =============================================================================

(defn generate-error-report
  "Generate a structured error report from test results.
   Returns a map suitable for JSON/EDN serialization."
  [test-name & {:keys [console-errors all-logs sidecar-logs]}]
  {:timestamp (System/currentTimeMillis)
   :test-name test-name
   :console-errors (or console-errors (get-console-errors))
   :all-console-logs (or all-logs (get-all-console-logs))
   :sidecar-logs sidecar-logs
   :environment {:java-version (System/getProperty "java.version")
                 :os-name (System/getProperty "os.name")
                 :user-dir (System/getProperty "user.dir")}})

(defn format-report-markdown
  "Format an error report as markdown for human reading."
  [report]
  (str "# E2E Test Error Report\n\n"
       "**Test:** " (:test-name report) "\n"
       "**Timestamp:** " (:timestamp report) "\n\n"
       "## Console Errors\n\n"
       (if (seq (:console-errors report))
         (str "```\n"
              (string/join "\n" (map :text (:console-errors report)))
              "\n```\n")
         "_No console errors detected._\n")
       "\n## All Console Logs\n\n"
       "```\n"
       (string/join "\n" (take 100 (:all-console-logs report)))
       (when (> (count (:all-console-logs report)) 100)
         "\n... (truncated)")
       "\n```\n"
       (when (:sidecar-logs report)
         (str "\n## Sidecar Logs\n\n"
              "```\n"
              (string/join "\n" (take 50 (:sidecar-logs report)))
              "\n```\n"))))

(defn write-error-report!
  "Write error report to file. Returns the file path."
  [report & {:keys [dir format]
             :or {dir "clj-e2e/error-reports"
                  format :edn}}]
  (let [filename (str (:test-name report) "-" (:timestamp report)
                      (case format :edn ".edn" :md ".md"))
        path (str dir "/" filename)
        content (case format
                  :edn (pr-str report)
                  :md (format-report-markdown report))]
    (clojure.java.io/make-parents path)
    (spit path content)
    path))
