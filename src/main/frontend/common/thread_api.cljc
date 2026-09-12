(ns frontend.common.thread-api
  "Macro for defining thread apis, which is invokeable by other threads"
  #?(:cljs (:require-macros [frontend.common.thread-api]))
  #?(:cljs (:require [logseq.db :as ldb]
                     [promesa.core :as p]
                     [lambdaisland.glogi :as log])))

#?(:cljs
   (def *thread-apis (volatile! {})))

#_:clj-kondo/ignore
(defmacro defkeyword [& _args])

(defmacro def-thread-api
  "Define a api invokeable by other threads.
  e.g. (def-thread-api :thread-api/a-api [arg1 arg2] body)"
  [qualified-keyword-name params & body]
  (assert (= "thread-api" (namespace qualified-keyword-name)) qualified-keyword-name)
  (assert (vector? params) params)
  `(vswap! *thread-apis assoc
           ~qualified-keyword-name
           (fn ~(symbol (str "thread-api--" (name qualified-keyword-name))) ~params ~@body)))

#?(:cljs (def *profile (volatile! {})))

#?(:cljs
   (defn- perf-api-error!
     "ADR-003 instrumentation: release builds log nothing (glogi is off), so a
      failing thread-api call vanishes without a trace. Print it."
     [qualified-kw-str stage e]
     (js/console.log
      (str "LSPERF "
           (js/JSON.stringify
            #js {:event "api-error"
                 :api qualified-kw-str
                 :thread (if (exists? js/document) "ui" "worker")
                 :stage stage
                 :msg (subs (str (or (ex-message e) e)) 0 500)
                 :stack (some-> e .-stack (subs 0 1500))})))))

#?(:cljs
   (defn- write-transit-str-with-catch
     [v qualified-kw-str]
     (try
       (ldb/write-transit-str v)
       (catch :default e
         (log/error :thread-api-write-transit-failed qualified-kw-str)
         (perf-api-error! qualified-kw-str "transit-write" e)
         (throw e)))))

#?(:cljs
   (defn- perf-api!
     "ADR-003 instrumentation: log a thread-api call that blocked its thread
      (sync-ms) or took long end to end (total-ms), so a stall has a name."
     [qualified-kw-str args sync-ms total-ms]
     (when (or (> sync-ms 50) (> total-ms 200))
       (js/console.log
        (str "LSPERF "
             (js/JSON.stringify
              #js {:event "api"
                   :api qualified-kw-str
                   :thread (if (exists? js/document) "ui" "worker")
                   :sync-ms (js/Math.round sync-ms)
                   :total-ms (js/Math.round total-ms)
                   :args (when (string? args) (subs args 0 (min 160 (count args))))}))))))

#?(:cljs
   (defn remote-function
     "Return a promise whose value is transit-str."
     [qualified-kw-str direct-pass? args-transit-str-or-args-array]
     (let [qkw (keyword qualified-kw-str)
           t0 (js/performance.now)]
       (vswap! *profile update qkw inc)
       (if-let [f (@*thread-apis qkw)]
         (let [result (try
                        (if (= qkw :thread-api/set-infer-worker-proxy)
                          (f args-transit-str-or-args-array)
                          (apply f (cond-> args-transit-str-or-args-array
                                     (not direct-pass?) ldb/read-transit-str)))
                        (catch :default e
                          (perf-api-error! qualified-kw-str "sync" e)
                          (throw e)))
               sync-ms (- (js/performance.now) t0)
               result-promise
               (if (fn? result) ;; missionary task is a fn
                 (js/Promise. result)
                 result)]
           (->
            (p/let [result' result-promise
                    out (if direct-pass?
                          result'
                          (write-transit-str-with-catch result' qualified-kw-str))]
              (perf-api! qualified-kw-str args-transit-str-or-args-array
                         sync-ms (- (js/performance.now) t0))
              out)
            (p/catch (fn [e]
                       (perf-api-error! qualified-kw-str "async" e)
                       (write-transit-str-with-catch e qualified-kw-str)))))
         (throw (ex-info (str "not found thread-api: " qualified-kw-str) {}))))))
