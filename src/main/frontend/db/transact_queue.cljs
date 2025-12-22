(ns frontend.db.transact-queue
  "Transact queue for serializing DataScript transactions.
   Prevents concurrent transact errors by chaining transactions sequentially.

   Used by parallel graph loading to ensure batch transactions don't overlap."
  (:require [logseq.db :as ldb]
            [promesa.core :as p]))

;; =============================================================================
;; Step 10: Concurrency Guard for DataScript
;; =============================================================================

;; Promise chain that serializes all transacts.
;; Each transact waits for the previous one to complete.
(defonce ^:private transact-queue (atom (p/resolved nil)))

(defn enqueue-transact!
  "Enqueue a transaction to be executed serially.
   Returns a promise that resolves when this transaction completes.

   Arguments:
   - conn: DataScript connection
   - tx-data: Transaction data vector
   - tx-meta: Optional transaction metadata

   The queue ensures only one transaction runs at a time,
   preventing 'datascript.core/transact!' concurrency errors."
  ([conn tx-data]
   (enqueue-transact! conn tx-data {}))
  ([conn tx-data tx-meta]
   (let [result-promise (p/deferred)]
     (swap! transact-queue
            (fn [prev]
              ;; First, ensure previous promise failures don't break the chain
              (-> (p/catch prev (constantly nil))
                  ;; Then execute our transaction
                  (p/then
                   (fn [_]
                     ;; Handle both sync and async transact results
                     (p/handle
                      (p/do (ldb/transact! conn tx-data tx-meta))
                      (fn [result error]
                        (if error
                          (do
                            (p/reject! result-promise error)
                            ;; Return resolved nil to keep chain going
                            nil)
                          (do
                            (p/resolve! result-promise result)
                            result)))))))))
     result-promise)))

(defn transact-batch!
  "Execute multiple transactions serially through the queue.
   Returns a promise that resolves with results from all transactions.

   Arguments:
   - conn: DataScript connection
   - tx-datas: Sequence of transaction data vectors
   - tx-meta: Optional transaction metadata (applied to all)

   Transactions are executed in order, each waiting for the previous."
  ([conn tx-datas]
   (transact-batch! conn tx-datas {}))
  ([conn tx-datas tx-meta]
   (p/all (mapv #(enqueue-transact! conn % tx-meta) tx-datas))))

(defn flush-queue!
  "Wait for all pending transactions to complete.
   Returns a promise that resolves when the queue is empty."
  []
  @transact-queue)

(defn reset-queue!
  "Reset the queue to empty state. Use with caution - only for testing."
  []
  (reset! transact-queue (p/resolved nil)))
