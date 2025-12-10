(ns logseq.tasks.benchmark
  "Tasks for running performance benchmarks"
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]))

(defn baseline
  "Run baseline performance benchmarks before sidecar implementation.
   Results are printed to console - update docs/tests/performance_before_sidecar.md manually."
  [& _args]
  (println "🚀 Running baseline performance benchmarks...")
  (println "   This will measure current Logseq performance without the JVM sidecar.")
  (println "")

  ;; Ensure the output directory exists
  (fs/create-dirs "docs/tests")

  ;; Compile tests - fail if this fails
  (let [{:keys [exit]} (shell {:out :inherit :err :inherit} "yarn" "cljs:test")]
    (when (not= 0 exit)
      (println "❌ Test compilation failed")
      (System/exit 1)))

  ;; Run benchmarks - fail if this fails
  (let [{:keys [exit]} (shell {:out :inherit :err :inherit} "yarn" "cljs:run-test" "-i" "benchmark")]
    (when (not= 0 exit)
      (println "❌ Benchmark tests failed")
      (System/exit 1)))

  (println "")
  (println "✅ Benchmarks complete.")
  (println "   Update docs/tests/performance_before_sidecar.md manually with results above."))

(defn compare-results
  "Compare baseline results with sidecar results (after sidecar is implemented)"
  [& _args]
  (println "📊 Comparing performance results...")
  (println "   (Not yet implemented - run after sidecar is complete)"))
