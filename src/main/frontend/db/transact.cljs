(ns frontend.db.transact
  "Provides async transact for use with ldb/transact!"
  (:require [frontend.state :as state]
            [frontend.util :as util]
            [lambdaisland.glogi :as log]
            [logseq.outliner.op :as outliner-op]
            [promesa.core :as p]))

(defn worker-call
  "Invokes request-f, which sends a request to the db worker and returns a
   promise, and returns a promise that settles once: it resolves to the
   request's result, and rejects when request-f throws synchronously (e.g.
   state/<invoke-db-worker when the worker is gone), when its promise rejects,
   or when it resolves to a failure result (a map with :ex-data).

   The former go block + deferred settled only the last case: p->c wraps a
   rejection in an ex-info, <? rethrew it inside the go block, and a
   synchronous throw also escaped the go block, so in both cases the returned
   promise stayed pending forever and awaiting callers (the reconcile's repair
   phase) hung."
  [request-f]
  ;; The synchronous throw is caught explicitly, not left to p/do: see the note
  ;; in frontend.common.async-util/<map-bounded.
  (-> (try (p/promise (request-f))
           (catch :default e (p/rejected e)))
      (p/then (fn [result]
                (if (:ex-data result)
                  (p/rejected result)
                  result)))
      (p/catch (fn [error]
                 (log/error :worker-request-failed error)
                 (p/rejected error)))))

(defn transact [worker-transact repo tx-data tx-meta]
  (let [tx-meta' (assoc tx-meta
                        ;; not from remote (rtc)
                        :local-tx? true)]
    (worker-call (fn async-request []
                   (worker-transact repo tx-data tx-meta')))))

(defn apply-outliner-ops
  [conn ops opts]
  (when (seq ops)
    (if util/node-test?
      (outliner-op/apply-ops! (state/get-current-repo)
                              conn
                              ops
                              (state/get-date-formatter)
                              opts)
      (let [opts' (assoc opts
                         :client-id (:client-id @state/state)
                         :local-tx? true)
            request #(frontend.state/<invoke-db-worker
                      :thread-api/apply-outliner-ops
                      (frontend.state/get-current-repo)
                      ops
                      opts')]
        (frontend.db.transact/worker-call request)))))
