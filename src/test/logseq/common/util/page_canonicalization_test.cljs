(ns logseq.common.util.page-canonicalization-test
  "Tests for page name canonicalization - the foundation of page identity.
   These tests verify that page names are canonicalized consistently
   for use in :block/name (the unique page identifier)."
  (:require [cljs.test :refer [deftest testing is are]]
            [clojure.string :as string]
            [logseq.common.util :as common-util]))

;; =============================================================================
;; Table-Driven Tests for Page Canonicalization
;; =============================================================================
;;
;; Page canonicalization must be consistent and predictable.
;; These tests document expected behavior for various edge cases.

(deftest ^:parallel page-name-sanity-lc-basic
  (testing "basic lowercase conversion"
    (are [input expected]
         (= expected (common-util/page-name-sanity-lc input))

      ;; Simple cases
      "Page"         "page"
      "PAGE"         "page"
      "page"         "page"
      "My Page"      "my page"

      ;; Mixed case
      "CamelCase"    "camelcase"
      "UPPER CASE"   "upper case")))

(deftest ^:parallel page-name-sanity-lc-whitespace
  (testing "whitespace handling"
    (are [input expected]
         (= expected (common-util/page-name-sanity-lc input))

      ;; Internal whitespace preserved
      "two words"        "two words"
      "three  spaces"    "three  spaces"      ; multiple spaces preserved
      "tab\there"        "tab\there"          ; tabs preserved

      ;; Leading/trailing whitespace NOT trimmed by page-name-sanity-lc
      ;; (trimming happens elsewhere in the pipeline)
      " leading"         " leading"
      "trailing "        "trailing ")))

(deftest ^:parallel page-name-sanity-lc-slashes
  (testing "boundary slash removal"
    (are [input expected]
         (= expected (common-util/page-name-sanity-lc input))

      ;; Leading/trailing slashes removed
      "/page"          "page"
      "page/"          "page"
      "/page/"         "page"

      ;; Internal slashes preserved (namespaces)
      "ns/page"        "ns/page"
      "a/b/c"          "a/b/c"

      ;; Multiple boundary slashes
      "//page"         "/page"      ; only one removed
      "page//"         "page/")))

(deftest ^:parallel page-name-sanity-lc-unicode
  (testing "unicode normalization (NFC)"
    (are [input expected]
         (= expected (common-util/page-name-sanity-lc input))

      ;; Simple unicode preserved
      "café"           "café"
      "日本語"          "日本語"
      "emoji"         "emoji"

      ;; Composed vs decomposed forms should normalize to same result
      ;; é as single codepoint (U+00E9) vs e + combining acute (U+0065 U+0301)
      "caf\u00e9"      "café"                 ; composed
      "cafe\u0301"     "café")))              ; decomposed -> NFC composed

(deftest ^:parallel page-name-sanity-lc-special-characters
  (testing "special characters preserved"
    (are [input expected]
         (= expected (common-util/page-name-sanity-lc input))

      ;; Common special characters
      "page-with-dashes"    "page-with-dashes"
      "page_underscore"     "page_underscore"
      "page.with.dots"      "page.with.dots"
      "page (with parens)"  "page (with parens)"

      ;; Brackets
      "[[Page]]"            "[[page]]"         ; brackets preserved
      "page [[ref]]"        "page [[ref]]"

      ;; Other special chars
      "page: colon"         "page: colon"
      "question?"           "question?"
      "exclaim!"            "exclaim!")))

(deftest ^:parallel page-name-sanity-lc-namespaces
  (testing "namespace pages"
    (are [input expected]
         (= expected (common-util/page-name-sanity-lc input))

      ;; Simple namespaces
      "Project/Tasks"       "project/tasks"
      "A/B/C"               "a/b/c"

      ;; Deep namespaces
      "Root/L1/L2/L3"       "root/l1/l2/l3"

      ;; Mixed case namespaces
      "AREA/Project/Task"   "area/project/task")))

(deftest ^:parallel page-name-sanity-lc-nil-empty
  (testing "nil and empty string handling"
    ;; Note: page-name-sanity-lc throws on nil input
    ;; Use safe-page-name-sanity-lc for nil-safe behavior
    (is (thrown? js/TypeError (common-util/page-name-sanity-lc nil))
        "nil input throws TypeError")
    (is (= "" (common-util/page-name-sanity-lc ""))
        "empty string returns empty string")))

(deftest ^:parallel page-name-sanity-lc-journal-dates
  (testing "journal date formats (just canonicalized as regular names)"
    (are [input expected]
         (= expected (common-util/page-name-sanity-lc input))

      ;; Journal dates are just page names
      "Dec 18th, 2025"      "dec 18th, 2025"
      "2025-12-18"          "2025-12-18"
      "December 18, 2025"   "december 18, 2025")))

;; =============================================================================
;; page-name-sanity (without lowercase) tests
;; =============================================================================

(deftest ^:parallel page-name-sanity-preserves-case
  (testing "page-name-sanity preserves case"
    (are [input expected]
         (= expected (common-util/page-name-sanity input))

      "Page"         "Page"
      "CamelCase"    "CamelCase"
      "/Page/"       "Page"
      "ns/Page"      "ns/Page")))

;; =============================================================================
;; safe-page-name-sanity-lc tests
;; =============================================================================

(deftest ^:parallel safe-page-name-sanity-lc-non-strings
  (testing "safe-page-name-sanity-lc handles non-strings"
    (is (= 123 (common-util/safe-page-name-sanity-lc 123))
        "numbers pass through")
    (is (= :keyword (common-util/safe-page-name-sanity-lc :keyword))
        "keywords pass through")
    (is (= "page" (common-util/safe-page-name-sanity-lc "Page"))
        "strings are canonicalized")))
