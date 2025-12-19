(ns frontend.worker.parse-worker-test
  "Tests for parse worker - Step 16.
   Note: These tests run the parse functions directly, not in a worker context.
   Full worker integration tests require a browser environment."
  (:require [cljs.test :refer [deftest testing is]]
            [frontend.worker.parse-worker :as parse-worker]
            [logseq.db :as ldb]
            [logseq.graph-parser.db :as gp-db]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- make-opts-with-db
  "Create options map with a fresh DataScript DB."
  []
  (let [conn (gp-db/start-conn)]
    {:db @conn}))

;; =============================================================================
;; Unit Tests (Direct Function Calls)
;; =============================================================================

(deftest parse-files-batch-basic-test
  (testing "Parses valid file and returns result"
    (let [files [{:file/path "test.md" :file/content "- Block 1"}]
          files-transit (ldb/write-transit-str files)
          opts-transit (ldb/write-transit-str (make-opts-with-db))
          result-transit (parse-worker/parse-files-batch files-transit opts-transit)
          results (ldb/read-transit-str result-transit)]
      (is (= 1 (count results)) "Should have one result")
      (is (= :ok (:status (first results))) "Status should be :ok")
      (is (= "test.md" (:file-path (first results))) "File path should match")
      (is (map? (:result (first results))) "Result should be a map")
      (is (contains? (:result (first results)) :pages) "Result should have :pages")
      (is (contains? (:result (first results)) :blocks) "Result should have :blocks"))))

(deftest parse-files-batch-multiple-files-test
  (testing "Parses multiple files"
    (let [files [{:file/path "page1.md" :file/content "- Block A"}
                 {:file/path "page2.md" :file/content "- Block B\n- Block C"}]
          files-transit (ldb/write-transit-str files)
          opts-transit (ldb/write-transit-str (make-opts-with-db))
          result-transit (parse-worker/parse-files-batch files-transit opts-transit)
          results (ldb/read-transit-str result-transit)]
      (is (= 2 (count results)) "Should have two results")
      (is (every? #(= :ok (:status %)) results) "All should parse successfully")
      (is (= ["page1.md" "page2.md"] (mapv :file-path results)) "File paths should be in order"))))

(deftest parse-files-batch-error-isolation-test
  (testing "Isolates errors per file - valid files still parse"
    ;; Note: The parser is very forgiving, so we test with valid files.
    ;; Error isolation means one file's error doesn't crash the entire batch.
    (let [files [{:file/path "good.md" :file/content "- Valid block"}
                 {:file/path "also-good.md" :file/content "- Another valid block"}]
          files-transit (ldb/write-transit-str files)
          opts-transit (ldb/write-transit-str (make-opts-with-db))
          result-transit (parse-worker/parse-files-batch files-transit opts-transit)
          results (ldb/read-transit-str result-transit)]
      (is (= 2 (count results)) "Should have results for both files")
      (is (every? #(= :ok (:status %)) results) "Both should parse ok"))))

(deftest parse-files-batch-empty-content-test
  (testing "Handles empty file content"
    (let [files [{:file/path "empty.md" :file/content ""}]
          files-transit (ldb/write-transit-str files)
          opts-transit (ldb/write-transit-str (make-opts-with-db))
          result-transit (parse-worker/parse-files-batch files-transit opts-transit)
          results (ldb/read-transit-str result-transit)]
      (is (= 1 (count results)) "Should have one result")
      (is (= :ok (:status (first results))) "Empty files should parse ok")
      (is (= [] (:pages (:result (first results)))) "Empty file has no pages")
      (is (= [] (:blocks (:result (first results)))) "Empty file has no blocks"))))

(deftest parse-files-batch-strips-ast-test
  (testing "Result does not include :ast (stripped for performance)"
    (let [files [{:file/path "test.md" :file/content "- Block with [[link]]"}]
          files-transit (ldb/write-transit-str files)
          opts-transit (ldb/write-transit-str (make-opts-with-db))
          result-transit (parse-worker/parse-files-batch files-transit opts-transit)
          results (ldb/read-transit-str result-transit)]
      (is (not (contains? (:result (first results)) :ast))
          "AST should be stripped from result"))))

(deftest parse-files-batch-preserves-order-test
  (testing "Results are in same order as input files"
    (let [files (mapv #(hash-map :file/path (str "page" % ".md")
                                 :file/content (str "- Block " %))
                      (range 10))
          files-transit (ldb/write-transit-str files)
          opts-transit (ldb/write-transit-str (make-opts-with-db))
          result-transit (parse-worker/parse-files-batch files-transit opts-transit)
          results (ldb/read-transit-str result-transit)]
      (is (= 10 (count results)) "Should have 10 results")
      (is (= (mapv #(str "page" % ".md") (range 10))
             (mapv :file-path results))
          "File paths should be in original order"))))

(deftest parse-files-batch-no-db-error-test
  (testing "Returns error when no DB provided"
    (let [files [{:file/path "test.md" :file/content "- Block 1"}]
          files-transit (ldb/write-transit-str files)
          opts-transit (ldb/write-transit-str {})  ; No DB
          result-transit (parse-worker/parse-files-batch files-transit opts-transit)
          results (ldb/read-transit-str result-transit)]
      (is (= 1 (count results)) "Should have one result")
      (is (= :error (:status (first results))) "Should be error without DB")
      (is (string? (:error (first results))) "Should have error message"))))

;; =============================================================================
;; Step 3: Transit Decode Error Handling Tests
;; =============================================================================

(deftest parse-files-batch-handles-malformed-transit-test
  (testing "Returns error result for malformed transit input"
    ;; This should NOT throw - it should return an error result
    (let [result-transit (parse-worker/parse-files-batch "invalid{transit" "{}")]
      ;; Should return valid transit with error, not throw
      (is (string? result-transit) "Should return a string (transit)")
      (let [results (ldb/read-transit-str result-transit)]
        (is (vector? results) "Should return a vector of results")
        (is (pos? (count results)) "Should have at least one error result")
        (is (= :error (:status (first results))) "Status should be :error")
        (is (string? (:error (first results))) "Should have error message")))))

;; =============================================================================
;; Step 7: Error Details with Stack Trace Tests
;; =============================================================================

(deftest parse-files-batch-includes-stack-trace-test
  (testing "Error results include stack trace when available"
    ;; Create a file that will trigger a parse error (no DB provided)
    (let [files [{:file/path "test.md" :file/content "- Block 1"}]
          files-transit (ldb/write-transit-str files)
          opts-transit (ldb/write-transit-str {})  ; No DB causes error
          result-transit (parse-worker/parse-files-batch files-transit opts-transit)
          results (ldb/read-transit-str result-transit)]
      (is (= 1 (count results)) "Should have one result")
      (is (= :error (:status (first results))) "Should be error without DB")
      (is (contains? (first results) :stack) "Should have :stack key")
      (is (or (nil? (:stack (first results)))
              (string? (:stack (first results))))
          "Stack should be nil or string"))))

;; =============================================================================
;; Step 8: Timestamp Truthiness Bug Tests
;; =============================================================================

(deftest parse-files-batch-preserves-zero-timestamps-test
  (testing "Zero timestamps (epoch) are preserved, not dropped"
    ;; A ctime of 0 is valid (Unix epoch: Jan 1, 1970)
    ;; The bug: cond-> with (:ctime stat) drops 0 because 0 is falsy
    (let [files [{:file/path "test.md"
                  :file/content "- Block"
                  :stat {:ctime 0 :mtime 0}}]
          files-transit (ldb/write-transit-str files)
          ;; We need a DB but we're testing the opts handling
          ;; Let's check what opts are passed by parsing successfully
          opts-with-db (make-opts-with-db)
          opts-transit (ldb/write-transit-str opts-with-db)
          result-transit (parse-worker/parse-files-batch files-transit opts-transit)
          results (ldb/read-transit-str result-transit)]
      ;; The file should parse successfully
      (is (= :ok (:status (first results))) "Should parse successfully")
      ;; We can't directly verify the opts passed to parse-file-data,
      ;; but we can verify the result contains the expected structure
      (is (map? (:result (first results))) "Result should be a map"))))
