(ns frontend.sidecar.websocket-client
  "WebSocket client for browser-based sidecar communication.

   This module provides direct WebSocket communication with the JVM sidecar,
   bypassing Electron IPC. Useful for:
   - Browser-based development/testing
   - Playwright MCP testing workflow
   - Future web app support

   Architecture:
   - Connects to sidecar on ws://localhost:47633
   - Uses same Transit serialization as IPC path
   - Provides same API as client.cljs for drop-in replacement

   Usage:
   ```clojure
   ;; In browser console or test
   (require '[frontend.sidecar.websocket-client :as ws])
   (ws/connect! {:url \"ws://localhost:47633\"})
   (ws/send-request :thread-api/list-db {:args []})
   ```"
  (:require [lambdaisland.glogi :as log]
            [logseq.db :as ldb]
            [promesa.core :as p]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:const DEFAULT_URL "ws://localhost:47633")
(def ^:const DEFAULT_TIMEOUT_MS 30000)
(def ^:const RECONNECT_DELAY_MS 2000)

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private *state
  (atom {:socket nil           ;; WebSocket instance
         :connected? false     ;; Connection status
         :pending {}           ;; Map of request-id -> {:resolve :reject :timeout-id}
         :url nil              ;; Current WebSocket URL
         :reconnecting? false  ;; Whether auto-reconnect is in progress
         :push-handlers []}))  ;; Handlers for push messages

;; =============================================================================
;; Push Message Handling
;; =============================================================================

(defn register-push-handler!
  "Register a handler for push messages from the sidecar.
   Handler receives {:event <keyword> :payload <map>}
   Returns a function to unregister the handler."
  [handler-fn]
  (swap! *state update :push-handlers conj handler-fn)
  (fn []
    (swap! *state update :push-handlers #(remove #{handler-fn} %))))

(defn- dispatch-push-message
  "Dispatch a push message to all registered handlers."
  [msg]
  (doseq [handler (:push-handlers @*state)]
    (try
      (handler msg)
      (catch :default e
        (log/error :push-handler-error {:error e})))))

;; =============================================================================
;; Message Handling
;; =============================================================================

(defn- handle-response
  "Handle a response message from the server."
  [msg]
  (let [request-id (:request-id msg)
        pending (get-in @*state [:pending request-id])]
    (when pending
      (swap! *state update :pending dissoc request-id)
      (when-let [timeout-id (:timeout-id pending)]
        (js/clearTimeout timeout-id))
      (if (:ok? msg)
        ((:resolve pending) (:payload msg))
        ((:reject pending) (ex-info (or (:message msg) "Unknown error")
                                    {:error-type (:error-type msg)}))))))

(defn- on-message
  "Handle an incoming WebSocket message."
  [event]
  (try
    (let [msg-str (.-data event)
          msg (ldb/read-transit-str msg-str)]
      (case (:type msg)
        :response (handle-response msg)
        :push (dispatch-push-message msg)
        :handshake-response (log/info :websocket-handshake-complete {:version (:version msg)})
        (log/warn :websocket-unknown-message-type {:type (:type msg)})))
    (catch :default e
      (log/error :websocket-message-parse-error {:error e}))))

(defn- on-open
  "Handle WebSocket connection open."
  [_event resolve]
  (swap! *state assoc :connected? true :reconnecting? false)
  (log/info :websocket-connected {:url (:url @*state)})
  ;; Send handshake
  (let [socket (:socket @*state)
        handshake {:type :handshake
                   :version "1.0.0"}]
    (.send socket (ldb/write-transit-str handshake)))
  (resolve {:connected true :url (:url @*state)}))

(defn- on-close
  "Handle WebSocket connection close."
  [event]
  (let [code (.-code event)
        reason (.-reason event)]
    (swap! *state assoc :socket nil :connected? false)
    (log/info :websocket-closed {:code code :reason reason})
    ;; Reject all pending requests
    (doseq [[_ pending] (:pending @*state)]
      (when-let [timeout-id (:timeout-id pending)]
        (js/clearTimeout timeout-id))
      ((:reject pending) (ex-info "Connection closed" {:type :connection-closed
                                                       :code code})))
    (swap! *state assoc :pending {})))

(defn- on-error
  "Handle WebSocket error."
  [event reject]
  (let [error (.-error event)]
    (log/error :websocket-error {:error error})
    (when-not (:connected? @*state)
      (reject (ex-info "WebSocket connection failed" {:type :connection-failed})))))

;; =============================================================================
;; Connection Management
;; =============================================================================

(defn connected?
  "Check if the WebSocket is connected."
  []
  (:connected? @*state))

(defn connect!
  "Connect to the sidecar via WebSocket.

   Options:
   - :url - WebSocket URL (default: ws://localhost:47633)

   Returns a promise that resolves when connected."
  ([] (connect! {}))
  ([{:keys [url] :or {url DEFAULT_URL}}]
   (if (:connected? @*state)
     (p/resolved {:already-connected true :url (:url @*state)})
     (p/create
      (fn [resolve reject]
        (try
          (let [socket (js/WebSocket. url)]
            (swap! *state assoc :socket socket :url url)

            (set! (.-onopen socket) #(on-open % resolve))
            (set! (.-onmessage socket) on-message)
            (set! (.-onclose socket) on-close)
            (set! (.-onerror socket) #(on-error % reject)))
          (catch :default e
            (log/error :websocket-create-error {:error e})
            (reject e))))))))

(defn disconnect!
  "Disconnect from the sidecar."
  []
  (when-let [socket (:socket @*state)]
    (log/info :websocket-disconnecting {})
    (.close socket))
  (swap! *state assoc
         :socket nil
         :connected? false
         :pending {}
         :reconnecting? false))

;; =============================================================================
;; Request/Response API
;; =============================================================================

(defn send-request
  "Send a request to the sidecar and return a promise for the response.

   op - The operation keyword (e.g., :thread-api/q)
   payload - The request payload
   opts - {:timeout-ms - Request timeout (default: 30000)}

   Returns a promise that resolves with the response payload (as Transit string)."
  ([op payload] (send-request op payload {}))
  ([op payload {:keys [timeout-ms] :or {timeout-ms DEFAULT_TIMEOUT_MS}}]
   (if-not (:connected? @*state)
     (p/rejected (ex-info "Not connected to sidecar" {:type :not-connected}))
     (p/create
      (fn [resolve reject]
        (let [request-id (str (random-uuid))
              request {:id request-id
                       :type :request
                       :op op
                       :payload payload
                       :timestamp (js/Date.now)}
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
          (try
            (.send (:socket @*state) (ldb/write-transit-str request))
            (catch :default e
              (swap! *state update :pending dissoc request-id)
              (js/clearTimeout timeout-id)
              (reject e)))))))))

;; =============================================================================
;; Worker Function API
;; =============================================================================

(defn create-worker-fn
  "Create a worker function compatible with state/*db-worker.

   This returns a function with the same signature as the Comlink-wrapped
   web worker or the IPC sidecar client, allowing it to be used as a
   drop-in replacement.

   The function:
   - Sends requests via WebSocket to the sidecar
   - Receives responses as CLJS data (sidecar serializes, we deserialize)
   - Returns Transit string if direct-pass? is true

   Usage:
   (reset! state/*db-worker (websocket-client/create-worker-fn))"
  []
  (fn [qkw direct-pass? & args]
    (p/let [result (send-request qkw
                                 (if direct-pass?
                                   {:args (vec args) :direct-pass? true}
                                   {:args (vec args)}))]
      ;; Response payload is already CLJS data (deserialized from Transit)
      ;; For direct-pass?, serialize it back to Transit string
      (if direct-pass?
        (ldb/write-transit-str result)
        result))))

;; =============================================================================
;; Status API
;; =============================================================================

(defn status
  "Get current WebSocket client status."
  []
  {:connected? (:connected? @*state)
   :url (:url @*state)
   :pending-count (count (:pending @*state))
   :push-handlers-count (count (:push-handlers @*state))})
