(ns logseq.graph-parser.batch-test
  "Tests for batch utilities - conflict detection and validation."
  (:require [cljs.test :refer [deftest testing is]]
            [datascript.core :as d]
            [logseq.graph-parser :as graph-parser]
            [logseq.graph-parser.batch :as batch]
            [logseq.graph-parser.db :as gp-db]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- pad-left
  [s n c]
  (let [s (str s)]
    (if (>= (count s) n)
      s
      (str (apply str (repeat (- n (count s)) c)) s))))

(defn make-uuid-generator
  []
  (let [counter (atom 0)]
    (fn []
      (let [n (swap! counter inc)]
        (uuid (str "00000000-0000-0000-0000-" (pad-left n 12 "0")))))))

(defn make-now-generator
  []
  (let [base-time (js/Date. "2024-01-01T00:00:00Z")
        counter (atom 0)]
    (fn []
      (let [n (swap! counter inc)]
        (js/Date. (+ (.getTime base-time) (* n 1000)))))))

(defn parse-file
  "Helper to parse a file with deterministic UUIDs.
   Can optionally take shared uuid-fn and now-fn for cross-file determinism."
  ([db file-path content]
   (parse-file db file-path content (make-uuid-generator) (make-now-generator)))
  ([db file-path content uuid-fn now-fn]
   (graph-parser/parse-file-data
    file-path content
    {:db db
     :uuid-fn uuid-fn
     :now-fn now-fn
     :extract-options {:verbose false}})))

;; =============================================================================
;; Step 4: Collectors Tests
;; =============================================================================

(deftest ^:parallel collect-uuids-test
  (testing "collect-uuids returns UUIDs from pages and blocks"
    (let [db @(gp-db/start-conn)
          parsed (parse-file db "test.md" "- Block 1\n- Block 2")
          uuids (batch/collect-uuids parsed)]
      (is (vector? uuids) "Returns a vector (preserves duplicates)")
      (is (pos? (count uuids)) "Has some UUIDs")
      (is (every? :uuid uuids) "Each entry has :uuid")
      (is (every? :source uuids) "Each entry has :source")
      (is (every? :file-path uuids) "Each entry has :file-path"))))

(deftest ^:parallel collect-page-titles-test
  (testing "collect-page-titles returns page info with canonical names"
    (let [db @(gp-db/start-conn)
          parsed (parse-file db "Test Page.md" "- Block with [[Other Page]]")
          titles (batch/collect-page-titles parsed)]
      (is (seq titles) "Returns some titles")
      (is (every? :canonical titles) "Each entry has :canonical")
      (is (every? :file-path titles) "Each entry has :file-path")
      (is (every? :source titles) "Each entry has :source (:primary or :ref)"))))

(deftest ^:parallel collect-page-titles-marks-primary-page
  (testing "collect-page-titles correctly marks primary vs ref pages"
    (let [db @(gp-db/start-conn)
          parsed (parse-file db "test.md" "- Block with [[ref page]]")
          titles (batch/collect-page-titles parsed)
          primary-titles (filter #(= :primary (:source %)) titles)
          ref-titles (filter #(= :ref (:source %)) titles)]
      (is (= 1 (count primary-titles)) "One primary page")
      (is (= "test" (:canonical (first primary-titles))) "Primary is the file page"))))

;; =============================================================================
;; Step 5: UUID Conflict Detection Tests
;; =============================================================================

(deftest ^:parallel detect-uuid-conflicts-no-conflicts
  (testing "detect-uuid-conflicts returns ok when no conflicts"
    (let [db @(gp-db/start-conn)
          uuid-fn (make-uuid-generator)
          now-fn (make-now-generator)
          parsed1 (parse-file db "file1.md" "- Block 1" uuid-fn now-fn)
          parsed2 (parse-file db "file2.md" "- Block 2" uuid-fn now-fn)
          result (batch/detect-uuid-conflicts db [parsed1 parsed2])]
      (is (:ok result) "Should be ok")
      (is (nil? (:conflicts result)) "No conflicts"))))

(deftest ^:parallel detect-uuid-conflicts-cross-file
  (testing "detect-uuid-conflicts finds cross-file UUID duplicates"
    (let [db @(gp-db/start-conn)
          shared-uuid #uuid "12345678-1234-1234-1234-123456789abc"
          ;; Both files have blocks with the same hardcoded UUID
          content1 (str "- Block 1\n  id:: " shared-uuid)
          content2 (str "- Block 2\n  id:: " shared-uuid)
          parsed1 (parse-file db "file1.md" content1)
          parsed2 (parse-file db "file2.md" content2)
          result (batch/detect-uuid-conflicts db [parsed1 parsed2])]
      (is (not (:ok result)) "Should have conflicts")
      (is (seq (:conflicts result)) "Has conflict entries")
      (is (= shared-uuid (:uuid (first (:conflicts result))))
          "Conflict is for the shared UUID"))))

(deftest ^:parallel detect-uuid-conflicts-with-db
  (testing "detect-uuid-conflicts finds collisions with existing DB"
    (let [conn (gp-db/start-conn)
          existing-uuid #uuid "eeeeeeee-0000-0000-0000-000000000001"
          ;; Pre-populate DB with a block
          _ (d/transact! conn [{:block/uuid existing-uuid
                                :block/name "existing-page"
                                :block/title "Existing Page"}])
          db @conn
          ;; Parse a file with the same UUID
          content (str "- New block\n  id:: " existing-uuid)
          parsed (parse-file db "new.md" content)
          result (batch/detect-uuid-conflicts db [parsed])]
      (is (not (:ok result)) "Should detect DB collision")
      (is (some #(= :db-collision (:conflict-type %)) (:conflicts result))
          "Conflict type is db-collision"))))

;; =============================================================================
;; Step 6: Validation Tests
;; =============================================================================

(deftest ^:parallel validate-no-uuid-conflicts-ok
  (testing "validate-no-uuid-conflicts! returns ok result when no conflicts"
    (let [db @(gp-db/start-conn)
          uuid-fn (make-uuid-generator)
          now-fn (make-now-generator)
          parsed1 (parse-file db "file1.md" "- Block 1" uuid-fn now-fn)
          parsed2 (parse-file db "file2.md" "- Block 2" uuid-fn now-fn)
          result (batch/validate-no-uuid-conflicts! db [parsed1 parsed2])]
      (is (:ok result) "Should be ok")
      (is (= [parsed1 parsed2] (:parsed-files result)) "Returns parsed files"))))

(deftest ^:parallel validate-no-uuid-conflicts-errors
  (testing "validate-no-uuid-conflicts! returns errors when conflicts exist"
    (let [db @(gp-db/start-conn)
          shared-uuid #uuid "12345678-1234-1234-1234-123456789abc"
          content1 (str "- Block 1\n  id:: " shared-uuid)
          content2 (str "- Block 2\n  id:: " shared-uuid)
          parsed1 (parse-file db "file1.md" content1)
          parsed2 (parse-file db "file2.md" content2)
          result (batch/validate-no-uuid-conflicts! db [parsed1 parsed2])]
      (is (not (:ok result)) "Should not be ok")
      (is (seq (:errors result)) "Has error entries")
      (is (every? :message (:errors result)) "Each error has a message")
      (is (every? #(= :uuid-conflict (:type %)) (:errors result))
          "Error type is uuid-conflict"))))

;; =============================================================================
;; Step 7: Page Conflict Detection Tests
;; =============================================================================

(deftest ^:parallel detect-page-conflicts-no-conflicts
  (testing "detect-page-conflicts returns ok when no conflicts"
    (let [db @(gp-db/start-conn)
          uuid-fn (make-uuid-generator)
          now-fn (make-now-generator)
          parsed1 (parse-file db "file1.md" "- Block 1" uuid-fn now-fn)
          parsed2 (parse-file db "file2.md" "- Block 2" uuid-fn now-fn)
          result (batch/detect-page-conflicts db [parsed1 parsed2])]
      (is (:ok result) "Should be ok"))))

(deftest ^:parallel detect-page-conflicts-case-insensitive
  (testing "detect-page-conflicts finds case-insensitive page name conflicts"
    (let [db @(gp-db/start-conn)
          ;; Two files that would create pages with same canonical name
          ;; Note: In Logseq, page name comes from filename, so "Foo.md" and "foo.md"
          ;; would conflict. We simulate this by using title property.
          content1 "title:: Foo\n- Block 1"
          content2 "title:: FOO\n- Block 2"
          parsed1 (parse-file db "file1.md" content1)
          parsed2 (parse-file db "file2.md" content2)
          result (batch/detect-page-conflicts db [parsed1 parsed2])]
      ;; Note: The pages have same canonical name "foo" but different file paths
      ;; This is a conflict because both are primary pages
      (is (not (:ok result)) "Should have conflicts")
      (is (seq (:conflicts result)) "Has conflict entries"))))

(deftest ^:parallel detect-page-conflicts-with-db
  (testing "detect-page-conflicts finds collisions with existing DB pages"
    (let [conn (gp-db/start-conn)
          ;; Pre-populate DB with a page
          _ (d/transact! conn [{:block/uuid (d/squuid)
                                :block/name "existing"
                                :block/title "Existing"}])
          db @conn
          ;; Parse a file that creates a page with same name
          content "title:: Existing\n- Block 1"
          parsed (parse-file db "new.md" content)
          result (batch/detect-page-conflicts db [parsed])]
      (is (not (:ok result)) "Should detect DB collision")
      (is (some #(= :db-collision (:conflict-type %)) (:conflicts result))
          "Conflict type is db-collision"))))

(deftest ^:parallel validate-no-page-conflicts-errors
  (testing "validate-no-page-conflicts! returns errors with actionable messages"
    (let [db @(gp-db/start-conn)
          content1 "title:: Foo\n- Block 1"
          content2 "title:: FOO\n- Block 2"
          parsed1 (parse-file db "file1.md" content1)
          parsed2 (parse-file db "file2.md" content2)
          result (batch/validate-no-page-conflicts! db [parsed1 parsed2])]
      (is (not (:ok result)) "Should not be ok")
      (is (seq (:errors result)) "Has error entries")
      (is (every? :message (:errors result)) "Each error has a message")
      (is (every? #(= :page-conflict (:type %)) (:errors result))
          "Error type is page-conflict"))))

;; =============================================================================
;; Combined Validation Tests
;; =============================================================================

(deftest ^:parallel validate-batch-all-ok
  (testing "validate-batch returns ok when all validations pass"
    (let [db @(gp-db/start-conn)
          uuid-fn (make-uuid-generator)
          now-fn (make-now-generator)
          parsed1 (parse-file db "file1.md" "- Block 1" uuid-fn now-fn)
          parsed2 (parse-file db "file2.md" "- Block 2" uuid-fn now-fn)
          result (batch/validate-batch db [parsed1 parsed2])]
      (is (:ok result) "Should be ok")
      (is (= [parsed1 parsed2] (:parsed-files result)) "Returns parsed files"))))

(deftest ^:parallel validate-batch-collects-all-errors
  (testing "validate-batch collects errors from all validators"
    (let [db @(gp-db/start-conn)
          shared-uuid #uuid "12345678-1234-1234-1234-123456789abc"
          ;; File with UUID conflict AND page name conflict
          content1 (str "title:: Foo\n- Block 1\n  id:: " shared-uuid)
          content2 (str "title:: FOO\n- Block 2\n  id:: " shared-uuid)
          parsed1 (parse-file db "file1.md" content1)
          parsed2 (parse-file db "file2.md" content2)
          result (batch/validate-batch db [parsed1 parsed2])]
      (is (not (:ok result)) "Should not be ok")
      (is (>= (count (:errors result)) 2) "Has multiple errors")
      (is (some #(= :uuid-conflict (:type %)) (:errors result))
          "Has UUID conflict error")
      (is (some #(= :page-conflict (:type %)) (:errors result))
          "Has page conflict error"))))

;; =============================================================================
;; Step 8: Transaction Builder Tests
;; =============================================================================

(deftest ^:parallel build-file-tx-produces-valid-tx
  (testing "build-file-tx returns transaction vector"
    (let [db @(gp-db/start-conn)
          parsed (parse-file db "test.md" "- Block 1\n- Block 2")
          tx (batch/build-file-tx parsed 0)]
      (is (vector? tx) "Returns a vector")
      (is (pos? (count tx)) "Has tx operations")
      ;; Should contain file-path assertion
      (is (some #(= {:file/path "test.md"} %) tx) "Contains file path")
      ;; Should contain block UUIDs for upserting
      (is (some #(and (map? %) (:block/uuid %)) tx) "Contains block UUIDs"))))

(deftest ^:parallel build-file-tx-can-be-transacted
  (testing "build-file-tx output can be transacted to DataScript"
    (let [conn (gp-db/start-conn)
          db @conn
          parsed (parse-file db "test.md" "- Block 1\n- Block 2")
          tx (batch/build-file-tx parsed 0)]
      ;; Should be able to transact without error
      (is (d/transact! conn tx) "Transaction succeeds")
      ;; Verify data is in DB
      (is (d/entity @conn [:file/path "test.md"]) "File entity exists"))))

;; =============================================================================
;; Step 9: Batch Transaction Composition Tests
;; =============================================================================

(deftest ^:parallel build-batch-tx-combines-files
  (testing "build-batch-tx combines multiple parsed files"
    (let [db @(gp-db/start-conn)
          uuid-fn (make-uuid-generator)
          now-fn (make-now-generator)
          parsed1 (parse-file db "file1.md" "- Block A" uuid-fn now-fn)
          parsed2 (parse-file db "file2.md" "- Block B" uuid-fn now-fn)
          tx (batch/build-batch-tx [parsed1 parsed2] [])]
      (is (vector? tx) "Returns a vector")
      ;; Should contain both file paths
      (is (some #(= {:file/path "file1.md"} %) tx) "Contains file1 path")
      (is (some #(= {:file/path "file2.md"} %) tx) "Contains file2 path"))))

(deftest ^:parallel build-batch-tx-deduplicates-pages
  (testing "build-batch-tx deduplicates pages referenced from multiple files"
    (let [db @(gp-db/start-conn)
          uuid-fn (make-uuid-generator)
          now-fn (make-now-generator)
          ;; Both files reference "shared page"
          parsed1 (parse-file db "file1.md" "- Block with [[Shared Page]]" uuid-fn now-fn)
          parsed2 (parse-file db "file2.md" "- Another block with [[Shared Page]]" uuid-fn now-fn)
          tx (batch/build-batch-tx [parsed1 parsed2] [])
          ;; Count page name assertions for "shared page"
          page-name-assertions (filter #(= {:block/name "shared page"} %) tx)]
      ;; Should only have one assertion per unique page name
      (is (= 1 (count page-name-assertions))
          "Shared page is deduplicated"))))

(deftest ^:parallel build-batch-tx-can-be-transacted
  (testing "build-batch-tx output can be transacted to DataScript"
    (let [conn (gp-db/start-conn)
          db @conn
          uuid-fn (make-uuid-generator)
          now-fn (make-now-generator)
          parsed1 (parse-file db "file1.md" "- Block A" uuid-fn now-fn)
          parsed2 (parse-file db "file2.md" "- Block B" uuid-fn now-fn)
          tx (batch/build-batch-tx [parsed1 parsed2] [])]
      ;; Should be able to transact without error
      (is (d/transact! conn tx) "Batch transaction succeeds")
      ;; Verify both files are in DB
      (is (d/entity @conn [:file/path "file1.md"]) "File1 exists")
      (is (d/entity @conn [:file/path "file2.md"]) "File2 exists")
      ;; Verify pages exist
      (is (d/entity @conn [:block/name "file1"]) "Page file1 exists")
      (is (d/entity @conn [:block/name "file2"]) "Page file2 exists"))))

(deftest ^:parallel build-batch-tx-equivalent-to-sequential
  (testing "batch transact yields same entity count as sequential transacts"
    (let [;; Sequential transacts
          conn1 (gp-db/start-conn)
          uuid-fn1 (make-uuid-generator)
          now-fn1 (make-now-generator)
          parsed1a (parse-file @conn1 "file1.md" "- Block A" uuid-fn1 now-fn1)
          parsed1b (parse-file @conn1 "file2.md" "- Block B" uuid-fn1 now-fn1)
          _ (d/transact! conn1 (batch/build-file-tx parsed1a 0))
          _ (d/transact! conn1 (batch/build-file-tx parsed1b 1))
          count-seq (count (d/datoms @conn1 :eavt))

          ;; Batch transact (same content, fresh generators)
          conn2 (gp-db/start-conn)
          uuid-fn2 (make-uuid-generator)
          now-fn2 (make-now-generator)
          parsed2a (parse-file @conn2 "file1.md" "- Block A" uuid-fn2 now-fn2)
          parsed2b (parse-file @conn2 "file2.md" "- Block B" uuid-fn2 now-fn2)
          _ (d/transact! conn2 (batch/build-batch-tx [parsed2a parsed2b] []))
          count-batch (count (d/datoms @conn2 :eavt))]
      ;; Entity counts should be the same (or very close due to ordering differences)
      (is (= count-seq count-batch)
          (str "Sequential (" count-seq ") should equal batch (" count-batch ")")))))

;; =============================================================================
;; Step 11: Error Isolation Tests
;; =============================================================================

(deftest ^:parallel wrap-parse-result-test
  (testing "wrap-parse-result creates ok status wrapper"
    (let [result {:pages [] :blocks []}
          wrapped (batch/wrap-parse-result "test.md" result)]
      (is (= :ok (:status wrapped)))
      (is (= "test.md" (:file-path wrapped)))
      (is (= result (:result wrapped))))))

(deftest ^:parallel wrap-parse-error-test
  (testing "wrap-parse-error creates error status wrapper"
    (let [error (ex-info "Parse failed" {:reason :syntax})
          wrapped (batch/wrap-parse-error "bad.md" error)]
      (is (= :error (:status wrapped)))
      (is (= "bad.md" (:file-path wrapped)))
      (is (= error (:error wrapped)))
      (is (= "Parse failed" (:message wrapped))))))

(deftest ^:parallel partition-parse-results-test
  (testing "partition-parse-results separates ok and error results"
    (let [ok1 (batch/wrap-parse-result "a.md" {:pages []})
          ok2 (batch/wrap-parse-result "b.md" {:pages []})
          err1 (batch/wrap-parse-error "c.md" (ex-info "Bad" {}))
          {:keys [ok errors]} (batch/partition-parse-results [ok1 err1 ok2])]
      (is (= 2 (count ok)) "Two ok results")
      (is (= 1 (count errors)) "One error result"))))

;; =============================================================================
;; Step 12: Load Plan Tests
;; =============================================================================

(deftest ^:parallel plan-graph-load-all-ok
  (testing "plan-graph-load returns ok when all files parse successfully"
    (let [db @(gp-db/start-conn)
          uuid-fn (make-uuid-generator)
          now-fn (make-now-generator)
          parsed1 (parse-file db "file1.md" "- Block A" uuid-fn now-fn)
          parsed2 (parse-file db "file2.md" "- Block B" uuid-fn now-fn)
          results [(batch/wrap-parse-result "file1.md" parsed1)
                   (batch/wrap-parse-result "file2.md" parsed2)]
          plan (batch/plan-graph-load db results)]
      (is (= :ok (:status plan)) "Status should be ok")
      (is (= 2 (:batch-count plan)) "Should have 2 files")
      (is (= 2 (count (:parsed-files plan))) "Should return parsed files"))))

(deftest ^:parallel plan-graph-load-with-parse-errors
  (testing "plan-graph-load reports parse errors"
    (let [db @(gp-db/start-conn)
          uuid-fn (make-uuid-generator)
          now-fn (make-now-generator)
          parsed1 (parse-file db "good.md" "- Block A" uuid-fn now-fn)
          results [(batch/wrap-parse-result "good.md" parsed1)
                   (batch/wrap-parse-error "bad.md" (ex-info "Syntax error" {}))]
          plan (batch/plan-graph-load db results)]
      (is (= :error (:status plan)) "Status should be error")
      (is (= 1 (count (:parse-errors plan))) "Should have 1 parse error")
      (is (= "bad.md" (:file-path (first (:parse-errors plan))))))))

(deftest ^:parallel plan-graph-load-with-conflicts
  (testing "plan-graph-load reports conflicts"
    (let [db @(gp-db/start-conn)
          shared-uuid #uuid "12345678-1234-1234-1234-123456789abc"
          content1 (str "- Block 1\n  id:: " shared-uuid)
          content2 (str "- Block 2\n  id:: " shared-uuid)
          parsed1 (parse-file db "file1.md" content1)
          parsed2 (parse-file db "file2.md" content2)
          results [(batch/wrap-parse-result "file1.md" parsed1)
                   (batch/wrap-parse-result "file2.md" parsed2)]
          plan (batch/plan-graph-load db results)]
      (is (= :error (:status plan)) "Status should be error due to UUID conflict")
      (is (seq (:conflicts plan)) "Should have conflicts")
      (is (empty? (:parse-errors plan)) "No parse errors"))))

(deftest ^:parallel create-batches-test
  (testing "create-batches splits files into groups"
    (let [files (range 10)
          batches (batch/create-batches files 3)]
      (is (= 4 (count batches)) "Should have 4 batches")
      (is (= [0 1 2] (vec (first batches))) "First batch has 3 items")
      (is (= [9] (vec (last batches))) "Last batch has remainder"))))

;; =============================================================================
;; Codex Review: Edge Case Tests
;; =============================================================================

(deftest ^:parallel parsed-files-refs-always-empty-test
  (testing "refs field from parsed files is always empty (extract doesn't return refs)"
    ;; This test documents that the :refs field in parsed files is always empty.
    ;; The extract/extract function returns {:pages :blocks :ast} without :refs,
    ;; so parse-file-data defaults :refs to [].
    ;; This means all-refs in build-batch-tx is always empty and can be removed.
    (let [db @(gp-db/start-conn)
          uuid-fn (make-uuid-generator)
          now-fn (make-now-generator)
          ;; Parse a file with various types of content
          parsed (parse-file db "test.md"
                             "- Block with [[Page Link]]\n- Another block"
                             uuid-fn now-fn)]
      ;; The :refs field should be empty (it defaults to [] in parse-file-data)
      (is (= [] (:refs parsed)) ":refs should be empty vector")
      ;; Pages and blocks should NOT be empty (to verify parsing worked)
      (is (seq (:pages parsed)) ":pages should not be empty")
      (is (seq (:blocks parsed)) ":blocks should not be empty"))))

(deftest ^:parallel dedupe-pages-stub-before-primary-test
  (testing "when stub page appears before primary page in file order"
    ;; This test documents the current behavior of page deduplication.
    ;; Scenario:
    ;; - File 1 (page-b.md): Links to [[target page]], creating a stub/ref page
    ;; - File 2 (target page.md): IS the actual "target page" with content
    ;;
    ;; The concern: If page-b.md is processed first, will the stub page
    ;; override the real "target page" from its own file?
    ;;
    ;; Current behavior: "First file wins" - dedupe-pages-by-name keeps
    ;; the first occurrence. Within each file, primary pages come before
    ;; ref pages in the :pages list.
    (let [db @(gp-db/start-conn)
          uuid-fn (make-uuid-generator)
          now-fn (make-now-generator)
          ;; File 1: References "target page" (creates stub)
          ;; This file's primary page is "page-b", but it also creates
          ;; a ref/stub page for "target page"
          parsed-b (parse-file db "pages/page-b.md"
                               "- Block in Page B\n- Links to [[target page]]"
                               uuid-fn now-fn)
          ;; File 2: IS "target page" (primary) - filename matches the page name
          ;; The primary page here is "target page" with actual content
          parsed-target (parse-file db "pages/target page.md"
                                    "- Block in target page\n- This is the real content"
                                    uuid-fn now-fn)
          ;; Process in order: page-b first (creates stub), then target page (real)
          tx (batch/build-batch-tx [parsed-b parsed-target] [])
          ;; Transact and verify
          conn (gp-db/start-conn)
          _ (d/transact! conn tx)
          target-entity (d/entity @conn [:block/name "target page"])]
      ;; The page should exist
      (is (some? target-entity) "target page should exist")
      ;; The page should have blocks from its own file
      ;; This verifies the primary page data was preserved
      (let [blocks-query (d/q '[:find ?b
                                :where
                                [?b :block/page ?p]
                                [?p :block/name "target page"]]
                              @conn)]
        (is (seq blocks-query)
            "target page should have blocks (not just be a stub)")))))
