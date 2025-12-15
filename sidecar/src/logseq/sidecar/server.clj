(ns logseq.sidecar.server
  "Logsidian sidecar server with DataScript backend.

   This server provides:
   - Multi-graph support (each graph has its own DataScript DB)
   - Thread-API compatible operations (query, transact, pull)
   - IPC interface via TCP sockets

   Architecture:
   - Server manages a map of graph-id -> DataScript connection
   - Incoming requests are routed based on :op field
   - Each operation is dispatched to the appropriate handler"
  (:require [clojure.tools.logging :as log]
            [clojure.pprint :as pp]
            [datascript.core :as d]
            [logseq.sidecar.extract :as extract]
            [logseq.sidecar.file-export :as file-export]
            [logseq.sidecar.outliner :as outliner]
            [logseq.sidecar.pipes :as pipes]
            [logseq.sidecar.protocol :as protocol]
            [logseq.sidecar.storage :as storage]
            [logseq.sidecar.websocket :as websocket])
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.io FileWriter])
  (:gen-class))

;; =============================================================================
;; Debug Logging
;; =============================================================================

;; Debug logging enabled during development
;; TODO: Disable by default and enable with LOGSIDIAN_DEBUG=true before release
(def ^:private ^:dynamic *debug-enabled* true)
(def ^:private debug-log-file "sidecar-debug.log")

(defn- debug-log!
  "Write debug information to both console and file for later analysis."
  [operation data]
  (when *debug-enabled*
    (let [timestamp (java.time.LocalDateTime/now)
          entry {:timestamp (str timestamp)
                 :operation operation
                 :data data}
          formatted (with-out-str (pp/pprint entry))]
      ;; Log to console
      (log/info (str "[DEBUG] " operation) (pr-str (select-keys data [:graph-id :op :type])))
      ;; Append to file
      (try
        (with-open [w (FileWriter. debug-log-file true)]
          (.write w (str "=== " timestamp " - " operation " ===\n"))
          (.write w formatted)
          (.write w "\n"))
        (catch Exception e
          (log/warn "Could not write to debug log:" (.getMessage e)))))))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:const DEFAULT_PORT 47632)

;; =============================================================================
;; Global State (NOT per-graph)
;; These atoms mirror the web worker's state management
;; =============================================================================

;; App state from frontend (synced via sync-app-state)
;; Contains :git/current-repo, :config, UI state, etc.
(defonce ^:private *app-state (atom {}))

;; Worker context (synced via set-context)
;; Contains :mobile?, :electron?, :nfs-supported?, etc.
(defonce ^:private *context (atom {}))

;; Thread atoms (keyed by :thread-atom/* keywords)
;; Used for editor state, cursor position, etc.
(defonce ^:private *thread-atoms (atom {}))

;; RTC WebSocket URL (from init)
(defonce ^:private *rtc-ws-url (atom nil))

;; Logseq-compatible schema for blocks and pages (matches file_based/schema.cljs)
(def base-schema
  {:db/ident          {:db/unique :db.unique/identity}
   :kv/value          {}
   :recent/pages      {}

   ;; Block core attributes
   :block/uuid        {:db/unique :db.unique/identity}
   :block/name        {:db/unique :db.unique/identity}  ;; Page name, lowercase
   :block/title       {:db/index true}                   ;; Page's original name
   :block/type        {:db/index true}
   :block/content     {}
   :block/format      {}
   :block/order       {:db/index true}
   :block/collapsed?  {}

   ;; Block relationships
   :block/parent      {:db/valueType :db.type/ref :db/index true}
   :block/left        {:db/valueType :db.type/ref}
   :block/page        {:db/valueType :db.type/ref :db/index true}
   :block/refs        {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :block/tags        {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :block/alias       {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many :db/index true}
   :block/link        {:db/valueType :db.type/ref :db/index true}
   :block/namespace   {:db/valueType :db.type/ref}
   :block/macros      {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :block/file        {:db/valueType :db.type/ref}

   ;; Block properties
   :block/properties            {}
   :block/properties-order      {}
   :block/properties-text-values {}
   :block/marker       {}
   :block/priority     {}
   :block/scheduled    {}
   :block/deadline     {}
   :block/repeated?    {}
   :block/pre-block?   {}
   :block/tx-id        {}

   ;; Timestamps (indexed for queries)
   :block/created-at   {:db/index true}
   :block/updated-at   {:db/index true}
   :block/journal-day  {:db/index true}

   ;; File attributes
   :file/path          {:db/unique :db.unique/identity}
   :file/content       {}
   :file/created-at    {}
   :file/last-modified-at {}
   :file/size          {}})

;; =============================================================================
;; Server State
;; =============================================================================

(defrecord SidecarServer [pipe-server     ; The underlying pipes server (TCP)
                          ws-server       ; WebSocket server for browser clients
                          graphs          ; ConcurrentHashMap of graph-id -> {:conn DataScript-conn :schema schema}
                          running?])

;; =============================================================================
;; Graph Management
;; =============================================================================

(defn create-graph
  "Create a new graph with the given ID.
   Returns the graph-id on success, nil on failure.

   Options:
   - :schema - DataScript schema (default: base-schema)
   - :storage-path - Path to SQLite database for persistence.
                     Use \":memory:\" for in-memory storage.
                     If nil, uses plain DataScript without persistence.
   - :ref-type - Reference type (:soft for soft references, nil for strong).
                 Soft references allow GC to reclaim memory under pressure."
  [^SidecarServer server graph-id {:keys [schema storage-path ref-type]
                                   :or {schema base-schema}}]
  (let [graphs ^ConcurrentHashMap (:graphs server)]
    (if (.containsKey graphs graph-id)
      (do
        (log/warn "Graph already exists:" graph-id)
        graph-id)
      (let [;; Create storage if path provided
            storage (when storage-path
                      (storage/create-sqlite-storage storage-path))
            ;; Build connection options
            conn-opts (cond-> {}
                        storage (assoc :storage storage)
                        ref-type (assoc :ref-type ref-type))
            ;; Create connection with or without storage
            conn (if (empty? conn-opts)
                   (d/create-conn schema)
                   (d/create-conn schema conn-opts))]
        (.put graphs graph-id {:conn conn
                               :schema schema
                               :storage storage
                               :storage-path storage-path})
        (log/info "Created graph:" graph-id
                  {:storage-path storage-path
                   :ref-type ref-type
                   :has-storage (some? storage)})
        graph-id))))

(defn remove-graph
  "Remove a graph from the server.
   Also closes the storage if it exists."
  [^SidecarServer server graph-id]
  (let [graphs ^ConcurrentHashMap (:graphs server)
        graph-info (.get graphs graph-id)]
    (when (.remove graphs graph-id)
      ;; Close storage if it exists and implements Closeable
      (when-let [storage (:storage graph-info)]
        (when (instance? java.io.Closeable storage)
          (.close ^java.io.Closeable storage)
          (log/info "Closed storage for graph:" graph-id)))
      (log/info "Removed graph:" graph-id)
      true)))

(defn graph-exists?
  "Check if a graph exists."
  [^SidecarServer server graph-id]
  (.containsKey ^ConcurrentHashMap (:graphs server) graph-id))

(defn- get-conn
  "Get the DataScript connection for a graph."
  [^SidecarServer server graph-id]
  (when-let [graph-info (.get ^ConcurrentHashMap (:graphs server) graph-id)]
    (:conn graph-info)))

;; =============================================================================
;; CLJS-to-CLJ Data Normalization
;; =============================================================================
;;
;; When data is sent from CLJS through Transit via Electron IPC, type information
;; can be lost. Keywords become strings, symbols become strings, etc.
;; These functions normalize the data back to proper Clojure types.

(def ^:private datalog-clause-names
  "Known Datalog clause names that should be keywords."
  #{"find" "where" "in" "with" "keys" "strs" "syms"})

(def ^:private datalog-function-names
  "Known Datalog/Clojure function names that should be symbols, not keywords."
  #{">" "<" ">=" "<=" "=" "!=" "not=" "not"
    "contains?" "get" "get-in" "count" "str" "re-find" "re-matches"
    "and" "or" "identity" "ground" "missing?" "tuple"
    ;; Custom rule predicates from Logseq
    "task"})

(defn- query-variable?
  "Check if s looks like a Datalog query variable or special symbol."
  [s]
  (let [s-str (str s)]
    (or (clojure.string/starts-with? s-str "?")
        (clojure.string/starts-with? s-str "$")  ;; Database reference ($, $1, etc.)
        (= s-str "_")      ;; Wildcard
        (= s-str "...")    ;; Spread operator
        (= s-str "pull")   ;; Pull keyword in query
        (= s-str "*")      ;; Wildcard for pull patterns
        (= s-str "%")      ;; Rules placeholder in queries
        (contains? datalog-function-names s-str))))  ;; Known function names

(defn normalize-cljs-data
  "Recursively normalize CLJS data that may have lost type information.

   Handles:
   - Namespaced strings (foo/bar) → keywords (:foo/bar)
   - Datalog clause names (find, where) → keywords (:find, :where)
   - Query variables (?e, ?name, _) → symbols
   - Vectors, lists, maps → recursively normalized
   - Numbers, booleans, nil → unchanged

   This is the core fix for Transit serialization issues between CLJS and CLJ."
  [data]
  (cond
    ;; Already a keyword - keep as is
    (keyword? data)
    data

    ;; Vectors - recurse into them
    (vector? data)
    (mapv normalize-cljs-data data)

    ;; Lists - recurse (for pull patterns etc)
    (list? data)
    (map normalize-cljs-data data)

    ;; Sets - recurse
    (set? data)
    (set (map normalize-cljs-data data))

    ;; Maps - normalize both keys and values
    (map? data)
    (into {} (map (fn [[k v]]
                    [(normalize-cljs-data k) (normalize-cljs-data v)])
                  data))

    ;; Strings or symbols that might need conversion
    (or (string? data) (symbol? data))
    (let [s (str data)]
      (cond
        ;; Datalog clause names become keywords
        (contains? datalog-clause-names s)
        (keyword s)

        ;; Query variables stay as symbols
        (query-variable? s)
        (symbol s)

        ;; Namespaced names (foo/bar) become keywords
        ;; This handles attribute names like block/name → :block/name
        (clojure.string/includes? s "/")
        (let [[ns name] (clojure.string/split s #"/" 2)]
          (keyword ns name))

        ;; Plain strings without namespace - keep as is (might be literal values)
        :else
        data))

    ;; Everything else (numbers, booleans, nil) - keep as is
    :else
    data))

;; =============================================================================
;; DataScript Operations
;; =============================================================================

(defn- normalize-query
  "Normalize query format from CLJS frontend.
   Uses normalize-cljs-data for the heavy lifting."
  [query-form]
  (let [result (normalize-cljs-data query-form)]
    (when-not (= query-form result)
      (log/debug "normalize-query" {:changed? true
                                    :sample-original (take 3 query-form)
                                    :sample-result (take 3 result)}))
    result))

(defn query
  "Execute a Datalog query on a graph."
  [^SidecarServer server graph-id query-form inputs]
  (let [normalized (normalize-query query-form)
        ;; Also normalize inputs (especially rules which contain symbols)
        normalized-inputs (mapv normalize-cljs-data inputs)]
    ;; Comprehensive debug logging for query analysis
    (debug-log! "QUERY-RECEIVED"
                {:graph-id graph-id
                 :query-form-raw query-form
                 :query-form-type (type query-form)
                 :inputs-raw inputs
                 :inputs-types (mapv type inputs)})
    (debug-log! "QUERY-NORMALIZED"
                {:graph-id graph-id
                 :query-normalized normalized
                 :query-normalized-type (type normalized)
                 :inputs-normalized normalized-inputs
                 :inputs-normalized-types (mapv type normalized-inputs)
                 :changed-query? (not= query-form normalized)
                 :changed-inputs? (not= inputs normalized-inputs)})
    ;; Deep type inspection for rules (usually the last input)
    ;; Rules are vectors of vectors: [[[rule-head clause1 clause2] ...]]
    ;; Skip inspection if the input doesn't look like rules (e.g., pull patterns)
    (when (seq inputs)
      (let [rules-candidate (last inputs)]
        (when (and (vector? rules-candidate)
                   (seq rules-candidate)
                   (vector? (first rules-candidate))  ;; Rules are nested vectors
                   (vector? (ffirst rules-candidate))) ;; First rule is also a vector
          (debug-log! "RULES-INSPECTION"
                      {:rules-raw rules-candidate
                       :rules-normalized (last normalized-inputs)
                       :first-rule-raw (first rules-candidate)
                       :first-rule-normalized (first (last normalized-inputs))}))))
    (if-let [conn (get-conn server graph-id)]
      (try
        (let [result (apply d/q normalized @conn normalized-inputs)]
          (debug-log! "QUERY-SUCCESS"
                      {:graph-id graph-id
                       :result-count (count result)
                       :result-sample (take 3 result)})
          result)
        (catch Exception e
          (debug-log! "QUERY-ERROR"
                      {:graph-id graph-id
                       :error-class (type e)
                       :error-message (.getMessage e)
                       :query-form normalized
                       :inputs normalized-inputs})
          (throw e)))
      (throw (ex-info "Graph not found" {:graph-id graph-id})))))

(defn transact!
  "Execute a transaction on a graph.
   Normalizes tx-data to handle CLJS data type issues.
   Returns the transaction report."
  [^SidecarServer server graph-id tx-data]
  (if-let [conn (get-conn server graph-id)]
    (let [normalized-tx (normalize-cljs-data tx-data)]
      (d/transact! conn normalized-tx))
    (throw (ex-info "Graph not found" {:graph-id graph-id}))))

(defn- normalize-eid
  "Normalize an entity ID for DataScript.
   Handles:
   - Numbers (db/id) - pass through
   - Keywords (:block/uuid) - pass through
   - Vectors (lookup refs) - normalize only the attribute (first element)
   - Strings with / that look like attributes - convert to keyword"
  [eid]
  (cond
    ;; Already a number or keyword - pass through
    (or (number? eid) (keyword? eid))
    eid

    ;; Lookup ref vector like ["file/path" "logseq/config.edn"]
    ;; Only the first element (attribute) should be a keyword
    ;; The second element (value) should stay as-is
    (and (vector? eid) (= 2 (count eid)))
    (let [[attr val] eid
          ;; keyword handles "ns/name" automatically, no need to split
          normalized-attr (if (string? attr)
                            (keyword attr)
                            attr)]
      [normalized-attr val])

    ;; Other - try generic normalization (might be an entity map or something)
    :else
    (normalize-cljs-data eid)))

(defn pull
  "Pull an entity from a graph.
   Normalizes selector and eid to handle CLJS data type issues."
  [^SidecarServer server graph-id selector eid]
  (if-let [conn (get-conn server graph-id)]
    (let [;; Normalize selector (pull pattern) - converts strings like "block/name" to :block/name
          normalized-selector (normalize-cljs-data selector)
          ;; Normalize eid - special handling for lookup refs
          normalized-eid (normalize-eid eid)]
      (log/debug "pull" {:selector selector
                         :normalized-selector normalized-selector
                         :eid eid
                         :normalized-eid normalized-eid})
      (d/pull @conn normalized-selector normalized-eid))
    (throw (ex-info "Graph not found" {:graph-id graph-id}))))

(defn pull-many
  "Pull multiple entities from a graph.
   Normalizes selector and eids to handle CLJS data type issues."
  [^SidecarServer server graph-id selector eids]
  (if-let [conn (get-conn server graph-id)]
    (let [normalized-selector (normalize-cljs-data selector)
          normalized-eids (mapv normalize-eid eids)]
      (d/pull-many @conn normalized-selector normalized-eids))
    (throw (ex-info "Graph not found" {:graph-id graph-id}))))

(defn- datom->vec
  "Convert a Datom to a vector [e a v tx added?] for Transit serialization.
   Uses keyword access since Datom implements ILookup."
  [datom]
  [(:e datom) (:a datom) (:v datom) (:tx datom) (:added datom)])

(defn datoms
  "Get datoms from a graph by index.
   Normalizes index and components to handle CLJS data type issues.
   Returns vectors instead of Datom objects for Transit serialization."
  [^SidecarServer server graph-id index & components]
  (if-let [conn (get-conn server graph-id)]
    (let [normalized-index (normalize-cljs-data index)
          normalized-components (mapv normalize-cljs-data components)]
      (mapv datom->vec (apply d/datoms @conn normalized-index normalized-components)))
    (throw (ex-info "Graph not found" {:graph-id graph-id}))))

;; =============================================================================
;; Datom Sync Operations
;; =============================================================================

(defn- normalize-attribute
  "Normalize a datom attribute from string to keyword.
   Handles Transit serialization where :block/name becomes \"block/name\"."
  [attr]
  (cond
    (keyword? attr) attr
    (string? attr)  (if (clojure.string/includes? attr "/")
                      (let [[ns name] (clojure.string/split attr #"/" 2)]
                        (keyword ns name))
                      (keyword attr))
    :else attr))

(defn- datom-vec->tx-data
  "Convert a datom vector [e a v tx added?] to transaction data.
   Normalizes the attribute from string to keyword.
   For assertions (added?=true): [:db/add e a v]
   For retractions (added?=false): [:db/retract e a v]"
  [[e a v _tx added?]]
  (let [normalized-attr (normalize-attribute a)]
    (if added?
      [:db/add e normalized-attr v]
      [:db/retract e normalized-attr v])))

(defn sync-datoms
  "Sync datom batches from the main process into the sidecar's DataScript database.

   This is the core mechanism for keeping the sidecar in sync with the main process.
   The main process sends datoms after parsing files or receiving edits.

   Arguments:
   - server: The SidecarServer instance
   - graph-id: The graph/repo identifier
   - datoms: Vector of datom vectors [e a v tx added?]
   - opts: Options map with:
     - :full-sync? - If true, this is an initial full sync (clears existing data first)

   Returns a map with sync statistics."
  [^SidecarServer server graph-id datoms opts]
  (if-let [conn (get-conn server graph-id)]
    (let [start-time (System/currentTimeMillis)
          full-sync? (:full-sync? opts)

          ;; For full sync, we could clear the DB first, but for now we just
          ;; apply all datoms. This works because we use entity IDs from the
          ;; source, not tempids.
          _ (when full-sync?
              (log/info "Full sync starting for" graph-id "with" (count datoms) "datoms"))

          ;; Convert datom vectors to transaction data
          tx-data (mapv datom-vec->tx-data datoms)

          ;; Apply transaction
          tx-report (d/transact! conn tx-data)

          elapsed (- (System/currentTimeMillis) start-time)]

      (log/info "Sync completed for" graph-id
                {:datom-count (count datoms)
                 :elapsed-ms elapsed
                 :full-sync? full-sync?})

      {:datom-count (count datoms)
       :elapsed-ms elapsed
       :tx-data-count (count tx-data)})

    (throw (ex-info "Graph not found" {:graph-id graph-id}))))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn- normalize-keyword
  "Normalize a potentially string keyword to an actual keyword.
   CLJS Transit with cljs-bean handlers may send keywords as strings like 'thread-api/q'
   instead of keywords like :thread-api/q. This handles both cases."
  [k]
  (cond
    (keyword? k) k
    (string? k) (let [k-str (str k)]
                  (if (clojure.string/includes? k-str "/")
                    (let [[ns name] (clojure.string/split k-str #"/" 2)]
                      (keyword ns name))
                    (keyword k-str)))
    :else k))

;; =============================================================================
;; State Management Operations
;; =============================================================================

(defn handle-sync-app-state
  "Merge new state into global app-state.
   Called by frontend to sync UI state to worker.
   Uses MERGE semantics to preserve existing state."
  [_server new-state]
  (when (and (contains? new-state :git/current-repo)
             (nil? (:git/current-repo new-state)))
    (log/warn "sync-app-state received nil :git/current-repo"))
  (swap! *app-state merge new-state)
  nil)

(defn handle-set-context
  "Merge new context into global context.
   Called by frontend to provide runtime context.
   Uses MERGE semantics to preserve existing context."
  [_server context]
  (when context
    (swap! *context merge context))
  nil)

(defn handle-update-thread-atom
  "Update a thread atom by key.
   Only :thread-atom/* namespaced keys are allowed.
   Normalizes string keys to keywords (CLJS Transit compat)."
  [_server atom-key new-value]
  (let [normalized-key (normalize-keyword atom-key)]
    (when-not (and (keyword? normalized-key)
                   (= "thread-atom" (namespace normalized-key)))
      (throw (ex-info "Invalid thread-atom key - must be namespaced with thread-atom"
                      {:key atom-key :normalized-key normalized-key})))
    (swap! *thread-atoms assoc normalized-key new-value)
    nil))

(defn handle-list-db
  "List all graph databases.
   Returns vector of {:name graph-id :metadata {...}}."
  [^SidecarServer server]
  (let [graphs ^ConcurrentHashMap (:graphs server)]
    (mapv (fn [[graph-id _]]
            {:name graph-id :metadata {}})
          graphs)))

(defn handle-init
  "Initialize the sidecar worker.
   Stores the RTC WebSocket URL for later use.
   Note: Unlike web worker, we don't init SQLite - we use DataScript."
  [_server rtc-ws-url]
  (reset! *rtc-ws-url rtc-ws-url)
  nil)

(defn handle-create-or-open-db
  "Create or open a graph database.
   This is the sidecar equivalent of the web worker's create-or-open-db."
  [^SidecarServer server repo opts]
  (let [graphs ^ConcurrentHashMap (:graphs server)]
    (when-not (.containsKey graphs repo)
      (create-graph server repo (or opts {}))))
  nil)

(defn handle-get-initial-data
  "Get initial data for bootstrapping the frontend.
   Returns {:schema ... :initial-data ...}
   For file-graph-import, returns all datoms.
   For normal load, returns page datoms only."
  [^SidecarServer server repo opts]
  (if-let [conn (get-conn server repo)]
    (let [db @conn
          schema (:schema db)]
      (if (:file-graph-import? opts)
        ;; For file graph import, return all datoms
        {:schema schema
         :initial-data (mapv datom->vec (d/datoms db :eavt))}
        ;; For normal load, return pages and essential data
        (let [page-datoms (mapcat (fn [d] (d/datoms db :eavt (:e d)))
                                  (d/datoms db :avet :block/name))]
          {:schema schema
           :initial-data (mapv datom->vec page-datoms)})))
    ;; Return nil if graph doesn't exist yet - frontend will handle
    nil))

(defn handle-get-view-data
  "Get view data for rendering.
   For journals? true, returns list of journal page IDs sorted by date descending.
   This mirrors db-view/get-view-data in the frontend."
  [^SidecarServer server repo _view-id {:keys [journals?] :as _opts}]
  (if journals?
    ;; Get latest journals from DataScript
    (let [conn (get-conn server repo)]
      (log/debug "get-view-data" {:repo repo :journals? journals? :conn? (some? conn)})
      (if conn
        (let [db @conn
              ;; Get today's date as YYYYMMDD integer
              today (let [now (java.time.LocalDate/now)]
                      (+ (* (.getYear now) 10000)
                         (* (.getMonthValue now) 100)
                         (.getDayOfMonth now)))
              ;; Get all journal-day datoms for debugging
              all-journal-datoms (d/datoms db :avet :block/journal-day)
              _ (log/debug "get-view-data all journal datoms" {:total (count (seq all-journal-datoms))})
              ;; Query datoms with :block/journal-day, filter to <= today, sort desc
              ids (->> all-journal-datoms
                       (filter (fn [d] (<= (:v d) today)))
                       (sort-by :v >)  ; descending by date
                       (mapv :e))]     ; get entity IDs
          (log/debug "get-view-data journals" {:count (count ids) :today today})
          {:count (count ids)
           :data ids})
        ;; No connection - return empty
        (do
          (log/warn "get-view-data: no connection for repo" {:repo repo
                                                              :graphs (keys (.graphs server))})
          {:count 0 :data []})))
    ;; Non-journal views not supported in file graphs
    nil))

;; Accessors for testing
(defn get-app-state [_server] @*app-state)
(defn get-context [_server] @*context)
(defn get-thread-atom [_server key] (get @*thread-atoms key))
(defn get-rtc-ws-url [_server] @*rtc-ws-url)

;; Reset functions for test isolation and clean shutdown
(defn reset-global-state!
  "Reset all global state atoms. Called during server stop for clean shutdown."
  []
  (reset! *app-state {})
  (reset! *context {})
  (reset! *thread-atoms {})
  (reset! *rtc-ws-url nil))

;; =============================================================================
;; Request Handler
;; =============================================================================

(defn- handle-request
  "Handle an incoming request and return a response.

   IMPORTANT: Thread-API operations receive args as {:args [arg1 arg2 ...]}
   from the sidecar client wrapper, not as named keys."
  [^SidecarServer server request]
  ;; Debug log all incoming requests
  (debug-log! "REQUEST-RAW" {:request request
                              :request-type (type request)
                              :op (:op request)
                              :type (:type request)})
  ;; Handle handshake messages specially - they use :type instead of :op
  (if (= :handshake (:type request))
    (let [client-version (get request :version "1.0.0")
          validation (protocol/validate-handshake client-version)]
      (log/info "Handshake from client version:" client-version)
      {:type :handshake-response
       :ok? (:ok? validation)
       :version protocol/PROTOCOL_VERSION
       :capabilities (:capabilities validation)})
    ;; Handle regular requests
    (let [{:keys [op payload id]} request
          op (normalize-keyword op)]  ;; Convert string ops to keywords (CLJS Transit compat)
      (try
        (case op
          ;; ============================================
          ;; Graph management (uses named keys for internal use)
          ;; ============================================
          :create-graph
          (let [result (create-graph server (:graph-id payload) payload)]
            (protocol/make-response op {:graph-id result} id))

          :remove-graph
          (let [result (remove-graph server (:graph-id payload))]
            (protocol/make-response op {:removed result} id))

          ;; ============================================
          ;; Thread API operations - extract from :args
          ;; Client sends: {:args [arg1 arg2 ...]}
          ;; ============================================

          :thread-api/q
          ;; Web worker signature: [repo inputs] where inputs = [query & query-inputs]
          (let [[repo inputs] (:args payload)
                query-form (first inputs)
                query-inputs (rest inputs)]
            (debug-log! "THREAD-API/Q-ARGS"
                        {:repo repo
                         :payload-args (:args payload)
                         :inputs inputs
                         :query-form query-form
                         :query-form-type (type query-form)
                         :query-inputs query-inputs
                         :query-inputs-count (count query-inputs)
                         :query-inputs-types (mapv type query-inputs)})
            (let [result (query server repo query-form query-inputs)]
              (protocol/make-response op (vec result) id)))

          :thread-api/transact
          ;; Web worker signature: [repo tx-data tx-meta context]
          (let [[repo tx-data _tx-meta _context] (:args payload)
                tx-report (transact! server repo tx-data)
                tempids (into {} (:tempids tx-report))]
            (protocol/make-response op {:tempids tempids} id))

          :thread-api/pull
          ;; Web worker signature: [repo selector id]
          (let [[repo selector eid] (:args payload)
                result (pull server repo selector eid)]
            (protocol/make-response op result id))

          :thread-api/pull-many
          ;; Web worker signature: [repo selector eids]
          (let [[repo selector eids] (:args payload)
                result (pull-many server repo selector eids)]
            (protocol/make-response op (vec result) id))

          :thread-api/datoms
          ;; Web worker signature: [repo & args]
          (let [[repo & args] (:args payload)
                result (apply datoms server repo args)]
            (protocol/make-response op (vec result) id))

          ;; ============================================
          ;; State management operations
          ;; ============================================

          :thread-api/sync-app-state
          ;; Web worker signature: [new-state]
          (let [[new-state] (:args payload)]
            (handle-sync-app-state server new-state)
            (protocol/make-response op nil id))

          :thread-api/set-context
          ;; Web worker signature: [context]
          (let [[context] (:args payload)]
            (handle-set-context server context)
            (protocol/make-response op nil id))

          :thread-api/update-thread-atom
          ;; Web worker signature: [atom-key new-value]
          (let [[atom-key new-value] (:args payload)]
            (handle-update-thread-atom server atom-key new-value)
            (protocol/make-response op nil id))

          :thread-api/list-db
          ;; Web worker signature: []
          (let [result (handle-list-db server)]
            (protocol/make-response op result id))

          :thread-api/init
          ;; Web worker signature: [rtc-ws-url]
          (let [[rtc-ws-url] (:args payload)]
            (handle-init server rtc-ws-url)
            (protocol/make-response op nil id))

          :thread-api/create-or-open-db
          ;; Web worker signature: [repo opts]
          (let [[repo opts] (:args payload)]
            (handle-create-or-open-db server repo opts)
            (protocol/make-response op nil id))

          :thread-api/get-initial-data
          ;; Web worker signature: [repo opts]
          (let [[repo opts] (:args payload)
                result (handle-get-initial-data server repo opts)]
            (protocol/make-response op result id))

          ;; ============================================
          ;; Datom Sync Operations
          ;; ============================================

          :thread-api/sync-datoms
          ;; Sync datom batches from main process
          ;; Signature: [repo datoms opts]
          ;; datoms = [[e a v tx added?] ...]
          ;; opts = {:full-sync? bool}
          (let [[repo datoms opts] (:args payload)
                result (sync-datoms server repo datoms (or opts {}))]
            (protocol/make-response op result id))

          ;; ============================================
          ;; AST Extraction and Transaction
          ;; ============================================

          :thread-api/extract-and-transact
          ;; Receive parsed AST from worker, extract pages/blocks, transact
          ;; Signature: [repo file-path {:ast <mldoc-ast> :format :markdown/:org}]
          ;; Returns: {:success bool :page-count N :block-count N}
          (let [[repo file-path ast-data] (:args payload)
                ast (:ast ast-data)
                format (or (:format ast-data) :markdown)]
            (if-let [conn (get-conn server repo)]
              (try
                (let [;; Extract pages and blocks from AST
                      {:keys [pages blocks]} (extract/extract-from-ast file-path ast {:format format})
                      ;; Build transaction data
                      tx-data (concat pages blocks)
                      ;; Transact to DataScript
                      _ (when (seq tx-data)
                          (d/transact! conn tx-data))
                      result {:success true
                              :page-count (count pages)
                              :block-count (count blocks)}]
                  (log/debug "extract-and-transact completed" {:file file-path
                                                               :pages (count pages)
                                                               :blocks (count blocks)})
                  (protocol/make-response op result id))
                (catch Exception e
                  (log/error e "extract-and-transact failed" {:file file-path})
                  (protocol/make-response op {:success false
                                              :error (.getMessage e)} id)))
              (protocol/make-error-response :graph-not-found
                                            (str "Graph not found: " repo)
                                            id)))

          ;; ============================================
          ;; Database existence check
          ;; ============================================

          :thread-api/db-exists
          ;; Web worker signature: [repo]
          (let [[repo] (:args payload)
                exists (graph-exists? server repo)]
            (protocol/make-response op exists id))

          ;; ============================================
          ;; Page Operations
          ;; ============================================

          :thread-api/delete-page
          ;; Delete a page and all its blocks from the sidecar DB
          ;; Signature: [repo page-name opts]
          ;; Used when files are deleted from disk
          (let [[repo page-name _opts] (:args payload)]
            (if-let [conn (get-conn server repo)]
              (let [db @conn
                    lowered-name (clojure.string/lower-case page-name)
                    ;; Find page and all its blocks in a single query using or-join
                    eids-to-retract (d/q '[:find [?e ...]
                                           :in $ ?page-name
                                           :where
                                           (or-join [?e ?page-id]
                                             (and [?page-id :block/name ?page-name]
                                                  [(identity ?page-id) ?e])
                                             (and [?page-id :block/name ?page-name]
                                                  [?e :block/page ?page-id]))]
                                         db lowered-name)]
                (if (seq eids-to-retract)
                  (let [;; Find page-id for logging (first entity with :block/name)
                        page-id (d/q '[:find ?e . :in $ ?name :where [?e :block/name ?name]]
                                     db lowered-name)
                        blocks-deleted (dec (count eids-to-retract))
                        retractions (mapv (fn [eid] [:db.fn/retractEntity eid]) eids-to-retract)]
                    (d/transact! conn retractions)
                    (log/info "Deleted page from sidecar" {:page page-name
                                                           :page-id page-id
                                                           :blocks-deleted blocks-deleted})
                    (protocol/make-response op {:deleted true
                                                :page-id page-id
                                                :blocks-deleted blocks-deleted} id))
                  ;; Page not found, just return success (idempotent)
                  (do
                    (log/debug "Page not found in sidecar for deletion" {:page page-name})
                    (protocol/make-response op {:deleted false :reason :not-found} id))))
              (protocol/make-error-response :graph-not-found
                                            (str "Graph not found: " repo)
                                            id)))

          ;; ============================================
          ;; Outliner Operations
          ;; TODO: Port outliner logic from CLJS to CLJ for full implementation
          ;; These are CRITICAL for block editing functionality
          ;; ============================================

          :thread-api/apply-outliner-ops
          ;; Applies outliner operations (insert, delete, move, indent, etc.)
          ;; Web worker signature: [repo ops opts]
          (let [[repo ops opts] (:args payload)]
            (if-let [conn (get-conn server repo)]
              (let [result (outliner/apply-ops! conn ops (or opts {}))]
                (protocol/make-response op result id))
              (protocol/make-error-response :graph-not-found
                                            (str "Graph not found: " repo)
                                            id)))

          :thread-api/get-page-trees
          ;; Get page trees for file synchronization
          ;; Signature: [repo page-ids]
          ;; Returns vector of page trees with blocks for file serialization
          (let [[repo page-ids] (:args payload)]
            (if-let [conn (get-conn server repo)]
              (let [db @conn
                    result (outliner/get-pages-for-file-sync db page-ids)]
                (protocol/make-response op result id))
              (protocol/make-error-response :graph-not-found
                                            (str "Graph not found: " repo)
                                            id)))

          :thread-api/get-file-writes
          ;; Get file writes for affected pages (for file sync after operations)
          ;; Signature: [repo page-ids opts]
          ;; Returns vector of [file-path content] tuples ready for writing
          ;; Used by main process after apply-outliner-ops to write files
          (let [[repo page-ids opts] (:args payload)]
            (if-let [conn (get-conn server repo)]
              (let [db @conn
                    ;; Get the graph directory from opts or use a placeholder
                    ;; (main process will resolve the actual path)
                    graph-dir (or (:graph-dir opts) "")
                    page-trees (outliner/get-pages-for-file-sync db page-ids)
                    file-writes (file-export/pages->file-writes page-trees graph-dir (or opts {}))]
                (protocol/make-response op file-writes id))
              (protocol/make-error-response :graph-not-found
                                            (str "Graph not found: " repo)
                                            id)))

          ;; ============================================
          ;; Stub operations (RTC, vec-search, mobile)
          ;; These return nil - sidecar doesn't support these features
          ;; ============================================

          ;; RTC (Real-Time Collaboration) - not supported in sidecar
          :thread-api/rtc-start
          (do
            (log/debug "Stub op called (unsupported):" op)
            (protocol/make-response op nil id))

          :thread-api/rtc-stop
          (do
            (log/debug "Stub op called (unsupported):" op)
            (protocol/make-response op nil id))

          :thread-api/rtc-sync-graph!
          (do
            (log/debug "Stub op called (unsupported):" op)
            (protocol/make-response op nil id))

          :thread-api/rtc-status
          (do
            (log/debug "Stub op called (unsupported):" op)
            (protocol/make-response op {:rtc-state :closed} id))

          ;; ============================================
          ;; Vector Search Operations
          ;; TODO: Implement full vector search using ONNX Runtime + JVector
          ;; For now, return "not configured" responses (nil/empty)
          ;; which matches behavior when no embedding model is loaded
          ;; ============================================

          :thread-api/vec-search-embedding-model-info
          ;; Returns available models and current graph's model
          ;; For now: no models available (feature not implemented)
          (do
            (log/debug "Vec search not yet implemented:" op)
            (protocol/make-response op {:available-model-names []
                                        :graph-text-embedding-model-name nil} id))

          :thread-api/vec-search-init-embedding-model
          ;; Initializes the embedding model for a repo
          ;; Returns nil if no model configured (which is the case for us)
          (do
            (log/debug "Vec search not yet implemented:" op)
            (protocol/make-response op nil id))

          :thread-api/vec-search-load-model
          ;; Loads a specific embedding model
          ;; Returns false (model not loaded) for now
          (do
            (log/debug "Vec search not yet implemented:" op)
            (protocol/make-response op false id))

          :thread-api/vec-search-embedding-graph
          ;; Triggers embedding of all blocks in graph
          ;; Returns nil (no-op when model not loaded)
          (do
            (log/debug "Vec search not yet implemented:" op)
            (protocol/make-response op nil id))

          :thread-api/vec-search-search
          ;; Semantic search - returns empty results when not configured
          (do
            (log/debug "Vec search not yet implemented:" op)
            (protocol/make-response op [] id))

          :thread-api/vec-search-cancel-indexing
          ;; Cancel ongoing indexing - no-op
          (do
            (log/debug "Vec search not yet implemented:" op)
            (protocol/make-response op nil id))

          :thread-api/vec-search-update-index-info
          ;; Update index info - returns empty info
          (do
            (log/debug "Vec search not yet implemented:" op)
            (protocol/make-response op nil id))

          ;; Legacy vec-search operations (may be used by older code)
          :thread-api/vec-upsert-blocks
          (do
            (log/debug "Vec search not yet implemented:" op)
            (protocol/make-response op nil id))

          :thread-api/vec-delete-blocks
          (do
            (log/debug "Vec search not yet implemented:" op)
            (protocol/make-response op nil id))

          :thread-api/vec-search-blocks
          (do
            (log/debug "Vec search not yet implemented:" op)
            (protocol/make-response op [] id))

          ;; Mobile logs - not applicable to sidecar
          :thread-api/write-log
          (do
            (log/debug "Stub op called (unsupported):" op)
            (protocol/make-response op nil id))

          :thread-api/mobile-get-logs
          (do
            (log/debug "Stub op called (unsupported):" op)
            (protocol/make-response op [] id))

          ;; Import DB - used for file graph import, returns nil since sidecar
          ;; handles graph data differently
          :thread-api/import-db
          (do
            (log/debug "Stub op called (unsupported):" op)
            (protocol/make-response op nil id))

          ;; View data - journals are supported, other views return nil
          :thread-api/get-view-data
          (let [[repo view-id opts] (:args payload)
                result (handle-get-view-data server repo view-id opts)]
            (protocol/make-response op result id))

          ;; Unknown operation (log at debug level for discovery)
          (do
            (log/debug "Unknown operation called:" op)
            (protocol/make-error-response :unknown-op (str "Unknown operation: " op) id)))

        (catch Exception e
          (log/error e "Error handling request:" op)
          (protocol/make-error-response :internal-error (.getMessage e) id))))))

;; =============================================================================
;; Server Lifecycle
;; =============================================================================

(def ^:const DEFAULT_WS_PORT 47633)

(defn start-server
  "Start the sidecar server.
   Options:
   - :port - TCP port to listen on (default: 47632)
   - :ws-port - WebSocket port for browser clients (default: 47633)
   - :enable-websocket? - Whether to start WebSocket server (default: true)"
  ([] (start-server {}))
  ([{:keys [port ws-port enable-websocket?]
     :or {port DEFAULT_PORT
          ws-port DEFAULT_WS_PORT
          enable-websocket? true}}]
   (let [graphs (ConcurrentHashMap.)
         running? (atom true)
         ;; Create server record first (needed for handler closure)
         server (->SidecarServer nil nil graphs running?)
         ;; Handler that closes over server
         handler (fn [request]
                   (handle-request server request))
         ;; Start TCP pipe server
         pipe-server (pipes/start-server "logsidian-sidecar" handler {:port port})
         ;; Start WebSocket server for browser clients
         ws-server (when enable-websocket?
                     (websocket/start-server handler {:port ws-port}))]
     (if pipe-server
       (do
         (log/info "Sidecar server started on port" port)
         (when ws-server
           (log/info "WebSocket server started on port" ws-port))
         (assoc server :pipe-server pipe-server :ws-server ws-server))
       (do
         (log/error "Failed to start sidecar server")
         (when ws-server
           (websocket/stop-server ws-server))
         nil)))))

(defn stop-server
  "Stop the sidecar server."
  [^SidecarServer server]
  (when server
    (reset! (:running? server) false)
    ;; Stop TCP pipe server
    (pipes/stop-server (:pipe-server server))
    ;; Stop WebSocket server if running
    (when-let [ws-server (:ws-server server)]
      (websocket/stop-server ws-server))
    (.clear ^ConcurrentHashMap (:graphs server))
    (reset-global-state!)
    (log/info "Sidecar server stopped")))

(defn running?
  "Check if the server is running.
   Returns true if the server record exists, running? flag is true,
   and the TCP pipe server is running. WebSocket server is optional."
  [^SidecarServer server]
  (and server
       @(:running? server)
       (pipes/server-running? (:pipe-server server))))

(defn websocket-running?
  "Check if the WebSocket server is running."
  [^SidecarServer server]
  (and server
       (:ws-server server)
       (websocket/running? (:ws-server server))))

;; =============================================================================
;; Main Entry Point (for standalone execution)
;; =============================================================================

(defn -main
  "Main entry point for running the sidecar as a standalone process."
  [& args]
  (let [port (if (seq args)
               (Integer/parseInt (first args))
               DEFAULT_PORT)
        server (start-server {:port port})]
    (if server
      (do
        (log/info "Sidecar ready. Press Ctrl+C to stop.")
        ;; Keep the main thread alive
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. #(stop-server server)))
        ;; Block forever
        @(promise))
      (System/exit 1))))
