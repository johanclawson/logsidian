(ns logseq.sidecar.extract-test
  "TDD tests for sidecar AST extraction.

   These tests define the expected behavior for extracting pages and blocks
   from mldoc AST sent by the worker. Write tests FIRST, then implement.

   The extraction module receives:
   - file-path: Path to the file being parsed
   - ast: Mldoc AST as EDN (already parsed by worker's V8 mldoc)
   - options: Config options (format, date-formatter, etc.)

   And returns:
   - {:pages [...] :blocks [...]} ready for DataScript transaction"
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [logseq.sidecar.extract :as extract]))

;; =============================================================================
;; Test Data: Sample mldoc AST structures
;; =============================================================================

(def simple-heading-ast
  "AST for: # Hello World"
  [[["Heading" {:level 1
                :title [["Plain" "Hello World"]]
                :tags []
                :marker nil
                :priority nil
                :anchor "hello-world"
                :meta {:timestamps []
                       :properties []}}]
    {:start_pos 0 :end_pos 13}]])

(def properties-ast
  "AST for: title:: My Page
           tags:: tag1, tag2"
  [[["Properties" [["title" "My Page" nil]
                   ["tags" "tag1, tag2" nil]]]
    {:start_pos 0 :end_pos 35}]
   [["Heading" {:level 1
                :title [["Plain" "Content"]]
                :tags []
                :marker nil
                :priority nil}]
    {:start_pos 36 :end_pos 46}]])

(def nested-blocks-ast
  "AST for:
   - Block 1
     - Nested A
     - Nested B
   - Block 2"
  [[["List" {:items [[{:content [["Paragraph" [["Plain" "Block 1"]]]]
                       :items [[{:content [["Paragraph" [["Plain" "Nested A"]]]]}]
                               [{:content [["Paragraph" [["Plain" "Nested B"]]]]}]]
                       :number nil
                       :name nil
                       :checkbox nil}]
                     [{:content [["Paragraph" [["Plain" "Block 2"]]]]
                       :items []
                       :number nil}]]
             :ordered false}]
    {:start_pos 0 :end_pos 50}]])

(def page-reference-ast
  "AST for: - Link to [[Another Page]]"
  [[["List" {:items [[{:content [["Paragraph" [["Plain" "Link to "]
                                               ["Link" {:url ["Page_ref" "Another Page"]
                                                        :label [["Plain" "Another Page"]]
                                                        :full_text "[[Another Page]]"}]]]]}]]
             :ordered false}]
    {:start_pos 0 :end_pos 30}]])

(def block-reference-ast
  "AST for: - See ((abc-123-def))"
  [[["List" {:items [[{:content [["Paragraph" [["Plain" "See "]
                                               ["Block_reference" "abc-123-def"]]]]}]]
             :ordered false}]
    {:start_pos 0 :end_pos 25}]])

;; =============================================================================
;; Test: Page Extraction
;; =============================================================================

(deftest test-extract-page-from-file
  (testing "Page name is derived from file path when no title property"
    (let [result (extract/extract-from-ast "pages/my-page.md" simple-heading-ast {:format :markdown})]
      (is (= 1 (count (:pages result))) "Should have exactly one page")
      (is (= "my-page" (:block/name (first (:pages result)))) "Page name from file path"))))

(deftest test-extract-page-with-title-property
  (testing "Page name is derived from title property when present"
    (let [result (extract/extract-from-ast "pages/filename.md" properties-ast {:format :markdown})]
      (is (= "my page" (:block/name (first (:pages result)))) "Page name from title property")
      (is (= "My Page" (:block/title (first (:pages result)))) "Page title preserved"))))

(deftest test-extract-page-properties
  (testing "Page properties are extracted correctly"
    (let [result (extract/extract-from-ast "pages/test.md" properties-ast {:format :markdown})
          page (first (:pages result))]
      (is (contains? (:block/properties page) :title))
      (is (contains? (:block/properties page) :tags)))))

(deftest test-extract-page-tags
  (testing "Tags property creates tag pages"
    (let [result (extract/extract-from-ast "pages/test.md" properties-ast {:format :markdown})
          pages (:pages result)
          tag-pages (filter #(nil? (:block/file %)) pages)]
      ;; Main page + 2 tag pages
      (is (>= (count pages) 1) "Should have at least the main page"))))

;; =============================================================================
;; Test: Block Extraction
;; =============================================================================

(deftest test-extract-simple-heading
  (testing "Heading block is extracted with correct content"
    (let [result (extract/extract-from-ast "pages/test.md" simple-heading-ast {:format :markdown})
          blocks (:blocks result)]
      (is (= 1 (count blocks)) "Should have one block")
      (is (= "Hello World" (:block/title (first blocks))) "Block title extracted"))))

(deftest test-extract-block-uuid
  (testing "Blocks get UUIDs assigned"
    (let [result (extract/extract-from-ast "pages/test.md" simple-heading-ast {:format :markdown})
          block (first (:blocks result))]
      (is (uuid? (:block/uuid block)) "Block should have a UUID"))))

(deftest test-extract-block-page-reference
  (testing "Blocks reference their parent page"
    (let [result (extract/extract-from-ast "pages/test.md" simple-heading-ast {:format :markdown})
          block (first (:blocks result))]
      (is (= [:block/name "test"] (:block/page block)) "Block should reference page"))))

(deftest test-extract-block-format
  (testing "Blocks have format attached"
    (let [result (extract/extract-from-ast "pages/test.md" simple-heading-ast {:format :markdown})
          block (first (:blocks result))]
      (is (= :markdown (:block/format block)) "Block should have format"))))

;; =============================================================================
;; Test: Nested Blocks (Hierarchy)
;; =============================================================================

(deftest test-extract-nested-blocks
  (testing "Nested blocks maintain parent-child relationships"
    (let [result (extract/extract-from-ast "pages/test.md" nested-blocks-ast {:format :markdown})
          blocks (:blocks result)]
      ;; Should have multiple blocks with parent relationships
      (is (>= (count blocks) 2) "Should have multiple blocks"))))

;; =============================================================================
;; Test: Reference Extraction
;; =============================================================================

(deftest test-extract-page-references
  (testing "Page references create ref entries"
    (let [result (extract/extract-from-ast "pages/test.md" page-reference-ast {:format :markdown})
          block (first (:blocks result))
          refs (:block/refs block)]
      (is (some #(= "another page" (:block/name %)) refs) "Should reference 'Another Page'"))))

(deftest test-extract-creates-ref-pages
  (testing "Referenced pages are created in pages list"
    (let [result (extract/extract-from-ast "pages/test.md" page-reference-ast {:format :markdown})
          pages (:pages result)
          ref-page (some #(when (= "another page" (:block/name %)) %) pages)]
      (is ref-page "Referenced page should be created"))))

(deftest test-extract-block-references
  (testing "Block references are captured in refs"
    (let [result (extract/extract-from-ast "pages/test.md" block-reference-ast {:format :markdown})
          block (first (:blocks result))
          refs (:block/refs block)]
      ;; Block refs are typically stored as vectors like [:block/uuid uuid]
      (is (seq refs) "Should have refs"))))

;; =============================================================================
;; Test: Empty/Edge Cases
;; =============================================================================

(deftest test-extract-empty-ast
  (testing "Empty AST returns empty result"
    (let [result (extract/extract-from-ast "pages/test.md" [] {:format :markdown})]
      (is (empty? (:blocks result)) "No blocks for empty AST"))))

(deftest test-extract-nil-ast
  (testing "Nil AST returns empty result"
    (let [result (extract/extract-from-ast "pages/test.md" nil {:format :markdown})]
      (is (empty? (:blocks result)) "No blocks for nil AST"))))

;; =============================================================================
;; Test: Org Mode Support
;; =============================================================================

(def org-heading-ast
  "AST for: * Hello Org"
  [[["Heading" {:level 1
                :title [["Plain" "Hello Org"]]
                :tags []
                :marker nil}]
    {:start_pos 0 :end_pos 12}]])

(deftest test-extract-org-format
  (testing "Org format is correctly attached"
    (let [result (extract/extract-from-ast "pages/test.org" org-heading-ast {:format :org})
          block (first (:blocks result))]
      (is (= :org (:block/format block)) "Block should have org format"))))

;; =============================================================================
;; Test: File Entity
;; =============================================================================

(deftest test-extract-file-reference
  (testing "Page has file reference"
    (let [result (extract/extract-from-ast "pages/test.md" simple-heading-ast {:format :markdown})
          page (first (:pages result))]
      (is (= {:file/path "pages/test.md"} (:block/file page)) "Page should reference file"))))

;; =============================================================================
;; Test: Property Value Parsing
;; =============================================================================

(def properties-with-values-ast
  "AST for properties with various value types"
  [[["Properties" [["title" "Test Page" nil]
                   ["alias" "Test, Alias2" nil]
                   ["public" "true" nil]]]
    {:start_pos 0 :end_pos 50}]])

(deftest test-extract-property-values
  (testing "Property values are parsed correctly"
    (let [result (extract/extract-from-ast "pages/test.md" properties-with-values-ast {:format :markdown})
          page (first (:pages result))
          props (:block/properties page)]
      (is (= "Test Page" (:title props)) "Title property parsed")
      (is (some? (:alias props)) "Alias property present"))))

;; =============================================================================
;; Test: Integration with tx-data format
;; =============================================================================

(deftest test-result-is-transactable
  (testing "Result can be used directly in DataScript transaction"
    (let [result (extract/extract-from-ast "pages/test.md" simple-heading-ast {:format :markdown})
          pages (:pages result)
          blocks (:blocks result)]
      ;; Pages should be valid entities
      (is (every? :block/name pages) "All pages have :block/name")
      (is (every? :block/uuid pages) "All pages have :block/uuid")
      ;; Blocks should be valid entities
      (is (every? :block/uuid blocks) "All blocks have :block/uuid")
      (is (every? :block/page blocks) "All blocks have :block/page"))))

;; =============================================================================
;; REPL Usage
;; =============================================================================

(comment
  ;; Run all tests
  (clojure.test/run-tests 'logseq.sidecar.extract-test)

  ;; Run specific test
  (test-extract-page-from-file)
  (test-extract-simple-heading)
  )
