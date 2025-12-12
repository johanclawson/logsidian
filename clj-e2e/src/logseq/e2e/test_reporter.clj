(ns logseq.e2e.test-reporter
  "Structured test reporting for TDD workflow.

   Provides:
   - Structured test result collection
   - Report generation in EDN/JSON/Markdown formats
   - Integration with Claude Code for recursive error fixing

   The test reporter collects:
   - Test pass/fail status
   - Console errors from browser
   - Screenshots on failure
   - Sidecar server logs (if running)"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [clojure.test :as t]
            [logseq.e2e.error-collector :as errors]))

;; =============================================================================
;; Test Result Collection
;; =============================================================================

(def ^:dynamic *test-results*
  "Atom containing test results during a run.
   Each entry is {:name :type :message :expected :actual :console-errors}"
  nil)

(defn init-test-results!
  "Initialize test results collection. Call before test run."
  []
  (reset! *test-results* []))

(defn record-test-result!
  "Record a test result. Called by custom test reporter methods."
  [result-type test-name & {:keys [message expected actual]}]
  (when *test-results*
    (swap! *test-results* conj
           {:name test-name
            :type result-type
            :message message
            :expected expected
            :actual actual
            :console-errors (errors/get-console-errors)
            :timestamp (System/currentTimeMillis)})))

;; =============================================================================
;; Custom Test Reporter Methods
;; =============================================================================

(defn install-reporter!
  "Install custom reporter methods that collect structured results.
   Call before running tests."
  []
  ;; Override :pass - record success
  (defmethod t/report :pass [m]
    (t/with-test-out
      (t/inc-report-counter :pass))
    (record-test-result! :pass
                         (string/join "/" (map #(:name (meta %)) t/*testing-vars*))))

  ;; Override :fail - record failure with details
  (defmethod t/report :fail [m]
    (t/with-test-out
      (t/inc-report-counter :fail)
      (println "\nFAIL in" (t/testing-vars-str m))
      (when (seq t/*testing-contexts*) (println (t/testing-contexts-str)))
      (when-let [message (:message m)] (println message))
      (println "expected:" (pr-str (:expected m)))
      (println "  actual:" (pr-str (:actual m))))
    (record-test-result! :fail
                         (string/join "/" (map #(:name (meta %)) t/*testing-vars*))
                         :message (:message m)
                         :expected (:expected m)
                         :actual (:actual m)))

  ;; Override :error - record error with stack trace
  (defmethod t/report :error [m]
    (t/with-test-out
      (t/inc-report-counter :error)
      (println "\nERROR in" (t/testing-vars-str m))
      (when (seq t/*testing-contexts*) (println (t/testing-contexts-str)))
      (when-let [message (:message m)] (println message))
      (println "expected:" (pr-str (:expected m)))
      (print "  actual: ")
      (let [actual (:actual m)]
        (if (instance? Throwable actual)
          (clojure.stacktrace/print-cause-trace actual t/*stack-trace-depth*)
          (prn actual))))
    (record-test-result! :error
                         (string/join "/" (map #(:name (meta %)) t/*testing-vars*))
                         :message (:message m)
                         :expected (:expected m)
                         :actual (if (instance? Throwable (:actual m))
                                   {:exception-class (.getName (class (:actual m)))
                                    :message (.getMessage ^Throwable (:actual m))
                                    :stack-trace (with-out-str
                                                   (clojure.stacktrace/print-cause-trace
                                                    (:actual m) 10))}
                                   (:actual m)))))

;; =============================================================================
;; Report Generation
;; =============================================================================

(defn- slurp-sidecar-logs
  "Read recent sidecar log lines if available."
  []
  (let [log-file (io/file "sidecar/logs/sidecar.log")]
    (when (.exists log-file)
      (try
        (let [lines (string/split-lines (slurp log-file))]
          ;; Return last 100 lines
          (take-last 100 lines))
        (catch Exception _
          nil)))))

(defn generate-test-report
  "Generate a comprehensive test report from collected results.
   Returns a map suitable for EDN/JSON serialization."
  []
  (let [results @*test-results*
        failures (filter #(#{:fail :error} (:type %)) results)
        passes (filter #(= :pass (:type %)) results)]
    {:timestamp (System/currentTimeMillis)
     :summary {:total (count results)
               :passed (count passes)
               :failed (count failures)}
     :failures (vec failures)
     :passes (mapv :name passes)
     :sidecar-logs (slurp-sidecar-logs)
     :environment {:java-version (System/getProperty "java.version")
                   :os-name (System/getProperty "os.name")
                   :os-arch (System/getProperty "os.arch")
                   :user-dir (System/getProperty "user.dir")}}))

(defn format-report-for-claude
  "Format a test report as markdown optimized for Claude Code.
   Includes actionable information for fixing errors."
  [report]
  (let [failures (:failures report)]
    (str
     "# E2E Test Failure Report\n\n"
     "**Timestamp:** " (:timestamp report) "\n"
     "**Summary:** " (:passed (:summary report)) "/" (:total (:summary report))
     " tests passed\n\n"

     (if (empty? failures)
       "All tests passed!\n"
       (str
        "## Failures (" (count failures) ")\n\n"
        (apply str
               (for [failure failures]
                 (str
                  "### " (:name failure) "\n\n"
                  "**Type:** " (name (:type failure)) "\n\n"
                  (when (:message failure)
                    (str "**Message:** " (:message failure) "\n\n"))
                  "**Expected:**\n```clojure\n" (pr-str (:expected failure)) "\n```\n\n"
                  "**Actual:**\n```clojure\n" (pr-str (:actual failure)) "\n```\n\n"
                  (when (seq (:console-errors failure))
                    (str "**Console Errors:**\n```\n"
                         (string/join "\n" (map :text (:console-errors failure)))
                         "\n```\n\n"))
                  "---\n\n")))

        (when-let [sidecar-logs (:sidecar-logs report)]
          (str
           "## Sidecar Logs (last 50 lines)\n\n"
           "```\n"
           (string/join "\n" (take-last 50 sidecar-logs))
           "\n```\n\n"))))

     "## Next Steps\n\n"
     "1. Fix the failing test(s) by addressing the error messages\n"
     "2. Check console errors for runtime issues\n"
     "3. Re-run tests: `cd clj-e2e && clj -M:test -i sidecar`\n")))

(defn write-test-report!
  "Write test report to file. Returns the file path.

   Options:
   - :dir - output directory (default: clj-e2e/error-reports)
   - :format - :edn, :md, or :both (default: :both)"
  [& {:keys [dir format]
      :or {dir "clj-e2e/error-reports"
           format :both}}]
  (let [report (generate-test-report)
        timestamp (:timestamp report)
        paths (atom [])]
    (io/make-parents (str dir "/dummy"))

    ;; Write EDN format
    (when (#{:edn :both} format)
      (let [edn-path (str dir "/test-report-" timestamp ".edn")]
        (spit edn-path (pr-str report))
        (swap! paths conj edn-path)))

    ;; Write Markdown format
    (when (#{:md :both} format)
      (let [md-path (str dir "/test-report-" timestamp ".md")]
        (spit md-path (format-report-for-claude report))
        (swap! paths conj md-path)))

    ;; Always write "latest" files for easy access
    (spit (str dir "/latest.edn") (pr-str report))
    (spit (str dir "/latest.md") (format-report-for-claude report))

    {:paths @paths
     :latest-edn (str dir "/latest.edn")
     :latest-md (str dir "/latest.md")
     :report report}))

;; =============================================================================
;; Test Run Wrapper
;; =============================================================================

(defn run-tests-with-reporting
  "Run tests with structured result collection.
   Returns test report and writes to files.

   Usage:
   ```clojure
   (run-tests-with-reporting 'logseq.e2e.sidecar-basic-test)
   ```"
  [& namespaces]
  (binding [*test-results* (atom [])]
    (install-reporter!)
    (init-test-results!)
    (apply t/run-tests namespaces)
    (write-test-report!)))

(defn -main
  "Entry point for running tests with reporting from command line."
  [& args]
  (let [namespaces (if (seq args)
                     (map symbol args)
                     ['logseq.e2e.sidecar-basic-test])]
    (doseq [ns namespaces]
      (require ns))
    (let [result (apply run-tests-with-reporting namespaces)]
      (println "\nTest report written to:" (:latest-md result))
      (System/exit (if (zero? (get-in result [:report :summary :failed]))
                     0
                     1)))))
