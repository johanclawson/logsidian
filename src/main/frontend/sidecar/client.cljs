(ns frontend.sidecar.client
  "Sidecar client - IPC wrapper for main process TCP client.

   This module provides the renderer-side API for communicating with the
   JVM sidecar. All actual Node.js operations (TCP socket, process spawning)
   happen in the Electron main process via IPC.

   Architecture:
   - Renderer calls IPC handlers via electron.ipc
   - Main process electron.sidecar handles TCP/process management
   - Push messages arrive via 'sidecar-push' IPC channel

   The public API remains the same as before to minimize changes to
   calling code (core.cljs, browser.cljs).

   IPC Communication:
   - Requests are sent as CLJS data (serialized by Electron IPC)
   - Responses are returned as Transit strings (to avoid CLJS->JS cloning issues)
   - This module deserializes Transit responses using ldb/read-transit-str"
  (:require [electron.ipc :as ipc]
            [lambdaisland.glogi :as log]
            [logseq.db :as ldb]
            [promesa.core :as p]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:const DEFAULT_PORT 47632)
(def ^:const DEFAULT_TIMEOUT_MS 30000)

;; =============================================================================
;; Connection Management
;; =============================================================================

(defn connect!
  "Connect to the sidecar server via IPC to main process.

   Options:
   - :port - TCP port (default: 47632)
   - :timeout-ms - Connection timeout (default: 30000)

   Returns a promise that resolves when connected."
  ([] (connect! {}))
  ([{:keys [port timeout-ms]
     :or {port DEFAULT_PORT
          timeout-ms DEFAULT_TIMEOUT_MS}}]
   (log/debug :sidecar-connect {:port port :timeout-ms timeout-ms})
   (ipc/ipc :sidecar/connect {:port port :timeout-ms timeout-ms})))

(defn disconnect!
  "Disconnect from the sidecar server."
  []
  (log/debug :sidecar-disconnect {})
  (ipc/ipc :sidecar/disconnect))

(defn connected?
  "Check if the client is connected.
   Returns a promise that resolves to boolean."
  []
  (p/let [status (ipc/ipc :sidecar/status)]
    (:connected? status)))

;; =============================================================================
;; Request/Response API
;; =============================================================================

(defn send-request
  "Send a request to the sidecar and return a promise for the response.

   op - The operation keyword (e.g., :thread-api/q)
   payload - The request payload
   opts - {:timeout-ms - Request timeout (default: 30000)}

   Returns a promise that resolves with the response payload."
  ([op payload] (send-request op payload {}))
  ([op payload {:keys [timeout-ms] :or {timeout-ms DEFAULT_TIMEOUT_MS}}]
   (ipc/ipc :sidecar/request op payload {:timeout-ms timeout-ms})))

;; =============================================================================
;; High-Level API (Thread API compatible)
;; =============================================================================

(defn invoke
  "Invoke a thread-api operation on the sidecar.

   This function has the same signature as the web worker invocation,
   making it a drop-in replacement.

   qkw - The operation keyword (e.g., :thread-api/q)
   args - The operation arguments

   Returns a promise that resolves with the result."
  [qkw & args]
  (send-request qkw {:args (vec args)}))

(defn create-worker-fn
  "Create a worker function that routes calls to the sidecar.

   This returns a function with the same signature as the Comlink-wrapped
   web worker, allowing it to be used as a drop-in replacement for
   state/*db-worker.

   The function:
   - Sends requests via IPC to the main process
   - Receives responses as Transit strings (main process serializes for IPC compatibility)
   - Deserializes Transit to CLJS data unless direct-pass? is true

   Usage:
   (reset! state/*db-worker (sidecar.client/create-worker-fn))"
  []
  (fn [qkw direct-pass? & args]
    (p/let [result (send-request qkw
                                 (if direct-pass?
                                   {:args (vec args) :direct-pass? true}
                                   {:args (vec args)}))]
      ;; Main process returns Transit string for IPC compatibility
      ;; Deserialize here unless direct-pass? (matches wrapped-worker behavior in browser.cljs)
      (if direct-pass?
        result
        (ldb/read-transit-str result)))))
