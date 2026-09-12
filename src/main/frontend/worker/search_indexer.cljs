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
  sync-failed / failed / cancelled."
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

(defn close!
  "Called before repo's search db closes."
  [repo]
  (cancel! repo)
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
                {:keys [blocks-to-remove-set blocks-to-add]} (search/sync-search-indice repo tx-report)]
            (when sdb
              (let [tx (:max-tx (:db-after tx-report))
                    w (if (number? tx)
                        (max tx (or (:indexed-tx (repo-state repo)) 0))
                        (:indexed-tx (repo-state repo)))]
                (search/sync-rows! sdb blocks-to-remove-set blocks-to-add {:indexed-tx w})
                (when (number? w)
                  (swap! *repos assoc-in [repo :indexed-tx] w))))
            (vswap! *sync-perf (fn [m] (-> m
                                           (update :ms + (- (now) t0))
                                           (update :rows + (count blocks-to-add)))))))))
    (catch :default e
      (js/console.error "search: incremental sync failed" e)
      (mark-dirty! repo e))))

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
