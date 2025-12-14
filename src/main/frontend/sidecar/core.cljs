(ns frontend.sidecar.core
  "Main entry point for JVM sidecar integration.

   This module provides a simple API for:
   - Starting the sidecar (spawn JVM + connect client)
   - Creating a worker function compatible with state/*db-worker
   - Fallback to web worker if sidecar unavailable

   Architecture:
   - Electron: Uses IPC (client.cljs) to communicate with sidecar
   - Browser: Uses WebSocket (websocket-client.cljs) to communicate with sidecar

   Usage:
   ```clojure
   ;; In browser.cljs, replace start-db-worker! with:
   (if (sidecar/available?)
     (sidecar/start-sidecar!)
     (start-web-worker!))
   ```"
  (:require [frontend.sidecar.client :as client]
            [frontend.sidecar.spawn :as spawn]
            [frontend.sidecar.websocket-client :as ws-client]
            [frontend.config :as config]
            [frontend.state :as state]
            [frontend.util :as util]
            [lambdaisland.glogi :as log]
            [promesa.core :as p]))

;; =============================================================================
;; Configuration
;; =============================================================================

(defonce ^:private *sidecar-enabled? (atom nil))
(defonce ^:private *websocket-sidecar-enabled? (atom nil))

(defn sidecar-enabled?
  "Check if sidecar mode is enabled (Electron IPC path).

   Sidecar is enabled when:
   1. Running in Electron (desktop app)
   2. User hasn't explicitly disabled it
   3. Sidecar JAR and Java are available"
  []
  (when (nil? @*sidecar-enabled?)
    ;; Cache the check result
    (reset! *sidecar-enabled?
            (and (util/electron?)
                 (not config/publishing?)
                 ;; TODO: Add user preference check from config
                 ;; (state/get-config :sidecar/enabled? true)
                 true)))
  @*sidecar-enabled?)

(defn websocket-sidecar-enabled?
  "Check if WebSocket sidecar mode is enabled (browser path).

   WebSocket sidecar is enabled when:
   1. NOT running in Electron (browser/mobile)
   2. User has explicitly enabled it via localStorage
   3. Sidecar server is expected to be running externally

   Set localStorage 'sidecar-websocket-enabled' to 'true' to enable."
  []
  (when (nil? @*websocket-sidecar-enabled?)
    ;; Cache the check result - check localStorage for explicit opt-in
    (reset! *websocket-sidecar-enabled?
            (and (not (util/electron?))
                 (not config/publishing?)
                 ;; Check localStorage for explicit opt-in
                 (= "true" (js/localStorage.getItem "sidecar-websocket-enabled")))))
  @*websocket-sidecar-enabled?)

(defn force-disable-sidecar!
  "Force disable sidecar mode. Useful for testing or recovery."
  []
  (reset! *sidecar-enabled? false)
  (reset! *websocket-sidecar-enabled? false))

(defn force-enable-sidecar!
  "Force enable sidecar mode (for Electron IPC)."
  []
  (reset! *sidecar-enabled? true))

(defn force-enable-websocket-sidecar!
  "Force enable WebSocket sidecar mode (for browser).
   Also sets localStorage to persist the setting."
  []
  (js/localStorage.setItem "sidecar-websocket-enabled" "true")
  (reset! *websocket-sidecar-enabled? true))

;; =============================================================================
;; Sidecar Worker Function
;; =============================================================================

;; The sidecar worker function is created by client/create-worker-fn which:
;; - Sends requests via IPC to main process
;; - Receives Transit strings back (for IPC compatibility)
;; - Deserializes Transit to CLJS data (unless direct-pass? is true)

;; =============================================================================
;; Lifecycle
;; =============================================================================

;; Note: Push messages from the sidecar are handled by electron.listener.cljs
;; via the "sidecar-push" IPC channel. The main process electron.sidecar module
;; forwards push messages from the JVM to the renderer.

(defn start-sidecar!
  "Start the JVM sidecar and connect the client.

   Returns a promise that resolves when:
   1. The JVM process is running
   2. The TCP client is connected
   3. state/*db-worker is set to route through sidecar

   If sidecar startup fails, returns a rejected promise."
  ([] (start-sidecar! {}))
  ([{:keys [port] :or {port spawn/DEFAULT_PORT}}]
   (log/info :sidecar-starting-full {:port port})
   (-> (p/let [;; Start the JVM process (also auto-connects)
               _ (spawn/start! {:port port})
               ;; Create and set the worker function using client/create-worker-fn
               ;; which properly deserializes Transit responses
               worker-fn (client/create-worker-fn)]
         ;; Set as the db-worker
         (reset! state/*db-worker worker-fn)
         ;; Register shutdown handler
         (spawn/register-shutdown-handler!)
         (log/info :sidecar-started {:port port})
         {:type :sidecar :port port})
       (p/catch (fn [err]
                  (log/error :sidecar-start-failed {:error err})
                  ;; Clean up partial state
                  (client/disconnect!)
                  (spawn/stop!)
                  (throw err))))))

(defn stop-sidecar!
  "Stop the sidecar and clean up resources.
   Returns a promise that resolves when stopped."
  []
  (log/info :sidecar-stopping {})
  (p/do!
   (client/disconnect!)
   (spawn/stop!)
   (reset! state/*db-worker nil)
   (log/info :sidecar-stopped {})))

(defn sidecar-running?
  "Check if the sidecar is currently running and connected.
   Returns a promise that resolves to boolean."
  []
  (p/let [running (spawn/running?)
          connected (client/connected?)]
    (and running connected)))

;; =============================================================================
;; WebSocket Sidecar (Browser Path)
;; =============================================================================

(defn start-websocket-sidecar!
  "Start the WebSocket sidecar connection (for browsers).

   This connects directly to an externally-running sidecar via WebSocket.
   Unlike start-sidecar! (Electron), this does NOT spawn the JVM process -
   the sidecar must already be running.

   Returns a promise that resolves when:
   1. The WebSocket is connected
   2. state/*db-worker is set to route through WebSocket sidecar
   3. Current graph is initialized in sidecar

   If connection fails, returns a rejected promise."
  ([] (start-websocket-sidecar! {}))
  ([{:keys [url] :or {url ws-client/DEFAULT_URL}}]
   (log/info :websocket-sidecar-starting {:url url})
   (-> (p/let [;; Connect via WebSocket
               _ (ws-client/connect! {:url url})
               ;; Create and set the worker function
               worker-fn (ws-client/create-worker-fn)]
         ;; Set as the db-worker
         (reset! state/*db-worker worker-fn)
         ;; Initialize current repo in sidecar if one is open
         (when-let [repo (state/get-current-repo)]
           (log/info :websocket-sidecar-init-repo {:repo repo})
           ;; Create or open the graph in sidecar
           (state/<invoke-db-worker :thread-api/create-or-open-db repo {}))
         (log/info :websocket-sidecar-started {:url url})
         {:type :websocket-sidecar :url url})
       (p/catch (fn [err]
                  (log/error :websocket-sidecar-start-failed {:error err})
                  ;; Clean up partial state
                  (ws-client/disconnect!)
                  (throw err))))))

(defn stop-websocket-sidecar!
  "Stop the WebSocket sidecar connection.
   Returns a promise that resolves when stopped."
  []
  (log/info :websocket-sidecar-stopping {})
  (p/do!
   (ws-client/disconnect!)
   (reset! state/*db-worker nil)
   (log/info :websocket-sidecar-stopped {})))

(defn websocket-sidecar-running?
  "Check if the WebSocket sidecar is currently connected."
  []
  (ws-client/connected?))

;; =============================================================================
;; High-Level Integration
;; =============================================================================

(defn start-db-backend!
  "Start the database backend - hybrid (worker + sidecar) or worker only.

   HYBRID ARCHITECTURE (2025-12-11):
   The web worker is ALWAYS started first because it handles file parsing via mldoc.
   mldoc is an OCaml→JavaScript library that can ONLY run in JS environments.
   The sidecar is then started alongside for efficient queries.

   Flow:
   1. Start web worker FIRST (for file parsing with mldoc)
   2. If sidecar enabled, also start sidecar (for queries)
   3. Initial sync happens on :graph/added event (see initial_sync.cljs)
   4. Queries route to sidecar once synced

   Options:
   - :prefer-sidecar? - Whether to also start sidecar (default: true when available)
   - :on-backend-ready - Callback when backend is ready
   - :start-web-worker-fn - Function to start the web worker (required)

   Returns a promise that resolves with:
   - {:type :hybrid :worker true :sidecar true} - Worker + sidecar running
   - {:type :hybrid :worker true :sidecar :websocket} - Worker + WebSocket sidecar
   - {:type :worker} - Worker only"
  ([] (start-db-backend! {}))
  ([{:keys [prefer-sidecar? on-backend-ready start-web-worker-fn]
     :or {prefer-sidecar? true}}]
   (let [use-ipc-sidecar? (and prefer-sidecar? (sidecar-enabled?))
         use-ws-sidecar? (and prefer-sidecar? (websocket-sidecar-enabled?))

         ;; Start sidecar after worker is running (non-blocking, errors logged)
         start-sidecar-async! (fn []
                                (cond
                                  use-ipc-sidecar?
                                  (-> (start-sidecar!)
                                      (p/then (fn [_]
                                                (log/info :hybrid-sidecar-connected {:type :ipc})
                                                :ipc))
                                      (p/catch (fn [err]
                                                 (log/warn :hybrid-sidecar-failed
                                                           {:type :ipc :error (str err)})
                                                 nil)))

                                  use-ws-sidecar?
                                  (-> (start-websocket-sidecar!)
                                      (p/then (fn [_]
                                                (log/info :hybrid-sidecar-connected {:type :websocket})
                                                :websocket))
                                      (p/catch (fn [err]
                                                 (log/warn :hybrid-sidecar-failed
                                                           {:type :websocket :error (str err)})
                                                 nil)))

                                  :else
                                  (p/resolved nil)))]

     (if start-web-worker-fn
       ;; ALWAYS start worker first (required for file parsing)
       (-> (p/let [_ (do
                       (log/info :hybrid-starting-worker {})
                       (start-web-worker-fn))
                   _ (log/info :hybrid-worker-started {})
                   ;; Now start sidecar (if enabled) in parallel
                   ;; Note: Don't block on sidecar - worker is primary for parsing
                   sidecar-type (when (or use-ipc-sidecar? use-ws-sidecar?)
                                  (start-sidecar-async!))]
             (let [result (cond
                            sidecar-type
                            {:type :hybrid :worker true :sidecar sidecar-type}

                            (or use-ipc-sidecar? use-ws-sidecar?)
                            ;; Sidecar was requested but failed - worker-only
                            {:type :worker :sidecar-requested true}

                            :else
                            {:type :worker})]
               (log/info :hybrid-backend-ready result)
               (when on-backend-ready
                 (on-backend-ready result))
               result))
           (p/catch (fn [err]
                      ;; Worker failed - this is fatal
                      (log/error :hybrid-worker-failed {:error err})
                      (throw err))))

       ;; No worker function provided - cannot start
       (p/rejected (ex-info "No start-web-worker-fn provided - required for file parsing"
                            {:type :no-worker-fn}))))))
