(ns logseq.sidecar.protocol
  "Protocol layer for JVM sidecar <-> ClojureScript client communication.

   Uses Transit JSON for serialization, matching Logseq's existing worker protocol.
   Supports:
   - Request/Response pattern (client -> server -> client)
   - Push messages (server -> client for notifications, file writes, etc.)
   - Protocol version handshake

   Transit handlers are configured to match ClojureScript's write-transit-str
   which uses datascript.transit and cljs-bean.transit handlers."
  (:require [cognitect.transit :as transit]
            [datascript.transit :as dt])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [clojure.lang ExceptionInfo]))

;; =============================================================================
;; Protocol Constants
;; =============================================================================

(def PROTOCOL_VERSION
  "Current protocol version. Increment when making breaking changes."
  "1.0.0")

(def MIN_COMPATIBLE_VERSION
  "Minimum client version this server can communicate with."
  "1.0.0")

;; =============================================================================
;; Transit Handlers (CLJS Compatibility)
;; =============================================================================
;; These handlers match what ClojureScript's write-transit-str uses:
;; - datascript.transit handlers for DB and Datom types
;; - Custom handlers for "error", "js/Error", and "datascript/Entity" tags

(def custom-read-handlers
  "Read handlers to match CLJS write-transit-str output."
  (merge dt/read-handlers
         {"datascript/Entity" (transit/read-handler (fn [m] m))  ;; Entity becomes plain map
          "error" (transit/read-handler (fn [m] (ex-info (:message m "") (:data m {}))))
          "js/Error" (transit/read-handler (fn [m] (ex-info (:message m "") {})))}))

(def custom-write-handlers
  "Write handlers to match what CLJS read-transit-str expects."
  (merge dt/write-handlers
         {ExceptionInfo (transit/write-handler
                          (constantly "error")
                          (fn [e] {:message (ex-message e)
                                   :data (ex-data e)}))}))

;; =============================================================================
;; Transit Serialization
;; =============================================================================

(defn serialize
  "Serialize Clojure data to Transit JSON string.
   Uses custom handlers for CLJS compatibility."
  [data]
  (let [out (ByteArrayOutputStream. 4096)
        writer (transit/writer out :json {:handlers custom-write-handlers})]
    (transit/write writer data)
    (.toString out "UTF-8")))

(defn deserialize
  "Deserialize Transit JSON string to Clojure data.
   Uses custom handlers to read CLJS-produced Transit."
  [^String s]
  (let [in (ByteArrayInputStream. (.getBytes s "UTF-8"))
        reader (transit/reader in :json {:handlers custom-read-handlers})]
    (transit/read reader)))

;; =============================================================================
;; Request Construction
;; =============================================================================

(defn make-request
  "Create a request message for the sidecar server.

   op - Operation keyword (e.g., :query, :transact, :pull, or thread-api keywords)
   payload - Operation-specific data"
  [op payload]
  {:id (random-uuid)
   :type :request
   :op op
   :payload payload
   :timestamp (System/currentTimeMillis)})

;; =============================================================================
;; Response Construction
;; =============================================================================

(defn make-response
  "Create a successful response message.

   op - The operation this responds to
   payload - Response data
   request-id - Optional ID of the request this responds to"
  ([op payload]
   (make-response op payload nil))
  ([op payload request-id]
   (cond-> {:type :response
            :ok? true
            :op op
            :payload payload
            :timestamp (System/currentTimeMillis)}
     request-id (assoc :request-id request-id))))

(defn make-error-response
  "Create an error response message.

   error-type - Keyword categorizing the error (e.g., :not-found, :invalid-query)
   message - Human-readable error description
   request-id - Optional ID of the request this responds to"
  ([error-type message]
   (make-error-response error-type message nil))
  ([error-type message request-id]
   (cond-> {:type :response
            :ok? false
            :error-type error-type
            :message message
            :timestamp (System/currentTimeMillis)}
     request-id (assoc :request-id request-id))))

;; =============================================================================
;; Handshake
;; =============================================================================

(defn make-handshake
  "Create a handshake request for protocol version negotiation."
  []
  {:id (random-uuid)
   :type :request
   :op :handshake
   :payload {:version PROTOCOL_VERSION
             :capabilities #{:query :transact :pull :push}}
   :timestamp (System/currentTimeMillis)})

(defn- version->vec
  "Parse version string to vector of integers for comparison."
  [version-str]
  (try
    (mapv #(Integer/parseInt %) (clojure.string/split version-str #"\."))
    (catch Exception _
      [0 0 0])))

(defn- version>=
  "Check if version-a >= version-b"
  [version-a version-b]
  (let [a (version->vec version-a)
        b (version->vec version-b)]
    (>= (compare a b) 0)))

(defn validate-handshake
  "Validate client version against server requirements.

   Returns response map with :ok? true/false and optional :message."
  [client-version]
  (if (version>= client-version MIN_COMPATIBLE_VERSION)
    {:ok? true
     :server-version PROTOCOL_VERSION
     :capabilities #{:query :transact :pull :push}}
    {:ok? false
     :message (format "Client version %s is not compatible. Minimum required: %s"
                      client-version MIN_COMPATIBLE_VERSION)
     :server-version PROTOCOL_VERSION
     :min-compatible MIN_COMPATIBLE_VERSION}))

;; =============================================================================
;; Push Messages (Server -> Client)
;; =============================================================================

(defn make-push-message
  "Create a push message from server to client.

   These are used for:
   - :write-files - Request client to write files to disk
   - :notification - Display notification to user
   - :sync-db-changes - Notify client of DB changes
   - :log - Send log messages
   - etc.

   event - Event type keyword
   payload - Event-specific data"
  [event payload]
  {:type :push
   :event event
   :payload payload
   :timestamp (System/currentTimeMillis)})

;; =============================================================================
;; Message Parsing
;; =============================================================================

(defn parse-message
  "Parse a raw Transit string into a typed message.

   Returns the deserialized message with :type indicating:
   - :request - Client request to server
   - :response - Server response to client
   - :push - Server push to client"
  [transit-str]
  (let [msg (deserialize transit-str)]
    (assoc msg :_raw transit-str)))

(defn request?
  "Check if message is a request."
  [msg]
  (= :request (:type msg)))

(defn response?
  "Check if message is a response."
  [msg]
  (= :response (:type msg)))

(defn push?
  "Check if message is a push notification."
  [msg]
  (= :push (:type msg)))

;; =============================================================================
;; Thread API Helpers
;; =============================================================================

(def thread-api-ops
  "Set of all thread-api operations that can be forwarded to sidecar."
  #{:thread-api/transact
    :thread-api/q
    :thread-api/pull
    :thread-api/datoms
    :thread-api/get-blocks
    :thread-api/get-block-refs
    :thread-api/search-blocks
    :thread-api/apply-outliner-ops
    :thread-api/export-db
    :thread-api/import-db
    :thread-api/reset-db
    :thread-api/sync-app-state
    :thread-api/get-all-page-titles
    :thread-api/build-graph
    ;; Add more as needed
    })

(defn thread-api-op?
  "Check if operation is a thread-api call."
  [op]
  (or (contains? thread-api-ops op)
      (and (keyword? op)
           (= "thread-api" (namespace op)))))
