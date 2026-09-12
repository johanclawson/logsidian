(ns frontend.handler.file-based.file
  "Provides util handler fns for file graph files"
  (:refer-clojure :exclude [load-file])
  (:require [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.db.file-based.model :as file-model]
            [frontend.fs :as fs]
            [frontend.handler.common.config-edn :as config-edn-common-handler]
            [frontend.handler.global-config :as global-config-handler]
            [frontend.handler.repo-config :as repo-config-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.schema.handler.global-config :as global-config-schema]
            [frontend.schema.handler.repo-config :as repo-config-schema]
            [frontend.state :as state]
            [frontend.util :as util]
            [frontend.worker.file.reset :as file-reset]
            [goog.object :as gobj]
            [lambdaisland.glogi :as log]
            [logseq.common.config :as common-config]
            [logseq.common.path :as path]
            [logseq.common.util :as common-util]
            [promesa.core :as p]))

;; TODO: extract all git ops using a channel

(defn load-file
  [repo-url path]
  (->
   (p/let [content (fs/read-file (config/get-repo-dir repo-url) path)]
     content)
   (p/catch
    (fn [e]
      (println "Load file failed: " path)
      (js/console.error e)))))

(defn reset-file!
  [repo file-path content opts]
  (if util/node-test?
    (file-reset/reset-file! repo (db/get-db repo false) file-path content opts)
    (state/<invoke-db-worker :thread-api/reset-file repo file-path content opts)))

(defn- load-multiple-files
  [repo-url paths]
  (doall
   (mapv #(load-file repo-url %) paths)))

(defn- keep-formats
  [files formats]
  (filter
   (fn [file]
     (let [format (common-util/get-format file)]
       (contains? formats format)))
   files))

(defn- only-text-formats
  [files]
  (keep-formats files (common-config/text-formats)))

(defn- only-image-formats
  [files]
  (keep-formats files (common-config/img-formats)))

(defn load-files-contents!
  [repo-url files ok-handler]
  (let [images (only-image-formats files)
        files (only-text-formats files)]
    (-> (p/all (load-multiple-files repo-url files))
        (p/then (fn [contents]
                  (let [file-contents (cond->
                                       (zipmap files contents)

                                        (seq images)
                                        (merge (zipmap images (repeat (count images) ""))))
                        file-contents (for [[file content] file-contents]
                                        {:file/path (common-util/path-normalize file)
                                         :file/content content})]
                    (ok-handler file-contents))))
        (p/catch (fn [error]
                   (log/error :fs/load-files-error repo-url)
                   (log/error :exception error))))))

(defn backup-file!
  "Backup db content to bak directory"
  [repo-url path db-content content]
  (when (util/electron?)
    (ipc/ipc "backupDbFile" repo-url path db-content content)))

(defn- detect-deprecations
  [path content]
  (when (or (= path "logseq/config.edn")
            (= (path/dirname path) (global-config-handler/safe-global-config-dir)))
    (config-edn-common-handler/detect-deprecations path content {:db-graph? false})))

(defn- validate-file
  "Returns true if valid and if false validator displays error message. Files
  that are not validated just return true"
  [path content]
  (cond
    (= path "logseq/config.edn")
    (config-edn-common-handler/validate-config-edn path content repo-config-schema/Config-edn)

    (= (path/dirname path) (global-config-handler/safe-global-config-dir))
    (config-edn-common-handler/validate-config-edn path content global-config-schema/Config-edn)

    :else
    true))

(defn- write-file-aux!
  "Writes content to path as a guarded write (fs.node/guard-expected).
   original-content is the db's content of path from before alter-file changed
   the db, i.e. what the app last saw on disk, or nil for a new file. It used
   to be read here, after alter-file had already put content into the db, so
   it was the new content rather than the base."
  [repo path content original-content write-file-options]
  (let [path-dir (config/get-repo-dir repo)
        write-file-options' (assoc write-file-options :old-content original-content)]
    (fs/write-plain-text-file! repo path-dir path content write-file-options')))

(defn alter-global-file
  "Does pre-checks on a global file, writes if it's not already written
  (:from-disk? is not set) and then does post-checks. Currently only handles
  global config.edn but can be extended as needed"
  [path content {:keys [from-disk?]}]
  (if (and path (= path (global-config-handler/safe-global-config-path)))
    (do
      (detect-deprecations path content)
      (when (validate-file path content)
        (-> (p/let [_ (when-not from-disk?
                        (fs/write-plain-text-file! "" nil path content {:skip-compare? true}))]
              (p/do! (global-config-handler/restore-global-config!)
                     (state/pub-event! [:shortcut/refresh])))
            (p/catch (fn [error]
                       (state/pub-event! [:notification/show
                                          {:content (str "Failed to write to file " path ", error: " error)
                                           :status :error}])
                       (log/error :write/failed error)
                       (state/pub-event! [:capture-error
                                          {:error error
                                           :payload {:type :write-file/failed-for-alter-file}}]))))))
    (log/error :msg "alter-global-file does not support this file" :file path)))

(defn apply-written-content?
  "Whether alter-file applies the content beyond the db once written, i.e.
   restores the repo config from it or reloads it as the custom CSS.
   - from-disk?: the content came from disk, nothing was written: applies.
     This is also how a refused write's reparse (fs.node) applies the disk's
     content instead of the refused one.
   - a guarded write (fs.node) resolves to a result with an outcome: applies
     only on \"written\"; not on \"mismatch\"/\"exists\" (refused, the reparse
     follows) or \"io-error\" (the disk was not changed).
   - a write reporting no outcome (skip-compare?, the browser backends):
     applies, as before the guard."
  [from-disk? write-result]
  (or (boolean from-disk?)
      (let [outcome (when (some? write-result) (gobj/get write-result "result"))]
        (or (nil? outcome) (= "written" outcome)))))

(defn alter-file
  "Write any in-DB file, e.g. repo config, page, whiteboard, etc."
  [repo path content {:keys [reset? re-render-root? from-disk? skip-compare? new-graph? verbose
                             ctime mtime]
                      :fs/keys [event]
                      :or {reset? true
                           re-render-root? false
                           from-disk? false
                           skip-compare? false}}]
  (let [path (common-util/path-normalize path)
        config-file? (= path "logseq/config.edn")
        _ (when config-file?
            (detect-deprecations path content))
        config-valid? (and config-file? (validate-file path content))]
    (when (or config-valid? (not config-file?)) ; non-config file or valid config
      (let [opts {:new-graph? new-graph?
                  :from-disk? from-disk?
                  :fs/event event
                  :ctime ctime
                  :mtime mtime}
            ;; the base of the guarded write below, read before this fn puts
            ;; content into the db
            original-content (when-not from-disk? (db/get-file repo path))]
        (-> (p/let [result (if reset?
                             (p/do!
                              (when-let [page-id (file-model/get-file-page-id path)]
                                (db/transact! repo
                                              [[:db/retract page-id :block/alias]
                                               [:db/retract page-id :block/tags]]
                                              opts))
                              (reset-file!
                               repo path content (merge opts
                                                         ;; To avoid skipping the `:or` bounds for keyword destructuring
                                                        (when (some? verbose) {:verbose verbose}))))
                             (db/set-file-content! repo path content opts))
                    write-result (when-not from-disk?
                                   (write-file-aux! repo path content original-content {:skip-compare? skip-compare?}))]
              (when re-render-root? (ui-handler/re-render-root!))

              (if (apply-written-content? from-disk? write-result)
                (cond
                  (= path "logseq/custom.css")
                  (do
                    ;; ui-handler will load css from db and config
                    (db/set-file-content! repo path content)
                    (ui-handler/add-style-if-exists!))

                  (= path "logseq/config.edn")
                  (p/let [_ (repo-config-handler/restore-repo-config! repo content)]
                    (state/pub-event! [:shortcut/refresh])))
                (log/warn :alter-file/not-applied
                          {:path path
                           :result (gobj/get write-result "result")}))

              result)
            (p/catch
             (fn [error]
               (println "Write file failed, path: " path ", content: " content)
               (log/error :write/failed error)
               (state/pub-event! [:capture-error
                                  {:error error
                                   :payload {:type :write-file/failed-for-alter-file}}]))))))))

(defn alter-file-test-version
  "Test version of alter-file that is synchronous"
  [repo path content {:keys [reset? from-disk? new-graph? verbose
                             ctime mtime]
                      :fs/keys [event]
                      :or {reset? true
                           from-disk? false}}]
  (let [path (common-util/path-normalize path)
        config-file? (= path "logseq/config.edn")
        _ (when config-file?
            (detect-deprecations path content))
        config-valid? (and config-file? (validate-file path content))]
    (when (or config-valid? (not config-file?)) ; non-config file or valid config
      (let [opts {:new-graph? new-graph?
                  :from-disk? from-disk?
                  :fs/event event
                  :ctime ctime
                  :mtime mtime}
            result (if reset?
                     (do
                       (when-let [page-id (file-model/get-file-page-id path)]
                         (db/transact! repo
                                       [[:db/retract page-id :block/alias]
                                        [:db/retract page-id :block/tags]]
                                       opts))
                       (reset-file!
                        repo path content (merge opts
                                                         ;; To avoid skipping the `:or` bounds for keyword destructuring
                                                 (when (some? verbose) {:verbose verbose}))))
                     (db/set-file-content! repo path content opts))]
        result))))

(defn set-file-content!
  [repo path new-content]
  (alter-file repo path new-content {:reset? false
                                     :re-render-root? false}))

(defn- alter-files-handler!
  [repo files {:keys [finish-handler]} file->content]
  (let [write-file-f (fn [[path content]]
                       (when path
                         (let [path (common-util/path-normalize path)
                               original-content (get file->content path)]
                           (-> (fs/write-plain-text-file! repo (config/get-repo-dir repo) path content
                                                          {:old-content original-content})
                               (p/catch (fn [error]
                                          (state/pub-event! [:notification/show
                                                             {:content (str "Failed to save the file " path ". Error: "
                                                                            (str error))
                                                              :status :error
                                                              :clear? false}])
                                          (state/pub-event! [:capture-error
                                                             {:error error
                                                              :payload {:type :write-file/failed}}])
                                          (log/error :write-file/failed {:path path
                                                                         :content content
                                                                         :error error})
                                          #js {:result "io-error" :error (str error)}))))))
        finish-handler (fn []
                         (when finish-handler
                           (finish-handler)))]
    ;; resolves to the write results, one per file (alter-files-outcome)
    (-> (p/all (map write-file-f files))
        (p/then (fn [results]
                  (finish-handler)
                  (vec results)))
        (p/catch (fn [error]
                   (println "Alter files failed:")
                   (js/console.error error)
                   [#js {:result "io-error" :error (str error)}])))))

(defn alter-files-outcome
  "The outcome of a page save for the worker (:thread-api/page-file-saved,
   frontend.worker.file/record-write-outcome!), from the write results
   alter-files resolves to:
   - :failed when a write failed (\"io-error\") or was refused without a
     conflict copy: the content may be only in the db;
   - else :refused when a write was refused (\"mismatch\"/\"exists\") and its
     proposal saved as a conflict copy (\"copy\");
   - else :written. A result without an outcome (skip-compare?, browser
     backends) counts as written."
  [results]
  (let [outcomes (map (fn [result]
                        (let [outcome (when (some? result) (gobj/get result "result"))
                              copy (when (some? result) (gobj/get result "copy"))]
                          (cond
                            (or (nil? outcome) (= "written" outcome)) :written
                            (and (contains? #{"mismatch" "exists"} outcome) (string? copy)) :refused
                            :else :failed)))
                      results)]
    (cond
      (some #{:failed} outcomes) :failed
      (some #{:refused} outcomes) :refused
      :else :written)))

(defn alter-files
  "Writes files, [path content] pairs, as guarded writes.
   With :base in opts (a page save from the worker, which posts one file per
   message), base is every write's expected content (nil: a new file) and
   the db is not updated: the worker already put the content into
   :file/content when it stamped the base (frontend.worker.file/send-proposal!).
   Advancing it again here would let a refused, stale proposal overwrite the
   disk content its refusal's reparse installed. Without :base the expected
   content is the db's current content, and update-db? (default true) puts
   the new content into the db."
  [repo files {:keys [reset? update-db?]
               :or {reset? false
                    update-db? true}
               :as opts}]
  ;; old file content
  (let [stamped? (contains? opts :base)
        update-db? (and update-db? (not stamped?))
        file->content (if stamped?
                        (zipmap (map (comp common-util/path-normalize first) files)
                                (repeat (:base opts)))
                        (let [paths (map first files)]
                          (zipmap paths
                                  (map (fn [path] (db/get-file repo path)) paths))))]
    ;; update db
    (when update-db?
      (p/all
       (map
        (fn [[path content]]
          (if reset?
            (reset-file! repo path content {})
            (db/set-file-content! repo path content)))
        files)))
    (alter-files-handler! repo files opts file->content)))

(defn watch-for-current-graph-dir!
  []
  (when-let [repo (state/get-current-repo)]
    (when-let [dir (config/get-repo-dir repo)]
      ;; An unwatch shouldn't be needed on startup. However not having this
      ;; after an app refresh can cause stale page data to load
      (fs/unwatch-dir! dir)
      (fs/watch-dir! dir))))
