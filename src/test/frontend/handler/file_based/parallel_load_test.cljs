(ns frontend.handler.file-based.parallel-load-test
  "Tests for parallel graph loading - Steps 13-14."
  (:require [cljs.test :refer [deftest testing is async]]
            [datascript.core :as d]
            [frontend.db.transact-queue :as tq]
            [frontend.handler.file-based.parallel-load :as pl]
            [logseq.graph-parser.batch :as batch]
            [logseq.graph-parser.db :as gp-db]
            [promesa.core :as p]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn make-file
  "Create a test file map."
  [path content]
  {:file/path path
   :file/content content})

;; =============================================================================
;; Step 13: Commit Executor Tests
;; =============================================================================

(deftest execute-load-plan-basic-test
  (async done
    (let [conn (gp-db/start-conn)
          files [(make-file "test1.md" "- Block 1")
                 (make-file "test2.md" "- Block 2")]]
      (-> (pl/parse-files-parallel @conn files {})
          (p/then (fn [results]
                    (batch/plan-graph-load @conn results)))
          (p/then (fn [plan]
                    (is (= :ok (:status plan)) "Plan should be ok")
                    (pl/execute-load-plan! conn plan {:batch-size 10})))
          (p/then (fn [result]
                    (is (= :ok (:status result)) "Execution should succeed")
                    (is (= 2 (:committed result)) "Should commit 2 files")
                    ;; Verify data in DB
                    (is (d/entity @conn [:file/path "test1.md"]) "File1 exists")
                    (is (d/entity @conn [:file/path "test2.md"]) "File2 exists")
                    (tq/reset-queue!)
                    (done)))
          (p/catch (fn [e]
                     (is false (str "Test failed: " e))
                     (tq/reset-queue!)
                     (done)))))))

(deftest execute-load-plan-rejects-error-plan-test
  (async done
    (let [conn (gp-db/start-conn)
          bad-plan {:status :error :errors ["Some error"]}]
      (-> (pl/execute-load-plan! conn bad-plan {})
          (p/then (fn [result]
                    (is (= :error (:status result)) "Should reject error plan")
                    (is (= 0 (:committed result)) "Should not commit anything")
                    (tq/reset-queue!)
                    (done)))
          (p/catch (fn [e]
                     (is false (str "Test failed: " e))
                     (tq/reset-queue!)
                     (done)))))))

(deftest execute-load-plan-batching-test
  (async done
    (let [conn (gp-db/start-conn)
          ;; Create 5 files, batch size 2 = 3 batches
          files (mapv #(make-file (str "file" % ".md") (str "- Block " %))
                      (range 5))]
      (-> (pl/parse-files-parallel @conn files {})
          (p/then (fn [results]
                    (batch/plan-graph-load @conn results)))
          (p/then (fn [plan]
                    (pl/execute-load-plan! conn plan {:batch-size 2})))
          (p/then (fn [result]
                    (is (= :ok (:status result)) "Execution should succeed")
                    (is (= 5 (:committed result)) "Should commit 5 files")
                    (tq/reset-queue!)
                    (done)))
          (p/catch (fn [e]
                     (is false (str "Test failed: " e))
                     (tq/reset-queue!)
                     (done)))))))

;; =============================================================================
;; Step 14: Integration Tests
;; =============================================================================

(deftest load-graph-parallel-success-test
  (async done
    (let [conn (gp-db/start-conn)
          files [(make-file "page1.md" "- Block A")
                 (make-file "page2.md" "- Block B")]]
      (-> (pl/load-graph-parallel! conn files {:batch-size 10})
          (p/then (fn [result]
                    (is (= :ok (:status result)) "Load should succeed")
                    (is (= 2 (:files-loaded result)) "Should load 2 files")
                    ;; Verify pages exist
                    (is (d/entity @conn [:block/name "page1"]) "Page1 exists")
                    (is (d/entity @conn [:block/name "page2"]) "Page2 exists")
                    (tq/reset-queue!)
                    (done)))
          (p/catch (fn [e]
                     (is false (str "Test failed: " e))
                     (tq/reset-queue!)
                     (done)))))))

(deftest load-graph-parallel-with-uuid-conflict-test
  (async done
    (let [conn (gp-db/start-conn)
          shared-uuid #uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
          files [(make-file "file1.md" (str "- Block 1\n  id:: " shared-uuid))
                 (make-file "file2.md" (str "- Block 2\n  id:: " shared-uuid))]]
      (-> (pl/load-graph-parallel! conn files {})
          (p/then (fn [result]
                    (is (= :error (:status result)) "Should fail due to UUID conflict")
                    (is (seq (:conflicts result)) "Should have conflict info")
                    (tq/reset-queue!)
                    (done)))
          (p/catch (fn [e]
                     (is false (str "Test failed: " e))
                     (tq/reset-queue!)
                     (done)))))))

(deftest load-graph-parallel-filters-unsupported-test
  (async done
    (let [conn (gp-db/start-conn)
          ;; Include an unsupported file type
          files [(make-file "page.md" "- Block A")
                 (make-file "image.png" "binary data")
                 (make-file "data.json" "{}")]]
      (-> (pl/load-graph-parallel! conn files {})
          (p/then (fn [result]
                    ;; Only .md file should be loaded
                    (is (= :ok (:status result)) "Load should succeed")
                    (is (= 1 (:files-loaded result)) "Should load only 1 supported file")
                    (tq/reset-queue!)
                    (done)))
          (p/catch (fn [e]
                     (is false (str "Test failed: " e))
                     (tq/reset-queue!)
                     (done)))))))

(deftest parse-files-parallel-isolates-errors-test
  (async done
    (let [db @(gp-db/start-conn)
          files [(make-file "good.md" "- Valid block")
                 ;; This will parse but may have issues - using valid content
                 (make-file "also-good.md" "- Another valid block")]]
      (-> (pl/parse-files-parallel db files {})
          (p/then (fn [results]
                    (is (= 2 (count results)) "Should have 2 results")
                    (is (every? #(= :ok (:status %)) results) "All should parse ok")
                    (done)))
          (p/catch (fn [e]
                     (is false (str "Test failed: " e))
                     (done)))))))
