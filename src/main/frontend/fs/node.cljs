(ns frontend.fs.node
  "Implementation of fs protocol for Electron, based on nodejs"
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.fs.protocol :as protocol]
            [frontend.state :as state]
            [frontend.util :as util]
            [goog.object :as gobj]
            [lambdaisland.glogi :as log]
            [logseq.common.path :as path]
            [promesa.core :as p]))

(defn- <contents-matched?
  [disk-content db-content]
  (when (and (string? disk-content) (string? db-content))
    (p/resolved (= (string/trim disk-content) (string/trim db-content)))))

(defn guard-expected
  "The expected argument of a guarded writeFile (electron.write-guard), or nil
   for a legacy, unguarded write.
   A write is guarded when the caller passes :old-content, the content the app
   last saw on disk (alter-files, alter-file), and not :skip-compare?. Callers
   without :old-content (assets, draw, create-if-not-exists, ...) or with
   :skip-compare? (sync, diff, git, plugins, global config, ...) keep the
   legacy write.
   A nil :old-content means the db has no content for the file, i.e. a new
   file, which must not exist on disk yet: it is sent as {:absent true}. An
   empty string is an existing empty file and is compared like any content."
  [{:keys [old-content skip-compare?] :as opts}]
  (when (and (not skip-compare?) (contains? opts :old-content))
    (cond
      (string? old-content) old-content
      (nil? old-content) {:absent true})))

(defn- <handle-refused-write!
  "A guarded writeFile refused to write rpath: the disk no longer holds what the
   app last saw (\"mismatch\"), or a new file's path is taken (\"exists\"). The
   disk wins. The proposed content is saved as a conflict copy under
   logseq/bak/conflicts/ (never pruned, electron.backup-file/create-conflict-copy!),
   a warning that stays until dismissed names the copy, and the file is
   reparsed from disk (:file/reparse-from-disk) so the db follows the disk.
   When the copy cannot be saved there is no reparse, which would drop the
   proposed content from the db as well, and the notice is an error. Resolves
   to the writeFile result."
  [repo dir rpath content result]
  (let [outcome (gobj/get result "result")
        reason (cond
                 (= "exists" outcome)
                 (str "a file " rpath " already exists on disk that the app had not loaded")

                 (= "missing" (gobj/get result "disk"))
                 (str rpath " was deleted or moved on disk")

                 :else
                 (str rpath " was changed on disk since the app last read it"))]
    (log/warn :write-file/refused {:path rpath :result outcome})
    (p/let [copy-path (-> (ipc/ipc "backupConflictFile" dir rpath content)
                          (p/catch (fn [error]
                                     (log/error :write-file/conflict-copy-failed {:path rpath :error error})
                                     nil)))]
      (if (string? copy-path)
        (do
          (state/pub-event! [:notification/show
                             {:content (str "Your change was not saved: " reason
                                            ". Your version was saved to " copy-path
                                            ". The app now shows the file as it is on disk.")
                              :status :warning
                              :clear? false}])
          (state/pub-event! [:file/reparse-from-disk repo rpath]))
        (state/pub-event! [:notification/show
                           {:content (str "Your change was not saved: " reason
                                          ", and saving your version to logseq/bak/conflicts/ failed."
                                          " Copy your changes elsewhere before editing this page again.")
                            :status :error
                            :clear? false}]))
      result)))

(defn- write-file-impl!
  "Writes content to rpath under dir through the writeFile IPC handler.
   - :skip-compare?: legacy write, no comparison.
   - :old-content passed (see guard-expected): guarded write. Electron replaces
     the file only when its bytes are exactly old-content, and creates it only
     when it does not exist (nil old-content). On a refusal the proposed content
     becomes a conflict copy and the file is reparsed from disk
     (<handle-refused-write!); ok-handler is not called. On an io error the
     user is notified and error-handler gets the error.
   - otherwise: legacy write, which also backs up the disk content when it
     differs from the db's (trimmed) and the write deletes text."
  [repo dir rpath content {:keys [ok-handler error-handler old-content skip-compare? skip-transact?] :as opts} stat]
  (let [file-fpath (path/path-join dir rpath)
        expected (guard-expected opts)]
    (cond
      skip-compare?
      (p/catch
       (p/let [result (ipc/ipc "writeFile" repo file-fpath content)]
         (when ok-handler
           (ok-handler repo rpath result)))
       (fn [error]
         (if error-handler
           (error-handler error)
           (log/error :write-file-failed error))))

      (some? expected)
      (-> (p/let [result (-> (ipc/ipc "writeFile" repo file-fpath content expected)
                             ;; a throwing IPC handler resolves to its error, which
                             ;; promesa turns into a rejection: the outcome is unknown
                             (p/catch (fn [error] #js {:result "io-error" :error (str error)})))
                  outcome (when (object? result) (gobj/get result "result"))]
            (case outcome
              "written"
              (do
                (when-not skip-transact?
                  (db/set-file-last-modified-at! repo rpath (gobj/get result "mtime")))
                (when ok-handler
                  (ok-handler repo rpath result))
                result)

              ("mismatch" "exists")
              (<handle-refused-write! repo dir rpath content result)

              ;; io-error, or an unexpected result: the disk was not changed
              ;; (electron.write-guard). Resolves to a result whose outcome
              ;; is "io-error", so callers (alter-file) can tell.
              (let [message (str "Write to the file " file-fpath " failed: "
                                 (or (when (object? result) (gobj/get result "error"))
                                     (str "unexpected result " (pr-str outcome))))
                    error (ex-info message {:path file-fpath :result outcome})]
                (state/pub-event! [:notification/show {:content message
                                                       :status :error
                                                       :clear? false}])
                (if error-handler
                  (error-handler error)
                  (log/error :write-file-failed error))
                (if (= "io-error" outcome)
                  result
                  #js {:result "io-error" :error message}))))
          (p/catch (fn [error]
                     (if error-handler
                       (error-handler error)
                       (log/error :write-file-failed error)))))

      :else
      (p/let [disk-content (when (not= stat :not-found)
                             (-> (ipc/ipc "readFile" file-fpath)
                                 (p/then bean/->clj)
                                 (p/catch (fn [error]
                                            (js/console.error error)
                                            nil))))
              disk-content (or disk-content "")
              db-content (or old-content (db/get-file repo rpath) "")
              contents-matched? (<contents-matched? disk-content db-content)]
        (->
         (p/let [result (ipc/ipc "writeFile" repo file-fpath content)
                 mtime (gobj/get result "mtime")]
           (when-not contents-matched?
             (ipc/ipc "backupDbFile" (config/get-local-dir repo) rpath disk-content content))
           (when-not skip-transact? (db/set-file-last-modified-at! repo rpath mtime))
           (when ok-handler
             (ok-handler repo rpath result))
           result)
         (p/catch (fn [error]
                    (if error-handler
                      (error-handler error)
                      (log/error :write-file-failed error)))))))))

(defn- open-dir
  "Open a new directory"
  [dir]
  (p/let [dir-path (or dir (util/mocked-open-dir-path))
          result (if dir-path
                   (do
                     (println "NOTE: Using mocked dir" dir-path)
                     (ipc/ipc "getFiles" dir-path))
                   (ipc/ipc "openDir" {}))
          result (bean/->clj result)]
    result))

(defrecord Node []
  protocol/Fs
  (mkdir! [_this dir]
    (-> (ipc/ipc "mkdir" dir)
        (p/then (fn [_] (js/console.log (str "Directory created: " dir))))
        (p/catch (fn [error]
                   (when-not (string/includes? (str error) "EEXIST")
                     (js/console.error (str "Error creating directory: " dir) error))))))

  (mkdir-recur! [_this dir]
    (ipc/ipc "mkdir-recur" dir))

  (readdir [_this dir]                   ; recursive
    (p/then (ipc/ipc "readdir" dir)
            bean/->clj))

  (unlink! [_this repo path _opts]
    (ipc/ipc "unlink"
             (config/get-repo-dir repo)
             path))
  (rmdir! [_this _dir]
    ;; !Too dangerous! We'll never implement this.
    nil)

  (read-file [_this dir path _options]
    (let [path (if (nil? dir)
                 path
                 (path/path-join dir path))]
      (ipc/ipc "readFile" path)))

  (read-file-raw [_this dir path _options]
    (let [path (if (nil? dir)
                 path
                 (path/path-join dir path))]
      (ipc/ipc "readFileRaw" path)))

  (write-file! [this repo dir path content opts]
    (p/let [fpath (path/path-join dir path)
            stat (p/catch
                  (protocol/stat this fpath)
                  (fn [_e] :not-found))
            parent-dir (path/parent fpath)
            _ (protocol/mkdir-recur! this parent-dir)]
      (write-file-impl! repo dir path content opts stat)))

  (rename! [_this _repo old-path new-path]
    (ipc/ipc "rename" old-path new-path))
  ;; copy with overwrite, without confirmation
  (copy! [_this repo old-path new-path]
    (ipc/ipc "copyFile" repo old-path new-path))
  (stat [_this fpath]
    (-> (ipc/ipc "stat" fpath)
        (p/then bean/->clj)))

  (open-dir [_this dir]
    (open-dir dir))

  (get-files [_this dir]
    (-> (ipc/ipc "getFiles" dir)
        (p/then (fn [result]
                  (:files (bean/->clj result))))))

  (watch-dir! [_this dir options]
    (ipc/ipc "addDirWatcher" dir options))

  (unwatch-dir! [_this dir]
    (ipc/ipc "unwatchDir" dir)))
