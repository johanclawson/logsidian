(ns frontend.fs.watcher-handler
  "Main ns that handles file watching events from electron's main process"
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [electron.ipc :as ipc]
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

(defn- missing-id-blocks
  "The blocks of the current repo's db referred to by ref-ids whose id:: property
   is missing from their file."
  [ref-ids]
  (->> ref-ids
       (distinct)
       (keep model/get-block-by-uuid)
       (filter (fn [block]
                 (not= (str (:id (:block/properties block)))
                       (str (:block/uuid block)))))))

(defn- set-missing-block-ids!
  "For every referred block in the content, fix their block ids in files if missing.
   The fix saves the block from its db content, which the worker then writes to
   the block's file. That write is guarded (fs.node, electron.write-guard): if
   the file changed on disk since the db read it, the write is refused, the
   proposed content goes to a conflict copy and the file is reparsed. The
   reopen reconcile still defers the fix until its files are loaded
   (<run-deferred-id-repairs!), so offline edits do not end in refusals."
  [content]
  (when (string? content)
    (let [missing-blocks (missing-id-blocks (block-ref/get-all-block-ref-ids content))]
      (when (seq missing-blocks)
        (file-property-handler/batch-set-block-property-aux!
         (mapv
          (fn [b] [(:block/uuid b) :id (str (:block/uuid b))])
          missing-blocks))))))

(defn- handle-add-and-change!
  "Resolves to true once the new content was handed to alter-file. Returns nil
   when the path is hidden or the content is unchanged. The reopen reconcile
   counts the true results as changed files.
   on-id-repair, when given, receives the content instead of
   set-missing-block-ids! running on it: the reopen reconcile collects the
   repairs and runs them after its last file. Live watcher events pass nil and
   repair at once, as before.
   alter-opts are merged into alter-file's options; on-altered, when given,
   receives what alter-file resolved to (<reparse-from-disk!)."
  [repo path content db-content ctime mtime backup? on-id-repair & {:keys [alter-opts on-altered]}]
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

              altered (file-handler/alter-file repo path content
                                               (merge {:re-render-root? true
                                                       :from-disk? true
                                                       :fs/event :fs/local-file-change
                                                       :ctime ctime
                                                       :mtime mtime}
                                                      alter-opts))
              _ (when on-altered (on-altered altered))
              _ (if on-id-repair
                  (on-id-repair content)
                  (set-missing-block-ids! content))]
        true))))

(defn- <handle-changed
  "handle-changed! as a promise that settles once the event's work is done: the
   worker pull of the db content, the backup, the reparse or the page delete.
   Resolves to true when an add or change was handed to alter-file (see
   handle-add-and-change!). load-graph-files! awaits it, so its file-phase bound
   also bounds the pulls and reparses; handle-changed! leaves them detached.
   on-pull-ms, when given, is called with the round trip of the worker pull, so
   the reconcile can log it. on-id-repair is passed to handle-add-and-change!."
  [type {:keys [dir path content stat global-dir] :as payload} & {:keys [on-pull-ms on-id-repair]}]
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
          (p/let [pull-t0 (js/performance.now)
                  db-content (db-async/<get-file repo path)
                  _ (when on-pull-ms (on-pull-ms (- (js/performance.now) pull-t0)))
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
                  (handle-add-and-change! repo path content db-content ctime mtime backup? on-id-repair))

                (and (= "change" type)
                     (= dir repo-dir)
                     (not (common-config/local-relative-asset? path)))
                (handle-add-and-change! repo path content db-content ctime mtime (not global-dir) on-id-repair) ;; no backup for global dir

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

(def reparse-missing-waits-ms
  "The waits, in ms, before each new look at a file that <reparse-from-disk!
   found missing, about 2 s in all: a sync client, or an editor that saves by
   delete and rename, can make a file vanish for a moment."
  [250 250 500 500 500])

(defn <await-presence
  "Calls probe-f, which resolves to :present or :missing (fs/<path-state),
   until it resolves to :present or waits-ms runs out, waiting the next
   element of waits-ms before each further call. Resolves to the last state;
   rejects when probe-f rejects (a failure other than absence)."
  [probe-f waits-ms]
  (p/let [state (probe-f)]
    (if (or (= :present state) (empty? waits-ms))
      state
      (p/let [_ (p/delay (first waits-ms))]
        (<await-presence probe-f (rest waits-ms))))))

(defn refusal-notice
  "The warning after a refused write (<reparse-from-disk!): why, where the
   refused version and the snapshot copy (or nil) are, and what the app
   shows now; kept, when not nil, is why the db was kept as it was."
  [{:keys [reason copy-path]} snapshot-copy kept]
  (str "Your change was not saved: " reason
       ". Your version was saved to " copy-path
       (when snapshot-copy
         (str ", and the page as it was in the app before reloading, with the edits made meanwhile, to "
              snapshot-copy))
       "."
       (if kept
         (str " The app could not reload the file from disk (" kept
              ") and keeps showing the page as it was; nothing was deleted.")
         " The app now shows the file as it is on disk.")))

(defn <reparse-from-disk!
  "Makes the db of repo follow the disk for rpath after a guarded writeFile
   refused to replace it (fs.node): reparses the file's disk content like a
   watcher change event. Unlike a watcher change it backs up nothing, since
   fs.node already saved the refused content as a conflict copy.
   It never deletes anything, neither in the db nor on disk. A missing file
   is looked for again for about 2 s (waits-ms, default
   reparse-missing-waits-ms). If it stays missing, or its stat fails for
   another reason than absence (fs/<path-state), the db keeps the page as it
   is and a warning says so. The unlink path is never taken from here: its
   page deletion ends in after-page-deleted!, which unlinks the path when it
   exists again, e.g. restored by a sync client meanwhile. A file that is
   really gone reaches that path through the watcher's own unlink event.
   refusal, from fs.node, is {:reason .. :copy-path .. :proposal ..}: why
   the write was refused, its conflict copy and its content. The reset then
   snapshots the page right before replacing it (:snapshot-before?, in the
   worker's same call). A snapshot that differs from the proposal and from
   the disk holds edits committed after the proposal, which the reset
   replaces: it is saved as a second conflict copy. One warning names the
   copies once all is done (refusal-notice).
   Resolves once done; failures are logged."
  [repo rpath & {:keys [waits-ms refusal] :or {waits-ms reparse-missing-waits-ms}}]
  (let [repo-dir (config/get-repo-dir repo)
        *snapshot (atom nil)
        *disk (atom nil)
        finish! (fn [kept]
                  (when kept
                    (log/warn :reparse-from-disk/db-kept {:path rpath :reason kept}))
                  (p/let [snapshot @*snapshot
                          snapshot-copy (when (and refusal
                                                   (string? snapshot)
                                                   (not= snapshot (:proposal refusal))
                                                   (not= snapshot @*disk))
                                          (-> (ipc/ipc "backupConflictFile" repo-dir rpath snapshot)
                                              (p/catch (fn [e]
                                                         (log/error :reparse-from-disk/snapshot-copy-failed
                                                                    {:path rpath :error e})
                                                         nil))))]
                    (cond
                      refusal
                      (notification/show!
                       (refusal-notice refusal (when (string? snapshot-copy) snapshot-copy) kept)
                       :warning
                       false)

                      kept
                      (notification/show!
                       (str "The app could not reload " rpath " from disk: " kept
                            ". It keeps showing the page as it was and deleted nothing.")
                       :warning
                       false))))]
    (-> (p/let [presence (<await-presence #(fs/<path-state repo-dir rpath) waits-ms)]
          (if (= :present presence)
            (p/let [stat (-> (fs/stat repo-dir rpath) (p/catch (constantly nil)))
                    content (fs/read-file repo-dir rpath)
                    _ (reset! *disk content)
                    db-content (db-async/<get-file repo rpath)
                    _ (when (string? content)
                        (handle-add-and-change! repo rpath content db-content
                                                (:ctime stat) (:mtime stat) false nil
                                                :alter-opts (when refusal {:snapshot-before? true})
                                                :on-altered (fn [altered]
                                                              (when (map? altered)
                                                                (reset! *snapshot (:snapshot altered))))))]
              (finish! nil))
            (finish! "the file is not on disk (deleted or moved?)")))
        (p/catch (fn [e]
                   (js/console.error "Reparsing" rpath "from disk after a refused write failed:" e)
                   (finish! (str e)))))))

(def ^:private reconcile-experiment-cap
  "File-phase cap of the ADR-003 H2 experiment: at most this many graph files
   in flight at once in the reopen reconcile, instead of all of them. Used only
   while the experiment flag is set (reconcile-cap-flag), see
   reconcile-file-bound."
  16)

(def ^:private reconcile-cap-flag
  "localStorage key of the H2 experiment flag. When its value is \"true\" the
   reopen reconcile caps its file phase at reconcile-experiment-cap files in
   flight; otherwise (the default) every file starts at once. To run the H2
   bench, set it in the app before the reopen, from the DevTools console or
   the harness (page.evaluate):
     localStorage.setItem('lsperf-reconcile-cap', 'true')
   and remove it to go back:
     localStorage.removeItem('lsperf-reconcile-cap')
   It is read once per run. The LSPERF reconcile line logs the bound the run
   used as :concurrency."
  "lsperf-reconcile-cap")

(defn- reconcile-cap-flag?
  []
  (try
    (= "true" (some-> js/globalThis .-localStorage (.getItem reconcile-cap-flag)))
    (catch :default _e
      false)))

(defn reconcile-file-bound
  "How many of n items the reopen reconcile works on at once: all of them by
   default (eager fan-out), or at most reconcile-experiment-cap while the H2
   flag is set (reconcile-cap-flag). Never below 1.
   Each graph file costs a stat and a read IPC call, a worker pull and, when
   changed, a reparse. Eager fan-out starts all of them, as the former p/all
   did, and so queues one pull per graph file in the worker; the cap tests
   whether that queue causes the reopen stalls (H2). Both modes run on
   <map-bounded, so the per-file catch, the stale-run check before each file
   and the LSPERF timings are the same. A slot is held until the file's worker
   calls settle (pull, reset-file). Worker calls have no timeout
   (state/<invoke-db-worker*), so a call that never settles keeps its slot and
   the run, its LSPERF line and the \"Loading changes from disk...\" notice
   never finish."
  ([n] (reconcile-file-bound n (reconcile-cap-flag?)))
  ([n capped?]
   (max 1 (if capped? (min n reconcile-experiment-cap) n))))

(def ^:private reconcile-delete-concurrency
  "Page deletes in flight at once for files that are gone from disk. Each is an
   outliner transact in the worker."
  4)

(defonce ^:private *reconcile-run
  ;; Id of the latest load-graph-files! run. An older run takes no new items.
  (atom 0))

(defonce ^:private *deferred-id-repairs
  ;; graph -> #{ref-id string}: block refs in files the reopen reconcile changed,
  ;; whose missing-id repair waits for the end of a run
  ;; (<run-deferred-id-repairs!). Repairs a run may not do yet stay here for the
  ;; next run of that graph. In memory only, like the former immediate repair.
  (atom {}))

(defn plan-id-repairs
  "Which missing-id repairs deferred by a finished, still current reconcile run
   may be saved now (:run), and which wait for the next run of the graph
   (:defer). A repair is {:ref-id .. :path ..}, :path being the file of the
   block's page, or nil when the db does not know it.
   - A repair whose file failed in this run (stat, read, pull or page delete)
     waits: the db may lack that file's disk content. The guarded write would
     refuse to replace it, at the cost of a conflict copy and a warning.
   - A repair without a known file always waits (review finding 3), a clean
     run included: no file of the run was reconciled for it, and its save
     would allocate a new destination nobody checked. It runs in a later run,
     once the block's page has a file.
   The caller still checks each :run file against the db before saving
   (<paths-matching-db), which also catches a failed reparse: alter-file
   swallows those."
  [repairs {:keys [failed-paths]}]
  (let [ready? (fn [{:keys [path]}]
                 (and (some? path)
                      (not (contains? failed-paths path))))]
    {:run (filterv ready? repairs)
     :defer (filterv (complement ready?) repairs)}))

(defn- <paths-matching-db
  "The set of paths whose disk content equals the worker db's content exactly,
   as the guarded write compares them: no trim, which accepted differences in
   leading indentation and trailing whitespace (review finding 4). A block of
   such a file can be saved without dropping text that is only on disk. A path
   whose read or pull fails is left out."
  [graph repo-dir paths]
  (p/let [results (async-util/<map-bounded
                   (reconcile-file-bound (count paths))
                   (fn [path]
                     (-> (p/let [disk-content (fs/read-file repo-dir path)
                                 db-content (db-async/<get-file graph path)]
                           (when (and (string? disk-content)
                                      (string? db-content)
                                      (= disk-content db-content))
                             path))
                         (p/catch (fn [e]
                                    (js/console.error "Reconcile: checking" path "before an id repair failed:" e)
                                    nil))))
                   paths)]
    (set (filter string? results))))

(defn- <run-deferred-id-repairs!
  "Runs the missing-id repairs deferred by a reconcile run of graph, once every
   file of the run has settled. Resolves to the number of blocks repaired.

   Why deferred: a repair saves the referred block from its db content and the
   worker writes that to the block's file. That file may not be read yet when
   the repair is collected, e.g. a journal refers to a block of a page that was
   edited offline and the page has not settled: the repair would serialize the
   page from its stale db content. The write is guarded (fs.node,
   electron.write-guard), so that no longer overwrites the offline edit, but
   it is refused with a conflict copy and a warning. Deferring to the end of
   the run avoids that. Regression scenario for an integration test: page P's
   block B has a uuid in the db but no id:: in P's file; with the app closed,
   edit P and add ((B's uuid)) to journal J; reopen; P on disk must keep the
   edit and gain B's id::, with no file in logseq/bak/.

   Nothing runs when the run was stopped, since the current repo may be another
   graph. Otherwise plan-id-repairs picks the repairs, and each file they write
   must still match the db (<paths-matching-db). The rest wait for the next run
   of graph, and so do refs that resolve to no block when the run was not
   clean, since their file may have failed to load. When anything in here
   fails, every ref taken for this run goes back to the queue, merged with refs
   collected meanwhile (review finding 7); refs repaired before the failure
   drop out in the next run, as missing-id-blocks no longer finds them."
  [graph repo-dir current-run? {:keys [failed-paths clean?]}]
  (if-not (and (seq (get @*deferred-id-repairs graph)) (current-run?))
    (p/resolved 0)
    (let [[old _] (swap-vals! *deferred-id-repairs dissoc graph)
          ref-ids (get old graph)
          defer! (fn [ids]
                   (when (seq ids)
                     (swap! *deferred-id-repairs update graph (fnil into #{}) ids)))]
      ;; p/do: a throw in the bindings is caught below too, so it cannot skip the
      ;; reconcile's completion notice
      (-> (p/do
           (let [{:keys [run defer]} (plan-id-repairs
                                      (map (fn [block]
                                             {:ref-id (str (:block/uuid block))
                                              :path (some-> block :block/page :block/file :file/path)})
                                           (missing-id-blocks ref-ids))
                                      {:failed-paths failed-paths})]
             (defer! (map :ref-id defer))
             (when-not clean?
               (defer! (remove model/get-block-by-uuid ref-ids)))
             (p/let [matching (<paths-matching-db graph repo-dir (distinct (keep :path run)))
                     safe? #(contains? matching (:path %))
                     ready (filterv safe? run)]
               (defer! (map :ref-id (remove safe? run)))
               (cond
                 (empty? ready)
                 0

                 (not (current-run?))
                 (do (defer! (map :ref-id ready)) 0)

                 :else
                 (p/do!
                  (file-property-handler/batch-set-block-property-aux!
                   (mapv (fn [{:keys [ref-id]}] [(uuid ref-id) :id ref-id]) ready))
                  (count ready))))))
          (p/catch (fn [e]
                     (defer! ref-ids)
                     (js/console.error "Reconcile: repairing missing block ids failed; they wait for the next run:" e)
                     0))))))

(defn- log-reconcile-perf!
  "ADR-003 instrumentation: one LSPERF line per reopen reconcile.
   - concurrency is the file-phase bound of the run (reconcile-file-bound):
     the file count by default, reconcile-experiment-cap under the H2 flag.
   - max-queue-ms is the longest wait from the start of the file phase (after
     the page deletes and the 500 ms delay) until a file got a slot. Under the
     cap that is the last file, so it runs close to files-ms: it is the
     reconcile's own queue, not the worker's. With eager fan-out it is near 0.
   - pulls, pull-ms-sum and pull-ms-max time each <get-file round trip from the
     UI: post, wait in the worker queue, pull, return. With the cap, at most
     reconcile-experiment-cap of them are outstanding, so pull-ms-max is the
     bound on the worker queue wait that H2 is about; with eager fan-out all of
     them are, and pull-ms-max is the whole worker queue.
   - max-file-ms is the slowest file, from its stat to its reparse settling.
   - changed counts files handed to alter-file, and deleted the vanished files.
     errors and delete-errors count only failures that reach the reconcile: the
     stat and read IPC calls, the pull and synchronous throws of the page
     delete. alter-file and page-handler/<delete! catch their own errors
     (console, :capture-error) and resolve, so a failed reparse still counts as
     changed and a failed page delete is not counted. The deferred missing-id
     repairs run after this line is logged and are not counted in it.
   - skipped and delete-skipped count items not started because the run was
     stopped (see load-graph-files!). run tells overlapping runs apart."
  [{:keys [run concurrency files deleted t0 files-t0 changed errors delete-errors skipped delete-skipped
           pulls pull-ms-sum pull-ms-max max-file-ms max-queue-ms]}]
  (let [now (js/performance.now)
        round #(js/Math.round (or % 0))]
    (js/console.log
     (str "LSPERF "
          (js/JSON.stringify
           (clj->js {:event "reconcile"
                     :thread "ui"
                     :run run
                     :concurrency concurrency
                     :files files
                     :changed changed
                     :deleted deleted
                     :errors errors
                     :delete-errors delete-errors
                     :skipped skipped
                     :delete-skipped delete-skipped
                     :total-ms (round (- now t0))
                     :files-ms (round (- now files-t0))
                     :pulls pulls
                     :pull-ms-sum (round pull-ms-sum)
                     :pull-ms-max (round pull-ms-max)
                     :max-file-ms (round max-file-ms)
                     :max-queue-ms (round max-queue-ms)
                     :t (round now)}))))))

(defn load-graph-files!
  "This fn replaces the former initial fs watcher.

   A run stops taking new files and page deletes once the current graph is no
   longer graph, or once a newer run started. Several steps read and write the
   current repo's db rather than graph's (file-model/get-file-page,
   set-missing-block-ids!, page-handler/<delete!), so after a graph switch they
   would change the other graph. Nothing is lost by stopping: every :graph/ready
   runs a full reconcile again. Items already in flight finish.

   The missing-id repairs of changed files (set-missing-block-ids!) are
   collected during the run and run after its last file, see
   <run-deferred-id-repairs!. A stopped run leaves them for the next run.

   The file phase starts every file at once (eager fan-out) unless the H2
   experiment flag caps it, see reconcile-file-bound and reconcile-cap-flag.

   At the end a stopped or superseded run reports nothing, as the current run
   will; this is checked when the notice would be shown. A run with failed
   files or page deletes shows a warning, with or without the large change set
   notice. Otherwise the success notice follows that notice."
  [graph]
  (when graph
    (let [repo-dir (config/get-repo-dir graph)
          t0 (js/performance.now)
          run-id (swap! *reconcile-run inc)
          current-run? #(and (= run-id @*reconcile-run)
                             (= graph (state/get-current-repo)))
          ;; LSPERF counters for this run, logged by log-reconcile-perf!
          *perf (volatile! {:changed 0 :errors 0 :delete-errors 0
                            :skipped 0 :delete-skipped 0
                            :pulls 0 :pull-ms-sum 0 :pull-ms-max 0
                            :max-file-ms 0 :max-queue-ms 0})
          on-pull-ms (fn [ms]
                       (vswap! *perf #(-> %
                                          (update :pulls inc)
                                          (update :pull-ms-sum + ms)
                                          (update :pull-ms-max max ms))))
          ;; files whose stat, read, pull or page delete failed in this run
          *failed-paths (volatile! #{})
          ;; collects the block refs of changed files for <run-deferred-id-repairs!
          on-id-repair (fn [content]
                         (when (string? content)
                           (when-let [ref-ids (seq (block-ref/get-all-block-ref-ids content))]
                             (swap! *deferred-id-repairs update graph
                                    (fnil into #{}) (map str ref-ids)))))]
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
              (if-not (current-run?)
                (vswap! *perf update :delete-skipped inc)
                (-> (p/do
                     (when-let [page-name (file-model/get-file-page path)]
                       (println "Delete page: " page-name ", file path: " path ".")
                       (page-handler/<delete! page-name #())))
                    (p/catch (fn [e]
                               (vswap! *perf update :delete-errors inc)
                               (vswap! *failed-paths conj path)
                               (js/console.error "Reconcile: deleting the page of" path "failed:" e))))))
            deleted-files))
         (-> (p/delay 500) ;; workaround for notification ui not showing
             (p/then
              (fn [_]
                (let [files-t0 (js/performance.now)
                      ;; every file at once, or the H2 cap (reconcile-file-bound)
                      concurrency (reconcile-file-bound (count files))]
                  (p/let [_ (async-util/<map-bounded
                             concurrency
                             (fn [file-rpath]
                               (if-not (current-run?)
                                 (vswap! *perf update :skipped inc)
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
                                                                          :stat stat}
                                                                         :on-pull-ms on-pull-ms
                                                                         :on-id-repair on-id-repair)]
                                         (when (true? changed?)
                                           (vswap! *perf update :changed inc)))
                                       (p/catch (fn [e]
                                                  (vswap! *perf update :errors inc)
                                                  (vswap! *failed-paths conj file-rpath)
                                                  (js/console.error "Reconcile: loading" file-rpath "failed:" e)))
                                       (p/then (fn [_]
                                                 (vswap! *perf update :max-file-ms max
                                                         (- (js/performance.now) start))))))))
                             files)]
                    (log-reconcile-perf! (assoc @*perf
                                                :run run-id
                                                :concurrency concurrency
                                                :files (count files)
                                                :deleted (count deleted-files)
                                                :t0 t0
                                                :files-t0 files-t0))
                    ;; every file of the run has settled: the deferred id repairs
                    ;; no longer serialize a file from content the db has not read
                    (let [{:keys [errors delete-errors skipped delete-skipped]} @*perf]
                      (p/let [repaired (<run-deferred-id-repairs!
                                        graph repo-dir current-run?
                                        {:failed-paths @*failed-paths
                                         :clean? (zero? (+ errors delete-errors skipped delete-skipped))})]
                        (when (pos? repaired)
                          (js/console.log "Reconcile: added the missing id:: of" repaired "referred blocks"))))))))
             (p/then (fn []
                       (let [{:keys [errors delete-errors skipped delete-skipped]} @*perf
                             failed (+ errors delete-errors)]
                         (when notification-uid
                           (prn ::init-notify)
                           (notification/clear! notification-uid))
                         (cond
                           ;; a stopped run did not load every file, and a run that a
                           ;; newer run or a graph switch superseded after it admitted
                           ;; all its items must not report either (review finding 9):
                           ;; the current run reports. The loading notice is cleared above.
                           (or (not (current-run?))
                               (pos? (+ skipped delete-skipped)))
                           nil

                           ;; the per-file catch keeps a failed file from stopping the
                           ;; others, so failures must be reported here
                           (pos? failed)
                           (state/pub-event! [:notification/show
                                              {:content (str "The graph " graph " is not fully loaded: "
                                                             failed " file(s) could not be loaded from disk. "
                                                             "See the developer console for details.")
                                               :status :warning
                                               :clear? false}])

                           notification-uid
                           (state/pub-event! [:notification/show {:content (str "The graph " graph " is loaded.")
                                                                  :status :success
                                                                  :clear? true}])))))
             (p/catch (fn [error]
                        (js/console.dir error)))))))))
