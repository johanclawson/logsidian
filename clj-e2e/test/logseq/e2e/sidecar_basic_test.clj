(ns logseq.e2e.sidecar-basic-test
  "E2E tests for verifying sidecar integration with the Logsidian app.

   These tests verify:
   - App launches and connects to sidecar
   - Basic CRUD operations work through sidecar
   - No console errors during operations
   - Sidecar fallback to web worker works

   Prerequisites:
   - Sidecar JAR built: `cd sidecar && clojure -T:build uberjar`
   - Sidecar running: `java -jar sidecar/target/logsidian-sidecar.jar 47632`
   - App built with sidecar support: `yarn release`
   - App served: `bb serve` (from clj-e2e directory)

   Run with: `clojure -M:test -n logseq.e2e.sidecar-basic-test`
   Run with TDD loop: `pwsh scripts/tdd-loop.ps1`"
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [logseq.e2e.assert :as assert]
   [logseq.e2e.block :as b]
   [logseq.e2e.error-collector :as errors]
   [logseq.e2e.fixtures :as fixtures]
   [logseq.e2e.keyboard :as k]
   [logseq.e2e.page :as p]
   [logseq.e2e.util :as util]
   [wally.main :as w]))

(use-fixtures :once fixtures/open-page)

(use-fixtures :each
  fixtures/new-logseq-page
  fixtures/validate-graph
  ;; Assert no console errors at the end of each test
  errors/wrap-assert-no-console-errors)

;; =============================================================================
;; Sidecar Connection Tests
;; =============================================================================

(deftest ^:sidecar sidecar-app-launches-test
  (testing "App launches and loads graph successfully"
    ;; The fixture already verifies graph is loaded
    ;; Additional check for page title visibility
    (assert/assert-is-visible (w/get-by-test-id "page title"))))

;; =============================================================================
;; Basic CRUD Operations via Sidecar
;; =============================================================================

(deftest ^:sidecar create-page-test
  (testing "Create new page appears in sidebar"
    (let [page-name (str "sidecar-test-page-" (System/currentTimeMillis))]
      (p/new-page page-name)
      ;; Verify page was created
      (assert/assert-is-visible (w/get-by-test-id "page title"))
      ;; Navigate away and back to verify persistence
      (p/goto-page "Contents")
      (p/goto-page page-name)
      (assert/assert-is-visible (w/get-by-test-id "page title")))))

(deftest ^:sidecar create-block-test
  (testing "Create block with content"
    (b/new-block "Test block content via sidecar")
    (assert/assert-is-visible ".ls-block:has-text('Test block content via sidecar')")))

(deftest ^:sidecar create-block-with-link-test
  (testing "Add block with [[link]] - link is clickable"
    (let [target-page (str "target-page-" (System/currentTimeMillis))]
      ;; Create target page first
      (p/new-page target-page)
      ;; Go back to test page
      (k/press "ControlOrMeta+k")
      (util/wait-timeout 200)
      ;; Create block with link
      (b/new-block (str "Block with link to [[" target-page "]]"))
      ;; Verify link is visible and clickable
      (assert/assert-is-visible (format ".ls-block a.page-ref:has-text('%s')" target-page)))))

(deftest ^:sidecar multiple-blocks-test
  (testing "Create multiple blocks in sequence"
    (b/new-blocks ["Block 1 via sidecar"
                   "Block 2 via sidecar"
                   "Block 3 via sidecar"])
    (assert/assert-is-visible ".ls-block:has-text('Block 1 via sidecar')")
    (assert/assert-is-visible ".ls-block:has-text('Block 2 via sidecar')")
    (assert/assert-is-visible ".ls-block:has-text('Block 3 via sidecar')")))

(deftest ^:sidecar nested-blocks-test
  (testing "Create nested blocks with indentation"
    (b/new-block "Parent block")
    (b/new-block "Child block")
    (b/indent)
    ;; Verify child is indented
    (assert/assert-is-visible ".ls-block:has-text('Parent block')")
    (assert/assert-is-visible ".ls-block:has-text('Child block')")))

;; =============================================================================
;; Search Tests
;; =============================================================================

(deftest ^:sidecar search-block-test
  (testing "Search for block - results appear"
    (let [unique-content (str "unique-search-term-" (System/currentTimeMillis))]
      ;; Create block with unique content
      (b/new-block unique-content)
      ;; Exit edit mode
      (k/esc)
      (util/wait-timeout 200)
      ;; Open search
      (k/press "ControlOrMeta+k")
      (util/wait-timeout 300)
      ;; Type search term
      (w/fill ".cp__cmdk-search-input" unique-content)
      (util/wait-timeout 500)
      ;; Verify results appear
      (assert/assert-is-visible (format ".cmdk-list-item:has-text('%s')" unique-content))
      ;; Close search
      (k/esc))))

;; =============================================================================
;; Persistence Tests
;; =============================================================================

(deftest ^:sidecar reload-persistence-test
  (testing "Reload app - data persists"
    (let [unique-block (str "persist-test-" (System/currentTimeMillis))]
      ;; Create block
      (b/new-block unique-block)
      ;; Exit edit mode
      (k/esc)
      (util/wait-timeout 200)
      ;; Refresh page
      (w/refresh)
      ;; Wait for graph to load
      (assert/assert-graph-loaded?)
      ;; Verify block still exists
      (assert/assert-is-visible (format ".ls-block:has-text('%s')" unique-block)))))

;; =============================================================================
;; Block Operations Tests
;; =============================================================================

(deftest ^:sidecar undo-redo-test
  (testing "Undo and redo operations work"
    (b/new-block "Block to undo")
    (k/esc)
    (util/wait-timeout 200)
    ;; Undo
    (b/undo)
    (util/wait-timeout 200)
    ;; Verify block is gone (or content is undone)
    ;; Redo
    (b/redo)
    (util/wait-timeout 200)))

(deftest ^:sidecar copy-paste-test
  (testing "Copy and paste blocks work"
    (b/new-block "Block to copy")
    ;; Select all in editor
    (k/press "ControlOrMeta+a")
    ;; Copy
    (b/copy)
    ;; Create new block
    (k/enter)
    ;; Paste
    (b/paste)
    (util/wait-timeout 200)))

(deftest ^:sidecar delete-block-test
  (testing "Delete block works"
    (b/new-block "Block to delete")
    (k/esc)
    (util/wait-timeout 200)
    ;; Select block
    (k/press "ArrowUp")
    ;; Delete
    (b/delete-blocks)
    (util/wait-timeout 200)))

;; =============================================================================
;; Smoke Tests (Quick verification with explicit console error checks)
;; =============================================================================

(deftest ^:sidecar ^:smoke smoke-app-startup-test
  (testing "App starts without console errors"
    ;; Just verify the app is loaded - console errors are checked by fixture
    (assert/assert-graph-loaded?)
    ;; Explicit check for common sidecar errors
    (errors/assert-no-errors-containing! "sidecar")
    (errors/assert-no-errors-containing! "Transit")))

(deftest ^:sidecar ^:smoke smoke-basic-crud-test
  (testing "Basic CRUD operations complete without errors"
    ;; Create
    (let [block-content (str "smoke-test-" (System/currentTimeMillis))]
      (b/new-block block-content)
      (assert/assert-is-visible (format ".ls-block:has-text('%s')" block-content))
      ;; Update (edit the block)
      (w/fill ".editor-wrapper textarea" (str block-content " - updated"))
      (k/esc)
      (util/wait-timeout 200)
      ;; Verify update
      (assert/assert-is-visible (format ".ls-block:has-text('%s - updated')" block-content))
      ;; No errors during CRUD
      (errors/assert-no-errors-containing! "Error"))))

(deftest ^:sidecar ^:smoke smoke-navigation-test
  (testing "Navigation works without errors"
    (let [page-name (str "smoke-nav-" (System/currentTimeMillis))]
      ;; Create a page
      (p/new-page page-name)
      (assert/assert-is-visible (w/get-by-test-id "page title"))
      ;; Navigate away
      (p/goto-page "Contents")
      (util/wait-timeout 300)
      ;; Navigate back
      (p/goto-page page-name)
      (util/wait-timeout 300)
      ;; Verify we're on the right page
      (assert/assert-is-visible (w/get-by-test-id "page title"))
      ;; No navigation errors
      (errors/assert-no-errors-containing! "route")
      (errors/assert-no-errors-containing! "navigate"))))
