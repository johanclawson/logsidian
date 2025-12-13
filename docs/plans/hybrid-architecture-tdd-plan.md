# Hybrid Architecture TDD Plan

**Status:** READY FOR IMPLEMENTATION
**Priority:** CRITICAL (Blocking all subsequent phases)
**Estimated Complexity:** HIGH
**Date:** 2025-12-13

## Executive Summary

This document provides an extensive TDD-driven plan for implementing the Hybrid Architecture (Phase 2.5). The goal is to enable the JVM sidecar and web worker to run simultaneously, where:
- **Web Worker** handles file parsing (mldoc is JavaScript-only)
- **JVM Sidecar** handles DataScript queries (lazy loading via IStorage)

---

## Table of Contents

1. [Problem Analysis](#problem-analysis)
2. [Current Architecture](#current-architecture)
3. [Target Architecture](#target-architecture)
4. [Root Cause Analysis](#root-cause-analysis)
5. [Solution Design](#solution-design)
6. [TDD Implementation Plan](#tdd-implementation-plan)
7. [Test Specifications](#test-specifications)
8. [File Changes Summary](#file-changes-summary)
9. [Risk Assessment](#risk-assessment)
10. [Rollback Strategy](#rollback-strategy)

---

## Problem Analysis

### The Core Issue

When the sidecar starts, it **overwrites** the `state/*db-worker` atom, causing ALL database operations (including file parsing) to route to the sidecar. However, the sidecar **cannot parse files** because:

1. Logseq's parser (mldoc) is written in OCaml, compiled to JavaScript
2. mldoc cannot run on the JVM
3. The `:thread-api/reset-file` operation requires mldoc

### Symptoms

| Symptom | Cause |
|---------|-------|
| E2E tests fail with "No pages found" | Files not parsed, DB empty |
| Console error: "Unknown operation: :thread-api/reset-file" | Sidecar doesn't implement reset-file |
| Queries return empty results | No data synced to sidecar |
| Initial sync fails silently | No datoms to extract from empty frontend DB |

### Impact

This bug blocks:
- All E2E testing
- Performance benchmarking (Phase 5)
- JRE bundling (Phase 5.5)
- Release workflow (Phase 6)

---

## Current Architecture

### Data Flow (CURRENT - BROKEN)

```
                                  ┌─────────────────────────┐
                                  │     Web Worker          │
                                  │  ┌─────────────────┐    │
                                  │  │  mldoc parser   │    │
                                  │  │  DataScript DB  │    │
                                  │  └─────────────────┘    │
                                  │   (UNREACHABLE!)        │
                                  └─────────────────────────┘
                                            ▲
                                            │ Should go here
                                            │ but doesn't!
┌──────────────────┐    *db-worker    ┌─────┴───────────────┐
│    Frontend      │ ───────────────► │    JVM Sidecar      │
│  (Renderer)      │   (WRONG!)       │  ┌─────────────────┐│
│                  │                  │  │ DataScript DB   ││
│  state/*db-worker│                  │  │ (EMPTY!)        ││
│  points here ────┼──────────────────│  └─────────────────┘│
│                  │                  │  NO mldoc parser!   │
└──────────────────┘                  └─────────────────────┘

Problem: When reset-file! is called, it goes to sidecar which
         returns "Unknown operation" and file is not parsed.
```

### Code Flow (CURRENT)

```clojure
;; In persist_db/browser.cljs start-db-worker!
(if (sidecar/sidecar-enabled?)
  (-> (sidecar/start-db-backend!
       {:start-web-worker-fn start-web-worker!  ;; Step 1: Start worker
        ...})))

;; In sidecar/core.cljs start-db-backend!
(-> (p/let [_ (start-web-worker-fn)        ;; Worker starts, sets *db-worker
            _ (start-sidecar-async!)]       ;; Sidecar starts...
    ...))

;; In sidecar/core.cljs start-sidecar!
(reset! state/*db-worker worker-fn)         ;; OVERWRITES worker! BUG!

;; Later, when file parsing is needed:
;; In handler/file_based/file.cljs reset-file!
(state/<invoke-db-worker :thread-api/reset-file ...)
;; This now goes to SIDECAR which doesn't implement it!
```

### Key Files Involved

| File | Role | Current Problem |
|------|------|-----------------|
| `src/main/frontend/state.cljs` | Holds `*db-worker` atom | Only one reference |
| `src/main/frontend/sidecar/core.cljs` | Sidecar startup | Overwrites `*db-worker` |
| `src/main/frontend/persist_db/browser.cljs` | Starts worker | Calls sidecar startup |
| `src/main/frontend/handler/file_based/file.cljs` | File parsing | Uses `*db-worker` |
| `sidecar/src/logseq/sidecar/server.clj` | Sidecar handler | No `reset-file` handler |

---

## Target Architecture

### Data Flow (TARGET - FIXED)

```
┌─────────────────────────────────────────────────────────────────┐
│                        Frontend (Renderer)                       │
│                                                                  │
│   state/*db-worker ──────────┐     state/*sidecar-worker ──┐    │
│         │                    │              │               │    │
│         ▼                    │              ▼               │    │
│   ┌───────────────┐          │       ┌───────────────┐     │    │
│   │ Parsing ops   │          │       │ Query ops     │     │    │
│   │ reset-file    │          │       │ q, pull, etc  │     │    │
│   │ transact      │          │       │ (sidecar-ops) │     │    │
│   └───────┬───────┘          │       └───────┬───────┘     │    │
│           │                  │               │             │    │
└───────────┼──────────────────┼───────────────┼─────────────┼────┘
            │                  │               │             │
            ▼                  │               ▼             │
┌───────────────────┐          │    ┌───────────────────────┐│
│   Web Worker      │          │    │    JVM Sidecar        ││
│ ┌───────────────┐ │          │    │ ┌───────────────────┐ ││
│ │ mldoc parser  │ │          │    │ │ DataScript        │ ││
│ │ DataScript    │◄┼──────────┼────│ │ IStorage (lazy)   │ ││
│ │ (authoritative)│ │  sync   │    │ │ SQLite-JDBC       │ ││
│ └───────────────┘ │  ────────┼───►│ └───────────────────┘ ││
│                   │          │    │                       ││
└───────────────────┘          │    └───────────────────────┘│
                               │                              │
                               └──────────────────────────────┘
                                        (IPC)
```

### Operation Routing Strategy

| Operation Category | Route To | Reason |
|-------------------|----------|--------|
| File parsing (`reset-file`, file write-back) | Web Worker | Needs mldoc |
| Transactions (`transact`) | Web Worker | Authoritative source |
| Queries (`q`, `pull`, `pull-many`, `datoms`) | Sidecar | Lazy loading |
| State management (`sync-app-state`, etc.) | Both | Keep in sync |
| Outliner ops (when sidecar has data) | Sidecar | Performance |
| Vec-search, RTC | Web Worker | Specialized features |

### Key Design Decisions

1. **Two separate atoms**: `*db-worker` (web worker) and `*sidecar-worker` (sidecar)
2. **Smart routing function**: `<invoke-db` chooses target based on operation
3. **Sync after parsing**: Initial sync extracts from worker's DB to sidecar
4. **Fallback mechanism**: If sidecar fails, queries fall back to worker

---

## Root Cause Analysis

### Why Does This Bug Exist?

Looking at `start-sidecar!` in `sidecar/core.cljs:126`:

```clojure
(defn start-sidecar!
  "Start the IPC sidecar client..."
  [{:keys [port on-connected on-error]}]
  ...
  (-> (p/let [_ (spawn/start! {:port port})
              worker-fn (client/create-worker-fn)]
        ;; Set as the db-worker  <-- THIS IS THE BUG
        (reset! state/*db-worker worker-fn)  ;; <-- OVERWRITES WORKER!
        ...)
      ...))
```

This was likely written before the hybrid architecture was designed. The original assumption was sidecar REPLACES worker, but:
- Sidecar can't parse files
- Worker is still needed
- They need to COEXIST

### Why Wasn't This Caught Earlier?

1. Unit tests don't exercise full app lifecycle
2. E2E tests with sidecar weren't comprehensive
3. The initial sync code assumed frontend DB had data
4. Error handling swallowed the "Unknown operation" errors

---

## Solution Design

### Approach: Dual Worker Architecture

Instead of one `*db-worker`, maintain two separate workers:

```clojure
;; In state.cljs
(defonce *db-worker (atom nil))         ;; Web worker (parsing + authoritative)
(defonce *sidecar-worker (atom nil))    ;; JVM sidecar (queries + lazy loading)
```

### New Invocation API

Create operation-aware routing:

```clojure
;; New function that routes based on operation
(defn <invoke-db
  "Invoke a database operation, routing to appropriate backend.
   - Parsing ops -> web worker
   - Query ops -> sidecar (with fallback to worker)"
  [qkw & args]
  (let [use-sidecar? (and @*sidecar-worker
                          (sidecar-query-op? qkw))]
    (if use-sidecar?
      (apply @*sidecar-worker qkw false args)
      (apply <invoke-db-worker qkw args))))
```

### Operation Classification

```clojure
(def worker-only-ops
  "Operations that MUST go to web worker (require mldoc or worker-specific code)"
  #{:thread-api/reset-file
    :thread-api/gc-graph
    :thread-api/export-db
    :thread-api/import-db
    :thread-api/fix-broken-graph
    ;; Vec search (uses browser WebGPU)
    :thread-api/vec-search-embedding-model-info
    :thread-api/vec-search-init-embedding-model
    :thread-api/vec-search-load-model
    :thread-api/vec-search-embedding-graph
    :thread-api/vec-search-search
    :thread-api/vec-search-cancel-indexing
    :thread-api/vec-search-update-index-info
    ;; RTC (WebSocket in browser)
    :thread-api/rtc-start
    :thread-api/rtc-stop
    :thread-api/rtc-sync-graph!
    :thread-api/rtc-status})

(def sidecar-preferred-ops
  "Operations that PREFER sidecar when available (for lazy loading performance)"
  #{:thread-api/q
    :thread-api/pull
    :thread-api/pull-many
    :thread-api/datoms
    :thread-api/apply-outliner-ops
    :thread-api/get-page-trees
    :thread-api/get-file-writes})

(defn sidecar-query-op?
  "Check if operation should prefer sidecar."
  [qkw]
  (and (not (contains? worker-only-ops qkw))
       (or (contains? sidecar-preferred-ops qkw)
           ;; State ops go to both, but sidecar is primary for reads
           (clojure.string/starts-with? (name qkw) "sync-")
           (clojure.string/starts-with? (name qkw) "get-"))))
```

### Sync Flow

```
1. App starts
   └─► start-db-worker! called

2. Web worker starts
   └─► *db-worker set to web worker
   └─► Worker initializes DataScript DB

3. Sidecar starts (async)
   └─► *sidecar-worker set to sidecar client  (NOT *db-worker!)
   └─► Sidecar initializes empty DataScript DB

4. Graph is opened
   └─► Files parsed via *db-worker (web worker)
   └─► Worker's DataScript DB populated
   └─► :graph/added event fires

5. Initial sync triggered
   └─► Extract datoms from FRONTEND DB (which was synced from worker)
   └─► Send to sidecar via sync-datoms
   └─► Sidecar now has the data!

6. Queries execute
   └─► <invoke-db routes to sidecar
   └─► Sidecar responds with lazy-loaded data
```

---

## TDD Implementation Plan

### Phase 1: Foundation (Tests First)

#### Test 1.1: Operation Classification

**File:** `src/test/frontend/sidecar/routing_test.cljs`

```clojure
(ns frontend.sidecar.routing-test
  (:require [cljs.test :refer [deftest testing is are]]
            [frontend.sidecar.routing :as routing]))

(deftest worker-only-ops-test
  (testing "parsing operations must go to worker"
    (are [op] (routing/worker-only-op? op)
      :thread-api/reset-file
      :thread-api/gc-graph
      :thread-api/export-db
      :thread-api/import-db
      :thread-api/fix-broken-graph))

  (testing "vec-search operations must go to worker"
    (are [op] (routing/worker-only-op? op)
      :thread-api/vec-search-search
      :thread-api/vec-search-embedding-graph))

  (testing "RTC operations must go to worker"
    (are [op] (routing/worker-only-op? op)
      :thread-api/rtc-start
      :thread-api/rtc-stop)))

(deftest sidecar-preferred-ops-test
  (testing "query operations prefer sidecar"
    (are [op] (routing/sidecar-preferred-op? op)
      :thread-api/q
      :thread-api/pull
      :thread-api/pull-many
      :thread-api/datoms))

  (testing "outliner operations prefer sidecar"
    (are [op] (routing/sidecar-preferred-op? op)
      :thread-api/apply-outliner-ops
      :thread-api/get-page-trees
      :thread-api/get-file-writes)))

(deftest routing-decision-test
  (testing "routes parsing to worker regardless of sidecar"
    (is (= :worker (routing/route-operation :thread-api/reset-file true)))
    (is (= :worker (routing/route-operation :thread-api/reset-file false))))

  (testing "routes queries to sidecar when available"
    (is (= :sidecar (routing/route-operation :thread-api/q true)))
    (is (= :worker (routing/route-operation :thread-api/q false))))

  (testing "routes unknown ops to worker by default"
    (is (= :worker (routing/route-operation :thread-api/unknown-op true)))))
```

#### Test 1.2: Dual Worker State

**File:** `src/test/frontend/sidecar/state_test.cljs`

```clojure
(ns frontend.sidecar.state-test
  (:require [cljs.test :refer [deftest testing is use-fixtures]]
            [frontend.state :as state]))

(use-fixtures :each
  {:before #(do
              (reset! state/*db-worker nil)
              (reset! state/*sidecar-worker nil))
   :after #(do
             (reset! state/*db-worker nil)
             (reset! state/*sidecar-worker nil))})

(deftest dual-worker-atoms-test
  (testing "both atoms exist and are independent"
    (is (nil? @state/*db-worker))
    (is (nil? @state/*sidecar-worker))

    (reset! state/*db-worker :test-worker)
    (is (= :test-worker @state/*db-worker))
    (is (nil? @state/*sidecar-worker))

    (reset! state/*sidecar-worker :test-sidecar)
    (is (= :test-worker @state/*db-worker))
    (is (= :test-sidecar @state/*sidecar-worker))))

(deftest sidecar-ready-test
  (testing "sidecar-ready? checks sidecar atom"
    (is (false? (state/sidecar-ready?)))
    (reset! state/*sidecar-worker identity)
    (is (true? (state/sidecar-ready?)))))
```

#### Test 1.3: Invocation Routing

**File:** `src/test/frontend/sidecar/invoke_test.cljs`

```clojure
(ns frontend.sidecar.invoke-test
  (:require [cljs.test :refer [deftest testing is async use-fixtures]]
            [promesa.core :as p]
            [frontend.state :as state]
            [frontend.sidecar.routing :as routing]))

(def ^:dynamic *worker-calls* (atom []))
(def ^:dynamic *sidecar-calls* (atom []))

(defn mock-worker [qkw _direct? & args]
  (swap! *worker-calls* conj {:op qkw :args args})
  (p/resolved {:from :worker}))

(defn mock-sidecar [qkw _direct? & args]
  (swap! *sidecar-calls* conj {:op qkw :args args})
  (p/resolved {:from :sidecar}))

(use-fixtures :each
  {:before #(do
              (reset! *worker-calls* [])
              (reset! *sidecar-calls* [])
              (reset! state/*db-worker mock-worker)
              (reset! state/*sidecar-worker nil))
   :after #(do
             (reset! state/*db-worker nil)
             (reset! state/*sidecar-worker nil))})

(deftest worker-only-routing-test
  (async done
    (p/let [result (routing/<invoke-db :thread-api/reset-file "repo" "path" "content" {})]
      (is (= {:from :worker} result))
      (is (= 1 (count @*worker-calls*)))
      (is (= :thread-api/reset-file (-> @*worker-calls* first :op)))
      (done))))

(deftest sidecar-routing-when-available-test
  (async done
    (reset! state/*sidecar-worker mock-sidecar)
    (p/let [result (routing/<invoke-db :thread-api/q "repo" '[[:find ?e :where [?e :block/name]]])]
      (is (= {:from :sidecar} result))
      (is (= 0 (count @*worker-calls*)))
      (is (= 1 (count @*sidecar-calls*)))
      (done))))

(deftest fallback-to-worker-test
  (async done
    ;; No sidecar available
    (p/let [result (routing/<invoke-db :thread-api/q "repo" '[[:find ?e :where [?e :block/name]]])]
      (is (= {:from :worker} result))
      (is (= 1 (count @*worker-calls*)))
      (done))))
```

### Phase 2: Implementation

#### Step 2.1: Add `*sidecar-worker` Atom

**File:** `src/main/frontend/state.cljs`

```clojure
;; Add new atom (around line 35)
(defonce *sidecar-worker (atom nil))

;; Add helper function
(defn sidecar-ready?
  "Check if sidecar worker is available for queries."
  []
  (some? @*sidecar-worker))
```

#### Step 2.2: Create Routing Module

**File:** `src/main/frontend/sidecar/routing.cljs` (NEW)

```clojure
(ns frontend.sidecar.routing
  "Smart routing of database operations to worker or sidecar.

   The hybrid architecture uses:
   - Web worker for parsing (mldoc is JavaScript-only)
   - JVM sidecar for queries (lazy loading via IStorage)

   This module determines which backend to use for each operation."
  (:require [frontend.state :as state]
            [promesa.core :as p]))

;; =============================================================================
;; Operation Classification
;; =============================================================================

(def worker-only-ops
  "Operations that MUST go to web worker.
   These either require mldoc parsing or worker-specific features."
  #{;; File parsing (requires mldoc)
    :thread-api/reset-file
    :thread-api/gc-graph
    :thread-api/export-db
    :thread-api/import-db
    :thread-api/fix-broken-graph

    ;; Vec search (uses browser WebGPU)
    :thread-api/vec-search-embedding-model-info
    :thread-api/vec-search-init-embedding-model
    :thread-api/vec-search-load-model
    :thread-api/vec-search-embedding-graph
    :thread-api/vec-search-search
    :thread-api/vec-search-cancel-indexing
    :thread-api/vec-search-update-index-info
    :thread-api/vec-upsert-blocks
    :thread-api/vec-delete-blocks
    :thread-api/vec-search-blocks

    ;; RTC (WebSocket in browser)
    :thread-api/rtc-start
    :thread-api/rtc-stop
    :thread-api/rtc-sync-graph!
    :thread-api/rtc-status

    ;; Mobile-specific
    :thread-api/mobile-logs
    :thread-api/write-log
    :thread-api/mobile-get-logs})

(def sidecar-preferred-ops
  "Operations that PREFER sidecar when available.
   These benefit from lazy loading and JVM performance."
  #{;; Query operations
    :thread-api/q
    :thread-api/pull
    :thread-api/pull-many
    :thread-api/datoms

    ;; Outliner operations (when sidecar has data)
    :thread-api/apply-outliner-ops
    :thread-api/get-page-trees
    :thread-api/get-file-writes})

(defn worker-only-op?
  "Check if operation must go to worker."
  [qkw]
  (contains? worker-only-ops qkw))

(defn sidecar-preferred-op?
  "Check if operation prefers sidecar."
  [qkw]
  (contains? sidecar-preferred-ops qkw))

(defn route-operation
  "Determine which backend to use for an operation.

   Arguments:
   - qkw: The operation keyword
   - sidecar-available?: Whether sidecar is ready

   Returns :worker or :sidecar"
  [qkw sidecar-available?]
  (cond
    ;; Worker-only ops always go to worker
    (worker-only-op? qkw)
    :worker

    ;; Sidecar-preferred ops go to sidecar if available
    (and sidecar-available? (sidecar-preferred-op? qkw))
    :sidecar

    ;; Default to worker (authoritative source)
    :else
    :worker))

;; =============================================================================
;; Invocation API
;; =============================================================================

(defn <invoke-db
  "Invoke a database operation, routing to appropriate backend.

   This is the main entry point for hybrid architecture.
   - Parsing ops -> web worker (authoritative)
   - Query ops -> sidecar (lazy loading) with fallback to worker

   Arguments:
   - qkw: Operation keyword (e.g., :thread-api/q)
   - args: Operation arguments

   Returns a promise with the result."
  [qkw & args]
  (let [sidecar-available? (state/sidecar-ready?)
        target (route-operation qkw sidecar-available?)]
    (if (= target :sidecar)
      ;; Route to sidecar
      (-> (apply @state/*sidecar-worker qkw false args)
          (p/catch (fn [err]
                     ;; Fallback to worker on sidecar error
                     (js/console.warn "Sidecar error, falling back to worker:" err)
                     (apply state/<invoke-db-worker qkw args))))
      ;; Route to worker
      (apply state/<invoke-db-worker qkw args))))

(defn <invoke-db-direct-pass
  "Like <invoke-db but with direct-pass semantics (no Transit round-trip)."
  [qkw & args]
  (let [sidecar-available? (state/sidecar-ready?)
        target (route-operation qkw sidecar-available?)]
    (if (= target :sidecar)
      (-> (apply @state/*sidecar-worker qkw true args)
          (p/catch (fn [err]
                     (js/console.warn "Sidecar error, falling back to worker:" err)
                     (apply state/<invoke-db-worker-direct-pass qkw args))))
      (apply state/<invoke-db-worker-direct-pass qkw args))))
```

#### Step 2.3: Fix Sidecar Startup

**File:** `src/main/frontend/sidecar/core.cljs`

Change `start-sidecar!` to use `*sidecar-worker` instead of `*db-worker`:

```clojure
;; BEFORE (line 126):
(reset! state/*db-worker worker-fn)

;; AFTER:
(reset! state/*sidecar-worker worker-fn)
```

Also update `stop-sidecar!`:

```clojure
;; BEFORE:
(reset! state/*db-worker nil)

;; AFTER:
(reset! state/*sidecar-worker nil)
```

And for WebSocket sidecar in `start-websocket-sidecar!`:

```clojure
;; BEFORE:
(reset! state/*db-worker worker-fn)

;; AFTER:
(reset! state/*sidecar-worker worker-fn)
```

#### Step 2.4: Update Callers to Use Routing

Files that need to use `routing/<invoke-db` instead of `state/<invoke-db-worker` for query operations:

1. **`src/main/frontend/db/async.cljs`** - Query functions
2. **`src/main/frontend/db/async/util.cljs`** - Utility query functions
3. **Components that make queries**

However, keep using `state/<invoke-db-worker` for:
- File operations in `src/main/frontend/handler/file_based/`
- Import/export operations

### Phase 3: Integration Tests

#### Test 3.1: Full Lifecycle Test

**File:** `src/test/frontend/sidecar/integration_test.cljs`

```clojure
(ns frontend.sidecar.integration-test
  (:require [cljs.test :refer [deftest testing is async use-fixtures]]
            [promesa.core :as p]
            [frontend.state :as state]
            [frontend.sidecar.core :as sidecar]
            [frontend.sidecar.routing :as routing]
            [frontend.test.helper :as test-helper]))

(deftest ^:integration hybrid-startup-test
  (testing "web worker and sidecar start without conflict"
    (async done
      (test-helper/start-and-destroy-db
       (fn []
         (p/let [;; Verify worker is set
                 _ (is (some? @state/*db-worker))
                 ;; Start sidecar
                 _ (sidecar/start-db-backend! {})
                 ;; Verify BOTH are set
                 _ (is (some? @state/*db-worker))
                 _ (is (some? @state/*sidecar-worker))
                 ;; Verify they're different
                 _ (is (not= @state/*db-worker @state/*sidecar-worker))]
           (done)))))))

(deftest ^:integration parse-then-query-test
  (testing "files parse via worker, queries via sidecar"
    (async done
      (test-helper/start-and-destroy-db
       (fn []
         (p/let [;; Parse a file (goes to worker)
                 _ (state/<invoke-db-worker :thread-api/reset-file
                                           "test-repo"
                                           "pages/test.md"
                                           "- Block 1\n- Block 2"
                                           {})
                 ;; Initial sync to sidecar
                 _ (initial-sync/sync-graph-to-sidecar! "test-repo")
                 ;; Query via hybrid routing (should use sidecar)
                 result (routing/<invoke-db :thread-api/q
                                            "test-repo"
                                            ['{:find [?e]
                                               :where [[?e :block/content]]}])]
           (is (seq result) "Query should return blocks")
           (done)))))))
```

### Phase 4: E2E Tests

Update Playwright tests to verify hybrid architecture:

**File:** `e2e-electron/tests/hybrid-architecture.spec.ts`

```typescript
import { test, expect, _electron as electron } from '@playwright/test';
import { ElectronApplication, Page } from 'playwright';

test.describe('Hybrid Architecture', () => {
  let electronApp: ElectronApplication;
  let page: Page;

  test.beforeAll(async () => {
    electronApp = await electron.launch({
      args: ['.'],
      cwd: 'static/out/Logseq-win32-x64'
    });
    page = await electronApp.firstWindow();
  });

  test.afterAll(async () => {
    await electronApp.close();
  });

  test('creates pages and blocks via hybrid architecture', async () => {
    // Wait for app to load
    await page.waitForSelector('.cp__sidebar-main-content', { timeout: 30000 });

    // Create a new page
    await page.click('[data-testid="left-sidebar-new-page"]');
    await page.fill('input[placeholder*="title"]', 'Test Page');
    await page.press('input[placeholder*="title"]', 'Enter');

    // Wait for page to be created
    await page.waitForSelector('.page-title:has-text("Test Page")');

    // Type a block
    await page.click('.block-editor');
    await page.keyboard.type('Test block content');
    await page.keyboard.press('Enter');

    // Verify block is saved (query should return it)
    const blocks = await page.evaluate(async () => {
      return await window.logseq.api.get_block('Test block content');
    });

    expect(blocks).toBeTruthy();
    expect(blocks.content).toContain('Test block content');
  });

  test('no critical console errors', async () => {
    const errors: string[] = [];
    page.on('console', msg => {
      if (msg.type() === 'error') {
        errors.push(msg.text());
      }
    });

    // Do some operations
    await page.click('[data-testid="left-sidebar-home"]');
    await page.waitForTimeout(2000);

    // Filter out expected errors
    const criticalErrors = errors.filter(e =>
      !e.includes('favicon') &&
      !e.includes('ResizeObserver')
    );

    expect(criticalErrors).toHaveLength(0);
  });
});
```

---

## File Changes Summary

### New Files

| File | Purpose |
|------|---------|
| `src/main/frontend/sidecar/routing.cljs` | Operation routing logic |
| `src/test/frontend/sidecar/routing_test.cljs` | Routing unit tests |
| `src/test/frontend/sidecar/state_test.cljs` | State management tests |
| `src/test/frontend/sidecar/invoke_test.cljs` | Invocation tests |
| `src/test/frontend/sidecar/integration_test.cljs` | Integration tests |
| `e2e-electron/tests/hybrid-architecture.spec.ts` | E2E tests |

### Modified Files

| File | Change |
|------|--------|
| `src/main/frontend/state.cljs` | Add `*sidecar-worker` atom, `sidecar-ready?` fn |
| `src/main/frontend/sidecar/core.cljs` | Use `*sidecar-worker` instead of `*db-worker` |
| `src/main/frontend/db/async.cljs` | Use `routing/<invoke-db` for queries |
| `src/main/frontend/db/async/util.cljs` | Use `routing/<invoke-db` for queries |

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Breaking existing functionality | Medium | High | Comprehensive tests, gradual rollout |
| Performance regression | Low | Medium | Benchmark before/after |
| State synchronization issues | Medium | High | Clear sync protocol, error handling |
| Race conditions | Medium | Medium | Proper async coordination |

---

## Rollback Strategy

If issues arise, rollback by:

1. Revert `routing.cljs` usage back to `state/<invoke-db-worker`
2. Revert `core.cljs` to use `*db-worker` for sidecar
3. This effectively disables sidecar (routes everything to worker)

The rollback doesn't require removing code, just reverting the routing changes.

---

## Success Criteria

- [ ] All unit tests pass
- [ ] All existing CLJS tests pass
- [ ] All JVM sidecar tests pass
- [ ] E2E tests pass (create page, create block, query)
- [ ] No console errors related to "Unknown operation"
- [ ] Initial sync completes with data
- [ ] Queries return expected results
- [ ] Performance is not degraded (benchmark)
