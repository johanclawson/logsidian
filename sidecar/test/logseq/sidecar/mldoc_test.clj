(ns logseq.sidecar.mldoc-test
  "Tests for the production GraalJS mldoc module."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [logseq.sidecar.mldoc :as mldoc]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn with-mldoc-context [f]
  "Ensure mldoc context is initialized before tests and shutdown after."
  (try
    (f)
    (finally
      ;; Don't shutdown between tests - keep context warm
      nil)))

(use-fixtures :once with-mldoc-context)

;; =============================================================================
;; Status and Initialization Tests
;; =============================================================================

(deftest test-status
  (testing "Status returns expected keys"
    (let [status (mldoc/status)]
      (is (contains? status :initialized?))
      (is (contains? status :engine-class))
      (is (contains? status :mldoc-path)))))

(deftest test-initialization
  (testing "First parse initializes context"
    (mldoc/parse-json "# Test" :markdown)
    (let [status (mldoc/status)]
      (is (true? (:initialized? status)))
      (is (some? (:engine-class status))))))

;; =============================================================================
;; Config Tests
;; =============================================================================

(deftest test-make-config
  (testing "Default markdown config"
    (let [config (mldoc/make-config :markdown)]
      (is (= "Markdown" (:format config)))
      (is (false? (:toc config)))
      (is (true? (:keep_line_break config)))))

  (testing "Org config"
    (let [config (mldoc/make-config :org)]
      (is (= "Org" (:format config)))))

  (testing "Custom options merge"
    (let [config (mldoc/make-config :markdown {:parse_outline_only true})]
      (is (= "Markdown" (:format config)))
      (is (true? (:parse_outline_only config))))))

;; =============================================================================
;; Parse JSON Tests
;; =============================================================================

(deftest test-parse-markdown-heading
  (testing "Parse markdown heading"
    (let [result (mldoc/parse-json "# Hello World" :markdown)]
      (is (vector? result))
      (is (pos? (count result)))
      (let [[block _pos] (first result)]
        (is (= "Heading" (first block)))))))

(deftest test-parse-markdown-paragraph
  (testing "Parse markdown paragraph"
    (let [result (mldoc/parse-json "This is a paragraph." :markdown)]
      (is (vector? result))
      (let [[block _pos] (first result)]
        (is (= "Paragraph" (first block)))))))

(deftest test-parse-logseq-bullet
  (testing "Parse Logseq bullet (parsed as heading)"
    (let [result (mldoc/parse-json "- Item 1\n- Item 2" :markdown)]
      (is (vector? result))
      ;; Logseq parses "- " as headings for outline structure
      (let [[block _pos] (first result)]
        (is (= "Heading" (first block)))))))

(deftest test-parse-org-heading
  (testing "Parse org-mode heading"
    (let [result (mldoc/parse-json "* Hello World" :org)]
      (is (vector? result))
      (let [[block _pos] (first result)]
        (is (= "Heading" (first block)))))))

(deftest test-parse-code-block
  (testing "Parse code block"
    (let [result (mldoc/parse-json "```clojure\n(+ 1 2)\n```" :markdown)]
      (is (vector? result))
      (let [[block _pos] (first result)]
        (is (= "Src" (first block)))))))

(deftest test-parse-complex-markdown
  (testing "Parse complex markdown with multiple blocks"
    (let [content "# Title\n\nParagraph text.\n\n- Item 1\n- Item 2\n\n```clojure\n(+ 1 2)\n```"
          result (mldoc/parse-json content :markdown)]
      (is (vector? result))
      (is (>= (count result) 3)))))

(deftest test-parse-page-reference
  (testing "Parse page reference"
    (let [result (mldoc/parse-json "Link to [[Another Page]]" :markdown)]
      (is (vector? result))
      ;; Should have a paragraph with nested link
      (let [[block _pos] (first result)]
        (is (= "Paragraph" (first block)))))))

(deftest test-parse-block-reference
  (testing "Parse block reference"
    (let [result (mldoc/parse-json "See ((abc123-uuid))" :markdown)]
      (is (vector? result)))))

(deftest test-parse-properties
  (testing "Parse properties block"
    (let [result (mldoc/parse-json "title:: My Page\ntags:: tag1, tag2\n\nContent" :markdown)]
      (is (vector? result))
      ;; First block should be property drawer
      (let [[block _pos] (first result)]
        (is (contains? #{"Property_Drawer" "Properties"} (first block)))))))

(deftest test-parse-empty-content
  (testing "Parse empty content returns empty vector"
    (let [result (mldoc/parse-json "" :markdown)]
      (is (or (nil? result) (empty? result))))))

;; =============================================================================
;; Parse Inline JSON Tests
;; =============================================================================

(deftest test-parse-inline-simple
  (testing "Parse inline text"
    (let [result (mldoc/parse-inline-json "Hello **world**" :markdown)]
      (is (some? result)))))

(deftest test-parse-inline-link
  (testing "Parse inline page reference"
    (let [result (mldoc/parse-inline-json "[[Page Name]]" :markdown)]
      (is (some? result)))))

;; =============================================================================
;; Get References Tests
;; =============================================================================

(deftest test-get-references-page
  (testing "Get page references"
    (let [result (mldoc/get-references "Link to [[Another Page]]" :markdown)]
      (is (some? result)))))

(deftest test-get-references-block
  (testing "Get block references"
    (let [result (mldoc/get-references "See ((abc123))" :markdown)]
      (is (some? result)))))

(deftest test-get-references-empty
  (testing "Get references from empty text"
    (let [result (mldoc/get-references "" :markdown)]
      (is (nil? result)))))

;; =============================================================================
;; Position Metadata Tests
;; =============================================================================

(deftest test-position-metadata
  (testing "Parse returns position metadata"
    (let [result (mldoc/parse-json "# Hello" :markdown)
          [_block pos] (first result)]
      (is (map? pos))
      (is (contains? pos :start_pos))
      (is (contains? pos :end_pos))
      (is (number? (:start_pos pos)))
      (is (number? (:end_pos pos))))))

;; =============================================================================
;; Thread Safety Tests
;; =============================================================================

(deftest test-concurrent-parsing
  (testing "Concurrent parsing is thread-safe"
    (let [contents (repeat 10 "# Heading\n\nParagraph")
          results (doall (pmap #(mldoc/parse-json % :markdown) contents))]
      (is (= 10 (count results)))
      (is (every? vector? results)))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest test-special-characters
  (testing "Parse content with special characters"
    (let [result (mldoc/parse-json "Content with \"quotes\" and 'apostrophes'" :markdown)]
      (is (vector? result)))))

(deftest test-unicode-content
  (testing "Parse content with unicode"
    (let [result (mldoc/parse-json "# 你好世界 🎉" :markdown)]
      (is (vector? result)))))

(deftest test-multiline-content
  (testing "Parse multiline content"
    (let [content "Line 1\nLine 2\nLine 3"
          result (mldoc/parse-json content :markdown)]
      (is (vector? result)))))

(deftest test-windows-line-endings
  (testing "Parse content with Windows line endings"
    (let [content "Line 1\r\nLine 2\r\nLine 3"
          result (mldoc/parse-json content :markdown)]
      (is (vector? result)))))

(comment
  ;; Run all tests:
  ;; cd sidecar && clj -J-Dpolyglot.engine.WarnInterpreterOnly=false -M:test

  ;; Run specific test:
  (test-parse-markdown-heading)
  (test-concurrent-parsing)

  ;; Manual testing:
  (mldoc/parse-json "# Hello" :markdown)
  (mldoc/status))
