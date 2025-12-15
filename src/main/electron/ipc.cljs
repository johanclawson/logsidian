(ns electron.ipc
  "Provides fns to send ipc messages to electron's main process"
  (:require [cljs-bean.core :as bean]
            [promesa.core :as p]
            [frontend.util :as util]))

(defn- handle-ipc-result
  "Handle IPC result, converting error objects back to exceptions.
   The main process wraps CLJS ExceptionInfo objects in a plain JS object
   with __ipc_error__ flag because Electron can't serialize CLJS objects."
  [result]
  (if (and (object? result) (.-__ipc_error__ ^js result))
    (throw (ex-info (or (.-message result) "IPC error")
                    (bean/->clj (or (.-data result) #js {}))))
    result))

(defn ipc
  [& args]
  (when (util/electron?)
    (p/let [result (js/window.apis.doAction (bean/->js args))]
      (handle-ipc-result result))))

(defn invoke
  [channel & args]
  (when (util/electron?)
    (p/let [result (js/window.apis.invoke channel (bean/->js args))]
      (handle-ipc-result result))))
