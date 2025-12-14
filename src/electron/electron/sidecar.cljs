(ns electron.sidecar
  "JVM sidecar management in Electron main process.

   This module handles all Node.js operations for the sidecar:
   - TCP socket connection to JVM sidecar server
   - JVM process spawning and lifecycle management
   - Request/response routing with timeouts
   - Push message forwarding to renderer

   Architecture:
   - Renderer calls IPC handlers (in electron.handler)
   - Handlers delegate to this module
   - This module uses Node.js APIs (net, child_process, fs, path)
   - Push messages forwarded to renderer via send-to-renderer"
  (:require ["child_process" :as child-process]
            ["fs" :as fs]
            ["net" :as net]
            ["os" :as os]
            ["path" :as node-path]
            [cljs-bean.core :as bean]
            [electron.logger :as logger]
            [electron.utils :as utils]
            [electron.window :as window]
            [logseq.db :as ldb]
            [promesa.core :as p]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:const DEFAULT_PORT 47632)
(def ^:const STARTUP_TIMEOUT_MS 15000)
(def ^:const REQUEST_TIMEOUT_MS 30000)
(def ^:const RECONNECT_DELAY_MS 1000)
(def ^:const PROTOCOL_VERSION "1.0.0")

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private *state
  (atom {:process nil          ; Node.js ChildProcess
         :socket nil           ; Node.js net.Socket
         :connected? false     ; TCP connection status
         :running? false       ; JVM process running status
         :pending {}           ; Map of request-id -> {:resolve :reject :timeout-id}
         :buffer ""            ; Accumulated data buffer for message parsing
         :port DEFAULT_PORT    ; Current port
         :pid nil}))           ; JVM process ID

;; =============================================================================
;; Message Framing
;; =============================================================================

(defn- write-message
  "Write a length-prefixed message to the socket.
   Format: <byte-length>\\n<message>\\n"
  [socket msg-str]
  (let [bytes (js/Buffer.from msg-str "utf8")
        len-line (str (.-length bytes) "\n")
        msg-line (str msg-str "\n")]
    (.write socket len-line)
    (.write socket msg-line)))

(defn- try-parse-message
  "Try to parse a complete message from the buffer.
   Returns [message remaining-buffer] or nil if incomplete."
  [buffer]
  (let [newline-idx (.indexOf buffer "\n")]
    (when (>= newline-idx 0)
      (let [len-str (subs buffer 0 newline-idx)
            len (js/parseInt len-str 10)]
        (when-not (js/isNaN len)
          (let [msg-start (inc newline-idx)
                msg-end (+ msg-start len)
                buffer-len (count buffer)]
            (when (> buffer-len msg-end)
              (let [msg (subs buffer msg-start msg-end)
                    remaining (subs buffer (inc msg-end))]
                [msg remaining]))))))))

;; =============================================================================
;; Protocol Messages
;; =============================================================================

(defn- make-request
  "Create a request message."
  [op payload]
  {:id (str (random-uuid))
   :type :request
   :op op
   :payload payload
   :timestamp (js/Date.now)})

(defn- make-handshake
  "Create a handshake message for version negotiation."
  []
  {:type :handshake
   :version PROTOCOL_VERSION})

;; =============================================================================
;; Response Handling
;; =============================================================================

(defn- handle-response
  "Handle a response message from the server.
   Returns the payload as a Transit string for IPC compatibility.
   CLJS data cannot be passed over Electron IPC (structured cloning fails),
   so we re-serialize the payload to Transit for the renderer to deserialize."
  [msg]
  (let [request-id (:request-id msg)
        pending (get-in @*state [:pending request-id])]
    (when pending
      (swap! *state update :pending dissoc request-id)
      (js/clearTimeout (:timeout-id pending))
      (if (:ok? msg)
        ;; Re-serialize payload to Transit string for IPC compatibility
        ;; The renderer will deserialize this using ldb/read-transit-str
        ((:resolve pending) (ldb/write-transit-str (:payload msg)))
        ((:reject pending) (ex-info (:message msg "Unknown error")
                                    {:error-type (:error-type msg)}))))))

(defn- handle-push
  "Handle a push message from the server.
   Forwards to all renderer windows via IPC."
  [msg]
  (let [data (bean/->js {:event (:event msg)
                         :payload (:payload msg)})]
    (doseq [^js win (window/get-all-windows)]
      (when-not (.isDestroyed win)
        (.. win -webContents (send "sidecar-push" data))))))

(defn- handle-message
  "Handle a received message from the server."
  [msg-str]
  (try
    (let [msg (ldb/read-transit-str msg-str)
          msg-type (:type msg)]
      (case msg-type
        :response (handle-response msg)
        :push (handle-push msg)
        :handshake-response (logger/debug "Sidecar handshake complete" {:version (:version msg)})
        (logger/warn "Unknown sidecar message type" {:type msg-type})))
    (catch :default e
      (logger/error "Failed to parse sidecar message" {:error e
                                                        :raw-preview (subs msg-str 0 (min 1000 (count msg-str)))}))))

(defn- process-buffer
  "Process accumulated buffer, extracting complete messages."
  []
  (loop []
    (let [buffer (:buffer @*state)]
      (when-let [[msg remaining] (try-parse-message buffer)]
        (swap! *state assoc :buffer remaining)
        (handle-message msg)
        (recur)))))

;; =============================================================================
;; JAR and Java Location
;; =============================================================================

(defn- get-resources-path
  "Get the resources path (works in both dev and production)."
  []
  (if (exists? js/process.resourcesPath)
    js/process.resourcesPath
    (.resolve node-path ".")))

(defn- find-sidecar-jar
  "Find the sidecar JAR file.
   Search order:
   1. {resourcesPath}/logsidian-sidecar.jar (production - extraResource)
   2. {resourcesPath}/sidecar/logsidian-sidecar.jar
   3. ./sidecar/target/logsidian-sidecar.jar (development)"
  []
  (let [resources-path (get-resources-path)
        candidates [(.join node-path resources-path "logsidian-sidecar.jar")
                    (.join node-path resources-path "sidecar" "logsidian-sidecar.jar")
                    (.join node-path "." "sidecar" "target" "logsidian-sidecar.jar")]]
    (logger/debug "Searching for sidecar JAR" {:candidates candidates})
    (some (fn [jar-path]
            (when (.existsSync fs jar-path)
              (logger/info "Found sidecar JAR" {:path jar-path})
              jar-path))
          candidates)))

(defn- find-bundled-java
  "Find the bundled JRE.
   Search order:
   1. {resourcesPath}/jre/bin/java[.exe]
   2. {resourcesPath}/jre-win32-x64/bin/java[.exe] (platform-specific)"
  []
  (let [resources-path (get-resources-path)
        platform (.-platform js/process)
        arch (.-arch js/process)
        java-exe (if (= platform "win32") "java.exe" "java")
        candidates [(.join node-path resources-path "jre" "bin" java-exe)
                    (.join node-path resources-path (str "jre-" platform "-" arch) "bin" java-exe)]]
    (logger/debug "Searching for bundled JRE" {:candidates candidates})
    (some (fn [java-path]
            (when (.existsSync fs java-path)
              (logger/info "Found bundled JRE" {:path java-path})
              java-path))
          candidates)))

(defn- find-java
  "Find the Java executable.
   Search order:
   1. Bundled JRE in app resources (preferred)
   2. JAVA_HOME environment variable
   3. System PATH (just 'java')"
  []
  (or (find-bundled-java)
      (let [java-home (.-JAVA_HOME js/process.env)
            java-exe (if (= (.-platform js/process) "win32") "java.exe" "java")]
        (if java-home
          (let [java-path (.join node-path java-home "bin" java-exe)]
            (if (.existsSync fs java-path)
              (do (logger/info "Found Java via JAVA_HOME" {:path java-path})
                  java-path)
              (do (logger/debug "JAVA_HOME set but java not found" {:path java-path})
                  java-exe)))
          java-exe))))

;; =============================================================================
;; TCP Socket Connection
;; =============================================================================

(defn connected?
  "Check if the TCP socket is connected."
  []
  (:connected? @*state))

(defn disconnect!
  "Disconnect the TCP socket."
  []
  (when-let [socket (:socket @*state)]
    (logger/info "Disconnecting sidecar socket")
    (.destroy socket))
  (swap! *state assoc
         :socket nil
         :connected? false
         :buffer "")
  ;; Reject all pending requests
  (doseq [[_ pending] (:pending @*state)]
    (js/clearTimeout (:timeout-id pending))
    ((:reject pending) (ex-info "Connection closed" {:type :connection-closed})))
  (swap! *state assoc :pending {}))

(defn connect!
  "Connect to the sidecar server via TCP.
   Returns a promise that resolves when connected."
  ([] (connect! {}))
  ([{:keys [port timeout-ms]
     :or {port DEFAULT_PORT
          timeout-ms REQUEST_TIMEOUT_MS}}]
   (p/create
    (fn [resolve reject]
      (if (:connected? @*state)
        (resolve {:already-connected true :port port})
        (let [socket (net/Socket.)
              timeout-id (js/setTimeout
                          #(do
                             (.destroy socket)
                             (reject (ex-info "Connection timeout" {:type :timeout :port port})))
                          timeout-ms)]

          ;; Set up socket event handlers
          (.on socket "connect"
               (fn []
                 (js/clearTimeout timeout-id)
                 (swap! *state assoc
                        :socket socket
                        :connected? true
                        :port port)
                 (logger/info "Connected to sidecar" {:port port})
                 ;; Send handshake
                 (write-message socket (ldb/write-transit-str (make-handshake)))
                 (resolve {:connected true :port port})))

          (.on socket "data"
               (fn [data]
                 (let [data-str (.toString data "utf8")]
                   (swap! *state update :buffer str data-str)
                   (process-buffer))))

          (.on socket "error"
               (fn [err]
                 (js/clearTimeout timeout-id)
                 (logger/error "Sidecar socket error" {:error err})
                 (reject err)))

          (.on socket "close"
               (fn [had-error]
                 (swap! *state assoc :connected? false :socket nil)
                 (when had-error
                   (logger/warn "Sidecar socket closed with error"))))

          ;; Connect
          (logger/debug "Connecting to sidecar" {:port port})
          (.connect socket port "127.0.0.1")))))))

;; =============================================================================
;; Request/Response API
;; =============================================================================

(defn send-request
  "Send a request to the sidecar and return a promise for the response.
   op - The operation keyword (e.g., :thread-api/q)
   payload - The request payload
   opts - {:timeout-ms - Request timeout (default: 30000)}"
  ([op payload] (send-request op payload {}))
  ([op payload {:keys [timeout-ms] :or {timeout-ms REQUEST_TIMEOUT_MS}}]
   (if-not (:connected? @*state)
     (p/rejected (ex-info "Not connected to sidecar" {:type :not-connected}))
     (p/create
      (fn [resolve reject]
        (let [request (make-request op payload)
              request-id (:id request)
              timeout-id (js/setTimeout
                          #(do
                             (swap! *state update :pending dissoc request-id)
                             (reject (ex-info "Request timeout" {:type :timeout :op op})))
                          timeout-ms)]
          ;; Register pending request
          (swap! *state assoc-in [:pending request-id]
                 {:resolve resolve
                  :reject reject
                  :timeout-id timeout-id})
          ;; Send request
          (write-message (:socket @*state) (ldb/write-transit-str request))))))))

;; =============================================================================
;; JVM Process Management
;; =============================================================================

(defn running?
  "Check if the JVM sidecar process is running."
  []
  (:running? @*state))

(defn- wait-for-ready
  "Wait for the sidecar to become ready by attempting TCP connection."
  [port timeout-ms]
  (let [start-time (js/Date.now)]
    (p/create
     (fn [resolve reject]
       (letfn [(try-connect []
                 (if (> (- (js/Date.now) start-time) timeout-ms)
                   (reject (ex-info "Sidecar startup timeout" {:type :timeout :port port}))
                   (let [test-socket (net/Socket.)]
                     (.setTimeout test-socket 1000)
                     (.once test-socket "connect"
                            (fn []
                              (.destroy test-socket)
                              (resolve true)))
                     (.once test-socket "error"
                            (fn [_]
                              (.destroy test-socket)
                              (js/setTimeout try-connect 200)))
                     (.connect test-socket port "127.0.0.1"))))]
         (try-connect))))))

(defn stop!
  "Stop the JVM sidecar process."
  []
  (logger/info "Stopping sidecar")
  ;; Disconnect socket first
  (disconnect!)
  ;; Kill process
  (when-let [proc (:process @*state)]
    (when-not (.-killed proc)
      (.kill proc)))
  (swap! *state assoc
         :process nil
         :running? false
         :pid nil)
  {:stopped true})

(defn start!
  "Start the JVM sidecar process.
   Returns a promise that resolves when the sidecar is ready."
  ([] (start! {}))
  ([{:keys [port]
     :or {port DEFAULT_PORT}}]
   (if (:running? @*state)
     (p/resolved {:already-running true :port (:port @*state) :pid (:pid @*state)})
     (let [java-path (find-java)
           jar-path (find-sidecar-jar)]
       (cond
         (nil? jar-path)
         (p/rejected (ex-info "Sidecar JAR not found" {:type :jar-not-found}))

         :else
         (p/create
          (fn [resolve reject]
            (logger/info "Starting sidecar" {:port port :java java-path :jar jar-path})

            (let [args #js ["-jar" jar-path (str port)]
                  opts #js {:stdio #js ["ignore" "pipe" "pipe"]}
                  proc (.spawn child-process java-path args opts)]

              (swap! *state assoc
                     :process proc
                     :pid (.-pid proc)
                     :port port)

              ;; Capture stdout
              (.on (.-stdout proc) "data"
                   (fn [data]
                     (logger/debug "Sidecar stdout" {:data (.toString data "utf8")})))

              ;; Capture stderr
              (.on (.-stderr proc) "data"
                   (fn [data]
                     (logger/debug "Sidecar stderr" {:data (.toString data "utf8")})))

              ;; Handle process exit
              (.on proc "exit"
                   (fn [code signal]
                     (logger/warn "Sidecar process exited" {:code code :signal signal})
                     (swap! *state assoc :running? false :process nil :pid nil)
                     (disconnect!)))

              ;; Handle spawn errors
              (.on proc "error"
                   (fn [err]
                     (logger/error "Sidecar spawn error" {:error err})
                     (swap! *state assoc :running? false)
                     (reject err)))

              ;; Wait for ready then connect
              (-> (wait-for-ready port STARTUP_TIMEOUT_MS)
                  (p/then (fn [_]
                            (swap! *state assoc :running? true)
                            (logger/info "Sidecar process ready" {:port port :pid (.-pid proc)})
                            ;; Auto-connect after spawn
                            (-> (connect! {:port port})
                                (p/then (fn [conn-result]
                                          (resolve {:started true
                                                    :port port
                                                    :pid (.-pid proc)
                                                    :connected (:connected conn-result)})))
                                (p/catch (fn [conn-err]
                                           ;; Started but failed to connect
                                           (resolve {:started true
                                                     :port port
                                                     :pid (.-pid proc)
                                                     :connected false
                                                     :connect-error (str conn-err)}))))))
                  (p/catch (fn [err]
                             (logger/error "Sidecar startup failed" {:error err})
                             ;; Kill the process if startup timed out
                             (when-not (.-killed proc)
                               (.kill proc))
                             (swap! *state assoc :running? false :process nil :pid nil)
                             (reject err))))))))))))

;; =============================================================================
;; Status API
;; =============================================================================

(defn status
  "Get current sidecar status."
  []
  {:connected? (:connected? @*state)
   :running? (:running? @*state)
   :port (:port @*state)
   :pid (:pid @*state)})

;; =============================================================================
;; Shutdown Handler
;; =============================================================================

(defn register-shutdown-handler!
  "Register handler to stop sidecar when app quits.
   Called during electron initialization."
  []
  (when-let [app (try (js/require "electron") (catch :default _ nil))]
    (when-let [app-obj (.-app app)]
      (.on app-obj "before-quit"
           (fn []
             (logger/info "App quitting, stopping sidecar")
             (stop!))))))
