(ns frontend.persist-db.browser
  "Browser db persist support, using sqlite-wasm.

   This interface uses clj data format as input."
  (:require ["comlink" :as Comlink]
            [electron.ipc :as ipc]
            [frontend.common.missionary :as c.m]
            [frontend.common.thread-api :as thread-api]
            [frontend.config :as config]
            [frontend.date :as date]
            [frontend.db :as db]
            [frontend.db.transact :as db-transact]
            [frontend.handler.notification :as notification]
            [frontend.handler.worker :as worker-handler]
            [frontend.persist-db.protocol :as protocol]
            [frontend.sidecar.core :as sidecar]
            [frontend.sidecar.initial-sync :as initial-sync]
            [frontend.sidecar.routing :as routing]
            [frontend.state :as state]
            [frontend.undo-redo :as undo-redo]
            [frontend.util :as util]
            [lambdaisland.glogi :as log]
            [logseq.db :as ldb]
            [missionary.core :as m]
            [promesa.core :as p]))

(defn- ask-persist-permission!
  []
  (p/let [persistent? (.persist js/navigator.storage)]
    (if persistent?
      (log/info :storage-persistent "Storage will not be cleared unless from explicit user action")
      (log/warn :opfs-storage-may-be-cleared "OPFS storage may be cleared by the browser under storage pressure."))))

(defn- sync-app-state!
  []
  (let [state-flow
        (->> (m/watch state/state)
             (m/eduction
              (map #(select-keys % [:git/current-repo :config
                                    :auth/id-token :auth/access-token :auth/refresh-token]))
              (dedupe)))
        <init-sync-done? (p/deferred)
        task (m/reduce
              (constantly nil)
              (m/ap
                (let [m (m/?> (m/relieve state-flow))]
                  (when (and (contains? m :git/current-repo)
                             (nil? (:git/current-repo m)))
                    (log/error :sync-app-state
                               [m (select-keys @state/state
                                               [:git/current-repo
                                                :auth/id-token :auth/access-token :auth/refresh-token])]))
                  (c.m/<? (state/<invoke-db-worker :thread-api/sync-app-state m))
                  (p/resolve! <init-sync-done?))))]
    (c.m/run-task* task)
    <init-sync-done?))

(defn get-route-data
  [route-match]
  (when (seq route-match)
    {:to (get-in route-match [:data :name])
     :path-params (:path-params route-match)
     :query-params (:query-params route-match)}))

(defn- sync-ui-state!
  []
  (add-watch state/state
             :sync-ui-state
             (fn [_ _ prev current]
               (when-not @(:history/paused? @state/state)
                 (let [f (fn [state]
                           (-> (select-keys state [:ui/sidebar-open? :ui/sidebar-collapsed-blocks :sidebar/blocks])
                               (assoc :route-data (get-route-data (:route-match state)))))
                       old-state (f prev)
                       new-state (f current)]
                   (when (not= new-state old-state)
                     (undo-redo/record-ui-state! (state/get-current-repo) (ldb/write-transit-str {:old-state old-state :new-state new-state}))))))))

(defn transact!
  [repo tx-data tx-meta]
  (let [;; TODO: a better way to share those information with worker, maybe using the state watcher to notify the worker?
        context {:dev? config/dev?
                 :node-test? util/node-test?
                 :mobile? (util/mobile?)
                 :validate-db-options (:dev/validate-db-options (state/get-config))
                 :importing? (:graph/importing @state/state)
                 :date-formatter (state/get-date-formatter)
                 :journal-file-name-format (or (state/get-journal-file-name-format)
                                               date/default-journal-filename-formatter)
                 :export-bullet-indentation (state/get-export-bullet-indentation)
                 :preferred-format (state/get-preferred-format)
                 :journals-directory (config/get-journals-directory)
                 :whiteboards-directory (config/get-whiteboards-directory)
                 :pages-directory (config/get-pages-directory)}]
    (state/<invoke-db-worker :thread-api/transact repo tx-data tx-meta context)))

(defn- set-worker-fs
  [worker]
  (p/let [portal (js/MagicPortal. worker)
          fs (.get portal "fs")
          pfs (.get portal "pfs")
          worker-thread (.get portal "workerThread")]
    (set! (.-fs js/window) fs)
    (set! (.-pfs js/window) pfs)
    (set! (.-workerThread js/window) worker-thread)))

(defn- reload-app-if-old-db-worker-exists
  []
  (when (util/capacitor?)
    (log/info ::reload-app {:client-id @state/*db-worker-client-id})
    (when-let [client-id @state/*db-worker-client-id]
      (js/navigator.locks.request client-id #js {:mode "exclusive"
                                                 :ifAvailable true}
                                  (fn [lock]
                                    (log/info ::reload-app-lock {:acquired? (some? lock)})
                                    (when-not lock
                                      (js/window.location.reload)))))))

(defn- start-web-worker!
  "Start the web worker backend for database operations.
   This is the original implementation, now internal.
   Returns a promise that resolves with the wrapped-worker function."
  []
  (when-not util/node-test?
    (p/do!
     (reload-app-if-old-db-worker-exists)
     (let [worker-url (if config/publishing? "static/js/db-worker.js" "js/db-worker.js")
           worker (js/Worker.
                   (str worker-url
                        "?electron=" (util/electron?)
                        "&capacitor=" (util/capacitor?)
                        "&publishing=" config/publishing?))
           _ (set-worker-fs worker)
           wrapped-worker* (Comlink/wrap worker)
           wrapped-worker (fn [qkw direct-pass? & args]
                            (p/let [result (.remoteInvoke ^js wrapped-worker*
                                                          (str (namespace qkw) "/" (name qkw))
                                                          direct-pass?
                                                          (cond
                                                            (= qkw :thread-api/set-infer-worker-proxy)
                                                            (first args)
                                                            direct-pass?
                                                            (into-array args)
                                                            :else
                                                            (ldb/write-transit-str args)))]
                              (if direct-pass?
                                result
                                (ldb/read-transit-str result))))
           t1 (util/time-ms)]
       (Comlink/expose #js{"remoteInvoke" thread-api/remote-function} worker)
       (worker-handler/handle-message! worker wrapped-worker)
       ;; Register with routing (replaces direct state/*db-worker set)
       (routing/set-web-worker! wrapped-worker)
       (reset! state/*db-worker wrapped-worker)
       (-> (p/let [_ (state/<invoke-db-worker :thread-api/init config/RTC-WS-URL)
                   _ (sync-app-state!)
                   _ (log/info "init worker spent" (str (- (util/time-ms) t1) "ms"))
                   _ (sync-ui-state!)
                   _ (ask-persist-permission!)
                   _ (state/pub-event! [:graph/sync-context])]
             (ldb/register-transact-fn!
              (fn worker-transact!
                [repo tx-data tx-meta]
                (db-transact/transact transact!
                                      (if (string? repo) repo (state/get-current-repo))
                                      tx-data
                                      (assoc tx-meta :client-id (:client-id @state/state)))))
             ;; Return the worker function for hybrid mode
             wrapped-worker)
           (p/catch (fn [error]
                      (log/error :init-sqlite-wasm-error ["Can't init SQLite wasm" error]))))))))

(defn start-db-worker!
  "Start the database backend - either JVM sidecar or web worker.

   On Electron desktop, attempts to start the JVM sidecar first for better
   performance with large graphs. Falls back to web worker if sidecar fails.

   On web/mobile, always uses the web worker.

   In hybrid mode, operations are routed based on type:
   - File operations (mldoc parsing) -> web worker
   - Queries, outliner ops -> sidecar (when available)"
  []
  (when-not util/node-test?
    (if (sidecar/sidecar-enabled?)
      ;; Desktop with sidecar support - hybrid mode
      (-> (sidecar/start-db-backend!
           {:start-web-worker-fn start-web-worker!
            :fallback-on-error? true
            :on-backend-ready (fn [{:keys [type]}]
                                (log/info :db-backend-started {:type type}))})
          (p/then (fn [{:keys [type sidecar sidecar-worker-fn] :as result}]
                    ;; Run sidecar setup when sidecar is actually active:
                    ;; - Pure sidecar mode: {:type :sidecar :port 47632}
                    ;; - Hybrid mode: {:type :hybrid :sidecar :ipc} or {:sidecar :ws}
                    ;; Bug fix: Check for truthy sidecar key, not just :hybrid type
                    (when (or (= type :sidecar) sidecar)
                      ;; Register sidecar with routing so queries go there
                      (when sidecar-worker-fn
                        (routing/set-sidecar-worker! sidecar-worker-fn)
                        (js/console.warn "[SIDECAR] Registered sidecar worker" (pr-str {:type type :sidecar sidecar}))
                        (log/info :routing-sidecar-registered {:type type :sidecar sidecar})
                        ;; If a file-based graph is already loaded AND parsed, sync it to sidecar
                        ;; This handles the case where sidecar connects after graph was parsed
                        ;; Note: Skip DB-based graphs (e.g. logseq_db_local) - sidecar is for file-based only
                        (letfn [(try-sync! [repo attempt]
                                  (let [db-based? (config/db-based-graph? repo)
                                        db-ready? (some? (db/get-db repo false))]
                                    (js/console.warn "[SIDECAR] Checking graph" (pr-str {:repo repo :db-based? db-based? :db-ready? db-ready? :attempt attempt}))
                                    (cond
                                      db-based?
                                      (js/console.warn "[SIDECAR] Skipping sync - DB-based graph")

                                      (not db-ready?)
                                      (if (< attempt 5)
                                        (do
                                          (js/console.warn "[SIDECAR] DB not ready, retrying in 1s...")
                                          (js/setTimeout #(try-sync! repo (inc attempt)) 1000))
                                        (js/console.warn "[SIDECAR] DB still not ready after retries, giving up"))

                                      :else
                                      (do
                                        (log/info :sidecar-sync-existing-graph {:repo repo})
                                        (-> (initial-sync/sync-graph-to-sidecar! repo {:storage-path ":memory:"})
                                            (p/then (fn [result]
                                                      (js/console.warn "[SIDECAR] Sync complete!" (pr-str result))
                                                      (log/info :sidecar-sync-existing-complete
                                                                {:repo repo
                                                                 :datom-count (:datom-count result)})))
                                            (p/catch (fn [err]
                                                       (js/console.warn "[SIDECAR] Sync failed!" (str err))
                                                       (log/error :sidecar-sync-existing-failed
                                                                  {:repo repo :error (str err)}))))))))]
                          (when-let [repo (state/get-current-repo)]
                            (try-sync! repo 1))))
                      ;; Sidecar needs some additional setup that web worker handles internally
                      (sync-app-state!)
                      (sync-ui-state!)
                      (state/pub-event! [:graph/sync-context])
                      (ldb/register-transact-fn!
                       (fn sidecar-transact!
                         [repo tx-data tx-meta]
                         (db-transact/transact transact!
                                               (if (string? repo) repo (state/get-current-repo))
                                               tx-data
                                               (assoc tx-meta :client-id (:client-id @state/state))))))
                    result))
          (p/catch (fn [error]
                     (log/error :start-db-backend-error {:error error}))))
      ;; Web/mobile - use web worker directly
      (start-web-worker!))))

(defn <check-webgpu-available?
  []
  (if (some? js/navigator.gpu)
    (p/chain (js/navigator.gpu.requestAdapter) some?)
    (p/promise false)))

(defn start-inference-worker!
  []
  (when-not util/node-test?
    (let [worker-url "js/inference-worker.js"
          ^js worker (js/SharedWorker.
                      (str worker-url
                           "?electron=" (util/electron?)
                           "&capacitor=" (util/capacitor?)
                           "&publishing=" config/publishing?))
          ^js port (.-port worker)
          wrapped-worker (Comlink/wrap port)
          t1 (util/time-ms)]
      (worker-handler/handle-message! port wrapped-worker)
      (reset! state/*infer-worker wrapped-worker)
      (p/do!
       (let [embedding-model-name (ldb/get-key-value (db/get-db) :logseq.kv/graph-text-embedding-model-name)]
         (.init wrapped-worker embedding-model-name))
       (log/info "init infer-worker spent:" (str (- (util/time-ms) t1) "ms"))))))

(defn <connect-db-worker-and-infer-worker!
  []
  (assert (and @state/*infer-worker @state/*db-worker))
  (state/<invoke-db-worker-direct-pass :thread-api/set-infer-worker-proxy (Comlink/proxy @state/*infer-worker)))

(defn <export-db!
  [repo data]
  (when (util/electron?)
    (ipc/ipc :db-export repo data)))

(defn- sqlite-error-handler
  [error]
  (state/pub-event! [:capture-error
                     {:error error
                      :payload {:type :sqlite-error}}])
  (if (util/mobile?)
    (js/window.location.reload)
    (do
      (log/error :sqlite-error error)
      (notification/show! (str "SQLiteDB error: " error) :error))))

(defrecord InBrowser []
  protocol/PersistentDB
  (<new [_this repo opts]
    (state/<invoke-db-worker :thread-api/create-or-open-db repo opts))

  (<list-db [_this]
    (-> (state/<invoke-db-worker :thread-api/list-db)
        (p/catch sqlite-error-handler)))

  (<unsafe-delete [_this repo]
    (state/<invoke-db-worker :thread-api/unsafe-unlink-db repo))

  (<release-access-handles [_this repo]
    (state/<invoke-db-worker :thread-api/release-access-handles repo))

  (<fetch-initial-data [_this repo opts]
    (-> (p/let [db-exists? (state/<invoke-db-worker :thread-api/db-exists repo)
                disk-db-data (when-not db-exists? (ipc/ipc :db-get repo))
                _ (when disk-db-data
                    (state/<invoke-db-worker-direct-pass :thread-api/import-db repo disk-db-data))
                _ (state/<invoke-db-worker :thread-api/create-or-open-db repo opts)]
          (state/<invoke-db-worker :thread-api/get-initial-data repo opts))
        (p/catch sqlite-error-handler)))

  (<export-db [_this repo opts]
    (-> (p/let [data (state/<invoke-db-worker-direct-pass :thread-api/export-db repo)]
          (when data
            (if (:return-data? opts)
              data
              (<export-db! repo data))))
        (p/catch (fn [error]
                   (log/error :export-db-error repo error "SQLiteDB save error")
                   (notification/show! (str "SQLiteDB save error: " error) :error) {}))))

  (<import-db [_this repo data]
    (-> (state/<invoke-db-worker-direct-pass :thread-api/import-db repo data)
        (p/catch (fn [error]
                   (log/error :import-db-error repo error "SQLiteDB import error")
                   (notification/show! (str "SQLiteDB import error: " error) :error) {})))))
