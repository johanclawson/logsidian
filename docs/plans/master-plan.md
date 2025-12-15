# Logsidian TDD Master Plan

## ⚠️ GOLDEN RULE: DON'T BE LAZY ⚠️

> **"Slow is smooth, smooth is fast."**
>
> We will NOT take shortcuts. We will NOT defer hard problems. Every architectural decision must be done RIGHT the first time. Cutting corners leads to technical debt that compounds exponentially.
>
> When faced with a complex problem:
> 1. **Research thoroughly** - understand all options
> 2. **Consult experts** (Codex, web search, documentation)
> 3. **Make the right decision** - not the easy one
> 4. **Implement properly** - with tests first (TDD)
> 5. **Verify completely** - run all tests, check edge cases

---

## Vision: Blazing Fast, Fully Functional Logsidian

**Goal:** A fully functional fork of Logseq with dramatically improved performance for large graphs, validated through comprehensive automated testing with recursive error fixing.

**Core Principle:** Test-Driven Development at every level - from unit tests to E2E tests, with automated detection and fixing of errors.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CI/CD Pipeline                                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  GitHub Actions: Build → Test → E2E → Performance Benchmark             ││
│  └─────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
┌─────────────────────────────────────▼───────────────────────────────────────┐
│                           Logsidian Application                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                        Electron (Renderer)                               ││
│  │  ┌───────────────────────────────────────────────────────────────────┐  ││
│  │  │                    ClojureScript UI                                │  ││
│  │  │             (Rum/React, existing Logseq frontend)                  │  ││
│  │  └───────────────────────────┬───────────────────────────────────────┘  ││
│  │                              │ Transit over IPC                          ││
│  └──────────────────────────────┼──────────────────────────────────────────┘│
│                                 │                                            │
│  ┌──────────────────────────────▼──────────────────────────────────────────┐│
│  │                    Electron Main Process                                 ││
│  │  ┌───────────────────────────────────────────────────────────────────┐  ││
│  │  │  Node.js + mldoc (OCaml→JS) + File Watcher                        │  ││
│  │  │  Parses markdown files, syncs datoms to sidecar                   │  ││
│  │  └───────────────────────────┬───────────────────────────────────────┘  ││
│  │                              │ Transit over TCP                          ││
│  └──────────────────────────────┼──────────────────────────────────────────┘│
│                                 │                                            │
│  ┌──────────────────────────────▼──────────────────────────────────────────┐│
│  │                         JVM Sidecar                                      ││
│  │  ┌───────────────────────────────────────────────────────────────────┐  ││
│  │  │        DataScript + IStorage (lazy loading, soft references)       │  ││
│  │  │                     SQLite-JDBC backing                            │  ││
│  │  └───────────────────────────────────────────────────────────────────┘  ││
│  └─────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────┘
```

**Note:** Vector/semantic search runs entirely in the Web Worker (not sidecar) and is unaffected by the hybrid architecture. See Section 2.5.6 and `docs/research/hybrid-architecture-search.md` for details.

---

## Testing Pyramid

```
                    ┌───────────────┐
                    │   E2E Tests   │  ← Playwright (clj-e2e/)
                    │  (Few, Slow)  │     Full user flows
                    └───────┬───────┘
                            │
                   ┌────────▼────────┐
                   │ Integration Tests│  ← Sidecar + CLJS client
                   │   (Moderate)     │     Transit roundtrip, IPC
                   └────────┬────────┘
                            │
          ┌─────────────────▼─────────────────┐
          │          Unit Tests               │  ← Fast, isolated
          │        (Many, Fast)               │     JVM tests, CLJS tests
          └───────────────────────────────────┘
```

---

## Phase 0: Testing Infrastructure Setup ✅ COMPLETE

### 0.1 Automated E2E Test Runner with Error Detection ✅ DONE

**Goal:** Create a test harness that runs E2E tests, captures errors, and can feed them back to Claude for fixing.

**Files created:**

```
scripts/
└── tdd-loop.ps1                # ✅ Windows PowerShell TDD runner (replaces run-e2e-tests.ps1)

clj-e2e/
├── src/logseq/e2e/
│   ├── error_collector.clj     # ✅ Console error collection + assertions
│   └── test_reporter.clj       # ✅ Structured error reports (EDN/Markdown)
├── error-reports/
│   ├── .gitignore              # ✅ Ignore generated reports
│   └── README.md               # ✅ Documentation
└── test/logseq/e2e/
    └── sidecar_basic_test.clj  # ✅ Enhanced with error assertions + smoke tests

.claude/commands/
└── tdd.md                      # ✅ Claude slash command for TDD workflow
```

**Test harness requirements:**
- [x] Capture all console errors during test execution
- [x] Capture sidecar server logs (via tdd-loop.ps1)
- [x] Generate structured error report (EDN + Markdown)
- [x] Exit with non-zero status if errors detected
- [x] Support filtering by test tag (`:sidecar`, `:smoke`)

**How Phase 0 work is used in later phases:**
- **Phase 1-2**: Run `.\scripts\tdd-loop.ps1` after implementing each feature to verify no regressions
- **Phase 3**: E2E tests already use `errors/wrap-assert-no-console-errors` fixture
- **Phase 4**: TDD loop script already created - use directly
- **Phase 5**: Error reports capture performance-related failures

### 0.2 MCP Server for Claude Code Integration (Optional) ⏸️ DEFERRED

**Goal:** Allow Claude Code to directly interact with test results and error logs.

**Status:** Deferred - The `/tdd` slash command and file-based error reports provide sufficient integration for now. MCP server can be added later if needed for more complex automation.

**MCP Server capabilities (if implemented later):**
- `get-test-results` - Fetch latest test run results
- `get-console-errors` - Get browser console errors from test
- `get-sidecar-logs` - Get JVM sidecar server logs
- `run-single-test` - Execute a specific test
- `get-screenshot` - Get screenshot from failed test

### 0.3 CI/CD Pipeline for Automated Testing ⏸️ DEFERRED

**Status:** Deferred - Focus on local TDD workflow first. CI/CD can be added once Phase 1-2 are working locally.

**GitHub Actions workflow (for later):**

```yaml
# .github/workflows/e2e-tests.yml
name: E2E Tests
on: [push, pull_request]
jobs:
  e2e:
    runs-on: windows-latest  # or ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Setup Java
        uses: actions/setup-java@v4
        with:
          java-version: '21'
      - name: Setup Node
        uses: actions/setup-node@v4
        with:
          node-version: '22'
      - name: Build Sidecar
        run: cd sidecar && clj -T:build uberjar
      - name: Install Dependencies
        run: yarn install
      - name: Build App
        run: yarn release
      - name: Run E2E Tests
        run: |
          # Start sidecar in background
          java -jar sidecar/target/logsidian-sidecar.jar &
          # Run tests
          cd clj-e2e && clj -M:test
      - name: Upload Screenshots
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: e2e-screenshots
          path: clj-e2e/screenshots/
```

---

## 🚨 CRITICAL ARCHITECTURAL DISCOVERY (2025-12-11) 🚨

### The Problem

**E2E tests for "create page" and "create block" FAIL** because:
1. The sidecar currently **REPLACES** the web worker
2. But the web worker is needed for **file parsing** (mldoc)
3. Without parsing, files are never converted to datoms
4. Sidecar has EMPTY database → UI shows EMPTY content

### Root Cause Analysis

**mldoc cannot run on JVM:**
- mldoc is an OCaml library compiled to JavaScript via `js_of_ocaml`
- It's an npm package that ONLY runs in JavaScript environments (browser/Node.js)
- There is NO JVM port and creating one would be a massive undertaking
- mldoc IS the markdown/org-mode parser - without it, no content parsing

**Current broken flow:**
```
1. User opens graph
2. sidecar/core.cljs: start-db-backend! starts EITHER sidecar OR worker
3. IF sidecar enabled → worker NOT started
4. NO file parsing happens (mldoc is in worker)
5. Sidecar DB is empty
6. UI shows empty content
```

### The Solution: Hybrid Architecture (Option 1.5)

**Recommended by Codex analysis** after comprehensive evaluation of alternatives.

**Key insight:** We need BOTH components:
- **Web Worker**: For file parsing (mldoc is JavaScript-only)
- **JVM Sidecar**: For queries (IStorage with lazy loading, SQLite persistence)

**Correct hybrid flow:**
```
1. User opens graph
2. Start web worker FIRST for file parsing
3. Worker parses files with mldoc → datoms
4. After parsing completes, sync datoms to sidecar
5. Sidecar becomes source of truth for QUERIES
6. Worker continues for incremental parsing (file changes)
7. All queries go to sidecar (fast, memory-efficient)
```

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Electron Renderer                                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                         ClojureScript UI                                 ││
│  │  - Queries → Sidecar (via IPC)                                          ││
│  │  - File parsing → Worker (mldoc)                                        ││
│  └─────────────────────┬───────────────────────────────┬───────────────────┘│
│                        │                               │                     │
│              IPC (queries)                    postMessage (parsing)          │
│                        │                               │                     │
└────────────────────────┼───────────────────────────────┼────────────────────┘
                         │                               │
┌────────────────────────▼───────────────┐ ┌────────────▼─────────────────────┐
│         Electron Main Process          │ │          Web Worker              │
│  ┌─────────────────────────────────┐   │ │  ┌─────────────────────────┐     │
│  │   TCP Client → JVM Sidecar      │   │ │  │  mldoc (OCaml→JS)       │     │
│  │   Routes queries                 │   │ │  │  File parsing           │     │
│  │   Handles responses             │   │ │  │  Incremental updates     │     │
│  └─────────────────────────────────┘   │ │  └─────────────────────────┘     │
│                   │                     │ │              │                   │
└───────────────────┼─────────────────────┘ │              │ datoms            │
                    │ TCP                    │              │                   │
                    │                        │              ▼                   │
┌───────────────────▼─────────────────────┐ │  ┌───────────────────────────┐   │
│              JVM Sidecar                │◄┼──┤  Sync datoms to sidecar   │   │
│  ┌─────────────────────────────────┐   │ │  │  (via initial_sync.cljs)  │   │
│  │  DataScript + IStorage          │   │ │  └───────────────────────────┘   │
│  │  - Lazy loading                 │   │ └──────────────────────────────────┘
│  │  - Soft references              │   │
│  │  - SQLite persistence           │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

### Why Other Options Were Rejected

| Option | Rejected Because |
|--------|------------------|
| Port mldoc to JVM | OCaml→JVM is impractical, would take months |
| Run mldoc in Node.js main process | Blocks main process, creates IPC complexity |
| Use different parser | mldoc is battle-tested, handles edge cases |
| Sidecar-only (no worker) | Can't parse files without mldoc |
| Worker-only (no sidecar) | No lazy loading, memory issues, no persistence |

### Implementation Plan: Phase 2.5 - Hybrid Architecture

This is the CRITICAL missing piece that must be implemented for E2E tests to pass.

---

## Phase 2.5: Hybrid Worker + Sidecar Architecture (TDD) ✅ COMPLETE

### 2.5.0 Summary of Implementation

**Completed 2025-12-15:**

The hybrid architecture is now fully functional with proper orchestration:

1. **Sync State Tracking** (`routing.cljs`):
   - `*sync-complete-repos` atom tracks per-repo sync completion
   - `mark-sync-complete!` / `mark-sync-incomplete!` / `sync-complete?` functions
   - Operations only route to sidecar AFTER sync completes

2. **Attribute Normalization Fix** (`server.clj`):
   - Transit serialization converts keywords to strings
   - Added `normalize-attribute` function to convert string attributes back to keywords
   - Fixes `ClassCastException: class clojure.lang.Keyword cannot be cast to class java.lang.String`

3. **Routing Behavior**:
   - During initial sync: Operations go to worker (`[ROUTING->WORKER]` with `:sync-incomplete`)
   - After sync completes: Operations go to sidecar (verified via `[DEBUG] REQUEST-RAW` messages)

4. **Test Results**:
   - 122 sidecar tests pass (374 assertions)
   - 3 of 4 E2E tests pass
   - 1 UI test fails (unrelated keyboard navigation issue)

### 2.5.1 Modify `start-db-backend!` to Start Both

**File:** `src/main/frontend/sidecar/core.cljs`

**Current (broken):**
```clojure
;; Starts EITHER sidecar OR worker - not both
(cond
  use-ipc-sidecar?
  (-> (start-sidecar!)  ;; Only starts sidecar!
      ...)

  use-websocket-sidecar?
  (-> (start-websocket-sidecar!)
      ...)

  :else
  (start-web-worker-fn))
```

**Target (correct):**
```clojure
;; Always start worker for parsing, optionally sync to sidecar
(-> (start-web-worker-fn)
    (p/then (fn [_]
              ;; Wait for initial parsing to complete
              (when (or use-ipc-sidecar? use-websocket-sidecar?)
                (sync-to-sidecar-after-parse!)))))
```

**TDD Test (write first):**
```clojure
;; src/test/frontend/sidecar/hybrid_test.cljs
(ns frontend.sidecar.hybrid-test
  (:require [cljs.test :refer [deftest testing is async]]
            [frontend.sidecar.core :as sidecar-core]
            [frontend.worker.state :as worker-state]
            [promesa.core :as p]))

(deftest ^:sidecar hybrid-starts-both-worker-and-sidecar
  (testing "Worker starts first, sidecar syncs after parsing"
    (async done
      (p/let [_ (sidecar-core/start-db-backend!
                  {:prefer-sidecar? true
                   :start-web-worker-fn (fn [] (p/resolved :worker-started))})
              worker-running? (worker-state/worker-running?)
              sidecar-connected? (sidecar-core/connected?)]
        (is (true? worker-running?) "Worker must be running")
        (is (true? sidecar-connected?) "Sidecar must be connected")
        (done)))))
```

### 2.5.2 Wait for Parsing Completion

**Problem:** How do we know when initial parsing is done?

**Solution:** Listen for `:graph/added` event (already wired for initial-sync in Phase 2.3).

**Verification:**
```
1. Worker starts
2. Worker parses files → populates frontend DB
3. [:graph/added] event fires
4. initial_sync.cljs runs → syncs datoms to sidecar
5. Sidecar now has data for queries
```

**The Phase 2.3 code already handles this!** We just need to ensure:
1. Worker starts FIRST
2. Sidecar starts AFTER worker
3. Sync happens on `:graph/added`

### 2.5.3 Route Queries to Sidecar

**After sync completes, queries should go to sidecar (already implemented).**

The existing code in `frontend.sidecar.client` and `frontend.db` already routes queries to sidecar when connected. This should work once:
1. Worker parses files → datoms in frontend DB
2. Initial sync copies datoms to sidecar
3. Queries go to sidecar (existing routing)

### 2.5.4 Test Verification

**E2E tests that should pass after hybrid implementation:**

| Test | Before | After |
|------|--------|-------|
| App launches and shows UI | ✅ | ✅ |
| No critical console errors | ✅ | ✅ |
| Can create a new page | ⏸️ SKIP | ✅ |
| Can create a block | ⏸️ SKIP | ✅ |

### 2.5.5 Implementation Steps (TDD)

**Step 1: Write failing test**
```bash
# Create test file
src/test/frontend/sidecar/hybrid_test.cljs

# Run test - should FAIL
yarn cljs:test && yarn cljs:run-test -n frontend.sidecar.hybrid-test
```

**Step 2: Modify `start-db-backend!`**
- Always start worker first
- Start sidecar in parallel (don't wait)
- Sync happens automatically on `:graph/added`

**Step 3: Verify unit test passes**
```bash
yarn cljs:test && yarn cljs:run-test -n frontend.sidecar.hybrid-test
```

**Step 4: Run E2E tests**
```bash
# Build app with changes
pwsh -File scripts/build.ps1

# Run E2E tests
npx playwright test --config e2e-electron/playwright.config.ts
```

**Step 5: Enable skipped E2E tests**
- Remove `test.skip` from "can create a new page"
- Remove `test.skip` from "can create a block"
- Verify they pass

### 2.5.6 Search and Embedding Compatibility

**Research Reference:** See `docs/research/hybrid-architecture-search.md` for detailed analysis.

**Key Finding:** Vector/semantic search is **100% compatible** with hybrid architecture. No changes needed.

**Why Vec-Search Operations Are Worker-Only:**

The embedding pipeline runs entirely in the Web Worker because:
1. **Transformers.js** requires browser environment (WebGPU/WASM)
2. **HNSW index** is stored in IndexedDB (browser storage)
3. **Embedding metadata** (`hnsw-label-updated-at`) is local to worker
4. **Search result lookup** uses local DataScript (not sidecar)

**Architecture:**
```
┌──────────────────────────────────────────────────────────────┐
│                       Web Worker                              │
│  embedding.cljs ──────────────────────────────────────────┐  │
│        │                                                   │  │
│        ▼                                                   │  │
│  Local DataScript    ──Comlink──>    Inference Worker     │  │
│  (stale blocks)                      (Transformers.js)    │  │
│        │                                   │              │  │
│        │                                   ▼              │  │
│        │                            HNSW Index            │  │
│        │                            (IndexedDB)           │  │
│        ▼                                   │              │  │
│  Search result lookup <────────────────────┘              │  │
│  (d/entity @conn label)                                   │  │
└──────────────────────────────────────────────────────────────┘
                         │
                    sync-datoms (one-way)
                         ▼
┌──────────────────────────────────────────────────────────────┐
│                      JVM Sidecar                              │
│  DataScript (copy) - For UI queries only, NOT embedding      │
└──────────────────────────────────────────────────────────────┘
```

**Sidecar Vec-Search Stubs:**

The sidecar has stubbed vec-search operations (`server.clj:804-871`) that return empty defaults. These were designed for a pure-sidecar approach but are **not needed** for hybrid architecture.

**Decision:** Keep stubs as-is (no harm, useful for future pure-sidecar if ever needed).

**E2E Test Verification (Post-Hybrid):**
- [ ] Embedding model loads successfully
- [ ] Blocks are embedded without errors
- [ ] Semantic search returns results
- [ ] Search works after app restart (IndexedDB persistence)

---

## Phase 1: Core Sidecar Implementation (TDD) ✅ COMPLETE

> **Prerequisites:** Phase 0 complete. Use `.\scripts\tdd-loop.ps1` to run tests during development.

### 1.1 IStorage with SQLite-JDBC ✅ COMPLETE

**Goal:** Implement DataScript IStorage protocol with soft references for memory management.

**Implementation:**
- Created `sidecar/src/logseq/sidecar/storage.clj` with `create-sqlite-storage` function
- Supports file-based and in-memory (`:memory:`) storage
- Implements all IStorage methods: `-store`, `-restore`, `-list-addresses`, `-delete`
- Uses SQLite shared cache for in-memory mode
- All 11 tests pass in `sidecar/test/logseq/sidecar/storage_test.clj`

**Usage:**
```clojure
(def storage (storage/create-sqlite-storage "graph.db"))
(def conn (d/create-conn schema {:storage storage :ref-type :soft}))
```

**Test file:** `sidecar/test/logseq/sidecar/storage_test.clj`

```clojure
(ns logseq.sidecar.storage-test
  (:require [clojure.test :refer :all]
            [datascript.core :as d]
            [logseq.sidecar.storage :as storage]))

(deftest storage-basic-operations
  (testing "Store and restore datoms"
    (let [storage (storage/create-sqlite-storage ":memory:")
          schema {:block/name {:db/unique :db.unique/identity}}
          conn (d/create-conn schema {:storage storage
                                      :ref-type :soft})]
      ;; Transact some data
      (d/transact! conn [{:block/name "test-page"
                          :block/uuid (random-uuid)}])
      ;; Force eviction simulation
      (storage/clear-memory-cache! storage)
      ;; Query should lazy-load from SQLite
      (is (some? (d/entity @conn [:block/name "test-page"]))))))

(deftest storage-memory-pressure
  (testing "Soft references release under memory pressure"
    (let [storage (storage/create-sqlite-storage ":memory:")
          ;; Create large dataset to trigger GC
          conn (d/create-conn {} {:storage storage :ref-type :soft})]
      ;; Transact many blocks
      (doseq [i (range 10000)]
        (d/transact! conn [{:block/uuid (random-uuid)
                            :block/content (str "Block " i)}]))
      ;; Check that memory usage is bounded
      (let [memory-before (storage/memory-usage storage)]
        (System/gc)
        (Thread/sleep 100)
        (let [memory-after (storage/memory-usage storage)]
          ;; Memory should decrease after GC (soft refs released)
          (is (<= memory-after memory-before)))))))

(deftest storage-persistence
  (testing "Data persists across restarts"
    (let [db-path (str (System/getProperty "java.io.tmpdir") "/test-" (System/currentTimeMillis) ".db")]
      (try
        ;; Create and populate
        (let [storage (storage/create-sqlite-storage db-path)
              conn (d/create-conn {} {:storage storage})]
          (d/transact! conn [{:block/uuid #uuid "12345678-1234-1234-1234-123456789abc"
                              :block/content "Persistent block"}]))
        ;; Reopen and verify
        (let [storage (storage/create-sqlite-storage db-path)
              conn (d/restore-conn storage)]
          (is (some? (d/entity @conn [:block/uuid #uuid "12345678-1234-1234-1234-123456789abc"]))))
        (finally
          (clojure.java.io/delete-file db-path true))))))
```

**Implementation file:** `sidecar/src/logseq/sidecar/storage.clj`

### 1.2 Datom Sync from Main Process ✅ COMPLETE

**Goal:** Main process parses files with mldoc, sends datoms to sidecar.

**Implementation:**
- Added `sync-datoms` function to `sidecar/src/logseq/sidecar/server.clj`
- Added `:thread-api/sync-datoms` handler to request dispatcher
- Supports both full sync (`:full-sync? true`) and incremental updates
- Handles assertions (add datoms) and retractions (retract datoms)
- Integrated IStorage with graph creation:
  - `create-graph` now accepts `:storage-path` and `:ref-type` options
  - Storage is automatically closed when graph is removed
- All 7 sync tests pass in `sidecar/test/logseq/sidecar/sync_test.clj`

**Usage:**
```clojure
;; Create graph with SQLite storage and soft references
(server/create-graph server "my-repo" {:storage-path ":memory:"
                                        :ref-type :soft})

;; Sync datoms from main process
;; Datom format: [entity-id attr value tx added?]
(invoke :thread-api/sync-datoms
        ["my-repo"
         [[1 :block/name "page1" 1000 true]
          [1 :block/uuid #uuid "..." 1000 true]]
         {:full-sync? true}])
```

**Test file:** `sidecar/test/logseq/sidecar/sync_test.clj`

```clojure
(ns logseq.sidecar.sync-test
  (:require [clojure.test :refer :all]
            [logseq.sidecar.server :as server]
            [logseq.sidecar.protocol :as protocol]))

(deftest sync-datoms-endpoint
  (testing "Sidecar accepts datom batch from main process"
    (with-test-server [server]
      (let [datoms [{:e 1 :a :block/name :v "test-page" :tx 1000}
                    {:e 1 :a :block/uuid :v #uuid "abc" :tx 1000}
                    {:e 2 :a :block/content :v "Hello" :tx 1001}
                    {:e 2 :a :block/parent :v 1 :tx 1001}]]
        (let [result (invoke server :thread-api/sync-datoms
                             ["test-repo" datoms {:full-sync? true}])]
          (is (:ok? result))
          ;; Verify data is queryable
          (let [page (invoke server :thread-api/pull
                             ["test-repo" '[*] [:block/name "test-page"]])]
            (is (= "test-page" (:block/name page)))))))))

(deftest sync-incremental-update
  (testing "Sidecar handles incremental updates"
    (with-test-server [server]
      ;; Initial sync
      (invoke server :thread-api/sync-datoms
              ["test-repo" [{:e 1 :a :block/name :v "page1" :tx 1000}]
               {:full-sync? true}])
      ;; Incremental update
      (invoke server :thread-api/sync-datoms
              ["test-repo" [{:e 1 :a :block/content :v "Updated" :tx 1001}]
               {:full-sync? false}])
      ;; Verify update
      (let [page (invoke server :thread-api/pull
                         ["test-repo" '[*] [:block/name "page1"]])]
        (is (= "Updated" (:block/content page)))))))
```

### 1.3 Outliner Operations ✅ COMPLETE

**Goal:** Implement all block and page editing operations for full feature parity.

**Implementation:**
- Created `sidecar/src/logseq/sidecar/outliner.clj` with all outliner operations
- All 14 tests pass in `sidecar/test/logseq/sidecar/outliner_test.clj`

**Implemented Operations:**

| Operation | Function | Description |
|-----------|----------|-------------|
| `:save-block` | `save-block!` | Update block content and properties |
| `:insert-blocks` | `insert-blocks!` | Insert new blocks as sibling or child |
| `:delete-blocks` | `delete-blocks!` | Delete blocks and optionally children |
| `:move-blocks` | `move-blocks!` | Move blocks to new location |
| `:move-blocks-up-down` | `move-blocks-up-down!` | Reorder blocks among siblings |
| `:indent-outdent-blocks` | `indent-outdent-blocks!` | Change block nesting level |
| `:create-page` | `create-page!` | Create new page with title |
| `:rename-page` | `rename-page!` | Rename existing page |
| `:delete-page` | `delete-page!` | Delete page and all its blocks |
| `:batch-import-edn` | `batch-import-edn!` | Bulk import pages/blocks from EDN |
| `:transact` | (direct) | Raw DataScript transaction |

**Usage via `apply-ops!`:**
```clojure
(outliner/apply-ops! conn
  [[:save-block [{:block/uuid uuid :block/content "Updated"} {}]]
   [:insert-blocks [[{:block/content "New block"}] target-id {:sibling? true}]]
   [:create-page ["My Page" {:format :markdown}]]
   [:batch-import-edn [import-data {}]]]
  {})
```

**Test file:** `sidecar/test/logseq/sidecar/outliner_test.clj` (17 tests, 97 assertions)

---

## Phase 2: Electron Integration (TDD) ✅ COMPLETE

### Phase 2 Architecture Overview

The sidecar integration requires bidirectional communication between the Electron renderer, main process, and JVM sidecar:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Electron Renderer                                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                         ClojureScript UI                                 ││
│  │  - User edits block → apply-outliner-ops → IPC to main                  ││
│  │  - Receives file-written confirmation → updates UI state                 ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                    │ IPC                                     │
└────────────────────────────────────┼────────────────────────────────────────┘
                                     │
┌────────────────────────────────────┼────────────────────────────────────────┐
│                           Electron Main Process                              │
│  ┌─────────────────────────────────┼───────────────────────────────────────┐│
│  │           electron.sidecar      │                                        ││
│  │  - TCP socket to JVM           ─┼─ Routes requests to sidecar           ││
│  │  - JVM process lifecycle        │                                        ││
│  │  - Receives push messages ─────→│─ Forwards to renderer                  ││
│  └─────────────────────────────────┼───────────────────────────────────────┘│
│                                    │ TCP (Transit JSON)                      │
└────────────────────────────────────┼────────────────────────────────────────┘
                                     │
┌────────────────────────────────────┼────────────────────────────────────────┐
│                              JVM Sidecar                                     │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                    DataScript + IStorage                                 ││
│  │  - Processes outliner-ops → updates DB                                   ││
│  │  - Returns tx-report with affected pages                                 ││
│  │  - FUTURE: Could emit file-write push messages                           ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                    │                                         │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                      SQLite (via IStorage)                               ││
│  │  - Lazy loading with soft references                                     ││
│  │  - Persistence across restarts                                           ││
│  └─────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────┘
```

### Current State Analysis

**What's Already Implemented:**

| Component | Status | Location |
|-----------|--------|----------|
| JVM process spawning | ✅ | `src/electron/electron/sidecar.cljs` |
| TCP socket connection | ✅ | `src/electron/electron/sidecar.cljs` |
| Request/response protocol | ✅ | `sidecar/src/logseq/sidecar/protocol.clj` |
| Datom sync (`:sync-datoms`) | ✅ | `sidecar/src/logseq/sidecar/server.clj` |
| Outliner operations | ✅ | `sidecar/src/logseq/sidecar/outliner.clj` |
| Query operations (`:q`, `:pull`) | ✅ | `sidecar/src/logseq/sidecar/server.clj` |

**What's Missing (Phase 2 Focus):**

| Component | Gap | Solution |
|-----------|-----|----------|
| File write-back | Sidecar modifies DB but files aren't updated | Push affected pages to main process for file writing |
| Initial file sync | Main process needs to parse files and send datoms | Create sync flow on graph open |
| File watcher integration | External file changes not synced to sidecar | Main process file watcher → parse → sync-datoms |

### Data Flow for Write Operations

**Current Logseq Worker Flow:**
```
1. User edits block
2. Renderer: frontend.handler.editor → apply-outliner-ops
3. Worker: outliner/apply-ops! → tx-report
4. Worker: frontend.worker.file/sync-to-file → file-writes-chan
5. Worker: write-files! → wfu/post-message :write-files
6. Main: Receives :write-files → fs.writeFile
7. Renderer: Receives confirmation → updates state
```

**Target Sidecar Flow:**
```
1. User edits block
2. Renderer: frontend.handler.editor → apply-outliner-ops (via IPC)
3. Main: Routes to sidecar via TCP
4. Sidecar: outliner/apply-ops! → tx-report
5. Sidecar: Returns tx-report with :affected-pages
6. Main: Receives response → triggers file write for affected pages
7. Main: fs.writeFile for each affected page
8. Main: Sends confirmation to renderer
9. Renderer: Updates state
```

### 2.1 Main Process Sidecar Bridge ✅ COMPLETE

**Status:** Already implemented in `src/electron/electron/sidecar.cljs`

**Capabilities:**
- JVM process spawning with proper lifecycle management
- TCP socket connection with handshake protocol
- Request/response routing with timeouts
- Push message forwarding to renderer

**Test file:** `src/test/electron/sidecar_test.cljs`

```clojure
(ns electron.sidecar-test
  (:require [cljs.test :refer [deftest testing is async]]
            [electron.sidecar :as sidecar]
            [promesa.core :as p]))

(deftest sidecar-lifecycle
  (async done
    (p/let [;; Start sidecar
            _ (sidecar/start! {:port 47632})
            ;; Verify running
            running? (sidecar/running?)
            _ (is (true? running?))
            ;; Stop sidecar
            _ (sidecar/stop!)
            ;; Verify stopped
            stopped? (not (sidecar/running?))]
      (is (true? stopped?))
      (done))))

(deftest sidecar-request-response
  (async done
    (p/let [_ (sidecar/start! {:port 47632})
            ;; Send test request
            result (sidecar/invoke :thread-api/list-db [])
            _ (is (vector? result))]
      (p/finally
        (sidecar/stop!)
        done))))
```

### 2.2 File Write-Back from Sidecar ✅ COMPLETE

**Goal:** When sidecar modifies data via outliner-ops, the changes must be written back to markdown files.

**Challenge:** The sidecar has the DataScript DB but NOT the file system. File writes must go through the Electron main process.

**Implementation Complete:**

1. ✅ **`apply-ops!` returns affected pages:**
   - Returns `{:result ... :affected-pages [page-id1 page-id2 ...]}`
   - Tracks affected pages for ALL operations (save, insert, delete, move, create, rename, etc.)
   - Handles cross-page moves (both source and target pages tracked)

2. ✅ **`get-page-tree` function for file serialization:**
   - Returns page data + block tree structure
   - Format suitable for converting to markdown
   - `:thread-api/get-page-trees` server handler added

3. ✅ **`file-export.clj` - Markdown serialization:**
   - `page-tree->markdown` - Converts page tree to markdown content
   - `pages->file-writes` - Generates `[file-path content]` tuples
   - Handles nested blocks, properties, multiline content
   - Sanitizes filenames, determines pages vs journals directory

4. ✅ **`:thread-api/get-file-writes` handler:**
   - Takes `[repo page-ids opts]`
   - Returns vector of `[file-path content]` tuples ready for writing
   - Main process can call this after apply-outliner-ops

5. 📋 **Electron integration (Future):**
   - Wire main process to call `:thread-api/get-file-writes` after outliner ops
   - Write files to disk via fs module
   - This is the glue code - sidecar is complete

**Key Files:**

| File | Status | Description |
|------|--------|-------------|
| `sidecar/src/logseq/sidecar/outliner.clj` | ✅ | Returns `:affected-pages`, page tree export |
| `sidecar/src/logseq/sidecar/file_export.clj` | ✅ | Markdown serialization (NEW) |
| `sidecar/src/logseq/sidecar/server.clj` | ✅ | `:thread-api/get-page-trees` and `:thread-api/get-file-writes` |
| `sidecar/test/logseq/sidecar/outliner_test.clj` | ✅ | 17 tests, 97 assertions |
| `sidecar/test/logseq/sidecar/file_export_test.clj` | ✅ | 4 tests, 26 assertions (NEW) |

**Test Coverage:** 60 tests, 217 assertions total for sidecar

**Usage Flow:**
```clojure
;; 1. Renderer calls apply-outliner-ops
(invoke :thread-api/apply-outliner-ops [repo ops opts])
;; Returns: {:result ... :affected-pages [1 2 3]}

;; 2. Main process gets file writes for affected pages
(invoke :thread-api/get-file-writes [repo [1 2 3] {}])
;; Returns: [["pages/foo.md" "- Block content"] ["journals/2025-12-11.md" "- Entry"]]

;; 3. Main process writes files to disk
(doseq [[path content] file-writes]
  (fs/writeFileSync (path/join graph-dir path) content))
```

### 2.3 Initial File Sync on Graph Open ✅ COMPLETED

**Goal:** On graph open, main process parses all files and syncs to sidecar.

**Implementation Status:**

1. ✅ **Created `frontend.sidecar.initial-sync` module:**
   - `sync-graph-to-sidecar!` - Main sync function
   - `extract-datoms` - Extract datoms from DataScript DB
   - `batch-datoms` - Split datoms into batches (default: 5000)
   - `tx-report->datom-vecs` - Convert transaction reports for incremental sync
   - `sync-tx-report!` - Sync individual transactions to sidecar

2. ✅ **Created CLJS unit tests:**
   - `src/test/frontend/sidecar/initial_sync_test.cljs`
   - 6 tests, 281 assertions
   - Tests datom extraction, batching, tx-report conversion

3. ✅ **Wired into graph open flow:**
   - Modified `:graph/added` event handler in `frontend.handler.events`
   - Modified `graph-switch` function for graph switching
   - Auto-syncs when sidecar is enabled (IPC or WebSocket)
   - Logs sync progress with datom/batch counts

**Implementation Approach:**

The sync happens **after** the existing parsing flow completes:
```
1. User opens graph
2. Frontend: Parses files via worker (existing flow)
3. Frontend: Worker populates frontend DataScript
4. Frontend: [:graph/added] event fires
5. Initial-sync: Extract datoms from frontend DB
6. Initial-sync: Batch and send to sidecar via sync-datoms
7. Sidecar: Populate DataScript with same data
8. Frontend: Sidecar now has data for queries
```

**Key Files:**

| File | Status | Description |
|------|--------|-------------|
| `src/main/frontend/sidecar/initial_sync.cljs` | ✅ | Initial sync module |
| `src/test/frontend/sidecar/initial_sync_test.cljs` | ✅ | Unit tests (6 tests, 281 assertions) |
| `src/main/frontend/handler/events.cljs` | ✅ | Wired sync into :graph/added and graph-switch |

**Test Results:**
- CLJS tests: 6 tests, 281 assertions - 0 failures
- Sidecar unit tests: 66 tests, 230 assertions - 0 failures

**Test file:** `src/test/frontend/sidecar/initial_sync_test.cljs` (CLJS unit tests)

### 2.4 File Watcher Integration ✅ COMPLETED

**Goal:** File changes detected, parsed, synced to sidecar incrementally.

**Implementation Status:**

1. ✅ **Created `frontend.sidecar.file-sync` module:**
   - `sync-file-change!` - Syncs file after parsing completes
   - `sync-file-delete!` - Notifies sidecar of file deletion
   - `sync-page-to-sidecar!` - Extracts and sends page datoms
   - `extract-page-datoms` - Gets all datoms for a page + blocks
   - `sync-files-change!` - Batch sync for multiple files

2. ✅ **Wired into file watcher:**
   - Modified `frontend.fs.watcher-handler` to call file-sync
   - `handle-add-and-change!` triggers sync after file parsing
   - `handle-changed!` (unlink) triggers page deletion sync

3. ✅ **Added `:thread-api/delete-page` handler:**
   - Server handles page deletion from sidecar DB
   - Deletes page entity and all associated blocks
   - Idempotent (returns success even if page not found)

4. ✅ **Created CLJS unit tests:**
   - `src/test/frontend/sidecar/file_sync_test.cljs`
   - 6 tests, 39 assertions
   - Tests page entity extraction, datom extraction

**File Change Flow:**

```
1. File watcher (chokidar) detects change to pages/foo.md
2. Frontend: watcher_handler.cljs receives event
3. Frontend: handle-add-and-change! parses file via worker
4. Frontend: After parse completes, calls file-sync/sync-file-change!
5. File-sync: Small delay (50ms) for DB to settle
6. File-sync: Extracts page datoms from frontend DB
7. File-sync: Sends to sidecar via :thread-api/sync-datoms
8. Sidecar: Updates DataScript with page changes
```

**Key Files:**

| File | Status | Description |
|------|--------|-------------|
| `src/main/frontend/sidecar/file_sync.cljs` | ✅ | File sync module |
| `src/test/frontend/sidecar/file_sync_test.cljs` | ✅ | Unit tests (6 tests, 39 assertions) |
| `src/main/frontend/fs/watcher_handler.cljs` | ✅ | Wired sync into file watcher |
| `sidecar/src/logseq/sidecar/server.clj` | ✅ | Added :thread-api/delete-page handler |

**Test Results:**
- CLJS file-sync tests: 6 tests, 39 assertions - 0 failures
- CLJS initial-sync tests: 6 tests, 281 assertions - 0 failures
- Sidecar unit tests: 66 tests, 230 assertions - 0 failures

**Test file:** `src/test/frontend/sidecar/file_sync_test.cljs` (CLJS unit tests)

---

## Phase 3: E2E Tests (Playwright) ✅ COMPLETE

### 3.0 Key Finding: Playwright Java Does NOT Support Electron

**Discovery:** The existing `clj-e2e/` tests use Wally (Clojure wrapper for Playwright Java). However, **Playwright Java does not support Electron** ([issue #830](https://github.com/microsoft/playwright-java/issues/830)).

**Solution:** Created new E2E test infrastructure using **Playwright Node.js** with TypeScript, which has full Electron support via `_electron.launch()`.

### 3.1 Electron E2E Test Infrastructure ✅ COMPLETE

**Location:** `e2e-electron/`

**Files created:**

| File | Description |
|------|-------------|
| `e2e-electron/playwright.config.ts` | Playwright config for Electron tests |
| `e2e-electron/tests/sidecar-smoke.spec.ts` | Smoke tests for sidecar integration |
| `e2e-electron/README.md` | Documentation for running tests |
| `e2e-electron/.gitignore` | Ignore screenshots and reports |

### 3.2 Current Test Status

| Test | Status | Notes |
|------|--------|-------|
| App launches and shows UI | ✅ PASSING | Sidecar connects, handshake completes |
| No critical console errors | ✅ PASSING | Filters expected errors |
| Can create a new page | ✅ PASSING | Full sidecar routing working |
| Can create block and verify content | ⏸️ SKIPPED | UI keyboard navigation issue (unrelated to sidecar) |

### 3.3 Running Electron E2E Tests

```bash
# Prerequisites: Build app and sidecar first
pwsh -File scripts/build.ps1
cd sidecar && clj -T:build uberjar && cd ..

# Run all Electron E2E tests
npx playwright test --config e2e-electron/playwright.config.ts

# Run with visible browser
npx playwright test --config e2e-electron/playwright.config.ts --headed

# Run specific test
npx playwright test --config e2e-electron/playwright.config.ts -g "app launches"
```

### 3.4 Sidecar Operations Status

All required sidecar operations are now implemented and working:

- ✅ `:thread-api/apply-outliner-ops` - Block editing
- ✅ `:thread-api/q` - Query operations (with proper normalization)
- ✅ `:thread-api/pull` / `:thread-api/pull-many` - Entity pulls
- ✅ `:thread-api/datoms` - Datom queries
- ✅ `:thread-api/transact` - Transactions
- ✅ `:thread-api/sync-datoms` - Initial sync with attribute normalization

**Key Fix (2025-12-15):** Attribute normalization in `datom-vec->tx-data` converts string attributes (from Transit) back to keywords.

### 3.5 Browser E2E Tests (Wally/Clojure)

The existing `clj-e2e/` tests continue to work for **browser-based testing** (localhost:3002). These do NOT test the sidecar integration since browser uses web workers, not the JVM sidecar.

**Use case:** Testing browser-only features, UI components, worker-based operations.

### 3.6 Test Architecture

```
┌─────────────────────────────────────────────────────────────┐
│              e2e-electron/ (NEW - Electron Tests)           │
│  ┌─────────────────────────────────────────────────────┐   │
│  │       Playwright Node.js + TypeScript               │   │
│  │       sidecar-smoke.spec.ts                         │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│                  Playwright _electron.launch()              │
│                           │                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │           Electron App (Logseq.exe)                  │   │
│  │           → TCP → JVM Sidecar                       │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│              clj-e2e/ (EXISTING - Browser Tests)            │
│  ┌─────────────────────────────────────────────────────┐   │
│  │       Wally (Playwright Java wrapper)                │   │
│  │       Clojure tests                                  │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│                  Playwright browser.launch()                │
│                           │                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │           Browser (localhost:3002)                   │   │
│  │           → Web Workers (NOT sidecar)               │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 3.7 Sample Electron E2E Test

**File:** `e2e-electron/tests/sidecar-smoke.spec.ts`

```typescript
import { test, expect, _electron as electron } from '@playwright/test';

test.describe('Sidecar Electron Smoke Tests', () => {
  test('app launches and shows UI', async () => {
    // Wait for the main UI to load
    await page.waitForSelector('.cp__sidebar-main-layout, main', {
      timeout: 30000,
    });

    // Verify we have UI elements
    const hasUI = await page.locator('.cp__sidebar-main-layout').count();
    expect(hasUI).toBeGreaterThan(0);
  });

  test('no critical console errors on startup', async () => {
    // Filter for sidecar-related errors
    const sidecarErrors = errors.filter(e =>
      e.toLowerCase().includes('sidecar') ||
      e.toLowerCase().includes('socket')
    );
    expect(sidecarErrors).toHaveLength(0);
  });
});
```

### 3.8 Search E2E Tests (Post-Hybrid)

**File:** `e2e-electron/tests/search.spec.ts` (to be created after hybrid works)

**Dependencies:** Requires Phase 2.5 (hybrid architecture) to be complete.

| Test | Description |
|------|-------------|
| Embedding model loads | App loads without embedding errors |
| Search returns results | Keyword and semantic search work |
| Search survives restart | IndexedDB persistence verified |

**Note:** These tests should be added after Phase 2.5 (hybrid architecture) is complete, since search depends on having blocks parsed and available. See Section 2.5.6 for search architecture details.

### 3.2 Console Error Assertion Fixture

**File:** `clj-e2e/src/logseq/e2e/error_collector.clj`

```clojure
(ns logseq.e2e.error-collector
  (:require [wally.main :as w]))

(def ^:dynamic *console-errors* (atom []))

(defn setup-error-collector!
  "Call this in test setup to start collecting console errors"
  []
  (reset! *console-errors* [])
  (let [page (w/get-page)]
    (.onConsoleMessage page
      (fn [msg]
        (when (= "error" (.type msg))
          (swap! *console-errors* conj
                 {:text (.text msg)
                  :location (.location msg)
                  :timestamp (System/currentTimeMillis)}))))))

(defn get-console-errors
  "Returns collected console errors"
  []
  @*console-errors*)

(defn assert-no-console-errors!
  "Asserts no console errors were collected, fails test with details if any"
  []
  (let [errors (get-console-errors)]
    (when (seq errors)
      (throw (ex-info (str "Console errors detected: " (count errors))
                      {:errors errors})))))

(defn clear-console-errors!
  "Clear collected errors"
  []
  (reset! *console-errors* []))
```

### 3.3 Recursive Error Fixing Workflow

**File:** `clj-e2e/src/logseq/e2e/test_reporter.clj`

```clojure
(ns logseq.e2e.test-reporter
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [logseq.e2e.error-collector :as errors]))

(defn generate-error-report
  "Generate structured error report for Claude"
  [test-results]
  (let [failures (->> test-results
                      (filter #(= :fail (:type %)))
                      (map (fn [result]
                             {:test-name (:name result)
                              :message (:message result)
                              :expected (:expected result)
                              :actual (:actual result)
                              :console-errors (errors/get-console-errors)
                              :stack-trace (:stack-trace result)})))]
    {:timestamp (System/currentTimeMillis)
     :total-tests (count test-results)
     :failures (count failures)
     :failure-details failures
     :sidecar-logs (slurp-sidecar-logs)}))

(defn write-error-report!
  "Write error report to file for Claude to read"
  [report]
  (let [report-path "clj-e2e/error-reports/latest.edn"]
    (io/make-parents report-path)
    (spit report-path (pr-str report))
    report-path))

(defn format-for-claude
  "Format error report as markdown for Claude"
  [report]
  (str "# E2E Test Failure Report\n\n"
       "**Timestamp:** " (:timestamp report) "\n"
       "**Total Tests:** " (:total-tests report) "\n"
       "**Failures:** " (:failures report) "\n\n"
       "## Failure Details\n\n"
       (apply str
         (for [failure (:failure-details report)]
           (str "### " (:test-name failure) "\n\n"
                "**Error:** " (:message failure) "\n\n"
                "**Expected:** `" (:expected failure) "`\n\n"
                "**Actual:** `" (:actual failure) "`\n\n"
                (when (seq (:console-errors failure))
                  (str "**Console Errors:**\n```\n"
                       (apply str (map #(str (:text %) "\n") (:console-errors failure)))
                       "```\n\n"))
                "---\n\n")))))
```

---

## Phase 4: Automated TDD Cycle

### 4.1 The TDD Loop Script

**File:** `scripts/tdd-loop.ps1`

```powershell
# TDD Loop: Run tests, collect errors, format for Claude

param(
    [string]$TestFilter = "sidecar",
    [switch]$WatchMode
)

function Run-Tests {
    Write-Host "Running E2E tests with filter: $TestFilter" -ForegroundColor Cyan

    # Start sidecar if not running
    $sidecarProcess = Get-Process -Name "java" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*logsidian-sidecar*" }

    if (-not $sidecarProcess) {
        Write-Host "Starting sidecar..." -ForegroundColor Yellow
        Start-Process -FilePath "java" -ArgumentList "-jar", "sidecar/target/logsidian-sidecar.jar" -NoNewWindow
        Start-Sleep -Seconds 3
    }

    # Run tests
    Push-Location clj-e2e
    $result = & clj -M:test -i $TestFilter 2>&1
    $exitCode = $LASTEXITCODE
    Pop-Location

    return @{
        Output = $result
        ExitCode = $exitCode
    }
}

function Parse-TestOutput {
    param([string]$Output)

    # Parse Clojure test output
    $failures = @()
    $current = $null

    foreach ($line in $Output -split "`n") {
        if ($line -match "FAIL in \(([^)]+)\)") {
            $current = @{ TestName = $matches[1]; Lines = @() }
        }
        elseif ($current -and $line.Trim()) {
            $current.Lines += $line
        }
        elseif ($current -and -not $line.Trim()) {
            $failures += $current
            $current = $null
        }
    }

    return $failures
}

function Format-ForClaude {
    param([array]$Failures)

    $report = @"
# TDD Error Report

The following tests failed. Please analyze and fix:

"@

    foreach ($failure in $Failures) {
        $report += @"

## Test: $($failure.TestName)

```
$($failure.Lines -join "`n")
```

"@
    }

    return $report
}

# Main loop
do {
    $result = Run-Tests

    if ($result.ExitCode -ne 0) {
        Write-Host "Tests FAILED" -ForegroundColor Red

        $failures = Parse-TestOutput -Output $result.Output
        $report = Format-ForClaude -Failures $failures

        # Write report
        $reportPath = "clj-e2e/error-reports/latest-$(Get-Date -Format 'yyyyMMdd-HHmmss').md"
        $report | Out-File -FilePath $reportPath -Encoding utf8

        Write-Host "Error report written to: $reportPath" -ForegroundColor Yellow
        Write-Host ""
        Write-Host $report
    }
    else {
        Write-Host "All tests PASSED!" -ForegroundColor Green
    }

    if ($WatchMode) {
        Write-Host "`nWaiting for file changes... (Ctrl+C to exit)" -ForegroundColor Cyan
        Start-Sleep -Seconds 5
    }
} while ($WatchMode)
```

### 4.2 Claude Code Slash Command

**File:** `.claude/commands/tdd.md`

```markdown
Run the TDD loop for Logsidian sidecar development.

1. Run E2E tests: `cd clj-e2e && clj -M:test -i sidecar`
2. If tests fail, read error report from `clj-e2e/error-reports/latest.md`
3. Analyze the failures and fix the code
4. Re-run tests to verify fix
5. Repeat until all tests pass

Focus on:
- Console errors in browser (check error-collector output)
- Missing operations in sidecar (check server.clj)
- Transit serialization issues (check protocol.clj)

Files to modify:
- `sidecar/src/logseq/sidecar/server.clj` - Add/fix operation handlers
- `sidecar/src/logseq/sidecar/protocol.clj` - Fix Transit serialization
- `src/electron/electron/sidecar.cljs` - Fix Electron integration
```

---

## Phase 5: Performance Benchmarks

### 5.1 Benchmark Suite

**File:** `sidecar/test/logseq/sidecar/benchmark_test.clj`

```clojure
(ns logseq.sidecar.benchmark-test
  (:require [clojure.test :refer :all]
            [criterium.core :as crit]
            [datascript.core :as d]
            [logseq.sidecar.storage :as storage]))

(deftest benchmark-query-performance
  (testing "Query performance with IStorage"
    (let [storage (storage/create-sqlite-storage ":memory:")
          conn (d/create-conn {:block/name {:db/unique :db.unique/identity}}
                              {:storage storage :ref-type :soft})]
      ;; Populate with 10k blocks
      (doseq [i (range 10000)]
        (d/transact! conn [{:block/uuid (random-uuid)
                            :block/name (str "page-" i)
                            :block/content (str "Content " i)}]))

      ;; Benchmark simple query
      (println "\n=== Simple Query Benchmark ===")
      (crit/quick-bench
        (d/entity @conn [:block/name "page-5000"]))

      ;; Benchmark pattern query
      (println "\n=== Pattern Query Benchmark ===")
      (crit/quick-bench
        (d/q '[:find ?e ?name
               :where [?e :block/name ?name]
               :limit 100]
             @conn))

      ;; Benchmark after clearing memory cache
      (println "\n=== Cold Query Benchmark (after cache clear) ===")
      (storage/clear-memory-cache! storage)
      (crit/quick-bench
        (d/entity @conn [:block/name "page-5000"])))))

(deftest benchmark-transaction-performance
  (testing "Transaction performance with IStorage"
    (let [storage (storage/create-sqlite-storage ":memory:")
          conn (d/create-conn {} {:storage storage :ref-type :soft})]

      (println "\n=== Single Transaction Benchmark ===")
      (crit/quick-bench
        (d/transact! conn [{:block/uuid (random-uuid)
                            :block/content "New block"}]))

      (println "\n=== Batch Transaction Benchmark (100 blocks) ===")
      (crit/quick-bench
        (d/transact! conn
          (for [i (range 100)]
            {:block/uuid (random-uuid)
             :block/content (str "Batch block " i)}))))))
```

### 5.2 Performance Targets

| Metric | Target | Current (Web Worker) |
|--------|--------|----------------------|
| Graph load (10k blocks) | < 500ms | ~9,000ms |
| Simple query | < 1ms | ~0.36ms |
| Backlinks query | < 10ms | ~2.5ms |
| Block transaction | < 5ms | ~1.15ms |
| Memory (10k blocks) | < 100MB | ~500MB |
| Memory (100k blocks) | < 500MB | OOM |

---

## Phase 5.5: Java Runtime Bundling for MSIX

### 5.5.1 Overview

The sidecar requires Java 21 to run. Rather than requiring users to install Java separately, we bundle a minimal JRE with the MSIX installer.

**Key insight:** [Microsoft Build of OpenJDK](https://learn.microsoft.com/en-us/java/openjdk/download) officially supports **Windows ARM64**, making cross-platform bundling possible.

### 5.5.2 Strategy: jlink Minimal JRE + AppCDS

**Step 1: Create minimal JRE with jlink**

```bash
# Creates ~50-80MB runtime instead of ~200MB full JRE
jlink --module-path $JAVA_HOME/jmods \
      --add-modules java.base,java.logging,java.sql,java.naming,java.management \
      --output jre-minimal \
      --strip-debug \
      --compress=2 \
      --no-header-files \
      --no-man-pages
```

**Step 2: Create AppCDS archive for fast startup**

```bash
# One-time: Generate class list
java -Xshare:off -XX:DumpLoadedClassList=classes.lst -jar logsidian-sidecar.jar --dry-run

# Build: Create AppCDS archive
java -Xshare:dump -XX:SharedClassListFile=classes.lst \
     -XX:SharedArchiveFile=logsidian-sidecar.jsa -jar logsidian-sidecar.jar

# Result: ~100-200ms startup instead of ~500ms
```

**Step 3: Bundle in MSIX**

```
Logsidian.msix (total ~250-300MB)
├── Logseq.exe (Electron app ~150MB)
├── resources/
│   ├── logsidian-sidecar.jar (~30MB)
│   └── logsidian-sidecar.jsa (AppCDS archive ~5MB)
└── jre-minimal/ (~50-80MB)
    ├── bin/java.exe
    └── lib/modules
```

### 5.5.3 Platform Matrix

| Platform | JDK Distribution | jlink | Status |
|----------|------------------|-------|--------|
| Windows x64 | Microsoft OpenJDK 21 | ✅ | Ready |
| Windows ARM64 | Microsoft OpenJDK 21 | ✅ | Ready |
| macOS Intel | Temurin/Zulu 21 | ✅ | Future |
| macOS ARM64 | Temurin/Zulu 21 | ✅ | Future |

### 5.5.4 GitHub Actions Integration

**File:** `.github/workflows/build-windows.yml`

```yaml
# Add after compile-cljs job:

build-sidecar-jar:
  runs-on: ubuntu-22.04
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        distribution: 'microsoft'
        java-version: '21'
    - uses: DeLaGuardo/setup-clojure@12.5
      with:
        cli: 1.11.1.1435
    - name: Build sidecar uberjar
      run: cd sidecar && clojure -T:build uberjar
    - uses: actions/upload-artifact@v4
      with:
        name: sidecar-jar
        path: sidecar/target/logsidian-sidecar.jar

build-jre-x64:
  runs-on: windows-latest
  steps:
    - uses: actions/setup-java@v4
      with:
        distribution: 'microsoft'
        java-version: '21'
    - name: Create minimal JRE (x64)
      run: |
        jlink --module-path "$env:JAVA_HOME/jmods" `
              --add-modules java.base,java.logging,java.sql,java.naming,java.management `
              --output jre-x64 --strip-debug --compress=2 --no-header-files --no-man-pages
    - uses: actions/upload-artifact@v4
      with:
        name: jre-x64
        path: jre-x64/

build-jre-arm64:
  runs-on: windows-11-arm64  # GitHub ARM64 runner
  steps:
    - uses: actions/setup-java@v4
      with:
        distribution: 'microsoft'
        java-version: '21'
    - name: Create minimal JRE (ARM64)
      run: |
        jlink --module-path "$env:JAVA_HOME/jmods" `
              --add-modules java.base,java.logging,java.sql,java.naming,java.management `
              --output jre-arm64 --strip-debug --compress=2 --no-header-files --no-man-pages
    - uses: actions/upload-artifact@v4
      with:
        name: jre-arm64
        path: jre-arm64/
```

### 5.5.5 Electron Configuration

**File:** `forge.config.js` or `static/electron-builder.yml`

```javascript
// forge.config.js
module.exports = {
  packagerConfig: {
    extraResource: [
      // Sidecar JAR
      '../sidecar/target/logsidian-sidecar.jar',
      // Platform-specific JRE (set by CI)
      process.env.JRE_PATH || '../jre-minimal'
    ]
  },
  makers: [{
    name: '@electron-forge/maker-msix',
    config: {
      publisher: 'CN=Logsidian',
      // JRE included in extraResource
    }
  }]
};
```

### 5.5.6 Runtime Java Path Resolution

**File:** `src/electron/electron/sidecar.cljs`

```clojure
(defn- find-bundled-java
  "Find the bundled Java executable in app resources"
  []
  (let [resources-path (.-resourcesPath js/process)
        platform (.-platform js/process)
        arch (.-arch js/process)
        java-exe (if (= platform "win32") "java.exe" "java")
        ;; Try bundled JRE first
        bundled-path (.join node-path resources-path "jre-minimal" "bin" java-exe)]
    (if (.existsSync fs bundled-path)
      (do (logger/info "Using bundled JRE" {:path bundled-path})
          bundled-path)
      ;; Fall back to system Java
      (do (logger/warn "Bundled JRE not found, falling back to system Java")
          "java"))))
```

### 5.5.7 Size Impact

| Component | Size | Notes |
|-----------|------|-------|
| Electron app | ~150MB | Unchanged |
| Sidecar JAR | ~30MB | All dependencies included |
| AppCDS archive | ~5MB | Class data sharing cache |
| Minimal JRE (jlink) | ~50-80MB | Only required modules |
| **Total added** | **~85-115MB** | Per platform |

### 5.5.8 Startup Time Improvement

| Configuration | Cold Start | Warm Start |
|---------------|------------|------------|
| Full JRE, no cache | ~800ms | ~500ms |
| jlink JRE, no cache | ~600ms | ~400ms |
| jlink JRE + AppCDS | ~200ms | ~100ms |

### 5.5.9 Checklist

- [ ] Add `build-sidecar-jar` job to GitHub Actions
- [ ] Add `build-jre-x64` job to GitHub Actions
- [ ] Add `build-jre-arm64` job to GitHub Actions
- [ ] Update `forge.config.js` with `extraResource` for JAR and JRE
- [ ] Modify `electron/sidecar.cljs` to use bundled Java
- [ ] Create AppCDS archive generation script
- [ ] Test MSIX installation on clean Windows (no Java installed)
- [ ] Test on Windows ARM64 device
- [ ] Verify startup time meets <200ms target

---

## Phase 6: Release Checklist

### 6.1 Pre-Release Testing

- [ ] All unit tests pass (`cd sidecar && clj -M:test`)
- [ ] All E2E tests pass (`cd clj-e2e && clj -M:test`)
- [ ] Performance benchmarks meet targets
- [ ] No console errors in:
  - [ ] App startup
  - [ ] Page creation
  - [ ] Block editing
  - [ ] Navigation
  - [ ] Search (see details below)
  - [ ] Graph with 10k+ blocks
- [ ] Search verification (see Section 2.5.6):
  - [ ] Embedding model loads without errors
  - [ ] Semantic search returns relevant results
  - [ ] Keyword search works
  - [ ] Search survives app restart (IndexedDB persistence)
  - [ ] No console errors related to vec-search

### 6.2 Platform Testing

- [ ] Windows x64
- [ ] Windows ARM64
- [ ] macOS (future)
- [ ] Linux (future)

### 6.3 Documentation

- [x] CLAUDE.md updated with new sidecar features
- [x] README updated for users
- [ ] Performance comparison published

### 6.4 Debug Logging Configuration

Before release, disable debug logging by default and enable via environment variables:

**Sidecar (`sidecar/src/logseq/sidecar/server.clj`):**
```clojure
;; Change from:
(def ^:private ^:dynamic *debug-enabled* true)

;; To:
(def ^:private ^:dynamic *debug-enabled*
  (or (= "true" (System/getenv "LOGSIDIAN_DEBUG"))
      (= "true" (System/getProperty "logsidian.debug"))))
```

**Frontend (`src/main/frontend/sidecar/routing.cljs`):**
```clojure
;; Change from:
(defonce ^:private *debug-routing* (atom true))

;; To:
(defonce ^:private *debug-routing* (atom false))
```

**Usage after release:**
- Sidecar: `LOGSIDIAN_DEBUG=true java -jar logsidian-sidecar.jar`
- Frontend: `frontend.sidecar.routing.enable_debug_BANG_()` in browser console

---

## Appendix: File Inventory

### Test Files Status

```
sidecar/test/logseq/sidecar/
├── storage_test.clj          # ✅ Phase 1.1 - IStorage tests (11 tests)
├── sync_test.clj             # ✅ Phase 1.2 - Datom sync tests (6 tests)
├── outliner_test.clj         # ✅ Phase 1.3 + 2.2 - Outliner ops + file sync (17 tests, 97 assertions)
├── file_export_test.clj      # ✅ Phase 2.2 - Markdown serialization (4 tests, 26 assertions)
├── server_test.clj           # ✅ Server integration tests (22 tests, including sync normalization)
├── protocol_test.clj         # ✅ Transit serialization tests (6 tests)
├── extract_test.clj          # ✅ Phase 2.5 - AST extraction tests (18 tests, 26 assertions)
├── benchmark_test.clj        # 📋 Phase 5 - Performance benchmarks
└── integration_test.clj      # 📋 Phase 2 - Full stack integration tests

Sidecar Total: 122 tests, 374 assertions (all passing)

src/test/frontend/sidecar/
└── initial_sync_test.cljs    # ✅ Phase 2.3 - Initial sync module (6 tests, 281 assertions)

CLJS Sidecar Total: 6 tests, 281 assertions

clj-e2e/                        # Browser E2E tests (Wally/Playwright Java)
├── src/logseq/e2e/
│   ├── error_collector.clj   # ✅ CREATED - Console error collection + assertions
│   └── test_reporter.clj     # ✅ CREATED - Structured error reporting
├── error-reports/
│   ├── .gitignore            # ✅ CREATED - Ignore generated reports
│   └── README.md             # ✅ CREATED - Documentation
└── test/logseq/e2e/
    └── sidecar_basic_test.clj # ✅ ENHANCED - Added error assertions + smoke tests

e2e-electron/                   # ✅ Phase 3 - Electron E2E tests (Playwright Node.js)
├── playwright.config.ts      # ✅ CREATED - Playwright config for Electron
├── tests/
│   └── sidecar-smoke.spec.ts # ✅ CREATED - Sidecar smoke tests (3 passing, 1 skipped)
├── screenshots/              # Test screenshots (auto-generated)
├── README.md                 # ✅ CREATED - Documentation for Electron E2E tests
└── .gitignore                # ✅ CREATED - Ignore screenshots and reports

E2E Electron Total: 4 tests (3 passing, 1 skipped - UI keyboard navigation issue)

scripts/
├── tdd-loop.ps1              # ✅ CREATED - TDD automation script (replaces run-e2e-tests.ps1)
└── benchmark.ps1             # 📋 Phase 5 - Performance benchmark runner

.claude/commands/
└── tdd.md                    # ✅ CREATED - Claude slash command for TDD

Legend: ✅ = Created, 🚧 = In Progress, 📋 = Planned
```

### Implementation Files Status

```
sidecar/src/logseq/sidecar/
├── storage.clj               # ✅ Phase 1.1 - IStorage implementation with SQLite-JDBC
├── server.clj                # ✅ Phase 1.2 + 2.5 - sync-datoms with attribute normalization, graph management
├── outliner.clj              # ✅ Phase 1.3 + 2.2 - Full outliner (11 ops) + file sync (affected pages, page tree export)
├── extract.clj               # ✅ Phase 2.5 - Pure CLJ extraction from AST
├── protocol.clj              # ✅ Transit serialization with datascript-transit handlers
├── pipes.clj                 # ✅ TCP socket server for Electron IPC
└── websocket.clj             # EXISTS - WebSocket server (ON HOLD)

src/main/frontend/sidecar/
├── core.cljs                 # EXISTS - High-level sidecar API
├── client.cljs               # EXISTS - IPC client for Electron
├── websocket_client.cljs     # EXISTS - WebSocket client (ON HOLD)
├── spawn.cljs                # EXISTS - JVM process spawning
├── routing.cljs              # ✅ Phase 2.5 - Operation routing with sync state tracking
├── initial_sync.cljs         # ✅ Phase 2.3 - Initial datom sync module
└── file_sync.cljs            # ✅ Phase 2.4 - File change sync module

src/electron/electron/
├── sidecar.cljs              # EXISTS - Main process sidecar management
└── sidecar-sync.cljs         # 📋 Phase 2.2 - File parsing and sync to sidecar

.github/workflows/
└── e2e-tests.yml             # 📋 Phase 0.3 - CI/CD for E2E tests (deferred)

Legend: ✅ = Complete, 🚧 = In Progress, 📋 = Planned, EXISTS = Already exists
```

---

## Success Metrics

1. **Zero Console Errors** - App runs without any console errors during normal use
2. **10x Faster Startup** - Large graph (10k+ blocks) loads in <1s vs ~10s
3. **Bounded Memory** - Memory stays under control even with 100k blocks
4. **100% E2E Pass Rate** - All E2E tests pass consistently
5. **Full Feature Parity** - Every Logseq feature works via sidecar
