(ns frontend.sidecar.spawn
  "Sidecar spawn - IPC wrapper for main process JVM management.

   This module provides the renderer-side API for spawning and managing
   the JVM sidecar process. All actual process management happens in
   the Electron main process via IPC.

   Architecture:
   - Renderer calls IPC handlers via electron.ipc
   - Main process electron.sidecar handles JVM spawning
   - Main process handles shutdown on app quit

   The public API remains the same as before to minimize changes to
   calling code (core.cljs)."
  (:require [electron.ipc :as ipc]
            [lambdaisland.glogi :as log]
            [promesa.core :as p]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:const DEFAULT_PORT 47632)

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn start!
  "Start the JVM sidecar process via IPC to main process.

   Options:
   - :port - TCP port (default: 47632)

   Returns a promise that resolves when the sidecar is ready."
  ([] (start! {}))
  ([{:keys [port] :or {port DEFAULT_PORT}}]
   (log/info :sidecar-start {:port port})
   (ipc/ipc :sidecar/start {:port port})))

(defn stop!
  "Stop the JVM sidecar process."
  []
  (log/info :sidecar-stop {})
  (ipc/ipc :sidecar/stop))

(defn running?
  "Check if the sidecar is running.
   Returns a promise that resolves to boolean."
  []
  (p/let [status (ipc/ipc :sidecar/status)]
    (:running? status)))

(defn get-port
  "Get the default port the sidecar listens on."
  []
  DEFAULT_PORT)

;; =============================================================================
;; Shutdown Handler
;; =============================================================================

(defn register-shutdown-handler!
  "Register handler to stop sidecar when Electron app quits.

   Note: This is now a no-op in the renderer. The main process
   electron.sidecar module registers its own shutdown handler
   during initialization."
  []
  ;; No-op - main process handles shutdown via app.on('before-quit')
  nil)
