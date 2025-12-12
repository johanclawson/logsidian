(ns frontend.sidecar.initial-sync-test
  "Unit tests for the initial sync module.

   These tests verify:
   - Datom extraction from DataScript DB
   - Datom batching for efficient transfer
   - Transaction report conversion

   Note: Integration tests requiring the sidecar should be in clj-e2e/"
  (:require [cljs.test :refer [deftest testing is use-fixtures]]
            [datascript.core :as d]
            [frontend.test.helper :as test-helper :refer [load-test-files]]
            [frontend.db :as db]
            [frontend.sidecar.initial-sync :as initial-sync]))

(use-fixtures :each {:before test-helper/start-test-db!
                     :after test-helper/destroy-test-db!})

;; =============================================================================
;; Datom Extraction Tests
;; =============================================================================

(deftest test-datom->vec
  (testing "datom->vec converts DataScript datom to vector format"
    ;; Create a test database with some data
    (load-test-files [{:file/path "pages/test.md"
                       :file/content "- Block 1\n- Block 2"}])
    (let [conn (db/get-db test-helper/test-db false)
          db @conn
          ;; Get a datom from the database
          datoms (d/datoms db :eavt)
          first-datom (first datoms)]
      (when first-datom
        (let [datom-vec (initial-sync/datom->vec first-datom)]
          (is (vector? datom-vec) "Result should be a vector")
          (is (= 5 (count datom-vec)) "Vector should have 5 elements [e a v tx added?]")
          (is (true? (nth datom-vec 4)) "Last element should be true (added)"))))))

(deftest test-extract-datoms
  (testing "extract-datoms returns all datoms from database"
    (load-test-files [{:file/path "pages/page1.md"
                       :file/content "- Block 1"}
                      {:file/path "pages/page2.md"
                       :file/content "- Block 2"}])
    (let [conn (db/get-db test-helper/test-db false)
          db @conn
          datoms (initial-sync/extract-datoms db)]
      (is (vector? datoms) "Result should be a vector")
      (is (pos? (count datoms)) "Should have some datoms")
      ;; Check format of each datom
      (doseq [datom datoms]
        (is (vector? datom) "Each datom should be a vector")
        (is (= 5 (count datom)) "Each datom should have 5 elements")))))

;; =============================================================================
;; Batching Tests
;; =============================================================================

(deftest test-batch-datoms-basic
  (testing "batch-datoms splits datoms into correct batch sizes"
    (let [;; Create 100 test datoms
          test-datoms (vec (for [i (range 100)]
                             [i :test/attr (str "value-" i) 1000 true]))
          ;; Batch with size 30
          batches (initial-sync/batch-datoms test-datoms 30)]
      (is (= 4 (count batches)) "100 datoms with batch size 30 should be 4 batches")
      ;; First 3 batches should have 30 items each
      (is (= 30 (count (first batches))))
      (is (= 30 (count (second batches))))
      (is (= 30 (count (nth batches 2))))
      ;; Last batch should have 10 items
      (is (= 10 (count (last batches))))))

  (testing "batch-datoms handles exact batch size multiple"
    (let [test-datoms (vec (repeat 60 [1 :a :b 1 true]))
          batches (initial-sync/batch-datoms test-datoms 20)]
      (is (= 3 (count batches)) "60 datoms with batch size 20 should be 3 batches")
      (is (every? #(= 20 (count %)) batches) "All batches should have 20 items")))

  (testing "batch-datoms handles empty input"
    (let [batches (initial-sync/batch-datoms [] 100)]
      (is (empty? batches) "Empty input should produce empty output")))

  (testing "batch-datoms with default batch size"
    (let [test-datoms (vec (repeat 10 [1 :a :b 1 true]))
          batches (initial-sync/batch-datoms test-datoms)]
      (is (= 1 (count batches)) "10 datoms should be 1 batch with default size"))))

;; =============================================================================
;; Transaction Report Conversion Tests
;; =============================================================================

(deftest test-tx-report->datom-vecs
  (testing "tx-report->datom-vecs converts transaction report to vectors"
    (load-test-files [{:file/path "pages/test.md"
                       :file/content "- Initial block"}])
    (let [conn (db/get-db test-helper/test-db false)
          ;; Perform a transaction
          tx-report (d/transact! conn [{:block/uuid (random-uuid)
                                        :block/content "New block"
                                        :block/name "new-block-test"}])
          datom-vecs (initial-sync/tx-report->datom-vecs tx-report)]
      (is (vector? datom-vecs) "Result should be a vector")
      (is (pos? (count datom-vecs)) "Should have some datom vectors")
      ;; Check that each has correct format
      (doseq [dv datom-vecs]
        (is (= 5 (count dv)) "Each should have 5 elements")
        ;; Last element should be boolean (added or retracted)
        (is (boolean? (nth dv 4)) "Last element should be boolean")))))

(deftest test-tx-report->datom-vecs-with-retractions
  (testing "tx-report->datom-vecs handles retractions"
    (load-test-files [{:file/path "pages/test.md"
                       :file/content "- Block to modify"}])
    (let [conn (db/get-db test-helper/test-db false)
          ;; Find the block
          block-id (ffirst (d/q '[:find ?e :where [?e :block/content "Block to modify"]] @conn))]
      (when block-id
        ;; Retract the content
        (let [tx-report (d/transact! conn [[:db/retract block-id :block/content "Block to modify"]])
              datom-vecs (initial-sync/tx-report->datom-vecs tx-report)]
          (is (seq datom-vecs) "Should have datom vectors")
          ;; At least one should be a retraction (added = false)
          (is (some #(false? (nth % 4)) datom-vecs)
              "Should have at least one retraction (added=false)"))))))

;; =============================================================================
;; BATCH_SIZE Constant Test
;; =============================================================================

(deftest test-batch-size-constant
  (testing "BATCH_SIZE is a reasonable value"
    (is (number? initial-sync/BATCH_SIZE) "BATCH_SIZE should be a number")
    (is (pos? initial-sync/BATCH_SIZE) "BATCH_SIZE should be positive")
    (is (>= initial-sync/BATCH_SIZE 1000) "BATCH_SIZE should be at least 1000 for efficiency")))
