(ns electron.write-guard
  "The compare-and-refuse guard of the writeFile IPC handler (electron.handler).
   A guarded write carries `expected`, what the app believes is on disk, and
   replaces the file only when the disk bytes equal it exactly. On refusal the
   disk is left alone; the renderer (frontend.fs.node) saves its proposed
   content as a conflict copy and reparses the file from disk.

   The destination is never written in place. A replacement writes the new
   content to a temporary sibling first, then compares the destination once
   more and renames the temporary file over it, synchronously, with nothing
   in between. Anything failing before the rename leaves the destination's
   bytes as they were, and the rename itself is atomic, so a failed write
   (ENOSPC, EIO) can no longer truncate or half-write the file. A new file is
   published by hard-linking its temporary file to the destination, which
   never replaces an existing file.

   Only node's fs is required, not electron, so the node test build can load
   and test this ns against a temporary directory.

   Not covered: an external write between the last comparison and the rename
   (all synchronous in the main process, well under a millisecond). Ordinary
   filesystem APIs cannot close that window against an uncooperative writer.
   A replacement gives the file a new inode: its permission bits are copied,
   but hard links to it, its owner and extended attributes are not kept. A
   symlink is followed: its target is replaced and the link stays."
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            ["path" :as node-path]))

(defn normalize-expected
  "The expected argument of writeFile as a guard value:
   - nil: legacy, unguarded write
   - a string: the exact content the app believes is on disk
   - :absent: the file must not exist yet
   Over IPC the renderer sends :absent as {:absent true}: bean/->js would turn
   the keyword into the string \"absent\", which is also valid file content.
   The keyword and {:absent true} are accepted; any other value throws."
  [expected]
  (cond
    (nil? expected) nil
    (string? expected) expected
    (keyword-identical? expected :absent) :absent
    (and (map? expected) (true? (:absent expected))) :absent
    :else (throw (ex-info "writeFile: invalid expected argument"
                          {:expected expected}))))

(defn guarded-write-decision
  "What a guarded writeFile does. disk is the file's current bytes (a Buffer)
   or :missing; expected is a value of normalize-expected. Returns
   - :legacy   expected is nil: write unguarded, as before the guard
   - :write    the bytes equal expected: replace the file
   - :create   expected :absent and the file is missing: create it (never
               replacing a file created in between)
   - :mismatch the bytes differ, or content was expected and the file is
               missing (deleted or moved on disk)
   - :exists   expected :absent but the file exists
   Equal means byte for byte against the UTF-8 encoding of expected, with no
   trimming or normalization: a BOM, CRLF line ends, a trailing newline or
   trailing spaces all make a difference. The app's copy of a file comes from
   Buffer.toString (electron.utils/read-file), which keeps a BOM and CRLF, so an
   unchanged file re-encodes to the same bytes."
  [disk expected]
  (cond
    (nil? expected) :legacy
    (keyword-identical? expected :absent) (if (keyword-identical? disk :missing) :create :exists)
    (keyword-identical? disk :missing) :mismatch
    (.equals ^js disk (.from js/Buffer expected "utf8")) :write
    :else :mismatch))

(defn read-snapshot
  "The bytes of path, or :missing when it does not exist. Other errors throw."
  [path]
  (try
    (fs/readFileSync path)
    (catch :default e
      (if (= "ENOENT" (.-code ^js e))
        :missing
        (throw e)))))

(defn temp-path
  "A new name for a temporary sibling of path: in the same directory, so the
   rename stays on one filesystem, with a leading dot, which
   logseq.common.graph/ignored-path? skips, and an extension no graph format
   uses, so neither the file watcher nor graph parsing picks it up."
  [path]
  (node-path/join (node-path/dirname path)
                  (str "." (node-path/basename path) "."
                       (.toString (crypto/randomBytes 6) "hex") ".logseq-tmp")))

(defn- create-exclusively!
  "Creates path with flag wx, which throws EEXIST when it exists, writes content
   to it and flushes it to disk, so a rename or link publishes complete
   content. When anything fails after the create, removes the partial file it
   created and rethrows."
  [path content]
  (let [fd (fs/openSync path "wx")]
    (try
      (fs/writeFileSync fd content)
      (fs/fsyncSync fd)
      (fs/closeSync fd)
      (catch :default e
        (try (fs/closeSync fd) (catch :default _e nil))
        (try (fs/unlinkSync path) (catch :default _e nil))
        (throw e)))))

(defn- write-temp!
  "Writes content to a new temporary sibling of path (temp-path) and returns
   the temporary file's path. A name collision retries with a new name."
  [path content]
  (loop [attempt 1]
    (let [tmp (temp-path path)
          created (try
                    (create-exclusively! tmp content)
                    tmp
                    (catch :default e
                      (if (and (= "EEXIST" (.-code ^js e)) (< attempt 5))
                        ::collision
                        (throw e))))]
      (if (keyword-identical? created ::collision)
        (recur (inc attempt))
        created))))

(defn- remove-quietly!
  [path]
  (try (fs/unlinkSync path) (catch :default _e nil)))

(defn rename-file!
  "fs.renameSync, as a var of its own so the tests can make it fail."
  [from to]
  (fs/renameSync from to))

(defn link-file!
  "fs.linkSync, as a var of its own so the tests can simulate a filesystem
   without hard links."
  [from to]
  (fs/linkSync from to))

(defn- stat-map
  [^js stat]
  {:size (.-size stat)
   :mtime (.-mtime stat)
   :ctime (.-ctime stat)})

(defn- io-error
  [e]
  {:result "io-error"
   :error (str e)
   :code (some-> (.-code ^js e) str)})

(defn- mismatch
  [disk]
  {:result "mismatch"
   :disk (if (keyword-identical? disk :missing) "missing" "present")})

(def ^:private permission-bits
  "07777: the mode bits chmod sets"
  4095)

(defn- replace-file!
  "The :write case of guarded-write!: path held expected when it was read.
   A symlink is resolved, so its target is replaced and the link stays.
   1. The content goes to a temporary sibling of the target (write-temp!).
   2. Synchronously, with no yield: before-replace runs on the target (the
      handler's chmod of a read-only file), the target is compared with
      expected once more, the temporary file gets the target's permission
      bits and is renamed over the target.
   On any failure, and on a refusal at step 2, the temporary file is removed
   and the target's bytes are as they were. The rename is the last step that
   can fail, so there is no failure after the destination changed: the stat
   of the result is the temporary file's, taken right before the rename
   (a rename keeps size and mtime)."
  [path content expected before-replace]
  (let [target (try
                 (fs/realpathSync path)
                 (catch :default e
                   (if (= "ENOENT" (.-code ^js e)) ::missing (throw e))))]
    (if (keyword-identical? target ::missing)
      (mismatch :missing)
      (let [tmp (write-temp! target content)
            result (try
                     (when before-replace (before-replace target))
                     (let [disk (read-snapshot target)]
                       (if (keyword-identical? :write (guarded-write-decision disk expected))
                         (do
                           (fs/chmodSync tmp (bit-and (.-mode (fs/statSync target)) permission-bits))
                           (let [stat (fs/statSync tmp)]
                             (rename-file! tmp target)
                             (assoc (stat-map stat) :result "written")))
                         (mismatch disk)))
                     (catch :default e
                       (io-error e)))]
        (when-not (= "written" (:result result))
          (remove-quietly! tmp))
        result))))

(defn- create-file!
  "The :create case of guarded-write!: path did not exist when it was read.
   The content goes to a temporary sibling, which is then hard-linked to
   path: a link never replaces an existing file (EEXIST: \"exists\") and
   publishes the complete content at once. The temporary name is removed
   afterwards. Where hard links fail for another reason (a filesystem without
   them), path is created directly with flag wx, still exclusively; a failed
   write there removes the partial file it created."
  [path content]
  (let [tmp (write-temp! path content)
        linked (try
                 (let [stat (fs/statSync tmp)]
                   (link-file! tmp path)
                   (assoc (stat-map stat) :result "written"))
                 (catch :default e
                   (if (= "EEXIST" (.-code ^js e))
                     {:result "exists"}
                     ::no-link)))]
    (remove-quietly! tmp)
    (if (keyword-identical? linked ::no-link)
      (try
        (create-exclusively! path content)
        (assoc (or (try (stat-map (fs/statSync path)) (catch :default _e nil)) {})
               :result "written")
        (catch :default e
          (if (= "EEXIST" (.-code ^js e))
            {:result "exists"}
            (io-error e))))
      linked)))

(defn guarded-write!
  "Writes content to path only when the guard allows it, synchronously, with no
   yield between the last comparison and the replacement (replace-file!,
   create-file!). expected is a value of normalize-expected other than nil
   (the handler keeps legacy writes apart). before-replace, when given, runs
   on the file to be replaced right before its last comparison (the handler's
   chmod). Returns a map for the renderer:
   - {:result \"written\" :size .. :mtime .. :ctime ..}
   - {:result \"mismatch\" :disk \"present\"|\"missing\"}
   - {:result \"exists\"}
   - {:result \"io-error\" :error message :code code}
   Only \"written\" changed the destination. On every other result its bytes
   are as they were (before-replace may have changed its mode) and no
   temporary file is left."
  [path content expected & {:keys [before-replace]}]
  (try
    (let [disk (read-snapshot path)]
      (case (guarded-write-decision disk expected)
        :write (replace-file! path content expected before-replace)
        :create (create-file! path content)
        :mismatch (mismatch disk)
        :exists {:result "exists"}
        :legacy (throw (ex-info "guarded-write! needs an expected value" {:path path}))))
    (catch :default e
      (io-error e))))
