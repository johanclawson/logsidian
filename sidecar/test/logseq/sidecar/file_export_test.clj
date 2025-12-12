(ns logseq.sidecar.file-export-test
  (:require [clojure.test :refer :all]
            [logseq.sidecar.file-export :as export]))

;; =============================================================================
;; Block Line Formatting Tests
;; =============================================================================

(deftest page-tree->markdown-basic-test
  (testing "converts simple page tree to markdown"
    (let [tree {:page {:block/uuid (random-uuid)
                       :block/name "test-page"
                       :block/title "Test Page"}
                :blocks [{:block/uuid (random-uuid)
                          :block/content "First block"}
                         {:block/uuid (random-uuid)
                          :block/content "Second block"}]}
          result (export/page-tree->markdown tree {})]
      (is (string? result))
      (is (re-find #"- First block" result))
      (is (re-find #"- Second block" result))))

  (testing "handles nested blocks with proper indentation"
    (let [tree {:page {:block/uuid (random-uuid)
                       :block/name "nested-page"}
                :blocks [{:block/uuid (random-uuid)
                          :block/content "Parent block"
                          :children [{:block/uuid (random-uuid)
                                      :block/content "Child block"
                                      :children [{:block/uuid (random-uuid)
                                                  :block/content "Grandchild"}]}]}]}
          result (export/page-tree->markdown tree {})]
      (is (re-find #"^- Parent block" result))
      (is (re-find #"  - Child block" result))
      (is (re-find #"    - Grandchild" result))))

  (testing "handles empty blocks"
    (let [tree {:page {:block/uuid (random-uuid)
                       :block/name "empty-page"}
                :blocks [{:block/uuid (random-uuid)
                          :block/content ""}]}
          result (export/page-tree->markdown tree {})]
      (is (string? result))
      (is (re-find #"^- $" result))))

  (testing "handles multiline content with proper indentation"
    (let [tree {:page {:block/uuid (random-uuid)
                       :block/name "multiline-page"}
                :blocks [{:block/uuid (random-uuid)
                          :block/content "Line 1\nLine 2\nLine 3"}]}
          result (export/page-tree->markdown tree {})]
      ;; Each continuation line should be indented
      (is (re-find #"- Line 1" result))
      (is (re-find #"  Line 2" result))
      (is (re-find #"  Line 3" result)))))

;; =============================================================================
;; Block Properties Tests
;; =============================================================================

(deftest page-tree-with-properties-test
  (testing "includes block properties"
    (let [tree {:page {:block/uuid (random-uuid)
                       :block/name "props-page"}
                :blocks [{:block/uuid (random-uuid)
                          :block/content "Block with props"
                          :block/properties {:status "done"
                                             :priority "high"}}]}
          result (export/page-tree->markdown tree {})]
      (is (re-find #"status::" result))
      (is (re-find #"priority::" result))))

  (testing "filters out internal properties"
    (let [tree {:page {:block/uuid (random-uuid)
                       :block/name "internal-props-page"}
                :blocks [{:block/uuid (random-uuid)
                          :block/content "Block"
                          :block/properties {:id "internal-id"
                                             :ls-type "shape"
                                             :visible-prop "value"}}]}
          result (export/page-tree->markdown tree {})]
      ;; Internal props should be filtered
      (is (not (re-find #"id::" result)))
      (is (not (re-find #"ls-type::" result)))
      ;; Visible prop should remain
      (is (re-find #"visible-prop::" result)))))

;; =============================================================================
;; File Path Generation Tests
;; =============================================================================

(deftest pages->file-writes-test
  (testing "generates correct file paths for regular pages"
    (let [trees [{:page {:block/uuid (random-uuid)
                         :block/name "my-page"
                         :block/title "My Page"
                         :block/format :markdown}
                  :blocks [{:block/content "Content"}]}]
          result (export/pages->file-writes trees "/graph" {})]
      (is (= 1 (count result)))
      (is (= "pages/My Page.md" (first (first result))))))

  (testing "generates correct file paths for journal pages"
    (let [trees [{:page {:block/uuid (random-uuid)
                         :block/name "dec 11th, 2025"
                         :block/title "Dec 11th, 2025"
                         :block/journal-day 20251211
                         :block/format :markdown}
                  :blocks [{:block/content "Journal entry"}]}]
          result (export/pages->file-writes trees "/graph" {})]
      (is (= 1 (count result)))
      (is (re-find #"^journals/" (first (first result))))))

  (testing "uses org extension for org format"
    (let [trees [{:page {:block/uuid (random-uuid)
                         :block/name "org-page"
                         :block/title "Org Page"
                         :block/format :org}
                  :blocks [{:block/content "Content"}]}]
          result (export/pages->file-writes trees "/graph" {})]
      (is (re-find #"\.org$" (first (first result))))))

  (testing "sanitizes unsafe filename characters"
    (let [trees [{:page {:block/uuid (random-uuid)
                         :block/name "page/with:unsafe*chars"
                         :block/title "Page/With:Unsafe*Chars"
                         :block/format :markdown}
                  :blocks [{:block/content "Content"}]}]
          result (export/pages->file-writes trees "/graph" {})]
      (let [file-path (first (first result))
            ;; Extract just the filename part (after pages/)
            filename (last (clojure.string/split file-path #"/"))]
        ;; Filename should not contain unsafe chars (but path can have /)
        (is (not (re-find #"[:*?\"<>|\\]" filename)))))))

;; =============================================================================
;; Edge Cases Tests
;; =============================================================================

(deftest edge-cases-test
  (testing "handles empty page tree"
    (let [tree {:page {:block/uuid (random-uuid)
                       :block/name "empty"}
                :blocks []}
          result (export/page-tree->markdown tree {})]
      (is (= "" result))))

  (testing "handles page with only nested blocks"
    (let [tree {:page {:block/uuid (random-uuid)
                       :block/name "deep"}
                :blocks [{:block/content "Level 1"
                          :children [{:block/content "Level 2"
                                      :children [{:block/content "Level 3"
                                                  :children [{:block/content "Level 4"}]}]}]}]}
          result (export/page-tree->markdown tree {})]
      (is (re-find #"^- Level 1" result))
      (is (re-find #"      - Level 4" result)))) ; 6 spaces for level 4

  (testing "handles blocks with title instead of content"
    (let [tree {:page {:block/uuid (random-uuid)
                       :block/name "title-page"}
                :blocks [{:block/uuid (random-uuid)
                          :block/title "Using title field"}]}
          result (export/page-tree->markdown tree {})]
      (is (re-find #"- Using title field" result)))))
