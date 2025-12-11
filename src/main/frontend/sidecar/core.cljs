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
  "Start the database backend - sidecar (Electron IPC or WebSocket) or web worker.

   This is the main entry point that replaces start-db-worker! in browser.cljs.
   It automatically chooses the best backend based on environment:
   - Electron + sidecar enabled → IPC sidecar (start-sidecar!)
   - Browser + WebSocket sidecar enabled → WebSocket sidecar (start-websocket-sidecar!)
   - Otherwise → web worker

   Options:
   - :prefer-sidecar? - Whether to try sidecar first (default: true when available)
   - :fallback-on-error? - Fall back to web worker if sidecar fails (default: true)
   - :on-backend-ready - Callback when backend is ready (receives {:type :sidecar|:websocket-sidecar|:worker})

   Returns a promise that resolves with {:type :sidecar|:websocket-sidecar|:worker}"
  ([] (start-db-backend! {}))
  ([{:keys [prefer-sidecar? fallback-on-error? on-backend-ready start-web-worker-fn]
     :or {prefer-sidecar? true
          fallback-on-error? true}}]
   (let [use-ipc-sidecar? (and prefer-sidecar? (sidecar-enabled?))
         use-ws-sidecar? (and prefer-sidecar? (websocket-sidecar-enabled?))
         fallback-to-worker (fn [err]
                              (if (and fallback-on-error? start-web-worker-fn)
                                (do
                                  (log/warn :sidecar-fallback-to-worker {:error err})
                                  (p/let [_ (start-web-worker-fn)]
                                    (let [result {:type :worker}]
                                      (when on-backend-ready
                                        (on-backend-ready result))
                                      result)))
                                (throw err)))]
     (cond
       ;; Electron: Try IPC sidecar
       use-ipc-sidecar?
       (-> (start-sidecar!)
           (p/then (fn [result]
                     (when on-backend-ready
                       (on-backend-ready result))
                     result))
           (p/catch fallback-to-worker))

       ;; Browser: Try WebSocket sidecar
       use-ws-sidecar?
       (-> (start-websocket-sidecar!)
           (p/then (fn [result]
                     (when on-backend-ready
                       (on-backend-ready result))
                     result))
           (p/catch fallback-to-worker))

       ;; Use web worker directly
       start-web-worker-fn
       (p/let [_ (start-web-worker-fn)]
         (let [result {:type :worker}]
           (when on-backend-ready
             (on-backend-ready result))
           result))

       :else
       (p/rejected (ex-info "No backend available" {:type :no-backend}))))))
