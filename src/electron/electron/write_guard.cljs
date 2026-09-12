(ns electron.write-guard
  "The compare-and-refuse guard of the writeFile IPC handler (electron.handler).
   A guarded write carries `expected`, what the app believes is on disk, and
   replaces the file only when the disk bytes equal it exactly. On refusal the
   disk is left alone; the renderer (frontend.fs.node) saves its proposed
   content as a conflict copy and reparses the file from disk.

   Only node's fs is required, not electron, so the node test build can load
   and test this ns against a temporary directory.

   Not covered: an external write between the comparison and the replacement
   (milliseconds, all synchronous in the main process). Ordinary filesystem
   APIs cannot close that window against an uncooperative writer."
  (:require ["fs" :as fs]))

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
   - :create   expected :absent and the file is missing: create it (flag wx,
               so a file created in between still refuses)
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

(defn- stat-map
  [path]
  (let [stat (fs/statSync path)]
    {:size (.-size stat)
     :mtime (.-mtime stat)
     :ctime (.-ctime stat)}))

(defn guarded-write!
  "Writes content to path only when the guard allows it, synchronously, with no
   yield between the comparison and the write. expected is a value of
   normalize-expected other than nil (the handler keeps legacy writes apart).
   before-replace, when given, runs right before an existing file is replaced
   (the handler's chmod). Returns a map for the renderer:
   - {:result \"written\" :size .. :mtime .. :ctime ..}
   - {:result \"mismatch\" :disk \"present\"|\"missing\"}
   - {:result \"exists\"}
   - {:result \"io-error\" :error message :code code}
   Nothing is written unless the result is \"written\"."
  [path content expected & {:keys [before-replace]}]
  (try
    (let [disk (read-snapshot path)]
      (case (guarded-write-decision disk expected)
        :write (do (when before-replace (before-replace path))
                   (fs/writeFileSync path content)
                   (assoc (stat-map path) :result "written"))
        :create (do (fs/writeFileSync path content #js {:flag "wx"})
                    (assoc (stat-map path) :result "written"))
        :mismatch {:result "mismatch"
                   :disk (if (keyword-identical? disk :missing) "missing" "present")}
        :exists {:result "exists"}
        :legacy (throw (ex-info "guarded-write! needs an expected value" {:path path}))))
    (catch :default e
      (if (= "EEXIST" (.-code ^js e))
        ;; created by someone else between the read and the wx create
        {:result "exists"}
        {:result "io-error"
         :error (str e)
         :code (some-> (.-code ^js e) str)}))))
