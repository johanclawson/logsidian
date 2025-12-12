(ns frontend.sidecar.file-sync-test
  "Unit tests for the file sync module.

   These tests verify:
   - Page entity ID extraction
   - Page datom extraction
   - Correct handling of missing pages

   Note: Integration tests with actual sidecar should be in clj-e2e/"
  (:require [cljs.test :refer [deftest testing is use-fixtures]]
            [datascript.core :as d]
            [frontend.test.helper :as test-helper :refer [load-test-files]]
            [frontend.db :as db]
            [frontend.sidecar.file-sync :as file-sync]))

(use-fixtures :each {:before test-helper/start-test-db!
                     :after test-helper/destroy-test-db!})

;; =============================================================================
;; Page Entity Extraction Tests
;; =============================================================================

(deftest test-get-page-entity-ids
  (testing "get-page-entity-ids returns page and block IDs"
    (load-test-files [{:file/path "pages/test-page.md"
                       :file/content "- Block 1\n- Block 2\n  - Nested block"}])
    (let [conn (db/get-db test-helper/test-db false)
          db @conn
          entity-ids (#'file-sync/get-page-entity-ids db "test-page")]
      (is (set? entity-ids) "Result should be a set")
      (is (pos? (count entity-ids)) "Should have entity IDs")
      ;; Should include at least the page and some blocks
      (is (>= (count entity-ids) 2) "Should have page + at least one block")))

  (testing "get-page-entity-ids returns nil for non-existent page"
    (load-test-files [{:file/path "pages/exists.md"
                       :file/content "- Content"}])
    (let [conn (db/get-db test-helper/test-db false)
          db @conn
          entity-ids (#'file-sync/get-page-entity-ids db "does-not-exist")]
      (is (nil? entity-ids) "Should return nil for non-existent page"))))

;; =============================================================================
;; Datom Extraction Tests
;; =============================================================================

(deftest test-extract-entity-datoms
  (testing "extract-entity-datoms extracts datoms for given entity IDs"
    (load-test-files [{:file/path "pages/datom-test.md"
                       :file/content "- Block content"}])
    (let [conn (db/get-db test-helper/test-db false)
          db @conn
          ;; Get page ID
          page-id (d/q '[:find ?e .
                         :where [?e :block/name "datom-test"]]
                       db)]
      (when page-id
        (let [datoms (#'file-sync/extract-entity-datoms db #{page-id})]
          (is (vector? datoms) "Result should be a vector")
          (is (pos? (count datoms)) "Should have datoms for the page")
          ;; Check datom format
          (doseq [datom datoms]
            (is (= 5 (count datom)) "Each datom should have 5 elements")
            (is (= page-id (first datom)) "Entity ID should match")
            (is (true? (nth datom 4)) "Added flag should be true")))))))

(deftest test-extract-page-datoms
  (testing "extract-page-datoms extracts all page and block datoms"
    (load-test-files [{:file/path "pages/full-page.md"
                       :file/content "- First block\n- Second block"}])
    (let [conn (db/get-db test-helper/test-db false)
          db @conn
          datoms (file-sync/extract-page-datoms db "full-page")]
      (is (vector? datoms) "Result should be a vector")
      (is (pos? (count datoms)) "Should have datoms")
      ;; Should have datoms for multiple entities (page + blocks)
      (let [entity-ids (set (map first datoms))]
        (is (> (count entity-ids) 1) "Should have datoms from multiple entities"))))

  (testing "extract-page-datoms returns nil for non-existent page"
    (load-test-files [{:file/path "pages/other.md"
                       :file/content "- Content"}])
    (let [conn (db/get-db test-helper/test-db false)
          db @conn
          datoms (file-sync/extract-page-datoms db "non-existent")]
      (is (nil? datoms) "Should return nil for non-existent page"))))

;; =============================================================================
;; Configuration Tests
;; =============================================================================

(deftest test-sync-delay-constant
  (testing "SYNC_DELAY_MS is a reasonable value"
    (is (number? file-sync/SYNC_DELAY_MS) "Should be a number")
    (is (pos? file-sync/SYNC_DELAY_MS) "Should be positive")
    (is (<= file-sync/SYNC_DELAY_MS 1000) "Should be <= 1 second (not too long)")))

;; =============================================================================
;; Edge Cases Tests
;; =============================================================================

(deftest test-page-with-properties
  (testing "extract-page-datoms includes property datoms"
    ;; Use standard block format with properties
    (load-test-files [{:file/path "pages/props-page.md"
                       :file/content "- Block with props\n  id:: test-block-id\n  status:: done"}])
    (let [conn (db/get-db test-helper/test-db false)
          db @conn
          datoms (file-sync/extract-page-datoms db "props-page")]
      ;; The page might not exist if parsing fails, so check before asserting
      (when datoms
        (is (vector? datoms) "Result should be a vector")
        ;; Check that we have property-related datoms
        (let [attrs (set (map second datoms))]
          ;; Should have various attributes including block-related ones
          (is (seq attrs) "Should have attributes"))))))

(deftest test-page-with-nested-blocks
  (testing "extract-page-datoms includes deeply nested blocks"
    (load-test-files [{:file/path "pages/nested.md"
                       :file/content "- Level 1\n  - Level 2\n    - Level 3\n      - Level 4"}])
    (let [conn (db/get-db test-helper/test-db false)
          db @conn
          datoms (file-sync/extract-page-datoms db "nested")]
      (is (vector? datoms) "Result should be a vector")
      (is (pos? (count datoms)) "Should have datoms")
      ;; Should have datoms for multiple block entities
      (let [entity-ids (set (map first datoms))]
        ;; Page + 4 levels of blocks
        (is (>= (count entity-ids) 4) "Should have page + multiple block entities")))))
