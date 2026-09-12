;; Read ClojureScript files with edamame to catch bracket/reader errors before a
;; 20-minute shadow-cljs build. usage: bb bench/parsecheck.clj <file>...
(require '[edamame.core :as e])
(def opts {:all true :read-cond :allow :features #{:cljs} :auto-resolve name
           :readers {'js identity 'inst identity 'uuid identity}})
(let [bad (atom 0)]
  (doseq [f *command-line-args*]
    (try
      (println "OK " (count (e/parse-string-all (slurp f) opts)) "forms" f)
      (catch Exception ex (swap! bad inc) (println "ERR" f (ex-message ex) (select-keys (ex-data ex) [:row :col])))))
  (System/exit (if (pos? @bad) 1 0)))
