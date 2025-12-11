(ns logseq.sidecar.pipes
  "Cross-platform IPC using TCP sockets (portable) with Named Pipes support for Windows.

   For Phase 2, we use TCP sockets as a portable foundation that works on all platforms.
   Windows Named Pipes can be added as an optimization later.

   Architecture:
   - Server listens on localhost TCP port (or Named Pipe on Windows)
   - Client connects and sends Transit-encoded requests
   - Server responds with Transit-encoded responses
   - Server can push messages to connected clients"
  (:require [clojure.core.async :as async :refer [chan go go-loop <! >! >!! <!! close! alts! timeout]]
            [clojure.tools.logging :as log]
            [logseq.sidecar.protocol :as protocol])
  (:import [java.net ServerSocket Socket InetAddress SocketTimeoutException]
           [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.util.concurrent ConcurrentHashMap Executors TimeUnit]
           [java.util UUID]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:const DEFAULT_PORT 47632)
(def ^:const DEFAULT_TIMEOUT_MS 30000)
(def ^:const SOCKET_READ_TIMEOUT_MS 200)  ; Short timeout to allow checking connected? flag
(def ^:const BUFFER_SIZE 65536)

;; =============================================================================
;; Pipe Name Generation
;; =============================================================================

(defn- normalize-name
  "Normalize graph name for use in pipe/socket identifier."
  [name]
  (-> name
      (clojure.string/replace #"[^a-zA-Z0-9-]" "-")
      (clojure.string/replace #"-+" "-")
      (clojure.string/lower-case)))

(defn make-pipe-name
  "Generate a pipe/socket name for a graph.

   On Windows: Returns Named Pipe path (like //./pipe/logsidian-sidecar-{graph})
   On Unix: Returns Unix socket path (/tmp/logsidian-sidecar-{graph}.sock)
   Fallback: Returns TCP port identifier"
  [graph-name]
  (let [normalized (normalize-name graph-name)
        os-name (System/getProperty "os.name")]
    (cond
      (clojure.string/includes? (clojure.string/lower-case os-name) "windows")
      ;; Windows Named Pipe path - using forward slashes for safety
      (str "//./pipe/logsidian-sidecar-" normalized)

      :else
      (str "/tmp/logsidian-sidecar-" normalized ".sock"))))

;; =============================================================================
;; Server State
;; =============================================================================

(defrecord Server [server-socket
                   handler-fn
                   running?
                   clients
                   executor
                   accept-thread])

(defrecord Client [socket
                   reader
                   writer
                   connected?
                   pending-requests
                   push-handler
                   read-thread])

;; =============================================================================
;; Message Framing
;; =============================================================================

;; Simple length-prefixed framing:
;; [4 bytes: message length][message bytes]

(defn- write-message
  "Write a Transit message with length prefix.
   Uses explicit \\n (not .newLine) for cross-platform compatibility."
  [^BufferedWriter writer ^String msg]
  (let [bytes (.getBytes msg "UTF-8")
        len (count bytes)]
    (.write writer (str len))
    (.write writer "\n")  ; Explicit \n, not .newLine (which is \r\n on Windows)
    (.write writer msg)
    (.write writer "\n")  ; Explicit \n for cross-platform compatibility
    (.flush writer)))

(defn- read-message
  "Read a length-prefixed Transit message."
  [^BufferedReader reader]
  (when-let [len-str (.readLine reader)]
    (try
      (let [_len (Integer/parseInt len-str)
            msg (.readLine reader)]
        msg)
      (catch NumberFormatException _
        ;; Legacy: treat as raw message if no length prefix
        len-str))))

;; =============================================================================
;; Server Implementation
;; =============================================================================

(defn- handle-client-connection
  "Handle a single client connection in a dedicated thread."
  [^Server server ^Socket client-socket]
  (let [client-id (str (UUID/randomUUID))
        reader (BufferedReader. (InputStreamReader. (.getInputStream client-socket) "UTF-8"))
        writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream client-socket) "UTF-8"))]
    (log/info "Client connected:" client-id)
    (.put ^ConcurrentHashMap (:clients server) client-id {:socket client-socket
                                                          :writer writer})
    (try
      (loop []
        (when (and @(:running? server)
                   (not (.isClosed client-socket)))
          (when-let [msg-str (read-message reader)]
            (try
              (let [msg (protocol/deserialize msg-str)
                    response ((:handler-fn server) msg)
                    response-str (protocol/serialize response)]
                (write-message writer response-str))
              (catch Exception e
                (log/error e "Error handling message")
                (let [error-response (protocol/make-error-response
                                      :internal-error
                                      (.getMessage e))
                      error-str (protocol/serialize error-response)]
                  (write-message writer error-str))))
            (recur))))
      (catch Exception e
        (when-not (or (.isClosed client-socket)
                      (instance? java.net.SocketException e))
          (log/error e "Client connection error:" client-id)))
      (finally
        (.remove ^ConcurrentHashMap (:clients server) client-id)
        (try (.close reader) (catch Exception _))
        (try (.close writer) (catch Exception _))
        (try (.close client-socket) (catch Exception _))
        (log/info "Client disconnected:" client-id)))))

(defn start-server
  "Start a server listening for connections.

   handler-fn - Function that takes a request message and returns a response message
   Returns a Server record"
  ([pipe-name handler-fn]
   (start-server pipe-name handler-fn {}))
  ([pipe-name handler-fn {:keys [port] :or {port DEFAULT_PORT}}]
   (try
     (let [server-socket (ServerSocket. port 50 (InetAddress/getByName "127.0.0.1"))
           running? (atom true)
           clients (ConcurrentHashMap.)
           executor (Executors/newCachedThreadPool)
           server (->Server server-socket handler-fn running? clients executor nil)
           accept-thread (Thread.
                          (fn []
                            (log/info "Server started on port" port "for" pipe-name)
                            (while @running?
                              (try
                                (let [client-socket (.accept server-socket)]
                                  (.execute executor
                                            #(handle-client-connection server client-socket)))
                                (catch java.net.SocketException _
                                  ;; Server socket closed, exit loop
                                  nil)
                                (catch Exception e
                                  (when @running?
                                    (log/error e "Accept error")))))))]
       (.start accept-thread)
       (assoc server :accept-thread accept-thread))
     (catch Exception e
       (log/error e "Failed to start server on port" port)
       nil))))

(defn stop-server
  "Stop a running server."
  [^Server server]
  (when server
    (reset! (:running? server) false)
    ;; Close all client connections
    (doseq [[_ client-info] (:clients server)]
      (try
        (.close ^Socket (:socket client-info))
        (catch Exception _)))
    ;; Close server socket
    (try
      (.close ^ServerSocket (:server-socket server))
      (catch Exception _))
    ;; Shutdown executor
    (try
      (.shutdown ^java.util.concurrent.ExecutorService (:executor server))
      (.awaitTermination ^java.util.concurrent.ExecutorService (:executor server) 5 TimeUnit/SECONDS)
      (catch Exception _))
    (log/info "Server stopped")))

(defn server-running?
  "Check if server is running."
  [^Server server]
  (and server
       @(:running? server)
       (not (.isClosed ^ServerSocket (:server-socket server)))))

(defn broadcast-push
  "Send a push message to all connected clients."
  [^Server server push-msg]
  (let [msg-str (protocol/serialize push-msg)]
    (doseq [[client-id client-info] (:clients server)]
      (try
        (write-message ^BufferedWriter (:writer client-info) msg-str)
        (catch Exception e
          (log/warn "Failed to push to client" client-id ":" (.getMessage e)))))))

;; =============================================================================
;; Client Implementation
;; =============================================================================

(defn connect-client
  "Connect to a running server.

   pipe-name - The pipe/socket name (or ignored for TCP)
   opts - {:port, :timeout-ms, :on-push (fn [push-msg])}"
  ([pipe-name]
   (connect-client pipe-name {}))
  ([pipe-name {:keys [port timeout-ms on-push]
               :or {port DEFAULT_PORT timeout-ms DEFAULT_TIMEOUT_MS}}]
   (try
     (let [socket (Socket.)
           _ (.connect socket
                       (java.net.InetSocketAddress. "127.0.0.1" port)
                       timeout-ms)
           ;; Set read timeout so readLine() doesn't block forever
           ;; This allows the read thread to periodically check connected? flag
           _ (.setSoTimeout socket SOCKET_READ_TIMEOUT_MS)
           reader (BufferedReader. (InputStreamReader. (.getInputStream socket) "UTF-8"))
           writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream socket) "UTF-8"))
           connected? (atom true)
           pending-requests (ConcurrentHashMap.)
           client (->Client socket reader writer connected? pending-requests on-push nil)
           ;; Background thread to read responses
           read-thread (Thread.
                        (fn []
                          (try
                            (loop []
                              (when (and @connected?
                                         (not (.isClosed socket)))
                                (try
                                  (when-let [msg-str (read-message reader)]
                                    (try
                                      (let [msg (protocol/deserialize msg-str)]
                                        (if (= :push (:type msg))
                                          ;; Push message - call handler
                                          (when on-push
                                            (on-push msg))
                                          ;; Response - deliver to waiting request
                                          (when-let [response-chan (.remove pending-requests
                                                                            (:request-id msg))]
                                            (>!! response-chan msg))))
                                      (catch Exception e
                                        (log/error e "Error processing response"))))
                                  (catch SocketTimeoutException _
                                    ;; Timeout is expected - just loop back and check connected? flag
                                    nil))
                                (recur)))
                            (catch Exception e
                              (when-not (.isClosed socket)
                                (log/error e "Client read error")))
                            (finally
                              (reset! connected? false)))))]
       (.start read-thread)
       (assoc client :read-thread read-thread))
     (catch java.net.SocketTimeoutException _
       (log/warn "Connection timed out")
       nil)
     (catch Exception e
       (log/error e "Failed to connect to server")
       nil))))

(defn disconnect-client
  "Disconnect from server."
  [^Client client]
  (when client
    (reset! (:connected? client) false)
    ;; Fail all pending requests
    (doseq [[_ ch] (:pending-requests client)]
      (close! ch))
    (.clear ^ConcurrentHashMap (:pending-requests client))
    ;; Close socket
    (try (.close ^BufferedReader (:reader client)) (catch Exception _))
    (try (.close ^BufferedWriter (:writer client)) (catch Exception _))
    (try (.close ^Socket (:socket client)) (catch Exception _))))

(defn client-connected?
  "Check if client is connected."
  [^Client client]
  (and client
       @(:connected? client)
       (not (.isClosed ^Socket (:socket client)))))

(defn send-request
  "Send a request and return a channel that will receive the response.
   The returned channel will contain the response or nil on timeout."
  ([^Client client request]
   (send-request client request {}))
  ([^Client client request {:keys [timeout-ms] :or {timeout-ms DEFAULT_TIMEOUT_MS}}]
   (let [result-chan (chan 1)
         request-id (:id request)]
     (if (client-connected? client)
       (do
         (.put ^ConcurrentHashMap (:pending-requests client) request-id result-chan)
         (try
           (let [msg-str (protocol/serialize request)]
             (write-message ^BufferedWriter (:writer client) msg-str))
           (catch Exception e
             (log/error e "Failed to send request")
             (.remove ^ConcurrentHashMap (:pending-requests client) request-id)
             (>!! result-chan nil)
             (close! result-chan)))
         ;; Start timeout watcher
         (go
           (<! (timeout timeout-ms))
           ;; If still pending after timeout, remove and signal timeout
           (when (.remove ^ConcurrentHashMap (:pending-requests client) request-id)
             (log/warn "Request timed out, id:" request-id)
             (>! result-chan nil)))
         result-chan)
       (do
         (>!! result-chan nil)
         (close! result-chan)
         result-chan)))))
