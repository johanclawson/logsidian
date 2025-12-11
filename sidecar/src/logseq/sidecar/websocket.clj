(ns logseq.sidecar.websocket
  "WebSocket server for browser clients.

   Provides WebSocket endpoint for browser-based sidecar communication.
   Uses http-kit for lightweight WebSocket support.

   Architecture:
   - Server listens on port 47633 (separate from TCP 47632)
   - Each WebSocket connection handled independently
   - Messages are Transit-encoded JSON (same as TCP)
   - No length-prefix needed (WebSocket frames handle message boundaries)

   CORS:
   - Allows localhost origins for development
   - Configurable for production lockdown"
  (:require [clojure.tools.logging :as log]
            [logseq.sidecar.protocol :as protocol]
            [org.httpkit.server :as http-kit]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:const DEFAULT_PORT 47633)
(def ^:const MAX_MESSAGE_SIZE (* 10 1024 1024)) ;; 10MB max message

;; Default allowed origins for development
(def ^:private default-allowed-origins
  #{"http://localhost:3001"
    "http://localhost:3002"
    "http://127.0.0.1:3001"
    "http://127.0.0.1:3002"
    "null"}) ;; file:// protocol sends "null" origin

;; =============================================================================
;; State
;; =============================================================================

(defrecord WebSocketServer [stop-fn      ;; Function to stop the server
                            running?     ;; Atom tracking running state
                            connections  ;; Atom tracking active connections
                            handler])    ;; Request handler function

;; =============================================================================
;; CORS Handling
;; =============================================================================

(defn- origin-allowed?
  "Check if the given origin is allowed."
  [origin allowed-origins]
  (or (nil? origin) ;; No origin header (non-browser client)
      (contains? allowed-origins origin)
      (some #(clojure.string/starts-with? origin %) ["http://localhost:" "http://127.0.0.1:"])))

;; =============================================================================
;; WebSocket Handler
;; =============================================================================

(defn- handle-websocket-message
  "Handle an incoming WebSocket message.
   Deserializes Transit, calls handler, serializes response."
  [handler channel msg-str]
  (try
    (let [request (protocol/deserialize msg-str)
          response (handler request)
          response-str (protocol/serialize response)]
      (http-kit/send! channel response-str))
    (catch Exception e
      (log/error e "Error handling WebSocket message")
      (let [error-response (protocol/make-error-response
                            :internal-error
                            (str "Error processing message: " (.getMessage e)))
            error-str (protocol/serialize error-response)]
        (http-kit/send! channel error-str)))))

(defn- websocket-handler
  "Create a WebSocket handler that routes messages to the request handler."
  [handler connections allowed-origins]
  (fn [req]
    (let [origin (get-in req [:headers "origin"])]
      (if-not (origin-allowed? origin allowed-origins)
        (do
          (log/warn "WebSocket connection rejected - origin not allowed" {:origin origin})
          {:status 403 :body "Origin not allowed"})
        (http-kit/with-channel req channel
          ;; Track connection
          (swap! connections conj channel)
          (log/debug "WebSocket connected" {:origin origin :connections (count @connections)})

          ;; Handle close
          (http-kit/on-close channel
            (fn [status]
              (swap! connections disj channel)
              (log/debug "WebSocket closed" {:status status :connections (count @connections)})))

          ;; Handle messages
          (http-kit/on-receive channel
            (fn [msg]
              (if (> (count msg) MAX_MESSAGE_SIZE)
                (let [error-response (protocol/make-error-response
                                      :message-too-large
                                      (str "Message exceeds max size of " MAX_MESSAGE_SIZE " bytes"))
                      error-str (protocol/serialize error-response)]
                  (http-kit/send! channel error-str))
                (handle-websocket-message handler channel msg)))))))))

;; =============================================================================
;; Server Lifecycle
;; =============================================================================

(defn start-server
  "Start the WebSocket server.

   handler - Function that receives request map and returns response map
   opts - {:port - Port to listen on (default: 47633)
           :allowed-origins - Set of allowed origins for CORS}

   Returns a WebSocketServer record."
  [handler {:keys [port allowed-origins]
            :or {port DEFAULT_PORT
                 allowed-origins default-allowed-origins}}]
  (let [running? (atom true)
        connections (atom #{})
        ws-handler (websocket-handler handler connections allowed-origins)
        stop-fn (http-kit/run-server ws-handler {:port port
                                                  :max-body (* 10 1024 1024)})]
    (if stop-fn
      (do
        (log/info "WebSocket server started" {:port port})
        (->WebSocketServer stop-fn running? connections handler))
      (do
        (log/error "Failed to start WebSocket server on port" port)
        nil))))

(defn stop-server
  "Stop the WebSocket server."
  [^WebSocketServer server]
  (when server
    (reset! (:running? server) false)
    ;; Close all active connections
    (doseq [channel @(:connections server)]
      (http-kit/close channel))
    (reset! (:connections server) #{})
    ;; Stop the server
    (when-let [stop-fn (:stop-fn server)]
      (stop-fn :timeout 1000))
    (log/info "WebSocket server stopped")))

(defn running?
  "Check if the WebSocket server is running."
  [^WebSocketServer server]
  (and server
       @(:running? server)))

(defn connection-count
  "Get the number of active WebSocket connections."
  [^WebSocketServer server]
  (if server
    (count @(:connections server))
    0))

;; =============================================================================
;; Push Messages (Server -> Client)
;; =============================================================================

(defn broadcast!
  "Send a message to all connected WebSocket clients."
  [^WebSocketServer server message]
  (when server
    (let [msg-str (protocol/serialize message)]
      (doseq [channel @(:connections server)]
        (try
          (http-kit/send! channel msg-str)
          (catch Exception e
            (log/warn "Failed to send to WebSocket client" {:error (.getMessage e)})))))))

(defn send-push!
  "Send a push message to all connected WebSocket clients."
  [^WebSocketServer server event payload]
  (broadcast! server (protocol/make-push-message event payload)))
