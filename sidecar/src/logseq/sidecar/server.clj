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
            [datascript.core :as d]
            [logseq.sidecar.pipes :as pipes]
            [logseq.sidecar.protocol :as protocol]
            [logseq.sidecar.websocket :as websocket])
  (:import [java.util.concurrent ConcurrentHashMap])
  (:gen-class))

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
   Returns the graph-id on success, nil on failure."
  [^SidecarServer server graph-id {:keys [schema] :or {schema base-schema}}]
  (let [graphs ^ConcurrentHashMap (:graphs server)]
    (if (.containsKey graphs graph-id)
      (do
        (log/warn "Graph already exists:" graph-id)
        graph-id)
      (let [conn (d/create-conn schema)]
        (.put graphs graph-id {:conn conn :schema schema})
        (log/info "Created graph:" graph-id)
        graph-id))))

(defn remove-graph
  "Remove a graph from the server."
  [^SidecarServer server graph-id]
  (let [graphs ^ConcurrentHashMap (:graphs server)]
    (when (.remove graphs graph-id)
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
;; DataScript Operations
;; =============================================================================

(defn query
  "Execute a Datalog query on a graph."
  [^SidecarServer server graph-id query-form inputs]
  (if-let [conn (get-conn server graph-id)]
    (apply d/q query-form @conn inputs)
    (throw (ex-info "Graph not found" {:graph-id graph-id}))))

(defn transact!
  "Execute a transaction on a graph.
   Returns the transaction report."
  [^SidecarServer server graph-id tx-data]
  (if-let [conn (get-conn server graph-id)]
    (d/transact! conn tx-data)
    (throw (ex-info "Graph not found" {:graph-id graph-id}))))

(defn pull
  "Pull an entity from a graph."
  [^SidecarServer server graph-id selector eid]
  (if-let [conn (get-conn server graph-id)]
    (d/pull @conn selector eid)
    (throw (ex-info "Graph not found" {:graph-id graph-id}))))

(defn pull-many
  "Pull multiple entities from a graph."
  [^SidecarServer server graph-id selector eids]
  (if-let [conn (get-conn server graph-id)]
    (d/pull-many @conn selector eids)
    (throw (ex-info "Graph not found" {:graph-id graph-id}))))

(defn- datom->vec
  "Convert a Datom to a vector [e a v tx added?] for Transit serialization.
   Uses keyword access since Datom implements ILookup."
  [datom]
  [(:e datom) (:a datom) (:v datom) (:tx datom) (:added datom)])

(defn datoms
  "Get datoms from a graph by index.
   Returns vectors instead of Datom objects for Transit serialization."
  [^SidecarServer server graph-id index & components]
  (if-let [conn (get-conn server graph-id)]
    (mapv datom->vec (apply d/datoms @conn index components))
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
                query-inputs (rest inputs)
                result (query server repo query-form query-inputs)]
            (protocol/make-response op (vec result) id))

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
          ;; Database existence check
          ;; ============================================

          :thread-api/db-exists
          ;; Web worker signature: [repo]
          (let [[repo] (:args payload)
                exists (graph-exists? server repo)]
            (protocol/make-response op exists id))

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

          ;; Vec search (Electron-only vector search feature)
          :thread-api/vec-upsert-blocks
          (do
            (log/debug "Stub op called (unsupported):" op)
            (protocol/make-response op nil id))

          :thread-api/vec-delete-blocks
          (do
            (log/debug "Stub op called (unsupported):" op)
            (protocol/make-response op nil id))

          :thread-api/vec-search-blocks
          (do
            (log/debug "Stub op called (unsupported):" op)
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
