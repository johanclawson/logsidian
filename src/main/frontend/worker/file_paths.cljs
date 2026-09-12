(ns frontend.worker.file-paths
  "File paths read from the :file/path AVET index (no pull, so no EAVT and no
  :file/content restores), plus a per-repo cache of the non-mldoc paths used by
  file search."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [logseq.common.config :as common-config]
            [logseq.common.util :as common-util]))

(def ^:private mldoc-exts (set (map name common-config/mldoc-support-formats)))

(defn mldoc-path?
  "Same predicate as `(mldoc-exts (frontend.util/get-file-ext path))`, which the
  UI file search uses to drop md/org paths."
  [path]
  (boolean
   (and (string? path)
        (string/includes? path ".")
        (contains? mldoc-exts (some-> (common-util/path->file-ext path) string/lower-case)))))

(defn all-paths
  "Every :file/path in db, in AVET (path) order."
  [db]
  (mapv :v (d/datoms db :avet :file/path)))

;; repo -> {:conn conn :paths [non-mldoc path ...]}
(defonce ^:private *non-mldoc (atom {}))

(defn forget!
  "Drops the cached non-mldoc paths of repo."
  [repo]
  (swap! *non-mldoc dissoc repo))

(defn- forget-conn!
  [repo conn]
  (swap! *non-mldoc (fn [m]
                      (if (identical? conn (get-in m [repo :conn]))
                        (dissoc m repo)
                        m))))

(defn invalidate?
  "Whether a tx report can change the non-mldoc path set.
  A reset-conn! report (tagged with :reset-conn! or carrying no :tempids, which
  every transact report has) always invalidates, without walking its tx-data:
  that is the whole old and new db."
  [{:keys [tx-data tx-meta tempids]}]
  (boolean
   (or (:reset-conn! tx-meta)
       (nil? tempids)
       (some #(and (= :file/path (:a %)) (not (mldoc-path? (:v %)))) tx-data))))

(defn non-mldoc-paths
  "Non md/org :file/path values of conn, cached per repo. The cache is dropped
  when a non-mldoc :file/path datom is added or retracted, when the conn is
  reset, or when repo gets a new conn."
  [repo conn]
  (let [{cached-conn :conn paths :paths} (get @*non-mldoc repo)]
    (if (identical? cached-conn conn)
      paths
      (let [paths (into [] (comp (map :v) (remove mldoc-path?)) (d/datoms @conn :avet :file/path))]
        ;; Raw listener: fires on every transact, also inside batch-tx mode
        (d/listen! conn ::non-mldoc-paths
                   (fn [report]
                     (when (invalidate? report)
                       (forget-conn! repo conn))))
        (swap! *non-mldoc assoc repo {:conn conn :paths paths})
        paths))))
