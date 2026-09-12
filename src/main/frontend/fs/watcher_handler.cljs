(ns frontend.fs.watcher-handler
  "Main ns that handles file watching events from electron's main process"
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [frontend.common.async-util :as async-util]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.db.file-based.model :as file-model]
            [frontend.db.model :as model]
            [frontend.fs :as fs]
            [frontend.handler.file-based.file :as file-handler]
            [frontend.handler.file-based.property :as file-property-handler]
            [frontend.handler.global-config :as global-config-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.page :as page-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.state :as state]
            [frontend.util.fs :as fs-util]
            [lambdaisland.glogi :as log]
            [logseq.common.config :as common-config]
            [logseq.common.path :as path]
            [logseq.common.util.block-ref :as block-ref]
            [promesa.core :as p]))

;; all IPC paths must be normalized! (via common-util/path-normalize)

(defn- set-missing-block-ids!
  "For every referred block in the content, fix their block ids in files if missing."
  [content]
  (when (string? content)
    (let [missing-blocks (->> (block-ref/get-all-block-ref-ids content)
                              (distinct)
                              (keep model/get-block-by-uuid)
                              (filter (fn [block]
                                        (not= (str (:id (:block/properties block)))
                                              (str (:block/uuid block))))))]
      (when (seq missing-blocks)
        (file-property-handler/batch-set-block-property-aux!
         (mapv
          (fn [b] [(:block/uuid b) :id (str (:block/uuid b))])
          missing-blocks))))))

(defn- handle-add-and-change!
  "Resolves to true once the new content was handed to alter-file. Returns nil
   when the path is hidden or the content is unchanged. The reopen reconcile
   counts the true results as changed files."
  [repo path content db-content ctime mtime backup?]
  (let [config (state/get-config repo)
        path-hidden-patterns (:hidden config)]
    (when-not (or (and (seq path-hidden-patterns)
                       (common-config/hidden? path path-hidden-patterns))
                  ;; File not changed
                  (= content db-content))
      (p/let [;; save the previous content in a versioned bak file to avoid data overwritten.
              _ (when backup?
                  (-> (when-let [repo-dir (config/get-local-dir repo)]
                        (file-handler/backup-file! repo-dir path db-content content))
                      (p/catch #(js/console.error "❌ Bak Error: " path %))))

              _ (file-handler/alter-file repo path content {:re-render-root? true
                                                            :from-disk? true
                                                            :fs/event :fs/local-file-change
                                                            :ctime ctime
                                                            :mtime mtime})
              _ (set-missing-block-ids! content)]
        true))))

(defn- <handle-changed
  "handle-changed! as a promise that settles once the event's work is done: the
   worker pull of the db content, the backup, the reparse or the page delete.
   Resolves to true when an add or change was handed to alter-file (see
   handle-add-and-change!). load-graph-files! awaits it, so its concurrency cap
   also bounds the pulls and reparses; handle-changed! leaves them detached."
  [type {:keys [dir path content stat global-dir] :as payload}]
  (let [repo (state/get-current-repo)]
    (when dir
      (let [;; Global directory events don't know their originating repo so we rely
          ;; on the client to correctly identify it
            repo (cond
                   global-dir repo
                   :else (config/get-local-repo dir))
            repo-dir (config/get-local-dir repo)
            {:keys [mtime ctime]} stat
            ext (keyword (path/file-ext path))]
        (when (contains? #{:org :md :markdown :css :js :edn :excalidraw :tldr} ext)
          (p/let [db-content (db-async/<get-file repo path)
                  exists-in-db? (not (nil? db-content))
                  db-content (or db-content "")]
            (when (or content (contains? #{"unlink" "unlinkDir" "addDir"} type))
              (cond
                (and (= "unlinkDir" type) dir)
                (state/pub-event! [:graph/dir-gone dir])

                (and (= "addDir" type) dir)
                (state/pub-event! [:graph/dir-back repo dir])

                (contains? (:file/unlinked-dirs @state/state) dir)
                nil

                (and (= "add" type)
                     (not= (string/trim content) (string/trim db-content)))
                (let [backup? (not (string/blank? db-content))]
                  (handle-add-and-change! repo path content db-content ctime mtime backup?))

                (and (= "change" type)
                     (= dir repo-dir)
                     (not (common-config/local-relative-asset? path)))
                (handle-add-and-change! repo path content db-content ctime mtime (not global-dir)) ;; no backup for global dir

                (and (= "unlink" type)
                     exists-in-db?)
                (p/let [dir-exists? (fs/file-exists? dir "")]
                  (when dir-exists?
                    (when-let [page-name (file-model/get-file-page path)]
                      (println "Delete page: " page-name ", file path: " path ".")
                      (page-handler/<delete! page-name #()))))

          ;; global config handling
                (and (= "change" type)
                     (= dir (global-config-handler/global-config-dir)))
                (when (= path "config.edn")
                  (file-handler/alter-global-file
                   (global-config-handler/global-config-path) content {:from-disk? true}))

                (and (= "change" type)
                     (not exists-in-db?))
                (js/console.error "Can't get file in the db: " path)

                (and (contains? #{"add" "change" "unlink"} type)
                     (string/ends-with? path "logseq/custom.css"))
                (do
                  (println "reloading custom.css")
                  (ui-handler/add-style-if-exists!))

                (contains? #{"add" "change" "unlink"} type)
                nil

                :else
                (log/error :fs/watcher-no-handler {:type type
                                                   :payload payload})))))))))

(defn handle-changed!
  [type payload]
  (<handle-changed type payload)
  ;; return nil, otherwise the entire db will be transferred by ipc
  nil)

(def ^:private reconcile-concurrency
  "Graph files the reopen reconcile works on at once. Each one costs a stat and
   a read IPC call, a worker pull and, when changed, a reparse. The former p/all
   started all of them at once and so queued one pull per graph file in the
   worker. ADR-003 step 1 caps it to test whether that queue causes the reopen
   stalls (H2). No bound on the stall is predicted: the LSPERF line measures it."
  16)

(def ^:private reconcile-delete-concurrency
  "Page deletes in flight at once for files that are gone from disk. Each is an
   outliner transact in the worker."
  4)

(defn- log-reconcile-perf!
  "ADR-003 instrumentation: one LSPERF line per reopen reconcile. max-queue-ms
   is the longest wait from the start of the file phase (after the page deletes
   and the 500 ms delay) until a file got a slot. max-file-ms is the slowest
   file, from its stat to its reparse settling."
  [{:keys [files deleted t0 files-t0 changed errors delete-errors max-file-ms max-queue-ms]}]
  (let [now (js/performance.now)
        round #(js/Math.round (or % 0))]
    (js/console.log
     (str "LSPERF "
          (js/JSON.stringify
           (clj->js {:event "reconcile"
                     :thread "ui"
                     :concurrency reconcile-concurrency
                     :files files
                     :changed changed
                     :deleted deleted
                     :errors errors
                     :delete-errors delete-errors
                     :total-ms (round (- now t0))
                     :files-ms (round (- now files-t0))
                     :max-file-ms (round max-file-ms)
                     :max-queue-ms (round max-queue-ms)
                     :t (round now)}))))))

(defn load-graph-files!
  "This fn replaces the former initial fs watcher"
  [graph]
  (when graph
    (let [repo-dir (config/get-repo-dir graph)
          t0 (js/performance.now)
          ;; LSPERF counters for this run, logged by log-reconcile-perf!
          *perf (volatile! {:changed 0 :errors 0 :delete-errors 0
                            :max-file-ms 0 :max-queue-ms 0})]
      ;; read all files in the repo dir, notify if readdir error
      (p/let [;; all paths, md/org included, or deleted-files would miss them
              db-files (db-async/<get-file-paths graph)
              [files deleted-files]
              (-> (fs/readdir repo-dir :path-only? true)
                  (p/chain (fn [files]
                             (->> files
                                  (map #(path/relative-path repo-dir %))
                                  (remove #(fs-util/ignored-path? repo-dir %))
                                  (sort-by (fn [f] [(not (string/starts-with? f "logseq/"))
                                                    (not (string/starts-with? f "journals/"))
                                                    (not (string/starts-with? f "pages/"))
                                                    (string/lower-case f)]))))
                           (fn [files]
                             (let [deleted-files (set/difference (set db-files) (set files))]
                               [files
                                deleted-files])))
                  (p/catch (fn [error]
                             (when-not (config/demo-graph? graph)
                               (js/console.error "reading" graph)
                               (state/pub-event! [:notification/show
                                                  {:content (str "The graph " graph " can not be read:" error)
                                                   :status :error
                                                   :clear? false}]))
                             [nil nil])))
              ;; notifies user when large initial change set is detected
              ;; NOTE: this is an estimation, not accurate
              notification-uid (when (or (> (abs (- (count db-files) (count files)))
                                            100)
                                         (> (count deleted-files)
                                            100))
                                 (prn ::init-watcher-large-change-set)
                                 (notification/show! "Loading changes from disk..."
                                                     :info
                                                     false))]
        (prn ::initial-watcher repo-dir {:deleted (count deleted-files)
                                         :total (count files)})
        (p/do!
         (when (seq deleted-files)
           (async-util/<map-bounded
            reconcile-delete-concurrency
            (fn [path]
              (-> (p/do
                   (when-let [page-name (file-model/get-file-page path)]
                     (println "Delete page: " page-name ", file path: " path ".")
                     (page-handler/<delete! page-name #())))
                  (p/catch (fn [e]
                             (vswap! *perf update :delete-errors inc)
                             (js/console.error "Reconcile: deleting the page of" path "failed:" e)))))
            deleted-files))
         (-> (p/delay 500) ;; workaround for notification ui not showing
             (p/then
              (fn [_]
                (let [files-t0 (js/performance.now)]
                  (p/let [_ (async-util/<map-bounded
                             reconcile-concurrency
                             (fn [file-rpath]
                               (let [start (js/performance.now)]
                                 (vswap! *perf update :max-queue-ms max (- start files-t0))
                                 ;; Caught per file: one unreadable or failing file is
                                 ;; logged and counted, and the others still load.
                                 (-> (p/let [stat (fs/stat repo-dir file-rpath)
                                             content (fs/read-file repo-dir file-rpath)
                                             type (if (db/file-exists? graph file-rpath)
                                                    "change"
                                                    "add")
                                             changed? (<handle-changed type
                                                                       {:dir repo-dir
                                                                        :path file-rpath
                                                                        :content content
                                                                        :stat stat})]
                                       (when (true? changed?)
                                         (vswap! *perf update :changed inc)))
                                     (p/catch (fn [e]
                                                (vswap! *perf update :errors inc)
                                                (js/console.error "Reconcile: loading" file-rpath "failed:" e)))
                                     (p/then (fn [_]
                                               (vswap! *perf update :max-file-ms max
                                                       (- (js/performance.now) start)))))))
                             files)]
                    (log-reconcile-perf! (assoc @*perf
                                                :files (count files)
                                                :deleted (count deleted-files)
                                                :t0 t0
                                                :files-t0 files-t0))))))
             (p/then (fn []
                       (when notification-uid
                         (prn ::init-notify)
                         (notification/clear! notification-uid)
                         (state/pub-event! [:notification/show {:content (str "The graph " graph " is loaded.")
                                                                :status :success
                                                                :clear? true}]))))
             (p/catch (fn [error]
                        (js/console.dir error)))))))))
