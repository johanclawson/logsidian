(ns frontend.worker.db-worker
  "Worker used for browser DB implementation"
  (:require ["@sqlite.org/sqlite-wasm" :default sqlite3InitModule]
            ["comlink" :as Comlink]
            [cljs-bean.core :as bean]
            [cljs.cache :as cache]
            [clojure.edn :as edn]
            [clojure.set]
            [clojure.string :as string]
            [datascript.core :as d]
            [datascript.storage :as storage]
            [frontend.common.cache :as common.cache]
            [frontend.common.graph-view :as graph-view]
            [frontend.common.missionary :as c.m]
            [frontend.common.thread-api :as thread-api :refer [def-thread-api]]
            [frontend.worker-common.util :as worker-util]
            [frontend.worker.db-listener :as db-listener]
            [frontend.worker.db-metadata :as worker-db-metadata]
            [frontend.worker.db.fix :as db-fix]
            [frontend.worker.db.migrate :as db-migrate]
            [frontend.worker.db.validate :as worker-db-validate]
            [frontend.worker.embedding :as embedding]
            [frontend.worker.export :as worker-export]
            [frontend.worker.file :as file]
            [frontend.worker.file-paths :as file-paths]
            [frontend.worker.file.reset :as file-reset]
            [frontend.worker.handler.page :as worker-page]
            [frontend.worker.handler.page.file-based.rename :as file-worker-page-rename]
            [frontend.worker.node-cache :as node-cache]
            [frontend.worker.pipeline :as worker-pipeline]
            [frontend.worker.rtc.asset-db-listener]
            [frontend.worker.rtc.client-op :as client-op]
            [frontend.worker.rtc.core :as rtc.core]
            [frontend.worker.rtc.db-listener]
            [frontend.worker.rtc.migrate :as rtc-migrate]
            [frontend.worker.search :as search]
            [frontend.worker.search-indexer :as search-indexer]
            [frontend.worker.shared-service :as shared-service]
            [frontend.worker.state :as worker-state]
            [frontend.worker.thread-atom]
            [goog.object :as gobj]
            [lambdaisland.glogi :as log]
            [lambdaisland.glogi.console :as glogi-console]
            [logseq.cli.common.mcp.tools :as cli-common-mcp-tools]
            [logseq.common.util :as common-util]
            [logseq.db :as ldb]
            [logseq.db.common.entity-plus :as entity-plus]
            [logseq.db.common.entity-util :as common-entity-util]
            [logseq.db.common.initial-data :as common-initial-data]
            [logseq.db.common.order :as db-order]
            [logseq.db.common.reference :as db-reference]
            [logseq.db.common.sqlite :as common-sqlite]
            [logseq.db.common.view :as db-view]
            [logseq.db.file-based.journal-window :as journal-window]
            [logseq.db.frontend.class :as db-class]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.db.sqlite.create-graph :as sqlite-create-graph]
            [logseq.db.sqlite.export :as sqlite-export]
            [logseq.db.sqlite.gc :as sqlite-gc]
            [logseq.db.sqlite.util :as sqlite-util]
            [logseq.outliner.op :as outliner-op]
            [me.tonsky.persistent-sorted-set :as set :refer [BTSet]]
            [missionary.core :as m]
            [promesa.core :as p]))

(.importScripts js/self "worker.js")

(defonce *sqlite worker-state/*sqlite)
(defonce *sqlite-conns worker-state/*sqlite-conns)
(defonce *datascript-conns worker-state/*datascript-conns)
(defonce *client-ops-conns worker-state/*client-ops-conns)
(defonce *opfs-pools worker-state/*opfs-pools)
(defonce *publishing? (atom false))

(defn- check-worker-scope!
  []
  (when (or (gobj/get js/self "React")
            (gobj/get js/self "module$react"))
    (throw (js/Error. "[db-worker] React is forbidden in worker scope!"))))

(defn- <get-opfs-pool
  [graph]
  (when-not @*publishing?
    (or (worker-state/get-opfs-pool graph)
        (p/let [^js pool (.installOpfsSAHPoolVfs ^js @*sqlite #js {:name (worker-util/get-pool-name graph)
                                                                   :initialCapacity 20})]
          (swap! *opfs-pools assoc graph pool)
          pool))))

(defn- init-sqlite-module!
  []
  (when-not @*sqlite
    (p/let [href (.. js/location -href)
            publishing? (string/includes? href "publishing=true")
            sqlite (sqlite3InitModule (clj->js {:print #(log/info :init-sqlite-module! %)
                                                :printErr #(log/error :init-sqlite-module! %)}))]
      (reset! *publishing? publishing?)
      (reset! *sqlite sqlite)
      nil)))

(def repo-path "/db.sqlite")

(defn- <export-db-file
  [repo]
  (p/let [^js pool (<get-opfs-pool repo)]
    (when pool
      (.exportFile ^js pool repo-path))))

(defn- <import-db
  [^js pool data]
  (.importDb ^js pool repo-path data))

(defn- get-all-datoms-from-sqlite-db
  [db]
  (some->> (.exec db #js {:sql "select * from kvs"
                          :rowMode "array"})
           bean/->clj
           (mapcat
            (fn [[_addr content _addresses]]
              (let [content' (sqlite-util/transit-read content)
                    datoms (when (map? content')
                             (:keys content'))]
                datoms)))
           distinct
           (map (fn [[e a v t]]
                  (d/datom e a v t)))))

(defn- rebuild-db-from-datoms!
  "Persistent-sorted-set has been broken, used addresses can't be found"
  [datascript-conn sqlite-db]
  (let [datoms (get-all-datoms-from-sqlite-db sqlite-db)
        db (d/init-db [] db-schema/schema
                      {:storage (storage/storage @datascript-conn)})
        db (d/db-with db
                      (map (fn [d]
                             [:db/add (:e d) (:a d) (:v d) (:t d)]) datoms))]
    (prn :debug :rebuild-db-from-datoms :datoms-count (count datoms))
    (worker-util/post-message :notification ["The SQLite db will be exported to avoid any data-loss." :warning false])
    (worker-util/post-message :export-current-db [])
    (.exec sqlite-db #js {:sql "delete from kvs"})
    (node-cache/clear-for! sqlite-db)
    (d/reset-conn! datascript-conn db)))

(defn- fix-broken-graph
  [graph]
  (let [conn (worker-state/get-datascript-conn graph)
        sqlite-db (worker-state/get-sqlite-conn graph)]
    (when (and conn sqlite-db)
      (rebuild-db-from-datoms! conn sqlite-db)
      (worker-util/post-message :notification ["The graph has been successfully rebuilt." :success false]))))

(defn upsert-addr-content!
  "Upsert addr+data-seq. Update sqlite-cli/upsert-addr-content! when making changes"
  [db data]
  (assert (some? db) "sqlite db not exists")
  (.transaction
   db
   (fn [tx]
     (doseq [item data]
       (.exec tx #js {:sql "INSERT INTO kvs (addr, content, addresses) values ($addr, $content, $addresses) on conflict(addr) do update set content = $content, addresses = $addresses"
                      :bind item})))))


;; ---------------------------------------------------------------------------
;; Perf instrumentation (branch perf/worker-stall-instrumentation, ADR-003 plan
;; step 1). Measures how long the db worker's event loop is blocked and how
;; many SQLite node restores each phase performs. Output: console lines
;; prefixed "LSPERF " carrying one JSON object each. Not for merging as-is.
(def ^:private perf-tick-ms 20)
(defonce ^:private *perf-last-tick (atom (js/performance.now)))
;; :restores / :restore-ms count SQLite reads (node cache misses) and their
;; time; :cache-hits counts restores served by frontend.worker.node-cache.
;; DataScript restore calls = restores + cache-hits.
(defonce ^:private *perf
  (atom {:restores 0 :restore-ms 0 :cache-hits 0 :max-lag 0 :phase-start (js/performance.now)}))

(defn- perf-log! [event m]
  (js/console.log
   (str "LSPERF " (js/JSON.stringify
                   (clj->js (assoc m :event event :t (js/Math.round (js/performance.now))))))))

(defn- perf-note-lag! [now]
  ;; A timer that fires late means the loop was busy for that long.
  (let [lag (- now @*perf-last-tick perf-tick-ms)]
    (reset! *perf-last-tick now)
    (when (> lag (:max-lag @*perf)) (swap! *perf assoc :max-lag lag))))

(defonce ^:private perf-heartbeat
  (js/setInterval (fn [] (perf-note-lag! (js/performance.now))) perf-tick-ms))

(defn- perf-node-cache
  "Node cache gauges, summed over the open SQLite handles."
  []
  (let [stats (keep node-cache/stats-for
                    (mapcat (fn [{:keys [db client-ops]}] [db client-ops])
                            (vals @*sqlite-conns)))]
    {:cache-entries (reduce + 0 (map :entries stats))
     :cache-mb (/ (js/Math.round (/ (reduce + 0 (map :bytes stats)) 104857.6)) 10)
     :cache-evictions (reduce + 0 (map :evictions stats))}))

(defn perf-phase!
  "Log one record for the phase that just ended, then reset the counters.
   The stall still in progress is counted here, since the heartbeat cannot
   fire until this synchronous phase has returned."
  ([phase] (perf-phase! phase nil))
  ([phase extra]
   (let [now (js/performance.now)
         _ (perf-note-lag! now)
         {:keys [restores restore-ms cache-hits max-lag phase-start]} @*perf]
     (perf-log! "phase" (merge {:phase phase
                                :elapsed-ms (js/Math.round (- now phase-start))
                                :max-lag-ms (js/Math.round (max 0 max-lag))
                                :restores restores
                                :restore-ms (js/Math.round restore-ms)
                                :cache-hits (or cache-hits 0)}
                               (perf-node-cache)
                               extra))
     (swap! *perf assoc :restores 0 :restore-ms 0 :cache-hits 0 :max-lag 0 :phase-start now))))

(defonce ^:private perf-activity
  ;; Once a second, report any window with restores (from SQLite or the node
  ;; cache) or a stall over 50 ms, so work after startup (reconcile, queries)
  ;; is visible too.
  (js/setInterval
   (fn []
     (let [{:keys [restores cache-hits max-lag]} @*perf]
       (when (or (pos? restores) (pos? cache-hits) (> max-lag 50))
         (perf-phase! "activity"))))
   1000))

;; Time spent in the SQLite storage adapter's -store (transit-write + upsert),
;; read and reset per call by :thread-api/reset-file.
(defonce ^:private *perf-store (atom {:ms 0 :calls 0}))
;; ---------------------------------------------------------------------------

(defn- restore-data-from-addr*
  "Update sqlite-cli/restore-data-from-addr when making changes. Returns
   [data chars], chars being the length of the stored text (it sizes the node
   cache entry), or nil when there is no row."
  [db addr]
  (assert (some? db) "sqlite db not exists")
  (when-let [result (-> (.exec db #js {:sql "select content, addresses from kvs where addr = ?"
                                       :bind #js [addr]
                                       :rowMode "array"})
                        first)]
    (let [[content addresses-json] (bean/->clj result)
          addresses (when addresses-json
                      (js/JSON.parse addresses-json))
          data (sqlite-util/transit-read content)]
      [(if (and addresses (map? data))
         (assoc data :addresses addresses)
         data)
       (+ (count content) (count addresses-json))])))

(defn restore-data-from-addr
  "Timed wrapper around restore-data-from-addr* (perf instrumentation). Only
   node cache misses get here."
  [db addr]
  (let [t0 (js/performance.now)
        r (restore-data-from-addr* db addr)]
    (swap! *perf (fn [m] (-> m
                             (update :restores inc)
                             (update :restore-ms + (- (js/performance.now) t0)))))
    r))

(defn- perf-note-cache-hit! []
  (swap! *perf update :cache-hits (fnil inc 0)))

(defn new-sqlite-storage
  "Update sqlite-cli/new-sqlite-storage when making changes. The node cache in
   front (frontend.worker.node-cache) is worker-only and changes no results:
   every store drops its addresses from the cache before the upsert."
  [^Object db]
  (node-cache/cached-storage
   (node-cache/cache-for db)
   {:read-row (fn [addr] (restore-data-from-addr db addr))
    :on-hit perf-note-cache-hit!
    :write-rows!
    (fn [addr+data-seq _delete-addrs]
      (let [t0 (js/performance.now)
            data (map
                  (fn [[addr data]]
                    (let [data' (if (map? data) (dissoc data :addresses) data)
                          addresses (when (map? data)
                                      (when-let [addresses (:addresses data)]
                                        (js/JSON.stringify (bean/->js addresses))))]
                      #js {:$addr addr
                           :$content (sqlite-util/transit-write data')
                           :$addresses addresses}))
                  addr+data-seq)
            r (upsert-addr-content! db data)]
        (swap! *perf-store (fn [m] (-> m
                                       (update :calls inc)
                                       (update :ms + (- (js/performance.now) t0)))))
        r))}))

(defn- close-db-aux!
  [repo ^Object db ^Object search ^Object client-ops]
  (swap! *sqlite-conns dissoc repo)
  (swap! *datascript-conns dissoc repo)
  (swap! *client-ops-conns dissoc repo)
  (file-paths/forget! repo)
  (search-indexer/close! repo)
  ;; Old db values may outlive the conn; their caches should not.
  (node-cache/clear-for! db)
  (node-cache/clear-for! client-ops)
  (when db (.close db))
  (when search (.close search))
  (when client-ops (.close client-ops))
  (when-let [^js pool (worker-state/get-opfs-pool repo)]
    (.pauseVfs pool))
  (swap! *opfs-pools dissoc repo))

(defn- close-other-dbs!
  [repo]
  (doseq [[r {:keys [db search client-ops]}] @*sqlite-conns]
    (when-not (= repo r)
      (close-db-aux! r db search client-ops))))

(defn close-db!
  [repo]
  (let [{:keys [db search client-ops]} (get @*sqlite-conns repo)]
    (close-db-aux! repo db search client-ops)))

(defn reset-db!
  [repo db-transit-str]
  (when-let [conn (get @*datascript-conns repo)]
    (let [new-db (ldb/read-transit-str db-transit-str)
          new-db' (update new-db :eavt (fn [^BTSet s]
                                         (set! (.-storage s) (.-storage (:eavt @conn)))
                                         s))]
      (d/reset-conn! conn new-db' {:reset-conn! true})
      (d/reset-schema! conn (:schema new-db)))))

(defn- get-dbs
  [repo]
  (if @*publishing?
    (p/let [^object DB (.-DB ^object (.-oo1 ^object @*sqlite))
            db (new DB "/db.sqlite" "c")
            search-db (new DB "/search-db.sqlite" "c")]
      [db search-db])
    (p/let [^js pool (<get-opfs-pool repo)
            capacity (.getCapacity pool)
            _ (when (zero? capacity)   ; file handle already releases since pool will be initialized only once
                (.unpauseVfs pool))
            db (new (.-OpfsSAHPoolDb pool) repo-path)
            search-db (new (.-OpfsSAHPoolDb pool) (str "search" repo-path))
            client-ops-db (new (.-OpfsSAHPoolDb pool) (str "client-ops-" repo-path))]
      [db search-db client-ops-db])))

(defn- enable-sqlite-wal-mode!
  [^Object db]
  (.exec db "PRAGMA locking_mode=exclusive")
  (.exec db "PRAGMA journal_mode=WAL"))

(defn- gc-sqlite-dbs!
  "Gc main db weekly and rtc ops db each time when opening it"
  [sqlite-db client-ops-db datascript-conn {:keys [full-gc?]}]
  (let [last-gc-at (:kv/value (d/entity @datascript-conn :logseq.kv/graph-last-gc-at))]
    (when (or full-gc?
              (nil? last-gc-at)
              (not (number? last-gc-at))
              (> (- (common-util/time-ms) last-gc-at) (* 3 24 3600 1000))) ; 3 days ago
      (println :debug "gc current graph")
      (doseq [db (if @*publishing? [sqlite-db] [sqlite-db client-ops-db])]
        (sqlite-gc/gc-kvs-table! db {:full-gc? full-gc?})
        ;; gc deletes kvs rows behind the storage's back
        (node-cache/clear-for! db)
        (.exec db "VACUUM"))
      (ldb/transact! datascript-conn [{:db/ident :logseq.kv/graph-last-gc-at
                                       :kv/value (common-util/time-ms)}]))))

(defn- <create-or-open-db!
  [repo {:keys [config datoms] :as opts}]
  (when-not (worker-state/get-sqlite-conn repo)
    (p/let [_ (perf-phase! "before-open")
            [db search-db client-ops-db :as dbs] (get-dbs repo)
            _ (perf-phase! "open-sqlite")
            storage (new-sqlite-storage db)
            client-ops-storage (when-not @*publishing?
                                 (new-sqlite-storage client-ops-db))
            db-based? (sqlite-util/db-based-graph? repo)]
      (swap! *sqlite-conns assoc repo {:db db
                                       :search search-db
                                       :client-ops client-ops-db})
      (doseq [db' dbs]
        (enable-sqlite-wal-mode! db'))
      ;; A file graph's main db (the kvs table DataScript stores into) on
      ;; NORMAL too. The wasm build defaults to FULL (DEFAULT_WAL_SYNCHRONOUS=2):
      ;; an OPFS flush on every commit, and every DataScript transact commits,
      ;; at least twice per save. WAL + NORMAL cannot corrupt the db; SQLite
      ;; then syncs only at checkpoints and WAL restarts, so a power loss or OS
      ;; crash can lose the last commits (the db stays consistent, one state
      ;; older). In a file graph the markdown files are the source of truth,
      ;; and they are not fsynced either (no fsync in src/electron). A DB graph
      ;; has no such source: its main db is the graph, so it keeps FULL. A
      ;; process kill or crash loses nothing, on the assumption that
      ;; FileSystemSyncAccessHandle writes reach the OS without a flush
      ;; (Chromium's implementation; the spec does not say).
      (when-not db-based?
        (.exec db "PRAGMA synchronous=NORMAL"))
      ;; The search db holds derived data and now commits once per DataScript
      ;; tx (search-indexer/sync-tx!): skip the WAL fsync on each commit. WAL +
      ;; NORMAL survives a process kill; a power loss can drop the last search
      ;; commits, which the watermark check on open sees as a gap and heals.
      ;; With a file graph's main db on NORMAL the search db can also be the
      ;; one ahead, which the open trusts (search-indexer/open-action lists
      ;; what that leaves wrong). Rows for blocks the main db lost are orphans:
      ;; a re-parse does not replace them (a file-graph block without id::
      ;; gets a new uuid), so search-blocks hides them and queues them for
      ;; deletion (search/queue-orphans!).
      (.exec search-db "PRAGMA synchronous=NORMAL")
      ;; Checkpoints leave the commit: search-indexer's maintenance tick runs
      ;; them (PASSIVE) between tasks, on a page budget or an age. The
      ;; auto-checkpoint stays on as a fallback threshold, not a hard cap: a
      ;; commit that takes the WAL past it (SQLite counts every frame, whoever
      ;; wrote it) still checkpoints inline, and one large transaction crosses
      ;; it before any timer runs. journal_size_limit is retained space, not a
      ;; maximum: the first commit after a WAL restart truncates the WAL file
      ;; down to it (the 10k search db had a 79 MB WAL that never shrank).
      ;; Both are per connection.
      (.exec search-db (str "PRAGMA wal_autocheckpoint=" search-indexer/search-wal-autocheckpoint))
      (.exec search-db (str "PRAGMA journal_size_limit=" search-indexer/search-journal-size-limit))
      (common-sqlite/create-kvs-table! db)
      (when-not @*publishing? (common-sqlite/create-kvs-table! client-ops-db))
      (search/create-tables-and-triggers! search-db)
      ;; Only here, on the real open: checkpoints, FTS5 merges and orphan
      ;; deletes for this search db, until close-db-aux! (search-indexer/close!)
      (search-indexer/start-maint! repo search-db)
      (ldb/register-transact-pipeline-fn!
       (fn [tx-report]
         (worker-pipeline/transact-pipeline repo tx-report)))
      (let [schema (ldb/get-schema repo)
            conn (common-sqlite/get-storage-conn storage schema)
            _ (perf-phase! "restore-conn")
            ;; max-tx as stored, before this session's txs: the search index
            ;; compares it with its watermark (search-indexer/on-open!)
            stored-max-tx (:max-tx @conn)
            _ (db-fix/check-and-fix-schema! repo conn)
            _ (perf-phase! "schema-fix")
            _ (when datoms
                (let [data (map (fn [datom]
                                  [:db/add (:e datom) (:a datom) (:v datom)]) datoms)]
                  (d/transact! conn data {:initial-db? true})))
            client-ops-conn (when-not @*publishing? (common-sqlite/get-storage-conn
                                                     client-ops-storage
                                                     client-op/schema-in-db))
            initial-data-exists? (when (nil? datoms)
                                   (and (d/entity @conn :logseq.class/Root)
                                        (= "db" (:kv/value (d/entity @conn :logseq.kv/db-type)))))]
        (swap! *datascript-conns assoc repo conn)
        (swap! *client-ops-conns assoc repo client-ops-conn)
        (when (and (not @*publishing?) (not= client-op/schema-in-db (d/schema @client-ops-conn)))
          (d/reset-schema! client-ops-conn client-op/schema-in-db))
        (when (and db-based? (not initial-data-exists?) (not datoms))
          (let [config (or config "")
                initial-data (sqlite-create-graph/build-db-initial-data
                              config (select-keys opts [:import-type :graph-git-sha]))]
            (ldb/transact! conn initial-data {:initial-db? true})))

        (let [r (gc-sqlite-dbs! db client-ops-db conn {})] (perf-phase! "gc") r)

        (let [migration-result (db-migrate/migrate conn)]
          (when (client-op/rtc-db-graph? repo)
            (let [client-ops (rtc-migrate/migration-results=>client-ops migration-result)]
              (client-op/add-ops! repo client-ops))))

        (db-listener/listen-db-changes! repo (get @*datascript-conns repo))
        ;; Trust, resume or heal the block search index (a walk the worker
        ;; runs in slices once it is idle; no UI involvement)
        (search-indexer/on-open! repo {:stored-max-tx stored-max-tx
                                       :file-graph? (not db-based?)})))))

(defn- iter->vec [iter']
  (when iter'
    (p/loop [acc []]
      (p/let [elem (.next iter')]
        (if (.-done elem)
          acc
          (p/recur (conj acc (.-value elem))))))))

(comment
  (defn- <list-all-files
    []
    (let [dir? #(= (.-kind %) "directory")]
      (p/let [^js root (.getDirectory js/navigator.storage)]
        (p/loop [result []
                 dirs [root]]
          (if (empty? dirs)
            result
            (p/let [dir (first dirs)
                    result (conj result dir)
                    values-iter (when (dir? dir) (.values dir))
                    values (when values-iter (iter->vec values-iter))
                    current-dir-dirs (filter dir? values)
                    result (concat result values)
                    dirs (concat
                          current-dir-dirs
                          (rest dirs))]
              (p/recur result dirs))))))))

(defn- <list-all-dbs
  []
  (let [dir? #(= (.-kind %) "directory")
        db-dir-prefix ".logseq-pool-"]
    (p/let [^js root (.getDirectory js/navigator.storage)
            values-iter (when (dir? root) (.values root))
            values (when values-iter (iter->vec values-iter))
            current-dir-dirs (filter dir? values)
            db-dirs (filter (fn [file]
                              (string/starts-with? (.-name file) db-dir-prefix))
                            current-dir-dirs)]
      (log/info :db-dirs (map #(.-name %) db-dirs) :all-dirs (map #(.-name %) current-dir-dirs))
      (p/all (map (fn [dir]
                    (p/let [graph-name (-> (.-name dir)
                                           (string/replace-first ".logseq-pool-" "")
                                           ;; TODO: DRY
                                           (string/replace "+3A+" ":")
                                           (string/replace "++" "/"))
                            repo (str sqlite-util/db-version-prefix graph-name)
                            metadata (worker-db-metadata/<get repo)]
                      {:name graph-name
                       :metadata (edn/read-string metadata)})) db-dirs)))))

(def-thread-api :thread-api/list-db
  []
  (<list-all-dbs))

(defn- <db-exists?
  [graph]
  (->
   (p/let [^js root (.getDirectory js/navigator.storage)
           _dir-handle (.getDirectoryHandle root (str "." (worker-util/get-pool-name graph)))]
     true)
   (p/catch
    (fn [_e]                         ; not found
      false))))

(defn- remove-vfs!
  [^js pool]
  (when pool
    (.removeVfs ^js pool)))

(defn- get-search-db
  [repo]
  (worker-state/get-sqlite-conn repo :search))

(comment
  (def-thread-api :thread-api/get-version
    []
    (when-let [sqlite @*sqlite]
      (.-version sqlite))))

(def-thread-api :thread-api/init
  [rtc-ws-url]
  (reset! worker-state/*rtc-ws-url rtc-ws-url)
  (init-sqlite-module!))

(def-thread-api :thread-api/set-infer-worker-proxy
  [infer-worker-proxy]
  (reset! worker-state/*infer-worker infer-worker-proxy)
  nil)

;; [graph service]
(defonce *service (atom []))

(defonce fns {"remoteInvoke" (fn [& args]
                                ;; the search index walk yields more while calls
                                ;; arrive or are still pending: stamp on entry and
                                ;; again when the result settles
                                (search-indexer/note-call!)
                                (p/finally (apply thread-api/remote-function args)
                                           (fn [_ _] (search-indexer/note-call!))))})

(defn- start-db!
  [repo {:keys [close-other-db?]
         :or {close-other-db? true}
         :as opts}]
  (p/do!
   (when close-other-db?
     (close-other-dbs! repo))
   (when @shared-service/*master-client?
     (<create-or-open-db! repo (dissoc opts :close-other-db?)))
   nil))

(def-thread-api :thread-api/create-or-open-db
  [repo opts]
  (when-not (= repo (worker-state/get-current-repo)) ; graph switched
    (reset! worker-state/*deleted-block-uuid->db-id {}))
  (start-db! repo opts))

(defn- q-fast-path
  "File graphs: the journals NOW/NEXT window queries (and user queries of the
   same shape) answered by index walks, logseq.db.file-based.journal-window.
   Returns {:result ..} for a hit, nil when d/q has to run the query: another
   shape, a db the walk cannot answer, or an error. Logs one LSPERF
   q-fastpath line per hit, with the SQLite restores the walk caused."
  [repo db inputs]
  (when-not (sqlite-util/db-based-graph? repo)
    (try
      (let [t0 (js/performance.now)
            restores0 (:restores @*perf)
            cache-hits0 (:cache-hits @*perf)
            plan (journal-window/plan (first inputs) (rest inputs))
            stats (if (= ::journal-window/no-match plan)
                    ::journal-window/no-match
                    ;; eager: walks, restores and pulls all happen here, not
                    ;; later in the transit write (thread-api sync-ms)
                    (journal-window/run-stats db plan))]
        (when-not (= ::journal-window/no-match stats)
          (js/console.log
           (str "LSPERF "
                (js/JSON.stringify
                 (clj->js {:event "q-fastpath"
                           :pages (:pages stats)
                           :blocks (:blocks stats)
                           :hits (:hits stats)
                           :ms (/ (js/Math.round (* 10 (- (js/performance.now) t0))) 10)
                           :restores (- (:restores @*perf) restores0)
                           :cache-hits (- (or (:cache-hits @*perf) 0) (or cache-hits0 0))}))))
          stats))
      (catch :default e
        (js/console.error "q-fastpath failed, running d/q instead" e)
        nil))))

(def-thread-api :thread-api/q
  [repo inputs]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (let [db @conn]
      (if-let [stats (q-fast-path repo db inputs)]
        (:result stats)
        (apply d/q (first inputs) db (rest inputs))))))

(def-thread-api :thread-api/get-file-paths
  [repo {:keys [exclude-mldoc?]}]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (if exclude-mldoc?
      (file-paths/non-mldoc-paths repo conn)
      (file-paths/all-paths @conn))))

(def-thread-api :thread-api/datoms
  [repo & args]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (let [result (apply d/datoms @conn args)]
      (map (fn [d] [(:e d) (:a d) (:v d) (:tx d) (:added d)]) result))))

(def-thread-api :thread-api/pull
  [repo selector id]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (let [eid (if (and (vector? id) (= :block/name (first id)))
                (:db/id (ldb/get-page @conn (second id)))
                id)]
      (some->> eid
               (d/pull @conn selector)
               (common-initial-data/with-parent @conn)))))

(def ^:private *get-blocks-cache (volatile! (cache/lru-cache-factory {} :threshold 1000)))
(def ^:private get-blocks-with-cache
  (common.cache/cache-fn
   *get-blocks-cache
   (fn [repo requests]
     (let [db (some-> (worker-state/get-datascript-conn repo) deref)]
       [[repo (:max-tx db) requests]
        [db requests]]))
   (fn [db requests]
     (when db
       (->> requests
            (mapv (fn [{:keys [id opts]}]
                    (let [id' (if (and (string? id) (common-util/uuid-string? id)) (uuid id) id)]
                      (-> (common-initial-data/get-block-and-children db id' opts)
                          (assoc :id id)))))
            ldb/write-transit-str)))))

(def-thread-api :thread-api/get-blocks
  [repo requests]
  (let [requests (ldb/read-transit-str requests)]
    (get-blocks-with-cache repo requests)))

(def-thread-api :thread-api/get-block-refs
  [repo id]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (->> (db-reference/get-linked-references @conn id)
         :ref-blocks
         (map (fn [b] (assoc (into {} b) :db/id (:db/id b)))))))

(def-thread-api :thread-api/get-block-refs-count
  [repo id]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (ldb/get-block-refs-count @conn id)))

(def-thread-api :thread-api/get-block-source
  [repo id]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (:db/id (first (:block/_alias (d/entity @conn id))))))

(defn- search-blocks
  [repo q option]
  (let [search-db (get-search-db repo)
        conn (worker-state/get-datascript-conn repo)]
    ;; builds the Fuse page index in slices when it is missing (small graphs)
    (search-indexer/ensure-fuse! repo)
    (search/search-blocks repo conn search-db q option)))

(def-thread-api :thread-api/block-refs-check
  [repo id {:keys [unlinked?]}]
  (m/sp
    (when-let [conn (worker-state/get-datascript-conn repo)]
      (let [db @conn
            block (d/entity db id)]
        (if unlinked?
          (if (search-indexer/building? repo)
            ;; The block index is incomplete while it builds: answer "maybe"
            ;; (show the section) rather than a confident "none".
            true
            (let [title (string/lower-case (:block/title block))
                  result (m/? (search-blocks repo title {:limit 100}))]
              (boolean (some (fn [b]
                               (let [block (d/entity db (:db/id b))]
                                 (and (not= id (:db/id block))
                                      (not ((set (map :db/id (:block/refs block))) id))
                                      (string/includes? (string/lower-case (:block/title block)) title)))) result))))
          (some? (first (common-initial-data/get-block-refs db (:db/id block)))))))))

(def-thread-api :thread-api/get-block-parents
  [repo id depth]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (let [block-id (:block/uuid (d/entity @conn id))]
      (->> (ldb/get-block-parents @conn block-id {:depth (or depth 3)})
           (map (fn [b] (d/pull @conn '[*] (:db/id b))))))))

(def-thread-api :thread-api/set-context
  [context]
  (when context (worker-state/update-context! context))
  nil)

(def-thread-api :thread-api/transact
  [repo tx-data tx-meta context]
  (assert (some? repo))
  (worker-state/set-db-latest-tx-time! repo)
  (let [conn (worker-state/get-datascript-conn repo)]
    (assert (some? conn) {:repo repo})
    (try
      (let [tx-data' (if (contains? #{:insert-blocks} (:outliner-op tx-meta))
                       (map (fn [m]
                              (if (and (map? m) (nil? (:block/order m)))
                                (assoc m :block/order (db-order/gen-key nil))
                                m)) tx-data)
                       tx-data)
            _ (when context (worker-state/set-context! context))
            tx-meta' (cond-> tx-meta
                       (and (not (:whiteboard/transact? tx-meta))
                            (not (:rtc-download-graph? tx-meta))) ; delay writes to the disk
                       (assoc :skip-store? true)

                       true
                       (dissoc :insert-blocks?))]
        (when-not (and (:create-today-journal? tx-meta)
                       (:today-journal-name tx-meta)
                       (seq tx-data')
                       (ldb/get-page @conn (:today-journal-name tx-meta))) ; today journal created already

          ;; (prn :debug :transact :tx-data tx-data' :tx-meta tx-meta')

          (worker-util/profile "Worker db transact"
                               (ldb/transact! conn tx-data' tx-meta')))
        nil)
      (catch :default e
        (prn :debug :worker-transact-failed :tx-meta tx-meta :tx-data tx-data)
        (log/error ::worker-transact-failed e)
        (throw e)))))

(def-thread-api :thread-api/get-initial-data
  [repo opts]
  (when-let [conn (do (perf-phase! "before-initial-data") (worker-state/get-datascript-conn repo))]
    (if (:file-graph-import? opts)
      {:schema (:schema @conn)
       :initial-data (vec (d/datoms @conn :eavt))}
      (let [r (common-initial-data/get-initial-data @conn)]
        (perf-phase! "initial-data" {:datoms (count (:initial-data r))})
        r))))

(def-thread-api :thread-api/reset-db
  [repo db-transit]
  (reset-db! repo db-transit)
  nil)

(def-thread-api :thread-api/unsafe-unlink-db
  [repo]
  (p/let [pool (<get-opfs-pool repo)
          _ (close-db! repo)
          _result (remove-vfs! pool)]
    nil))

(def-thread-api :thread-api/release-access-handles
  [repo]
  (when-let [^js pool (worker-state/get-opfs-pool repo)]
    (.pauseVfs pool)
    nil))

(def-thread-api :thread-api/db-exists
  [repo]
  (<db-exists? repo))

(def-thread-api :thread-api/export-db
  [repo]
  (when-let [^js db (worker-state/get-sqlite-conn repo :db)]
    (.exec db "PRAGMA wal_checkpoint(2)"))
  (p/let [data (<export-db-file repo)]
    (Comlink/transfer data #js [(.-buffer data)])))

(def-thread-api :thread-api/import-db
  [repo data]
  (when-not (string/blank? repo)
    (p/let [pool (<get-opfs-pool repo)]
      ;; Still not awaited. The import replaces the file under any open handle
      ;; of this repo, so drop that handle's cached rows once it has landed.
      (p/then (<import-db pool data)
              (fn [_] (node-cache/clear-for! (worker-state/get-sqlite-conn repo :db))))
      nil)))

(def-thread-api :thread-api/search-blocks
  [repo q option]
  (search-blocks repo q option))

(def-thread-api :thread-api/search-upsert-blocks
  [repo blocks]
  (p/let [db (get-search-db repo)]
    (search/upsert-blocks! db (bean/->js blocks))
    nil))

(def-thread-api :thread-api/search-delete-blocks
  [repo ids]
  (p/let [db (get-search-db repo)]
    (search/delete-blocks! db ids)
    nil))

(def-thread-api :thread-api/search-truncate-tables
  [repo]
  (search-indexer/truncate! repo))

;; Rebuild (opts {:force? true}: truncate + worker walk, resolves when the walk
;; ends) or ensure ({:force? false}: resume a pending walk, returns status).
(def-thread-api :thread-api/search-rebuild-blocks-index
  [repo opts]
  (search-indexer/start! repo opts))

(def-thread-api :thread-api/search-index-status
  [repo]
  (search-indexer/status repo))

(def-thread-api :thread-api/search-build-pages-indice
  [_repo]
  nil)

(def-thread-api :thread-api/apply-outliner-ops
  [repo ops opts]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    ;; Perf instrumentation: one LSPERF line per call, splitting the worker time
    ;; of a save into SQLite stores, the search sync and node restores.
    ;; apply-ops! is synchronous, so the counters only see this call.
    (reset! *perf-store {:ms 0 :calls 0})
    (vreset! search-indexer/*sync-perf {:ms 0 :rows 0})
    (let [t0 (js/performance.now)
          {restores0 :restores restore-ms0 :restore-ms cache-hits0 :cache-hits} @*perf]
      (try
        (worker-util/profile
         "apply outliner ops"
         (outliner-op/apply-ops! repo conn ops (worker-state/get-date-formatter repo) opts))
        (catch :default e
          (let [data (ex-data e)
                {:keys [type payload]} (when (map? data) data)]
            (case type
              :notification
              (do
                (log/error ::apply-outliner-ops-failed e)
                (shared-service/broadcast-to-clients! :notification [(:message payload) (:type payload) (:clear? payload) (:uid payload) (:timeout payload)]))
              (throw e))))
        (finally
          (let [{store-ms :ms store-calls :calls} @*perf-store
                {search-ms :ms search-rows :rows} @search-indexer/*sync-perf
                {:keys [restores restore-ms cache-hits]} @*perf
                round #(js/Math.round (or % 0))]
            (js/console.log
             (str "LSPERF "
                  (js/JSON.stringify
                   (clj->js {:event "apply-ops"
                             :op (some-> ops first first name)
                             :ops (count ops)
                             :total-ms (round (- (js/performance.now) t0))
                             :store-ms (round store-ms)
                             :store-calls store-calls
                             :search-ms (round search-ms)
                             :search-rows search-rows
                             :restores (- (or restores 0) (or restores0 0))
                             :restore-ms (round (- (or restore-ms 0) (or restore-ms0 0)))
                             :cache-hits (- (or cache-hits 0) (or cache-hits0 0))}))))))))))

(def-thread-api :thread-api/file-writes-finished?
  [repo]
  (let [conn (worker-state/get-datascript-conn repo)
        writes @file/*writes]
    ;; Clean pages that have been deleted
    (when conn
      (swap! file/*writes (fn [writes]
                            (->> writes
                                 (remove (fn [[_ pid]] (d/entity @conn pid)))
                                 (into {})))))
    (if (empty? writes)
      true
      (do
        (prn "Unfinished file writes:" @file/*writes)
        false))))

(def-thread-api :thread-api/page-file-saved
  [request-id _page-id]
  (file/dissoc-request! request-id)
  nil)

(def-thread-api :thread-api/sync-app-state
  [new-state]
  (when (and (contains? new-state :git/current-repo)
             (nil? (:git/current-repo new-state)))
    (log/error :thread-api/sync-app-state new-state))
  (worker-state/set-new-state! new-state)
  nil)

(def-thread-api :thread-api/export-get-debug-datoms
  [repo]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (worker-export/get-debug-datoms conn)))

(def-thread-api :thread-api/export-get-all-pages
  [repo]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (worker-export/get-all-pages repo @conn)))

(def-thread-api :thread-api/export-get-all-page->content
  [repo options]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (worker-export/get-all-page->content repo @conn options)))

(def-thread-api :thread-api/validate-db
  [repo]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (worker-db-validate/validate-db conn)))

;; Returns an export-edn map for given repo. When there's an unexpected error, a map
;; with key :export-edn-error is returned
(def-thread-api :thread-api/export-edn
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (try
      (sqlite-export/build-export @conn options)
      (catch :default e
        (js/console.error "export-edn error: " e)
        (js/console.error "Stack:\n" (.-stack e))
        (worker-util/post-message :notification
                                  ["An unexpected error occurred during export. See the javascript console for details."
                                   :error])
        {:export-edn-error (.-message e)}))))

(def-thread-api :thread-api/get-view-data
  [repo view-id option]
  (let [db @(worker-state/get-datascript-conn repo)]
    (db-view/get-view-data db view-id option)))

(def-thread-api :thread-api/get-class-objects
  [repo class-id]
  (let [db @(worker-state/get-datascript-conn repo)]
    (->> (db-class/get-class-objects db class-id)
         (map common-entity-util/entity->map))))

(def-thread-api :thread-api/get-property-values
  [repo {:keys [property-ident] :as option}]
  (let [conn (worker-state/get-datascript-conn repo)]
    (db-view/get-property-values @conn property-ident option)))

(def-thread-api :thread-api/build-graph
  [repo option]
  (let [conn (worker-state/get-datascript-conn repo)]
    (graph-view/build-graph @conn option)))

(def ^:private *get-all-page-titles-cache (volatile! (cache/lru-cache-factory {})))
(defn- get-all-page-titles
  [db]
  (let [pages (ldb/get-all-pages db)]
    (sort (map :block/title pages))))

(def ^:private get-all-page-titles-with-cache
  (common.cache/cache-fn
   *get-all-page-titles-cache
   (fn [repo]
     (let [db @(worker-state/get-datascript-conn repo)]
       [[repo (:max-tx db)] ;cache-key
        [db]             ;f-args
        ]))
   get-all-page-titles))

(def-thread-api :thread-api/get-all-page-titles
  [repo]
  (get-all-page-titles-with-cache repo))

(def-thread-api :thread-api/fix-broken-graph
  [graph]
  (fix-broken-graph graph))

(def-thread-api :thread-api/reset-file
  [repo file-path content opts]
  ;; (prn :debug :reset-file :file-path file-path :opts opts)
  (when-let [conn (worker-state/get-datascript-conn repo)]
    ;; Perf instrumentation: one LSPERF line per call with its phase timings.
    ;; reset-file! is synchronous, so *perf-store only sees this call's stores.
    (reset! *perf-store {:ms 0 :calls 0})
    (vreset! search-indexer/*sync-perf {:ms 0 :rows 0})
    (let [t0 (js/performance.now)
          timings (atom {})
          result (file-reset/reset-file! repo conn file-path content (assoc opts :timings timings))
          total-ms (- (js/performance.now) t0)
          {:keys [parse-ms mldoc-ms delete-ms build-tx-ms transact-ms]} @timings
          {store-ms :ms store-calls :calls} @*perf-store
          ;; search index sync, inside transact (search-indexer/sync-tx!)
          {search-ms :ms search-rows :rows} @search-indexer/*sync-perf
          round #(js/Math.round (or % 0))]
      (js/console.log
       (str "LSPERF "
            (js/JSON.stringify
             (clj->js {:event "reset-file"
                       :path file-path
                       :mldoc-ms (round mldoc-ms)
                       :extract-ms (round (- (or parse-ms 0) (or mldoc-ms 0)))
                       :delete-ms (round delete-ms)
                       :build-tx-ms (round build-tx-ms)
                       :transact-ms (round transact-ms)
                       :store-ms (round store-ms)
                       :store-calls store-calls
                       :search-ms (round search-ms)
                       :search-rows search-rows
                       :total-ms (round total-ms)}))))
      result)))

(def-thread-api :thread-api/gc-graph
  [repo]
  (let [{:keys [db client-ops]} (get @*sqlite-conns repo)
        conn (get @*datascript-conns repo)]
    (when (and db conn)
      (gc-sqlite-dbs! db client-ops conn {:full-gc? true})
      nil)))

(def-thread-api :thread-api/vec-search-embedding-model-info
  [repo]
  (embedding/task--embedding-model-info repo))

(def-thread-api :thread-api/vec-search-init-embedding-model
  [repo]
  (js/Promise. (embedding/task--init-embedding-model repo)))

(def-thread-api :thread-api/vec-search-load-model
  [repo model-name]
  (js/Promise. (embedding/task--load-model repo model-name)))

(def-thread-api :thread-api/vec-search-embedding-graph
  [repo opts]
  (embedding/embedding-graph! repo opts))

(def-thread-api :thread-api/vec-search-search
  [repo query-string nums-neighbors]
  (embedding/task--search repo query-string nums-neighbors))

(def-thread-api :thread-api/vec-search-cancel-indexing
  [repo]
  (embedding/cancel-indexing repo))

(def-thread-api :thread-api/vec-search-update-index-info
  [repo]
  (js/Promise. (embedding/task--update-index-info! repo)))

(def-thread-api :thread-api/mobile-logs
  []
  @worker-state/*log)

(def-thread-api :thread-api/get-rtc-graph-uuid
  [repo]
  (when-let [conn (worker-state/get-datascript-conn repo)]
    (ldb/get-graph-rtc-uuid @conn)))

(def-thread-api :thread-api/api-get-page-data
  [repo page-title]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-common-mcp-tools/get-page-data @conn page-title)))

(def-thread-api :thread-api/api-list-properties
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-common-mcp-tools/list-properties @conn options)))

(def-thread-api :thread-api/api-list-tags
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-common-mcp-tools/list-tags @conn options)))

(def-thread-api :thread-api/api-list-pages
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-common-mcp-tools/list-pages @conn options)))

(def-thread-api :thread-api/api-build-upsert-nodes-edn
  [repo ops]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-common-mcp-tools/build-upsert-nodes-edn @conn ops)))

(comment
  (def-thread-api :general/dangerousRemoveAllDbs
    []
    (p/let [r (<list-all-dbs)
            dbs (ldb/read-transit-str r)]
      (p/all (map #(.unsafeUnlinkDB this (:name %)) dbs)))))

(defn- rename-page!
  [repo conn page-uuid new-name]
  (let [config (worker-state/get-config repo)
        f (if (sqlite-util/db-based-graph? repo)
            (throw (ex-info "Rename page is a file graph only operation" {}))
            file-worker-page-rename/rename!)]
    (f repo conn config page-uuid new-name)))

(defn- delete-page!
  [repo conn page-uuid]
  (let [error-handler (fn [{:keys [msg]}]
                        (worker-util/post-message :notification
                                                  [[:div [:p msg]] :error]))]
    (worker-page/delete! repo conn page-uuid {:error-handler error-handler})))

(defn- create-page!
  [repo conn title options]
  (let [config (worker-state/get-config repo)]
    (try
      (worker-page/create! repo conn config title options)
      (catch :default e
        (js/console.error e)
        (throw e)))))

(defn- outliner-register-op-handlers!
  []
  (outliner-op/register-op-handlers!
   {:create-page (fn [repo conn [title options]]
                   (create-page! repo conn title options))
    :rename-page (fn [repo conn [page-uuid new-name]]
                   (rename-page! repo conn page-uuid new-name))
    :delete-page (fn [repo conn [page-uuid]]
                   (delete-page! repo conn page-uuid))}))

(defn- <ratelimit-file-writes!
  []
  (file/<ratelimit-file-writes!
   (fn [col]
     (when (seq col)
       (let [repo (ffirst col)
             conn (worker-state/get-datascript-conn repo)]
         (if conn
           (when-not (ldb/db-based-graph? @conn)
             (file/write-files! conn col (worker-state/get-context)))
           (js/console.error (str "DB is not found for " repo))))))))

(defn- on-become-master
  [repo start-opts]
  (js/Promise.
   (m/sp
     (c.m/<? (init-sqlite-module!))
     (when-not (:import-type start-opts)
       (c.m/<? (start-db! repo start-opts))
       (assert (some? (worker-state/get-datascript-conn repo))))
     ;; Don't wait for rtc started because the app will be slow to be ready
     ;; for users.
     (when @worker-state/*rtc-ws-url
       (rtc.core/new-task--rtc-start true)))))

(def broadcast-data-types
  (set (map
        common-util/keyword->string
        [:sync-db-changes
         :notification
         :log
         :add-repo
         :rtc-log
         :rtc-sync-state])))

(defn- <init-service!
  [graph start-opts]
  (let [[prev-graph service] @*service]
    (some-> prev-graph close-db!)
    (when graph
      (if (= graph prev-graph)
        service
        (p/let [service (shared-service/<create-service graph
                                                        (bean/->js fns)
                                                        #(on-become-master graph start-opts)
                                                        broadcast-data-types
                                                        {:import? (:import-type? start-opts)})]
          (assert (p/promise? (get-in service [:status :ready])))
          (reset! *service [graph service])
          service)))))

(defn- notify-invalid-data
  [{:keys [tx-meta]} errors]
  ;; don't notify on production when undo/redo failed
  (when-not (and (or (:undo? tx-meta) (:redo? tx-meta))
                 (not worker-util/dev?))
    (shared-service/broadcast-to-clients! :notification
                                          [["Invalid DB!"] :error])
    (worker-util/post-message :capture-error
                              {:error (ex-info "Invalid DB" {})
                               :payload {:errors (str errors)}})))

(defn init
  "web worker entry"
  []
  (ldb/register-transact-invalid-callback-fn! notify-invalid-data)

  (let [proxy-object (->>
                      fns
                      (map
                       (fn [[k f]]
                         [k
                          (fn [& args]
                            (let [[_graph service] @*service
                                  method-k (keyword (first args))]
                              (cond
                                (= :thread-api/create-or-open-db method-k)
                                ;; because shared-service operates at the graph level,
                                ;; creating a new database or switching to another one requires re-initializing the service.
                                (let [[graph opts] (ldb/read-transit-str (last args))]
                                  (p/let [service (<init-service! graph opts)
                                          client-id (:client-id service)]
                                    (when client-id
                                      (worker-util/post-message :record-worker-client-id {:client-id client-id}))
                                    (get-in service [:status :ready])
                                    ;; wait for service ready
                                    (js-invoke (:proxy service) k args)))

                                (or
                                 (contains? #{:thread-api/set-infer-worker-proxy :thread-api/sync-app-state} method-k)
                                 (nil? service))
                                ;; only proceed down this branch before shared-service is initialized
                                (apply f args)

                                :else
                                ;; ensure service is ready
                                (p/let [_ready-value (get-in service [:status :ready])]
                                  (js-invoke (:proxy service) k args)))))]))
                      (into {})
                      bean/->js)]
    (glogi-console/install!)
    (log/set-levels {:glogi/root :info})
    (log/add-handler worker-state/log-append!)
    (check-worker-scope!)
    (outliner-register-op-handlers!)
    (<ratelimit-file-writes!)
    (js/setInterval #(.postMessage js/self "keepAliveResponse") (* 1000 25))
    (Comlink/expose proxy-object)
    (let [^js wrapped-main-thread* (Comlink/wrap js/self)
          wrapped-main-thread (fn [qkw direct-pass? & args]
                                (p/let [result (.remoteInvoke wrapped-main-thread*
                                                              (str (namespace qkw) "/" (name qkw))
                                                              direct-pass?
                                                              (if direct-pass?
                                                                (into-array args)
                                                                (ldb/write-transit-str args)))]
                                  (if direct-pass?
                                    result
                                    (ldb/read-transit-str result))))]
      (reset! worker-state/*main-thread wrapped-main-thread))))

(comment
  (defn <remove-all-files!
    "!! Dangerous: use it only for development."
    []
    (p/let [all-files (<list-all-files)
            files (filter #(= (.-kind %) "file") all-files)
            dirs (filter #(= (.-kind %) "directory") all-files)
            _ (p/all (map (fn [file] (.remove file)) files))]
      (p/all (map (fn [dir] (.remove dir)) dirs)))))
