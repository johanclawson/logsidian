(ns ^:node-only logseq.cli.common.graph
  "Graph related fns shared between CLI and electron"
  (:require ["fs" :as fs]
            ["fs-extra" :as fs-extra]
            ["os" :as os]
            ["path" :as node-path]
            [clojure.string :as string]
            [logseq.common.config :as common-config]
            [logseq.common.graph :as common-graph]))

(defn ^:api graph-name->path
  [graph-name]
  (when graph-name
    (-> graph-name
        (string/replace "+3A+" ":")
        (string/replace "++" "/"))))

(defn get-db-graphs-dir
  "Directory where DB graphs are stored"
  []
  (node-path/join (os/homedir) "logseq" "graphs"))

(defn get-db-based-graphs
  []
  (let [dir (get-db-graphs-dir)]
    (fs-extra/ensureDirSync dir)
    (->> (common-graph/read-directories dir)
         (remove (fn [s] (= s common-config/unlinked-graphs-dir)))
         (map graph-name->path)
         (map (fn [s]
                (if (string/starts-with? s common-config/file-version-prefix)
                  s
                  (str common-config/db-version-prefix s)))))))

(defn get-file-graphs-dir
  "Directory where file graph transit cache is stored"
  []
  (node-path/join (os/homedir) ".logseq" "graphs"))

(defn get-file-graphs
  "Returns paths of all file-based graphs discovered from the transit cache.
   Transit filenames use ++ to encode path separators, e.g.:
   logseq_local_X++source++repos++graph → X:/source/repos/graph"
  []
  (let [dir (get-file-graphs-dir)]
    (when (fs/existsSync dir)
      (->> (js->clj (fs/readdirSync dir))
           (filter #(string/ends-with? % ".transit"))
           (map #(node-path/basename % ".transit"))
           (filter #(string/starts-with? % "logseq_local_"))
           (map #(string/replace-first % "logseq_local_" ""))
           (map graph-name->path)
           (filter #(and (fs/existsSync %)
                         (fs/existsSync (node-path/join % "logseq" "config.edn"))
                         (not (fs/existsSync (node-path/join % "db.sqlite")))))
           vec))))
