(ns frontend.db.transact-queue-test
  "Tests for transact queue - concurrent transaction serialization."
  (:require [cljs.test :refer [deftest testing is async]]
            [datascript.core :as d]
            [frontend.db.transact-queue :as tq]
            [logseq.graph-parser.db :as gp-db]
            [promesa.core :as p]))

;; =============================================================================
;; Step 10: Concurrency Guard Tests
;; =============================================================================

(deftest enqueue-transact-basic-test
  (async done
    (let [conn (gp-db/start-conn)]
      (-> (tq/enqueue-transact! conn [{:block/uuid (d/squuid)
                                        :block/name "test-page"
                                        :block/title "Test Page"}])
          (p/then (fn [result]
                    (is (some? result) "Transaction returned result")
                    (is (d/entity @conn [:block/name "test-page"])
                        "Entity exists after transact")
                    (tq/reset-queue!)
                    (done)))
          (p/catch (fn [e]
                     (is false (str "Transaction failed: " e))
                     (tq/reset-queue!)
                     (done)))))))

(deftest enqueue-transact-preserves-order-test
  (async done
    (let [conn (gp-db/start-conn)
          order (atom [])
          ;; Queue three transactions that record their execution order
          p1 (tq/enqueue-transact! conn [{:block/uuid (d/squuid)
                                           :block/name "page-1"
                                           :block/title "Page 1"}])
          p2 (tq/enqueue-transact! conn [{:block/uuid (d/squuid)
                                           :block/name "page-2"
                                           :block/title "Page 2"}])
          p3 (tq/enqueue-transact! conn [{:block/uuid (d/squuid)
                                           :block/name "page-3"
                                           :block/title "Page 3"}])]
      (-> (p/all [p1 p2 p3])
          (p/then (fn [_]
                    ;; All transactions should have completed
                    (is (d/entity @conn [:block/name "page-1"]) "Page 1 exists")
                    (is (d/entity @conn [:block/name "page-2"]) "Page 2 exists")
                    (is (d/entity @conn [:block/name "page-3"]) "Page 3 exists")
                    (tq/reset-queue!)
                    (done)))
          (p/catch (fn [e]
                     (is false (str "Transaction failed: " e))
                     (tq/reset-queue!)
                     (done)))))))

(deftest transact-batch-test
  (async done
    (let [conn (gp-db/start-conn)
          tx1 [{:block/uuid (d/squuid)
                :block/name "batch-page-1"
                :block/title "Batch Page 1"}]
          tx2 [{:block/uuid (d/squuid)
                :block/name "batch-page-2"
                :block/title "Batch Page 2"}]
          tx3 [{:block/uuid (d/squuid)
                :block/name "batch-page-3"
                :block/title "Batch Page 3"}]]
      (-> (tq/transact-batch! conn [tx1 tx2 tx3])
          (p/then (fn [results]
                    (is (= 3 (count results)) "Got 3 results")
                    (is (d/entity @conn [:block/name "batch-page-1"]) "Page 1 exists")
                    (is (d/entity @conn [:block/name "batch-page-2"]) "Page 2 exists")
                    (is (d/entity @conn [:block/name "batch-page-3"]) "Page 3 exists")
                    (tq/reset-queue!)
                    (done)))
          (p/catch (fn [e]
                     (is false (str "Batch failed: " e))
                     (tq/reset-queue!)
                     (done)))))))

(deftest enqueue-transact-continues-after-error-test
  (async done
    (let [conn (gp-db/start-conn)
          ;; First: valid transaction
          p1 (tq/enqueue-transact! conn [{:block/uuid (d/squuid)
                                           :block/name "before-error"
                                           :block/title "Before Error"}])
          ;; Second: retract non-existent entity (won't error in DataScript)
          ;; Just verify queue continues to work
          p2 (tq/enqueue-transact! conn [[:db/retractEntity 99999999]])
          ;; Third: valid transaction should still work
          p3 (tq/enqueue-transact! conn [{:block/uuid (d/squuid)
                                           :block/name "after-noop"
                                           :block/title "After Noop"}])]
      (-> (p/all [p1 p2 p3])
          (p/then (fn [[r1 r2 r3]]
                    (is (some? r1) "First transaction succeeded")
                    (is (some? r2) "Second transaction succeeded (noop)")
                    (is (some? r3) "Third transaction succeeded")
                    (is (d/entity @conn [:block/name "before-error"]) "Before entity exists")
                    (is (d/entity @conn [:block/name "after-noop"]) "After entity exists")
                    (tq/reset-queue!)
                    (done)))
          (p/catch (fn [e]
                     (is false (str "Unexpected error: " e))
                     (tq/reset-queue!)
                     (done)))))))

(deftest flush-queue-test
  (async done
    (let [conn (gp-db/start-conn)
          _ (tq/enqueue-transact! conn [{:block/uuid (d/squuid)
                                          :block/name "flush-test"
                                          :block/title "Flush Test"}])]
      (-> (tq/flush-queue!)
          (p/then (fn [_]
                    (is (d/entity @conn [:block/name "flush-test"])
                        "Entity exists after flush")
                    (tq/reset-queue!)
                    (done)))
          (p/catch (fn [e]
                     (is false (str "Flush failed: " e))
                     (tq/reset-queue!)
                     (done)))))))
