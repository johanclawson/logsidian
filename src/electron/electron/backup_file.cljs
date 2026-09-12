(ns electron.backup-file
  (:require [clojure.string :as string]
            ["crypto" :as crypto]
            ["path" :as node-path]
            ["fs" :as fs]
            ["fs-extra" :as fs-extra]))

(def backup-dir "logseq/bak")
(def version-file-dir "logseq/version-files/local")
(def conflict-dir
  "Conflict copies: content a guarded writeFile refused (electron.write-guard).
   A tree of its own, never pruned, see create-conflict-copy!."
  "logseq/bak/conflicts")

(defn- get-backup-dir*
  [repo relative-path bak-dir]
  (let [relative-path* (string/replace relative-path repo "")
        bak-dir (node-path/join repo bak-dir)
        path (node-path/join bak-dir relative-path*)
        parsed-path (node-path/parse path)]
    (node-path/join (.-dir parsed-path)
                    (.-name parsed-path))))

(defn get-backup-dir
  [repo relative-path]
  (get-backup-dir* repo relative-path backup-dir))

(defn get-version-file-dir
  [repo relative-path]
  (get-backup-dir* repo relative-path version-file-dir))

(defn get-conflict-dir
  [repo relative-path]
  (get-backup-dir* repo relative-path conflict-dir))

(defn in-conflict-dir?
  "Whether dir is the conflict tree of repo or inside it."
  [repo dir]
  (let [rel (node-path/relative (node-path/join repo conflict-dir) dir)]
    (not (or (= rel "..")
             (string/starts-with? rel (str ".." node-path/sep))
             (node-path/isAbsolute rel)))))

;; TODO: add interval support like days
(defn- truncate-old-versioned-files!
  "reserve the latest 6 version files
   Only files are pruned, never directories: the backup directory of a file
   named like a directory (pages.md -> logseq/bak/pages/) also holds the
   backup directories of the files in that directory, which a recursive
   remove would delete. Nothing is pruned in the conflict tree: the backups of
   a graph file under a top-level conflicts/ directory land there too."
  [repo dir]
  (when-not (in-conflict-dir? repo dir)
    (let [files (->> (fs/readdirSync dir (clj->js {:withFileTypes true}))
                     (filter #(.isFile ^js %))
                     (mapv #(.-name ^js %)))
          old-versioned-files (drop 6 (reverse (sort files)))]
      (doseq [file old-versioned-files]
        (fs-extra/removeSync (node-path/join dir file))))))

(defn backup-file
  "backup CONTENT under DIR :backup-dir or :version-file-dir
  :backup-dir = `backup-dir`
  :version-file-dir = `version-file-dir`
  Returns the path of the new file, or nil when skip-backup-fn skipped it."
  [repo dir relative-path ext content & {:keys [add-desktop? skip-backup-fn]
                                         :or {add-desktop? true}}]
  {:pre [(contains? #{:backup-dir :version-file-dir} dir)]}
  (let [dir* (case dir
               :backup-dir (get-backup-dir repo relative-path)
               :version-file-dir (get-version-file-dir repo relative-path))
        _ (fs-extra/ensureDirSync dir*)
        backups (fs/readdirSync dir*)
        latest-backup-size (when (seq backups)
                             (some->> (nth backups (dec (count backups)))
                                      (node-path/join dir*)
                                      (fs/statSync)
                                      (.-size)))]
    (when-not (and (fn? skip-backup-fn) latest-backup-size (skip-backup-fn latest-backup-size))
      (let [new-path (node-path/join dir*
                                     (str (string/replace (.toISOString (js/Date.)) ":" "_")
                                          (when add-desktop? ".Desktop")
                                          ext))]
        (fs/writeFileSync new-path content)
        (fs/statSync new-path)
        (truncate-old-versioned-files! repo dir*)
        new-path))))

(defn- create-file-exclusively!
  "Creates path with flag wx, so an existing file is never replaced (EEXIST
   is thrown), and writes content to it. When the write fails after the
   create, removes the partial file it created and rethrows."
  [path content]
  (let [fd (fs/openSync path "wx")]
    (try
      (fs/writeFileSync fd content)
      (fs/closeSync fd)
      (catch :default e
        (try (fs/closeSync fd) (catch :default _e nil))
        (try (fs/unlinkSync path) (catch :default _e nil))
        (throw e)))))

(def ^:private max-conflict-copy-attempts 10)

(defn- random-suffix
  []
  (.toString (crypto/randomBytes 4) "hex"))

(defn create-conflict-copy!
  "Saves content, which a guarded writeFile refused, as a new file under
   logseq/bak/conflicts/<dir of relative-path>/<name of relative-path>/ and
   returns the new file's path.
   - Never pruned: ordinary backups (backup-file) prune only their own
     directory and never inside the conflict tree. Conflict copies stay until
     the user removes them.
   - Never replaces a file: the name is the time plus a random suffix, the
     file is created with flag wx, and a name collision retries with a new
     suffix (up to max-conflict-copy-attempts, then throws).
   Other errors throw. now and suffix-fn exist for the tests."
  [repo relative-path ext content & {:keys [now suffix-fn]
                                     :or {now #(js/Date.)
                                          suffix-fn random-suffix}}]
  (let [dir* (get-conflict-dir repo relative-path)]
    (fs/mkdirSync dir* #js {:recursive true})
    (loop [attempt 1]
      (let [new-path (node-path/join dir*
                                     (str (string/replace (.toISOString (now)) ":" "_")
                                          "." (suffix-fn) ".Desktop" ext))
            created (try
                      (create-file-exclusively! new-path content)
                      new-path
                      (catch :default e
                        (if (and (= "EEXIST" (.-code ^js e))
                                 (< attempt max-conflict-copy-attempts))
                          ::collision
                          (throw e))))]
        (if (keyword-identical? created ::collision)
          (recur (inc attempt))
          created)))))
