(ns frontend.worker.search-indexer
  "Worker-owned build of the block search index (blocks_fts), ADR-003.

  - Every DataScript tx is indexed as it happens, inside the tx's own task
    (`sync-tx!`, called by the db listener), first-open and watcher txs
    included. Each commit also writes the tx's max-tx as a watermark.
  - A full walk is only needed after a truncate (manual rebuild), when a
    parsed file graph has an empty index, or when the index can't be trusted:
    an incremental sync failed (dirty), or DataScript stored a tx the index
    never saw (watermark gap, e.g. a crash between the two commits).
  - The walk runs in the worker, in slices bounded by a time budget per
    event-loop turn, reading the current db on every slice. Rows and progress
    commit in one SQLite transaction, so a quit resumes from the cursor.
    Nothing goes through the UI thread.
  - The Fuse page index is built in slices too (`ensure-fuse!`).

  - A search db whose tables and triggers are from another version
    (search/schema-version) is truncated and walked again (reason \"schema\").

  Observability for the benchmark harness: `LSSEARCH {json}` console lines
  with :step open / build-start / progress / slow-slice / upsert-done /
  slow-sync / sync-failed / failed / cancelled."
  (:require [datascript.core :as d]
            [frontend.worker.search :as search]
            [frontend.worker.state :as worker-state]
            [logseq.db :as ldb]
            [promesa.core :as p]))

;; A thread-api call in the last busy-window-ms means the user is waiting on
;; the worker: small slices, long yields. Otherwise bigger slices, short yields.
(def ^:private busy-window-ms 300)
(def ^:private busy-budget-ms 8)
(def ^:private idle-budget-ms 25)
(def ^:private busy-yield-ms 40)
(def ^:private idle-yield-ms 4)
(def ^:private max-chars-per-slice 200000)
;; A walk the worker starts on its own waits for this much quiet (or at most
;; max-gate-wait-ms), so it doesn't compete with startup.
(def ^:private idle-gate-ms 1000)
(def ^:private gate-poll-ms 250)
(def ^:private max-gate-wait-ms 30000)
;; Truncate-and-restart attempts per repo and session (dirty heals), so a
;; persistent SQLite failure can't loop.
(def ^:private max-restarts 3)
(def ^:private progress-log-ms 1000)
;; One tx's search sync above this logs a slow-sync line.
(def ^:private slow-sync-ms 20)
;; Rate limit for slow-sync lines: time of the last line, slow txs since, worst.
(defonce ^:private *slow-sync (volatile! {:last-log -1e9 :n 0 :max-ms 0}))

;; Search db maintenance (ADR-003 step 4; see the Maintenance section). All
;; to be tuned from the LSSEARCH maint lines.
;;
;; Cost model (SQLite 3.50.3 wal.c, from a Codex source review): under WAL +
;; synchronous=NORMAL a commit flushes nothing. A PASSIVE checkpoint that
;; copies frames flushes twice (the WAL before the copy, the db after it),
;; and the first write after the WAL restarts flushes once more (its header).
;; On opfs-sahpool a flush is FileSystemSyncAccessHandle.flush(). So many
;; small checkpoints cost more flushes and more worker time in total than a
;; few bigger ones (about 12 against 3 per 1000 frames), for a modestly
;; smaller worst pause. Neither a checkpoint nor an FTS5 merge step can be
;; bounded in time.
;;
;; WAL auto-checkpoint of the search db (db_worker sets it on every open): a
;; fallback threshold, not a hard cap. A commit that takes the WAL past 2000
;; frames still checkpoints (PASSIVE) inline, right after it commits, and one
;; large transaction crosses it before any timer runs. The tick normally
;; checkpoints long before that (maint-checkpoint-soft-pages).
(def search-wal-autocheckpoint 2000)
;; journal_size_limit of the search db: retained space, not a maximum WAL
;; size. SQLite truncates the WAL file down to it on the first commit after a
;; WAL restart (the tick's restart row), never during the checkpoint, so a
;; fully checkpointed WAL keeps its size until the next write.
(def search-journal-size-limit 4194304)
;; Tick interval while writes happen or merge work waits.
(def maint-tick-ms 250)
;; No application write for this long and no thread-api call: idle. The idle
;; drain merges (maint-idle-step-delay-ms) and then checkpoints once.
(def maint-quiet-ms 1000)
;; Checkpoint once this many pages were written since the last complete
;; checkpoint. Every write through the connection counts: syncs, walks, the
;; tick's merges, orphan deletes and restart rows (write-count).
(def maint-checkpoint-soft-pages 512)
;; ... or once the oldest application write not checkpointed has waited this
;; long, busy or not. This also covers writes too sparse for any budget (a
;; save every 0.5-1 s while typing): the former 2 s max-pending rule adds
;; nothing on top of it and is gone.
(def maint-checkpoint-max-age-ms 1000)
;; FTS5 merge step ('merge', N): N budgets pages of leaf output. It starts at
;; maint-merge-n-initial and adapts to step time (maint-merge-n), aiming at
;; about 5 ms: halved after a step over maint-merge-slow-ms, doubled after
;; maint-merge-grow-after steps in a row under maint-merge-fast-ms that all
;; found work. FTS5 ends a step only at a term boundary, so even N=1 can run
;; long on a common trigram. Always positive: a negative N merges the whole
;; index.
(def maint-merge-n-initial 8)
(def maint-merge-n-min 1)
(def maint-merge-n-max 64)
(def maint-merge-slow-ms 10)
(def maint-merge-fast-ms 2.5)
(def maint-merge-grow-after 4)
;; Idle drain: one merge step per timer task, this far apart.
(def maint-idle-step-delay-ms 16)
;; At most one LSSEARCH maint line per this many ms (counts merged).
(def maint-log-ms 1000)

(defn- now [] (js/performance.now))

(defn- log-step!
  [step m]
  (js/console.log (str "LSSEARCH " (js/JSON.stringify (clj->js (assoc m :step step))))))

;; ---------------------------------------------------------------------------
;; Pure decisions (unit tested)

(defn next-batch-size
  "Item cap for the next slice, scaled from the last one (n items in ms) so
  a slice takes about budget-ms, commit included. Between 8 and 1024."
  [n ms budget-ms]
  (-> (/ (* (max n 1) budget-ms) (max ms 0.1))
      js/Math.round
      (max 8)
      (min 1024)))

(defn slice-action
  "What a walk does at the start of a slice. job is the walk's own view
  {:token :gen :conn}; current is the repo's state now
  {:token :gen :dirty? :conn :sdb}.
  :stop             cancelled, superseded, or the graph was closed/reopened
  :truncate-restart an incremental sync failed mid-walk: rows can be missing
                    below the cursor, so truncate and walk again from 0
  :restart          the index was truncated under the walk (new generation):
                    walk again from 0
  :continue"
  [job current]
  (cond
    (not= (:token job) (:token current)) :stop
    (or (nil? (:conn current)) (nil? (:sdb current))) :stop
    (not (identical? (:conn job) (:conn current))) :stop
    (:dirty? current) :truncate-restart
    (not= (:gen job) (:gen current)) :restart
    :else :continue))

(defn open-action
  "What to do with the block index when a graph opens.
  meta: persisted state (search/get-meta).
  facts: :stored-max-tx, the max-tx of the DataScript db as restored from
  storage (before this session's txs); :triggers-current?
  (search/triggers-current?, ignored when absent); :blocks-empty? and
  :has-files? (a parsed file graph), which are only needed when meta has no
  :state.
  Returns {:action :trust} or {:action :walk :truncate? :cursor :reason}."
  [{:keys [state cursor dirty? indexed-tx schema]}
   {:keys [blocks-empty? has-files? stored-max-tx triggers-current?]}]
  (cond
    ;; Tables and triggers from another version: no schema row (an index from
    ;; before versions), an older or newer one, or a version row over triggers
    ;; a build without versions recreated. Their blocks_fts rowids are not the
    ;; blocks rowids, so no row can be kept: truncate (which recreates the
    ;; triggers and records search/schema-version in one transaction) and
    ;; walk. Checked first: an old index is never resumed or trusted. Until the
    ;; truncate commits, the old triggers stay in place (slow, but correct) and
    ;; the old version stays recorded, so a quit before it migrates again.
    (or (not= schema search/schema-version) (false? triggers-current?))
    {:action :walk :truncate? true :cursor 0 :reason "schema"}

    ;; No state row (at the current version, so a search db created by
    ;; create-tables-and-triggers!): a new graph (every tx will be indexed as
    ;; it happens), or a parsed graph whose search db is new, which is walked
    ;; when empty. An index from before search_meta has no schema row and
    ;; took the rule above.
    (nil? state)
    (if (and blocks-empty? has-files?)
      {:action :walk :truncate? false :cursor 0 :reason "empty"}
      {:action :trust})

    dirty?
    {:action :walk :truncate? true :cursor 0 :reason "dirty"}

    ;; DataScript stored a tx the index never reflected: rows can be missing or
    ;; stale anywhere, so truncate (which also drops stale rows) and walk.
    ;; An index ahead of the stored db is trusted. With the main db on
    ;; synchronous=NORMAL a power loss can drop its last commits while the
    ;; search db keeps them: rows of blocks the db lost become orphans
    ;; (search/queue-orphans!), and a block that still exists keeps the title
    ;; of a lost edit until it is edited again. Accepted (it takes a power loss
    ;; or OS crash; clean reopens show the index 0 or 1 tx ahead, so no
    ;; threshold tells the two apart). The trust path sets the watermark back
    ;; to max-tx (on-open!), so later gaps still show.
    (and (some? stored-max-tx)
         (or (nil? indexed-tx) (> stored-max-tx indexed-tx)))
    {:action :walk :truncate? true :cursor 0 :reason "gap"}

    (= "building" state)
    {:action :walk :truncate? false :cursor (or cursor 0) :reason "resume"}

    :else
    {:action :trust}))

(defn- p99
  [xs]
  (when (seq xs)
    (let [v (vec (sort xs))]
      (nth v (js/Math.floor (* 0.99 (dec (count v))))))))

;; ---------------------------------------------------------------------------
;; State

;; repo -> {:token n       ; bumped to cancel whatever walk runs or waits
;;          :running n     ; token of the running walk
;;          :scheduled n   ; token of the walk waiting for the idle gate
;;          :state s       ; "complete" | "building" (mirror of search_meta)
;;          :gen g :cursor e :indexed-tx t :dirty? b :restarts n
;;          :rows n}       ; rows written by the current/last walk
(defonce ^:private *repos (atom {}))

;; Time spent in sync-tx! (reset and read per call by thread-api/reset-file).
(defonce *sync-perf (volatile! {:ms 0 :rows 0}))

(defonce ^:private *last-call-ms (volatile! -1e9))

(defn note-call!
  "Record that a thread-api call arrived (db_worker calls this for every one)."
  []
  (vreset! *last-call-ms (now)))

(defn- busy?
  [t]
  (< (- t @*last-call-ms) busy-window-ms))

(defn- repo-state [repo] (get @*repos repo))

(defn cancel!
  "Stop the walk that runs or waits for repo (at its next slice). Returns the
  new token."
  [repo]
  (get-in (swap! *repos update-in [repo :token] (fnil inc 0)) [repo :token]))

(defn- running?
  [st]
  (and (some? (:running st)) (= (:running st) (:token st))))

(defn- scheduled?
  [st]
  (and (some? (:scheduled st)) (= (:scheduled st) (:token st))))

(defn building?
  "Whether repo's block index is incomplete right now (a walk is due or runs)."
  [repo]
  (= "building" (:state (repo-state repo))))

(defn status
  [repo]
  (let [st (repo-state repo)
        max-eid (some-> (worker-state/get-datascript-conn repo) deref :max-eid)
        cursor (or (:cursor st) 0)
        building (= "building" (:state st))]
    {:state (:state st)
     :running? (running? st)
     :cursor cursor
     :max-eid max-eid
     :pct (if (and building max-eid (pos? max-eid))
            (min 100 (js/Math.floor (/ (* 100 cursor) max-eid)))
            (if building 0 100))
     :rows (or (:rows st) 0)}))

;; ---------------------------------------------------------------------------
;; Walk

(defn- current-view
  [repo]
  (let [st (repo-state repo)]
    {:token (:token st)
     :gen (:gen st)
     :dirty? (:dirty? st)
     :conn (worker-state/get-datascript-conn repo)
     :sdb (worker-state/get-sqlite-conn repo :search)}))

(defn- truncate-now!
  "Truncate blocks (search_meta: building, cursor 0, gen + 1, not dirty, and
  indexed-tx = the current max-tx, since the walk that follows covers every
  earlier tx). Also drops the Fuse page index. Returns the new gen."
  [repo sdb conn]
  (search/forget-fuzzy! repo)
  (let [gen (search/truncate-table! sdb {:indexed-tx (some-> conn deref :max-tx)})]
    (swap! *repos update repo assoc
           :gen gen :cursor 0 :state "building" :dirty? false
           :indexed-tx (some-> conn deref :max-tx))
    gen))

(defn- run-walk!
  "Walk from the cursor in repo's state until done. Returns a promise of
  {:state \"complete\" :rows :ms} | {:cancelled true} | {:error msg}."
  [repo token conn reason]
  (let [done (p/deferred)
        t-start (now)
        *gen (volatile! (:gen (repo-state repo)))
        *stats (volatile! {:rows 0 :slices 0 :slice-ms [] :last-log t-start})]
    (swap! *repos update repo assoc :running token :rows 0)
    (letfn [(log-start! [reason']
              (log-step! "build-start" {:reason reason'
                                        :cursor (or (:cursor (repo-state repo)) 0)
                                        :gen @*gen
                                        :max-eid (:max-eid @conn)}))
            (summary []
              (let [{:keys [rows slices slice-ms]} @*stats]
                {:rows rows
                 :ms (js/Math.round (- (now) t-start))
                 :slices slices
                 :max-slice-ms (js/Math.round (if (seq slice-ms) (apply max slice-ms) 0))
                 :p99-slice-ms (js/Math.round (or (p99 slice-ms) 0))
                 :reason reason}))
            (finish! [result]
              (swap! *repos update repo
                     (fn [st] (if (= token (:running st)) (dissoc st :running) st)))
              (p/resolve! done result))
            (log-progress! [t]
              (when (>= (- t (:last-log @*stats)) progress-log-ms)
                (vswap! *stats assoc :last-log t)
                (let [{:keys [cursor]} (repo-state repo)
                      max-eid (:max-eid @conn)]
                  (log-step! "progress" {:cursor cursor
                                         :max-eid max-eid
                                         :pct (when (pos? max-eid)
                                                (js/Math.floor (/ (* 100 cursor) max-eid)))
                                         :rows (:rows @*stats)
                                         :slices (:slices @*stats)}))))
            (slice! [max-items]
              (let [st (repo-state repo)
                    db @conn
                    sdb (worker-state/get-sqlite-conn repo :search)
                    t0 (now)
                    budget (if (busy? t0) busy-budget-ms idle-budget-ms)
                    {:keys [rows last-e n done?]}
                    (search/index-batch db (or (:cursor st) 0)
                                        {:max-items max-items
                                         :max-chars max-chars-per-slice
                                         :deadline (+ t0 budget)})
                    t-index (now)
                    ;; Every tx up to now went through sync-tx! in this thread,
                    ;; and a failed one sets :dirty? (checked before this slice).
                    indexed-tx (when done? (max (:max-tx db) (or (:indexed-tx st) 0)))
                    _ (search/commit-batch! sdb rows (cond-> {:cursor last-e
                                                              :state (if done? "complete" "building")}
                                                       done? (assoc :indexed-tx indexed-tx)))
                    t1 (now)
                    ms (- t1 t0)
                    ;; Perf instrumentation: the deadline bounds only the entity
                    ;; loop, not the commit. Split slow slices to see which part
                    ;; overruns the budget (p99 was 276 ms against 8-25 ms).
                    _ (when (> ms 100)
                        (log-step! "slow-slice" {:ms (js/Math.round ms)
                                                 :index-ms (js/Math.round (- t-index t0))
                                                 :commit-ms (js/Math.round (- t1 t-index))
                                                 :n n
                                                 :rows (count rows)
                                                 :max-items max-items
                                                 :budget budget}))]
                (swap! *repos update repo
                       (fn [st']
                         (cond-> (-> st'
                                     (assoc :cursor last-e)
                                     (update :rows (fnil + 0) (count rows)))
                           done? (assoc :state "complete" :indexed-tx indexed-tx))))
                (vswap! *stats (fn [s] (-> s
                                           (update :rows + (count rows))
                                           (update :slices inc)
                                           (update :slice-ms conj ms))))
                (if done?
                  (let [s (summary)]
                    (log-step! "upsert-done" s)
                    (finish! (assoc s :state "complete")))
                  (do
                    (log-progress! t1)
                    (js/setTimeout #(step (next-batch-size n ms budget))
                                   (if (busy? (now)) busy-yield-ms idle-yield-ms))))))
            (step [max-items]
              (try
                (let [current (current-view repo)]
                  (case (slice-action {:token token :gen @*gen :conn conn} current)
                    :stop
                    (do
                      (log-step! "cancelled" (summary))
                      (finish! {:cancelled true}))

                    :truncate-restart
                    (let [restarts (or (:restarts (repo-state repo)) 0)]
                      (if (>= restarts max-restarts)
                        (let [msg "incremental sync keeps failing; giving up until the next open"]
                          (log-step! "failed" (assoc (summary) :msg msg))
                          (finish! {:error msg}))
                        (do
                          (swap! *repos update-in [repo :restarts] (fnil inc 0))
                          (vreset! *gen (truncate-now! repo (:sdb current) conn))
                          (log-start! "restart-dirty")
                          (js/setTimeout #(step 64) (if (busy? (now)) busy-yield-ms idle-yield-ms)))))

                    :restart
                    (do
                      (vreset! *gen (:gen current))
                      (swap! *repos update repo assoc :cursor 0)
                      (log-start! "restart-gen")
                      (js/setTimeout #(step 64) (if (busy? (now)) busy-yield-ms idle-yield-ms)))

                    :continue
                    (slice! max-items)))
                (catch :default e
                  ;; search_meta keeps "building" and the cursor: retried on the
                  ;; next open, never in a hot loop.
                  (js/console.error "search: index walk failed" e)
                  (log-step! "failed" (assoc (summary) :msg (str (or (ex-message e) e))))
                  (finish! {:error (str (or (ex-message e) e))}))))]
      (log-start! reason)
      (step 64))
    done))

(defn- start-walk!*
  "Start a walk now under token (which must still be current). truncate? drops
  every row first; otherwise the walk starts at cursor."
  [repo token {:keys [truncate? cursor reason]}]
  (let [conn (worker-state/get-datascript-conn repo)
        sdb (worker-state/get-sqlite-conn repo :search)]
    (if (or (nil? conn) (nil? sdb) (not= token (:token (repo-state repo))))
      (p/resolved {:cancelled true})
      (do
        (if truncate?
          (truncate-now! repo sdb conn)
          (swap! *repos update repo assoc :cursor (or cursor 0) :state "building"))
        (run-walk! repo token conn reason)))))

(defn- schedule-walk!
  "Start a walk once no thread-api call has arrived for idle-gate-ms (at most
  max-gate-wait-ms from now). Cancels whatever walk runs or waits."
  [repo opts]
  (let [token (cancel! repo)
        t-start (now)]
    (swap! *repos update repo assoc :scheduled token :state "building")
    (letfn [(poll []
              (when (= token (:token (repo-state repo)))
                (let [t (now)]
                  (if (or (>= (- t @*last-call-ms) idle-gate-ms)
                          (>= (- t t-start) max-gate-wait-ms))
                    (do
                      (swap! *repos update repo dissoc :scheduled)
                      (try
                        (start-walk!* repo token opts)
                        (catch :default e
                          (js/console.error "search: cannot start index walk" e)
                          (log-step! "failed" {:msg (str (or (ex-message e) e))}))))
                    (js/setTimeout poll gate-poll-ms)))))]
      (js/setTimeout poll gate-poll-ms))
    nil))

;; ---------------------------------------------------------------------------
;; Entry points

(defn start!
  "thread-api/search-rebuild-blocks-index.
  force? true: truncate and walk now; returns a promise of the walk's result
  ({:state \"complete\" :rows :ms} | {:cancelled true} | {:error msg}).
  force? false (ensure): resume a pending walk if nothing runs or waits; returns
  the status at once."
  [repo {:keys [force?]}]
  (if force?
    (let [token (cancel! repo)]
      (swap! *repos update repo assoc :restarts 0)
      (start-walk!* repo token {:truncate? true :reason "manual"}))
    (let [st (repo-state repo)]
      (when (and (= "building" (:state st))
                 (not (running? st))
                 (not (scheduled? st))
                 (worker-state/get-sqlite-conn repo :search))
        (schedule-walk! repo {:truncate? false :cursor (:cursor st) :reason "resume"}))
      (status repo))))

(defn truncate!
  "thread-api/search-truncate-tables: cancel any walk and empty the index. The
  index stays 'building' (no walk is started: the only caller deletes the
  graph right after)."
  [repo]
  (when-let [sdb (worker-state/get-sqlite-conn repo :search)]
    (cancel! repo)
    (truncate-now! repo sdb (worker-state/get-datascript-conn repo))
    nil))

(defn on-open!
  "Called by <create-or-open-db! once the db listener is registered.
  stored-max-tx: the restored db's max-tx, taken before this session's txs.
  Never throws."
  [repo {:keys [stored-max-tx file-graph?]}]
  (try
    (let [sdb (worker-state/get-sqlite-conn repo :search)
          conn (worker-state/get-datascript-conn repo)]
      (when (and sdb conn)
        (let [m (search/get-meta sdb)
              triggers-current? (search/triggers-current? sdb)
              blocks-empty? (when (nil? (:state m)) (search/blocks-empty? sdb))
              has-files? (when (and blocks-empty? file-graph?)
                           (some? (first (d/datoms @conn :avet :file/path))))
              {:keys [action truncate? cursor reason]}
              (open-action m {:blocks-empty? blocks-empty?
                              :has-files? has-files?
                              :stored-max-tx stored-max-tx
                              :triggers-current? triggers-current?})
              max-tx (:max-tx @conn)
              gen (or (:gen m) 1)
              token (inc (or (:token (repo-state repo)) 0))]
          ;; txs between restore and now (schema fix, gc, migrate) ran before
          ;; the listener existed; count them as seen, as before this change.
          (swap! *repos assoc repo {:token token
                                    :gen gen
                                    :cursor (or (:cursor m) 0)
                                    :indexed-tx max-tx
                                    :dirty? false
                                    :restarts 0})
          (log-step! "open" {:action (name action) :reason reason :state (:state m)
                             :cursor (:cursor m) :indexed-tx (:indexed-tx m)
                             :stored-max-tx stored-max-tx
                             :schema (:schema m) :triggers-current? triggers-current?})
          (case action
            :trust
            (do
              (search/set-meta-tx! sdb {:state "complete" :gen gen :indexed-tx max-tx})
              (swap! *repos update repo assoc :state "complete"))

            :walk
            (do
              ;; Persist the decision first: if the app quits before the walk
              ;; starts, later txs advance the watermark and would hide the gap.
              ;; One transaction; cursor 0 so a heal can never resume from a
              ;; stale cursor. A "schema" walk needs no extra row: search_meta
              ;; keeps the old version until the truncate commits.
              (if truncate?
                (search/set-meta-tx! sdb {:state "building" :dirty? true :cursor 0})
                (search/set-meta-tx! sdb {:state "building" :cursor (or cursor 0) :gen gen
                                          :indexed-tx max-tx}))
              (swap! *repos update repo assoc :state "building" :cursor (or cursor 0))
              (schedule-walk! repo {:truncate? truncate? :cursor cursor :reason reason}))))))
    (catch :default e
      (js/console.error "search: index check on open failed" e)
      (log-step! "failed" {:msg (str "open: " (or (ex-message e) e))}))))

(declare stop-maint!)

(defn close!
  "Called before repo's search db closes."
  [repo]
  (cancel! repo)
  (stop-maint! repo)
  ;; Orphans found in this db; a reopen finds them again if they are left
  (search/take-orphans! repo)
  ;; The Fuse page index was built from this conn: a reopen must rebuild it
  (search/forget-fuzzy! repo)
  (swap! *repos update repo select-keys [:token]))

(defn- mark-dirty!
  "An incremental sync failed: rows may be missing anywhere, so 'complete'
  can no longer be trusted. Persist dirty (the next open truncates and walks),
  and heal in this session too unless restarts are used up."
  [repo e]
  (swap! *repos update repo assoc :dirty? true :state "building")
  (try
    (when-let [sdb (worker-state/get-sqlite-conn repo :search)]
      ;; One transaction; cursor 0 so a lost dirty flag can't resume from a
      ;; stale cursor.
      (search/set-meta-tx! sdb {:state "building" :dirty? true :cursor 0}))
    (catch :default e2
      (js/console.error "search: cannot persist dirty index state" e2)))
  (log-step! "sync-failed" {:msg (str (or (ex-message e) e))})
  (let [st (repo-state repo)]
    ;; A running walk sees :dirty? at its next slice and restarts itself.
    (when (and (not (running? st))
               (not (scheduled? st))
               (< (or (:restarts st) 0) max-restarts))
      (swap! *repos update-in [repo :restarts] (fnil inc 0))
      (schedule-walk! repo {:truncate? true :reason "dirty"}))))

(defn- schedule-reset-restart!
  "A reset-conn! report (publishing's reset-db!) replaces the whole db without
  a tx the incremental path can use. Truncate and walk, but only after
  reset-db! has also run reset-schema! (hence the timeout), and only if nothing
  else took over meanwhile."
  [repo]
  (let [token (cancel! repo)]
    (search/forget-fuzzy! repo)
    (js/setTimeout
     (fn []
       (try
         (when (= token (:token (repo-state repo)))
           (start-walk!* repo token {:truncate? true :reason "reset-conn"}))
         (catch :default e
           (js/console.error "search: restart after reset-conn! failed" e)
           (log-step! "failed" {:msg (str (or (ex-message e) e))}))))
     0)))

(defn sync-tx!
  "Index one DataScript tx synchronously, inside the tx's own task. Called by
  the db listener for every tx, from-disk (first open, watcher, reconcile)
  ones included. Writes rows and the tx's watermark in one SQLite
  transaction. Never throws: a failure marks the index dirty."
  [repo tx-report]
  (try
    ;; A reset-conn! report: tagged (reset-db!) or untagged (fix-broken-graph's
    ;; rebuild-db-from-datoms!), which has no :tempids (every transact report
    ;; has them). Its tx-data is the whole old and new db.
    (if (or (:reset-conn! (:tx-meta tx-report)) (nil? (:tempids tx-report)))
      (schedule-reset-restart! repo)
      (let [sdb (worker-state/get-sqlite-conn repo :search)]
        ;; No search db (tests, or a graph mid-close): only a Fuse index could
        ;; need the page changes.
        (when (or sdb
                  (get @search/fuzzy-search-indices repo)
                  (get @search/fuzzy-builds repo))
          (let [t0 (now)
                {:keys [blocks-to-remove-set blocks-to-add]} (search/sync-search-indice repo tx-report)
                t-index (now)]
            (when sdb
              (let [tx (:max-tx (:db-after tx-report))
                    w (if (number? tx)
                        (max tx (or (:indexed-tx (repo-state repo)) 0))
                        (:indexed-tx (repo-state repo)))]
                (search/sync-rows! sdb blocks-to-remove-set blocks-to-add {:indexed-tx w})
                (when (number? w)
                  (swap! *repos assoc-in [repo :indexed-tx] w))))
            (let [t1 (now)
                  ms (- t1 t0)]
              ;; :rows counts rows handed to the index (an unchanged one
              ;; included, though its upsert writes nothing), :deletes block ids
              ;; removed from it.
              (vswap! *sync-perf (fn [m] (-> m
                                             (update :ms + ms)
                                             (update :rows + (count blocks-to-add)))))
              ;; Perf instrumentation: one tx's sync, split into the DataScript
              ;; side (affected blocks, block->index) and the SQLite commit.
              ;; At most one line a second (bulk loads sync thousands of txs):
              ;; :merged slow txs since the last line, :max-ms the worst of them.
              (when (> ms slow-sync-ms)
                (let [{:keys [last-log n max-ms]}
                      (vswap! *slow-sync (fn [s] (-> s (update :n inc) (update :max-ms max ms))))]
                  (when (>= (- t1 last-log) 1000)
                    (vreset! *slow-sync {:last-log t1 :n 0 :max-ms 0})
                    (log-step! "slow-sync" {:ms (js/Math.round ms)
                                            :sql-ms (js/Math.round (- t1 t-index))
                                            :rows (count blocks-to-add)
                                            :deletes (count blocks-to-remove-set)
                                            :merged n
                                            :max-ms (js/Math.round max-ms)})))))))))
    (catch :default e
      (js/console.error "search: incremental sync failed" e)
      (mark-dirty! repo e))))

;; ---------------------------------------------------------------------------
;; Maintenance (ADR-003 step 4)
;;
;; Commits to the search db no longer checkpoint the WAL or merge FTS5
;; segments inline: wal_autocheckpoint is only a fallback threshold
;; (search-wal-autocheckpoint), and blocks_fts runs automerge=0
;; (search/fts-config). This tick does both between tasks and deletes orphan
;; rows search-blocks found. A PASSIVE checkpoint still blocks the worker
;; while it copies; it is moved out of the commit, not made cheaper, and each
;; one that copies costs its flushes whatever it copies (the cost model at
;; search-wal-autocheckpoint). So it runs when a page budget or an age says
;; so, not on every tick that saw a write.

(defn maint-action
  "What one maintenance tick does (pure). m:
  :wrote?        application writes since the last tick (total_changes moved;
                 the tick's own writes are not counted)
  :quiet-ms      since the last tick that saw application writes
  :pending-pages pages written and not checkpointed yet: application writes
                 and the tick's own merges and orphan deletes, not the
                 restart row (the checkpoint's own write)
  :budget-pages  pages written since the last complete checkpoint, the
                 restart row included
  :age-ms        since the first tick that saw application writes the last
                 complete checkpoint does not have (0 if none)
  :held?         a checkpoint came back partial or failed less than
                 maint-checkpoint-max-age-ms ago
  :merge?        FTS5 merge work may be left
  :busy?         a thread-api call arrived in the last busy-window-ms
  Returns {:ckpt :merge? :idle?}.
  - :ckpt is why to checkpoint now, or nil. Only with pages pending and no
    hold: :budget once budget-pages reaches maint-checkpoint-soft-pages,
    :age once age-ms reaches maint-checkpoint-max-age-ms (busy or not), and
    :final once idle with no merge work left (the drain's last writes).
    Never merely because the last tick saw a write: a streak gets one
    checkpoint per budget or per second, not one per tick. One save gets a
    single checkpoint (:age) about a second later.
  - :merge? is one positive FTS5 merge step now, whenever merge work may be
    left (a first open, which never goes idle, merges too). Never in a tick
    that checkpoints: that tick leaves the merge to the next one.
  - :idle? is no application write for maint-quiet-ms and no thread-api
    call: the next tick then comes maint-idle-step-delay-ms later while the
    drain has work (maint-next-ms), one step per task.
  No tick has a time bound: a checkpoint copies every pending frame, and
  FTS5 ends a merge step only at a term boundary. Steps that are too small or
  too rare leave the work to crisismerge, a whole level inside a commit. The
  maint line (ckpt-max-ms, ckpt-pending-max, merge-max-step-ms, l0-drops)
  shows what each costs; the defs above are the knobs."
  [{:keys [wrote? quiet-ms pending-pages budget-pages age-ms held? merge? busy?]}]
  (let [idle? (and (not wrote?) (not busy?) (>= quiet-ms maint-quiet-ms))
        ckpt (when (and (pos? pending-pages) (not held?))
               (cond
                 (>= budget-pages maint-checkpoint-soft-pages) :budget
                 (>= age-ms maint-checkpoint-max-age-ms) :age
                 (and idle? (not merge?)) :final))]
    {:ckpt ckpt
     :merge? (boolean (and merge? (nil? ckpt)))
     :idle? idle?}))

(defn maint-observe
  "A tick's bookkeeping before it decides (pure; maint-tick! and the tests
  run it). st: repo's maintenance state (*maint). t: now. tc: total_changes
  as the tick starts, before its own writes, so any change since the last
  tick is an application write. pages: the write counter (write-count)
  after the tick's orphan deletes. orphans: rows those deleted. Returns
  [st' m], m being maint-action's argument without :busy?."
  [st t tc pages orphans]
  (let [wrote? (not= tc (:tc st))
        st (cond-> st
             wrote? (assoc :write-t t :app-t (or (:app-t st) t))
             (or wrote? (pos? orphans)) (assoc :merge? true))]
    [st {:wrote? wrote?
         :quiet-ms (- t (:write-t st))
         :pending-pages (max 0 (- pages (:done-pages st)))
         :budget-pages (max 0 (- pages (:ckpt-pages st)))
         :age-ms (if-let [a (:app-t st)] (- t a) 0)
         :held? (< t (or (:held-until st) 0))
         :merge? (boolean (:merge? st))}]))

(defn maint-settle
  "A tick's bookkeeping after its work (pure). t: the tick's time. r:
  :tc          total_changes after the tick's own writes, which never count
               as application writes (the tick can't feed itself)
  :pages       the write counter after them
  :ckpt        nil, or how the checkpoint came out: :complete, :partial
               (busy, or log != checkpointed) or :failed (an error)
  :ckpt-pages  the write counter right after the checkpoint, before the
               restart row
  :merge-left? whether merge work may be left (FTS5's answer if a step ran)
  A complete checkpoint starts a new budget and leaves nothing pending, its
  restart row included. A partial or failed one leaves everything pending,
  the application writes' age included, and holds the next try for
  maint-checkpoint-max-age-ms: retried on a later tick, never in a loop."
  [st t {:keys [tc pages ckpt ckpt-pages merge-left?]}]
  (cond-> (assoc st :tc tc :merge? (boolean merge-left?))
    (= :complete ckpt) (assoc :ckpt-pages ckpt-pages :done-pages pages :app-t nil)
    (contains? #{:partial :failed} ckpt) (assoc :held-until (+ t maint-checkpoint-max-age-ms))))

(defn maint-merge-n
  "N for the next FTS5 merge step (pure), from the last step: it took ms and
  left? says whether it found work (total_changes delta >= 2). Halved, down
  to maint-merge-n-min, after a step over maint-merge-slow-ms; doubled, up
  to maint-merge-n-max, after maint-merge-grow-after steps in a row under
  maint-merge-fast-ms that all found work; any other step starts that count
  again. Returns the state's :merge-n and :merge-fast."
  [{:keys [merge-n merge-fast]} ms left?]
  (cond
    (> ms maint-merge-slow-ms)
    {:merge-n (max maint-merge-n-min (quot merge-n 2)) :merge-fast 0}

    (and left? (< ms maint-merge-fast-ms))
    (if (>= (inc merge-fast) maint-merge-grow-after)
      {:merge-n (min maint-merge-n-max (* 2 merge-n)) :merge-fast 0}
      {:merge-n merge-n :merge-fast (inc merge-fast)})

    :else
    {:merge-n merge-n :merge-fast 0}))

(defn maint-next-ms
  "Delay to the next tick (pure). st: the state after maint-settle, t: the
  tick's time, idle?: maint-action's, pending?: pages still pending after
  the tick. While an idle drain has work (merge work left, or pages for its
  final checkpoint that no hold keeps back): maint-idle-step-delay-ms, so
  each step is its own short task. Otherwise maint-tick-ms; a drain with
  nothing left is disarmed, and ticks find nothing to do until new writes."
  [st t idle? pending?]
  (if (and idle?
           (or (:merge? st)
               (and pending? (>= t (or (:held-until st) 0)))))
    maint-idle-step-delay-ms
    maint-tick-ms))

;; repo -> {:sdb db :timer id
;;          :src :pages|:changes ; write-count's counter, fixed at start
;;          :tc n          ; total_changes at the end of the last tick
;;          :ckpt-pages n  ; write count right after the last complete
;;                         ; checkpoint: the budget counts from here
;;          :done-pages n  ; ... and after its restart row: pages above
;;                         ; this are pending
;;          :write-t t     ; last tick that saw application writes
;;          :app-t t|nil   ; first tick that saw application writes the last
;;                         ; complete checkpoint does not have
;;          :held-until t|nil ; no checkpoint try before (after a partial
;;                         ; or failed one)
;;          :merge? b      ; FTS5 merge work may be left
;;          :merge-n n :merge-fast n ; the next step's N (maint-merge-n)
;;          :l0 n|nil}     ; level-0 segments at the end of the last tick
(defonce ^:private *maint (atom {}))

;; Counts merged into the next LSSEARCH maint line.
(defonce ^:private *maint-log (volatile! {:last-log -1e9}))

(defn- note-maint!
  [f]
  (vswap! *maint-log f))

(defn- log-maint!
  "At most one maint line per maint-log-ms, only after some work. src: the
  write counter behind its page counts (write-count)."
  [t src]
  (let [{:keys [last-log] :as m} @*maint-log]
    (when (and (> (count m) 1) (>= (- t last-log) maint-log-ms))
      (vreset! *maint-log {:last-log t})
      (log-step! "maint" (into {:pages-src (name src)}
                               (map (fn [[k v]] [k (if (number? v) (js/Math.round v) v)]))
                               (dissoc m :last-log))))))

(defn stop-maint!
  "Stop repo's maintenance tick (close!)."
  [repo]
  (when-let [timer (get-in @*maint [repo :timer])]
    (js/clearTimeout timer))
  (swap! *maint dissoc repo))

(defn maint-running?
  [repo]
  (some? (get-in @*maint [repo :timer])))

(defn- delete-orphans!
  "Delete the queued orphan rows DataScript still has no entity for, in one
  statement. Returns how many ids were deleted."
  [repo sdb]
  (let [ids (search/take-orphans! repo)
        conn (worker-state/get-datascript-conn repo)
        ids' (when (and (seq ids) conn) (vec (search/still-orphans @conn ids)))]
    (when (seq ids')
      (search/delete-blocks! sdb ids'))
    (count ids')))

(defn- write-count
  "The tick's write counter for sdb. src :pages: search/cache-writes, pages
  written through the connection (in WAL mode, WAL frames), whoever wrote
  them: syncs, walks, and the tick's merges, orphan deletes and restart rows.
  src :changes, only when that call did not work at start-maint!:
  total_changes, rows written, FTS5 shadow rows included. An approximate
  proxy that over-counts pages (about 20 rows a page for a 50-row commit in
  a bench probe), so the page budget then fires early: more and smaller
  checkpoints, never a bigger WAL."
  [sdb src]
  (if (= src :pages)
    (or (search/cache-writes @worker-state/*sqlite sdb)
        (throw (ex-info "search: sqlite3_db_status failed" {})))
    (search/total-changes sdb)))

(defn- merge-step!
  "One FTS5 merge step of n pages: its own statement, no transaction.
  search/merge-step! reads total_changes right around it, so the delta is
  the step's alone: below 2, FTS5 found nothing to merge. Notes the step for
  the maint line (pages: the WAL frames it wrote, for a checkpoint to copy).
  Returns {:left? :ms}."
  [sdb src n idle?]
  (let [p0 (write-count sdb src)
        t0 (now)
        delta (search/merge-step! sdb n)
        ms (- (now) t0)
        pages (- (write-count sdb src) p0)]
    (note-maint! (fn [m] (cond-> (-> m
                                     (update :merge-steps (fnil inc 0))
                                     (update :merge-changes (fnil + 0) delta)
                                     (update :merge-pages (fnil + 0) pages)
                                     (update :merge-ms (fnil + 0) ms)
                                     (update :merge-max-step-ms (fnil max 0) ms)
                                     ;; the overshoot past n: a step ends only
                                     ;; at a term boundary
                                     (update :merge-max-step-changes (fnil max 0) delta)
                                     (update :merge-n-min (fnil min n) n)
                                     (update :merge-n-max (fnil max n) n))
                           idle? (update :merge-idle-steps (fnil inc 0)))))
    {:left? (>= delta 2) :ms ms}))

(defn- checkpoint!
  "PASSIVE checkpoint for why (maint-action's :ckpt) with pending pages
  waiting, then the WAL restart row if it completed with frames in the WAL:
  the next write restarts the WAL anyway, and this way the tick pays for the
  header flush and the journal_size_limit truncate, not the next save. Timed
  apart: ckpt-ms is the copy and its two flushes, restart-ms the restart
  write. An error (SQLITE_LOCKED inside a transaction, I/O) is counted on its
  own (ckpt-errors, ckpt-error) and, like a partial result, leaves the
  writes pending for a later try (maint-settle). Returns {:ckpt outcome
  :ckpt-pages n}."
  [sdb src why pending]
  (let [t0 (now)
        [r err] (try [(search/checkpoint! sdb) nil]
                     (catch :default e [nil e]))
        t1 (now)
        [busy log ckpt] r
        outcome (cond
                  err :failed
                  (and (= 0 busy) (number? log) (= log ckpt)) :complete
                  :else :partial)
        ckpt-pages (write-count sdb src)
        restart? (and (= :complete outcome) (pos? log))
        _ (when restart? (search/restart-wal! sdb))
        ms (- t1 t0)
        restart-ms (- (now) t1)]
    (note-maint! (fn [m] (cond-> (-> m
                                     (update :ckpts (fnil inc 0))
                                     (update (keyword (str "ckpt-" (name why))) (fnil inc 0))
                                     (update :ckpt-ms (fnil + 0) ms)
                                     (update :ckpt-max-ms (fnil max 0) ms)
                                     (update :ckpt-pending (fnil + 0) pending)
                                     (update :ckpt-pending-max (fnil max 0) pending)
                                     (update :wal-log-max (fnil max 0) (or log 0))
                                     (update :wal-ckpt (fnil + 0) (or ckpt 0)))
                           restart? (-> (update :restarts (fnil inc 0))
                                        (update :restart-ms (fnil + 0) restart-ms))
                           (= :partial outcome) (update :ckpt-partial (fnil inc 0))
                           err (-> (update :ckpt-errors (fnil inc 0))
                                   (assoc :ckpt-error (str (or (ex-message err) err)))))))
    {:ckpt outcome :ckpt-pages ckpt-pages}))

(defn- maint-tick!
  "One tick; reschedules itself while sdb is still repo's search db. It
  deletes queued orphans, then does at most one of: a checkpoint (and its
  restart row), or one FTS5 merge step. Nothing while the connection has a
  transaction open. A checkpoint error is counted and retried later
  (checkpoint!); any other failure stops the tick for this session (logged
  once): the auto-checkpoint and crisismerge still bound the WAL and the
  segments."
  [repo sdb]
  (let [st (get @*maint repo)]
    (if-not (and st
                 (identical? sdb (:sdb st))
                 (identical? sdb (worker-state/get-sqlite-conn repo :search)))
      (when (and st (identical? sdb (:sdb st)))
        (swap! *maint dissoc repo))
      (let [t0 (now)
            src (:src st)
            next-ms
            (try
              (if (search/txn-open? @worker-state/*sqlite sdb)
                ;; A transaction is open on the connection: a checkpoint would
                ;; fail with SQLITE_LOCKED and a write would join it. Not one
                ;; of ours: .transaction runs its callback through COMMIT in
                ;; one task, so no timer task runs inside it, and oo1 exec
                ;; finalizes its statements. So a statement left stepping or a
                ;; BEGIN nobody ended; the next tick looks again.
                (do (note-maint! (fn [m] (update m :txn-skips (fnil inc 0))))
                    (log-maint! (now) src)
                    maint-tick-ms)
                (let [tc (search/total-changes sdb)
                      busy (busy? t0)
                      orphans (delete-orphans! repo sdb)
                      pages (write-count sdb src)
                      [st' seen] (maint-observe st t0 tc pages orphans)
                      {:keys [ckpt merge? idle?]} (maint-action (assoc seen :busy? busy))
                      ;; Level 0 before this tick's merge. Only writes add
                      ;; segments, so it is read only after some.
                      l0 (if (:wrote? seen) (search/level0-segments sdb) (:l0 st))
                      c (when ckpt (checkpoint! sdb src ckpt (:pending-pages seen)))
                      step (when merge? (merge-step! sdb src (:merge-n st') idle?))
                      pages' (write-count sdb src)
                      st' (cond-> (maint-settle st' t0 {:tc (search/total-changes sdb)
                                                        :pages pages'
                                                        :ckpt (:ckpt c)
                                                        :ckpt-pages (:ckpt-pages c)
                                                        :merge-left? (if step (:left? step) (:merge? st'))})
                            step (merge (maint-merge-n st' (:ms step) (:left? step))))
                      l0' (if step (search/level0-segments sdb) l0)
                      ms (- (now) t0)]
                  (when (pos? orphans)
                    (note-maint! (fn [m] (update m :orphans (fnil + 0) orphans))))
                  ;; Level 0 went down since the last tick with no merge step in
                  ;; between: a crisismerge ran inside a commit (or the index was
                  ;; truncated), the stall this tick is there to prevent.
                  (when (and (some? l0) (some? (:l0 st)) (< l0 (:l0 st)))
                    (note-maint! (fn [m] (update m :l0-drops (fnil inc 0)))))
                  (when (or c step (pos? orphans))
                    (note-maint! (fn [m] (cond-> (-> m
                                                     (update :ticks (fnil inc 0))
                                                     (update (if idle? :idle-tick-max-ms :busy-tick-max-ms)
                                                             (fnil max 0) ms))
                                           (some? l0) (update :l0-max (fnil max 0) l0)))))
                  (swap! *maint update repo
                         (fn [cur]
                           (when cur
                             (merge cur (dissoc st' :sdb :timer) {:l0 l0'}))))
                  (log-maint! (now) src)
                  (maint-next-ms st' t0 idle? (> pages' (:done-pages st')))))
              (catch :default e
                (js/console.error "search: maintenance tick failed" e)
                (log-step! "maint-failed" {:msg (str (or (ex-message e) e))})
                nil))]
        (if (and next-ms (get @*maint repo))
          (swap! *maint assoc-in [repo :timer] (js/setTimeout #(maint-tick! repo sdb) next-ms))
          (swap! *maint dissoc repo))))))

(defn start-maint!
  "Start repo's maintenance tick on sdb. Only db_worker's open path calls this,
  right after it sets the search db's pragmas; never a test. A db without
  changes() (a fake, no sqlite) starts nothing and returns nil. close! stops
  the tick. The write counter is SQLITE_DBSTATUS_CACHE_WRITE when the capi
  call works, else total_changes (write-count), fixed for the session.
  Nothing is pending at start: frames an earlier session left in the WAL go
  with the first checkpoint. FTS5 merging starts as pending, so segments left
  by an earlier session are merged in idle time."
  [repo ^js sdb]
  (stop-maint! repo)
  (when (and sdb (fn? (.-changes sdb)))
    (let [t (now)
          tc (search/total-changes sdb)
          p (search/cache-writes @worker-state/*sqlite sdb)
          pages (if (some? p) p tc)]
      (swap! *maint assoc repo {:sdb sdb :src (if (some? p) :pages :changes) :tc tc
                                :ckpt-pages pages :done-pages pages
                                :write-t t :app-t nil :merge? true
                                :merge-n maint-merge-n-initial :merge-fast 0
                                :timer (js/setTimeout #(maint-tick! repo sdb) maint-tick-ms)})
      true)))

;; ---------------------------------------------------------------------------
;; Fuse page index, built in slices

(defonce ^:private *fuse-token (atom 0))

(defn- build-fuse!
  [repo conn]
  (let [token (swap! *fuse-token inc)
        indice (search/new-fuzzy-indice)
        t-start (now)
        *stats (volatile! {:pages 0 :slices 0 :max-slice-ms 0})]
    (swap! search/fuzzy-builds assoc repo {:indice indice :cursor 0 :token token})
    (letfn [(current? []
              (and (= token (get-in @search/fuzzy-builds [repo :token]))
                   (identical? conn (worker-state/get-datascript-conn repo))))
            (abort! []
              (swap! search/fuzzy-builds
                     (fn [m] (if (= token (get-in m [repo :token])) (dissoc m repo) m))))
            (step [max-items]
              (try
                (if-not (current?)
                  (abort!)
                  (let [t0 (now)
                        budget (if (busy? t0) busy-budget-ms idle-budget-ms)
                        {:keys [docs last-e n done?]}
                        (search/fuzzy-page-batch @conn (get-in @search/fuzzy-builds [repo :cursor])
                                                 {:max-items max-items :deadline (+ t0 budget)})
                        _ (search/add-fuzzy-docs! indice docs)
                        ms (- (now) t0)
                        pages (+ (:pages @*stats) n)]
                    (swap! search/fuzzy-builds assoc-in [repo :cursor] last-e)
                    (vswap! *stats (fn [s] (-> s
                                               (assoc :pages pages)
                                               (update :slices inc)
                                               (update :max-slice-ms max ms))))
                    (cond
                      (> pages search/fuzzy-page-limit)
                      (do (abort!)
                          (log-step! "fuse-skipped" {:pages pages}))

                      done?
                      (do
                        (swap! search/fuzzy-builds dissoc repo)
                        (swap! search/fuzzy-search-indices assoc repo indice)
                        (log-step! "fuse-done" {:pages pages
                                                :slices (:slices @*stats)
                                                :max-slice-ms (js/Math.round (:max-slice-ms @*stats))
                                                :ms (js/Math.round (- (now) t-start))}))

                      :else
                      (js/setTimeout #(step (next-batch-size n ms budget))
                                     (if (busy? (now)) busy-yield-ms idle-yield-ms)))))
                (catch :default e
                  (abort!)
                  (js/console.error "search: page index build failed" e)
                  (log-step! "fuse-failed" {:msg (str (or (ex-message e) e))}))))]
      (step 64))))

(defn ensure-fuse!
  "Called on every search: for a file graph with at most search/fuzzy-page-limit
  pages and no Fuse page index, start building one in slices. Until it is done
  search-blocks has no fuzzy page hits (page titles still match through
  blocks_fts). DB graphs keep the synchronous build in search/fuzzy-search."
  [repo]
  (try
    (when-let [conn (worker-state/get-datascript-conn repo)]
      (let [db @conn]
        (when (and (nil? (get @search/fuzzy-search-indices repo))
                   (nil? (get @search/fuzzy-builds repo))
                   (not (ldb/db-based-graph? db))
                   (not (search/large-graph? db)))
          (build-fuse! repo conn))))
    (catch :default e
      (js/console.error "search: cannot start page index build" e))))
