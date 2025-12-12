# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Logsidian** - "Obsidian's speed with Logseq's blocks, files stay yours."

Logsidian is a high-performance fork of Logseq focused on file-based graphs with dramatically improved performance for large knowledge bases. The project aims to combine:
- **Logseq's power**: Block-level editing, bidirectional linking, outliner operations
- **Obsidian's speed**: Fast startup, responsive UI, efficient memory usage
- **File-first philosophy**: Markdown files are the source of truth, no vendor lock-in

### Project Identity

| Item | Value |
|------|-------|
| **Name** | Logsidian |
| **Tagline** | "Obsidian's speed with Logseq's blocks, files stay yours" |
| **Base** | Logseq 0.10.15 (stable, file-based only) |
| **License** | AGPL-3.0 (app), MIT (sidecar) |
| **Status** | Early Development |

### Why 0.10.15?

We chose Logseq 0.10.15 as the base because:
- **Stable**: Last major stable release before the database version
- **File-based only**: No SQLite/DB-based graph code to maintain
- **Clean codebase**: Simpler architecture without dual file/DB code paths
- **Our focus**: The JVM sidecar is specifically for file-based graphs

---

## Build Commands

### Requirements

- Node.js 22.x (use `nvm use 22.21.0`)
- Java 21 (Temurin recommended)
- Clojure CLI 1.11+
- Yarn (via corepack: `corepack enable`)

### Browser Development

```bash
yarn install              # Install dependencies (includes postinstall builds)
yarn watch                # Dev server at http://localhost:3001
```

Dev servers started by `yarn watch`:
- http://localhost:3001 - Main application
- http://localhost:3002 - Secondary server
- http://localhost:9630 - Shadow-cljs dashboard
- nREPL on port 8701

### Desktop App Development

```bash
yarn install && cd static && yarn install && cd ..
yarn watch                # Wait for "Build Completed" for :electron and :app
yarn dev-electron-app     # In a separate terminal
# Or use: bb dev:electron-start
```

### Production Builds

```bash
yarn release              # Browser - outputs to static/
yarn release-electron     # Desktop - outputs to static/out/
```

### Windows Builds (x64 + ARM64)

Local build for Windows x64:
```powershell
yarn install
yarn gulp:build
yarn cljs:release-electron
yarn webpack-app-build

cd static
yarn install
yarn electron:make
# Output: static/out/make/
```

Local build for Windows ARM64:
```powershell
cd static
$env:npm_config_arch = "arm64"
yarn install
yarn electron:make-win-arm64
```

**Note:** ARM64 builds require the rsapi native module to be compiled for ARM64. This is handled automatically by the GitHub Actions workflow.

---

## GitHub Actions Workflows

| Workflow | File | Trigger |
|----------|------|---------|
| Build Windows | `.github/workflows/build-windows.yml` | `version.cljs` changes or manual |
| Sync Upstream | `.github/workflows/sync-upstream.yml` | Weekly (Mon 6:00 UTC) or manual |

**Build workflow jobs:**
1. `build-rsapi-arm64` (Windows ARM64 runner) - Compiles rsapi with Rust + Clang
2. `compile-cljs` (Ubuntu) - Builds ClojureScript with release optimizations
3. `build-windows-x64` (Windows) - Builds Electron x64
4. `build-windows-arm64` (Windows) - Builds Electron ARM64 with native modules
5. `release` (Ubuntu) - Publishes versioned and rolling releases

**Manual workflow options:**
- `test_release`: Create test release with branch name in tag
- `skip_x64`: Skip x64 build
- `skip_arm64`: Skip ARM64 build

**Release tags:**
- `{version}-win64` - Versioned x64 release
- `{version}-arm64` - Versioned ARM64 release
- `win-latest` - Rolling release (both architectures)

**Sync workflow:**
- Only syncs with 0.10.x releases (ignores 0.11.x+ database versions)
- Creates PR if merge is clean, creates issue if conflicts occur

---

## Performance Optimizations

Production builds include these optimizations:

| Optimization | File | Impact |
|--------------|------|--------|
| Node.js 22 Compile Cache | `resources/electron-entry.js` | 30-50% faster startup |
| Direct Function Invocation | `shadow-cljs.edn` (`:fn-invoke-direct`) | 10-30% faster |
| Disabled Logging | `shadow-cljs.edn` (`goog.debug.LOGGING_ENABLED`) | ~5-10% faster |
| No Source Maps | `shadow-cljs.edn` (`:source-map false`) | Smaller bundles |
| Webpack Production Mode | `webpack.config.js` | Tree shaking enabled |
| Splash Screen | `resources/splash.html` | Perceived faster startup |

---

## Testing Commands

```bash
bb dev:lint-and-test      # Run all linters and unit tests
bb lint:dev               # Run all linters only
yarn test                 # Run unit tests only
```

**Focus Testing:**
1. Add `^:focus` metadata to test: `(deftest ^:focus test-name ...)`
2. Run: `bb dev:test -i focus`

**Individual Linters:**
```bash
bb lint:kondo-git-changes   # Fast lint for changed files only
bb lint:carve               # Detect unused vars
bb lint:large-vars          # Check for overly complex functions
```

---

## Electron E2E Testing

E2E tests for the Electron desktop app with sidecar integration use **Playwright Node.js** (not Wally/Java).

**Why Node.js?** Playwright Java does not support Electron ([issue #830](https://github.com/microsoft/playwright-java/issues/830)).

### Running Electron E2E Tests

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

### Test Coverage

| Test | Status | Notes |
|------|--------|-------|
| App launches and shows UI | ✅ | Sidecar connects, handshake completes |
| No critical console errors | ✅ | Filters expected errors |
| Create page | ⏸️ SKIPPED | Needs `:thread-api/apply-outliner-ops` |
| Create block | ⏸️ SKIPPED | Needs `:thread-api/apply-outliner-ops` |

See `e2e-electron/README.md` for detailed documentation.

---

## Performance Testing

Performance benchmarks measure baseline metrics before sidecar implementation.

### Running Benchmarks

```bash
# Compile tests first (required after any test file changes)
yarn cljs:test

# Run all benchmarks
yarn cljs:run-test -i benchmark

# Or via bb task
bb benchmark:baseline

# Run specific benchmark
yarn cljs:run-test -n frontend.sidecar.baseline-benchmark-test/query-simple-benchmark
```

### Benchmark Results Location

- **Results document**: `docs/tests/performance_before_sidecar.md`
- **Test implementation**: `src/test/frontend/sidecar/baseline_benchmark_test.cljs`

### Current Baseline (2025-12-09)

| Metric | Value | Notes |
|--------|-------|-------|
| Small graph load (1K blocks) | 1,123 ms | 100 pages × 10 blocks |
| Medium graph load (7.5K blocks) | 9,106 ms | 500 pages × 15 blocks |
| Simple query avg | 0.36 ms | Entity lookup by name |
| Backlinks query avg | 2.51 ms | Pattern matching |
| Property filter avg | 6.98 ms | Content search |
| Pull entity avg | 0.03 ms | Single entity pull |
| Single block tx avg | 1.15 ms | Insert operation |

### Adding New Tests

**IMPORTANT QUIRKS** - Read before creating new tests:

1. **File naming**: Test files MUST end with `_test.cljs`
   - ✅ `my_feature_test.cljs`
   - ❌ `my_feature_benchmark.cljs` (won't be discovered!)

2. **Namespace naming**: Must match file path with `-test` suffix
   - File: `src/test/frontend/sidecar/baseline_benchmark_test.cljs`
   - Namespace: `frontend.sidecar.baseline-benchmark-test`

3. **Test metadata for filtering**: Use `^:keyword` metadata
   ```clojure
   (deftest ^:benchmark my-benchmark-test ...)  ; Run with -i benchmark
   (deftest ^:focus my-focused-test ...)        ; Run with -i focus
   ```

4. **Recompilation required**: After creating/modifying test files:
   ```bash
   # Clear cache and recompile (sometimes needed for new files)
   rm -rf .shadow-cljs
   yarn cljs:test
   ```

5. **Shadow-cljs test discovery**: Uses `:node-test` target which auto-discovers
   tests from `src/test/` but ONLY files matching `*_test.cljs` pattern.

### Test Helper Functions

Located in `src/test/frontend/test/helper.cljs`:

```clojure
;; Database lifecycle
(test-helper/start-and-destroy-db
  (fn []
    ;; Your test code here - db is available
    ))

;; Load test files
(test-helper/load-test-files
  [{:file/path "pages/test.md"
    :file/content "- block 1\n- block 2"}])

;; Timing measurement
(let [{:keys [time result]} (util/with-time (expensive-operation))]
  (println "Took:" time "ms"))
```

### Windows/Claude Code Path Issues

When running tests via Claude Code's bash environment, yarn may fail with path translation errors.

**Workaround**: Use PowerShell wrapper:
```bash
pwsh -Command "cd 'X:\source\repos\logsidian'; yarn cljs:test"
pwsh -Command "cd 'X:\source\repos\logsidian'; yarn cljs:run-test -i benchmark"
```

---

## TDD Workflow for Sidecar Development

The sidecar development uses a Test-Driven Development (TDD) workflow with automated error reporting.

### TDD Loop Script

The primary development tool is `scripts/tdd-loop.ps1`:

```powershell
# Run sidecar E2E tests once
.\scripts\tdd-loop.ps1

# Run in watch mode (continuous testing)
.\scripts\tdd-loop.ps1 -WatchMode

# Run only smoke tests
.\scripts\tdd-loop.ps1 -TestFilter smoke

# Skip starting sidecar (if already running)
.\scripts\tdd-loop.ps1 -SkipSidecarStart
```

**What the script does:**
1. Builds sidecar JAR if not present
2. Starts sidecar server if not running
3. Runs E2E tests with Playwright
4. Generates error reports on failure
5. Writes reports to `clj-e2e/error-reports/`

### Claude Code TDD Command

Use the `/tdd` slash command to run the TDD workflow:
1. Runs tests
2. Reads error reports
3. Fixes code
4. Repeats until tests pass

### Error Reporting Infrastructure

**Files:**
| File | Purpose |
|------|---------|
| `clj-e2e/src/logseq/e2e/error_collector.clj` | Console error collection |
| `clj-e2e/src/logseq/e2e/test_reporter.clj` | Structured error reports |
| `clj-e2e/error-reports/latest-tdd.md` | Latest TDD loop report |
| `clj-e2e/error-reports/latest.edn` | Latest structured report |

**Test metadata:**
```clojure
(deftest ^:sidecar ^:smoke my-test ...)  ; Tagged for filtering
```

Filter options:
- `-i sidecar` - All sidecar tests
- `-i smoke` - Quick smoke tests only
- `-n namespace/test-name` - Single test

### Console Error Assertions

Tests automatically check for console errors:

```clojure
;; Fixture automatically asserts no console errors
(use-fixtures :each errors/wrap-assert-no-console-errors)

;; Or check manually in test
(testing "my operation"
  (do-something)
  (errors/assert-no-console-errors!)
  (errors/assert-no-errors-containing! "sidecar"))
```

### Running E2E Tests Manually

```bash
# All sidecar tests
cd clj-e2e && clj -M:test -i sidecar

# Specific test
cd clj-e2e && clj -M:test -n logseq.e2e.sidecar-basic-test

# With structured reporting
cd clj-e2e && clj -M:test-reporter logseq.e2e.sidecar-basic-test
```

---

## Architecture

### Tech Stack

- **ClojureScript** compiled via Shadow-cljs
- **React** wrapped with Rum for UI components
- **DataScript** for in-memory database with Datalog queries
- **Electron** for desktop app

### Source Organization

```
logsidian/
├── src/
│   ├── main/frontend/          # Main frontend code
│   │   ├── components/         # UI components (Rum/React)
│   │   ├── handler/            # System handlers and business logic
│   │   ├── worker/             # Web worker code
│   │   └── common/             # Shared code between worker and frontend
│   ├── electron/               # Electron desktop app specific code
│   └── test/                   # Unit tests
├── deps/                       # Internal ClojureScript libraries
│   ├── graph-parser/           # Parses Logseq graphs
│   ├── db/                     # Database operations
│   ├── outliner/               # Outliner operations
│   └── common/                 # Shared utilities
├── packages/                   # JavaScript dependencies
│   ├── ui/                     # shadcn-based component system
│   └── tldraw/                 # Custom fork for whiteboards
└── clj-e2e/                    # End-to-end Clojure tests
```

### State Management

- **Document state** (pages, blocks): stored in DataScript
- **UI state**: stored in Clojure atoms
- **Components**: subscribe via Rum reactive components

### Code Conventions

- Keywords defined using `logseq.common.defkeywords/defkeyword`
- File-specific code in `file_based/` directories
- Worker and frontend namespaces must stay separate

---

## REPL Setup

### VSCode + Calva

1. Run `yarn watch` (starts nREPL on port 8701)
2. `Cmd+Shift+P` → "Calva: Connect to a Running REPL Server in the Project"
3. Select: logseq → shadow-cljs → :app → localhost:8701

### Web Worker REPL

Use `(shadow.user/worker-repl)` or check http://localhost:9630/runtimes for runtime IDs.

---

## JVM Sidecar Performance Project

The core differentiator of Logsidian is a JVM sidecar approach to dramatically improve performance for large file-based graphs.

### The Problem

Large Logseq graphs (10k+ blocks) suffer from:
- Slow startup (minutes to load)
- High memory usage (GBs for large graphs)
- UI lag during operations

### The Solution: JVM Sidecar with Lazy Loading

**Architecture (Sync-Based Model):**

The sidecar cannot parse markdown files directly because Logseq's parser ([mldoc](https://github.com/logseq/mldoc)) is written in OCaml and compiled to JavaScript. Instead, we use a sync-based approach where the Electron main process parses files and syncs datoms to the sidecar.

```
┌─────────────────────────────────────────────────────────────┐
│                     Electron (Renderer)                      │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                 ClojureScript UI                     │    │
│  │              (queries go to sidecar)                 │    │
│  └───────────────────────┬─────────────────────────────┘    │
│                          │ Transit over IPC                  │
└──────────────────────────┼──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                   Electron Main Process                      │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              Node.js + mldoc                         │    │
│  │         (parses files, syncs to sidecar)             │    │
│  └───────────────────────┬─────────────────────────────┘    │
│                          │ Transit over TCP                  │
└──────────────────────────┼──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                   JVM Sidecar                                │
│  ┌─────────────────────────────────────────────────────┐    │
│  │           DataScript + IStorage (lazy loading)       │    │
│  │                 SQLite-JDBC backing                  │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Key Components:**
1. **DataScript IStorage**: JVM-only protocol enabling lazy loading with soft references ([docs](https://github.com/tonsky/datascript/blob/master/docs/storage.md))
2. **Transit Serialization**: Already used in Logseq worker communication
3. **Soft References**: JVM garbage collector automatically evicts unused data
4. **SQLite-JDBC**: Persistent storage for lazy-loaded datoms

### What the Sidecar Handles

- ✅ DataScript queries (`q`, `pull`, `pull-many`, `datoms`)
- ✅ Transactions (`transact`)
- ✅ Graph CRUD operations
- ✅ Lazy loading via IStorage (the key performance win)

### What the Sidecar Does NOT Handle

Vector search stays in the browser where it has WebGPU acceleration:
- ❌ Text embeddings (@huggingface/transformers)
- ❌ HNSW index (hnswlib-wasm)
- ❌ ML model inference

The vec-search operations in `server.clj` return "not configured" responses, which is correct - the frontend handles this gracefully.

### Why JVM?

- **Soft References**: JVM GC can automatically evict unused data under memory pressure - JavaScript has no equivalent
- **Memory management**: The `:ref-type :soft` option in JVM DataScript enables true lazy loading where unused B-tree nodes are automatically unloaded
- **Clojure compatibility**: Same language as ClojureScript, easy code sharing
- **Proven technology**: DataScript on JVM is battle-tested

### Note: Logseq's CLJS IStorage Fork

Logseq has forked DataScript and **ported IStorage to ClojureScript** ([logseq/datascript](https://github.com/logseq/datascript)). This means:

- ✅ Incremental storage works (only changed B-tree nodes written to SQLite)
- ✅ Lazy restore from SQLite works (nodes loaded on demand)
- ❌ **No soft references** - JavaScript has no equivalent to Java's `SoftReference`
- ❌ **No automatic memory eviction** - Once loaded, data stays in memory until page refresh

**The real performance bottleneck** is in `deps/db/src/logseq/db/common/initial_data.cljs`:
```clojure
;; Line 360-362: load ALL pages for file graphs at startup
(->> (d/datoms db :avet :block/name)
     (mapcat (fn [d] (d/datoms db :eavt (:e d)))))
```

This loads every page's datoms into memory at startup, regardless of platform (desktop, mobile, browser).

**The JVM sidecar advantage**: With `:ref-type :soft`, the JVM can automatically evict unused B-tree nodes when memory is low, then lazily reload them from SQLite when needed again. This is impossible in JavaScript.

### Project Structure

```
logsidian/                        # Main app (AGPL-3.0)
├── src/main/frontend/sidecar/    # CLJS sidecar client code
├── src/electron/electron/        # Electron sidecar integration
├── sidecar/                      # JVM sidecar server (MIT)
│   ├── src/logseq/sidecar/       # Clojure server code
│   ├── test/                     # Server tests
│   └── deps.edn                  # Sidecar dependencies
└── ...
```

### Implementation Phases

1. **Phase 0: Testing Infrastructure** ✅ - TDD workflow, error reporting, E2E tests
2. **Phase 1.1: IStorage with SQLite-JDBC** ✅ - `storage.clj` implements lazy loading (11 tests)
3. **Phase 1.2: Datom Sync** ✅ - `sync-datoms` operation, storage integration (6 tests)
4. **Phase 1.3: Outliner Operations** ✅ - Full outliner with 11 operations
   - Block ops: save, insert, delete, move, up/down, indent/outdent
   - Page ops: create, rename, delete
   - Import ops: batch-import-edn
5. **Phase 2: Electron Integration** ✅ - Complete sidecar integration
   - ✅ Phase 2.1: Main process sidecar bridge
   - ✅ Phase 2.2: File write-back
     - Affected pages tracking in outliner ops
     - Page tree export (`get-page-trees`)
     - Markdown serialization (`file-export.clj`)
     - `:thread-api/get-file-writes` handler
   - ✅ Phase 2.3: Initial file sync on graph open
     - `initial_sync.cljs` - Full graph datom sync
     - Wired into `:graph/added` event and graph-switch
   - ✅ Phase 2.4: File watcher integration
     - `file_sync.cljs` - Incremental sync on file changes
     - Wired into `watcher_handler.cljs`
     - `:thread-api/delete-page` handler for deletions
6. **Phase 3: E2E Tests** 📋 - Full Playwright test suite

**Current focus:** Phase 3 - E2E tests with Playwright

### Sidecar Development & Testing

The sidecar has two communication paths:
- **Electron (IPC)**: Desktop app uses TCP socket via main process IPC - **PRIMARY PATH**
- **Browser (WebSocket)**: Web app connects directly via WebSocket - **ON HOLD** (see below)

#### Running the Sidecar

```bash
# Build the sidecar JAR (from repo root)
cd sidecar && clj -T:build uberjar

# Run the sidecar server
cd sidecar && java -jar target/logsidian-sidecar.jar

# Sidecar listens on:
# - TCP port 47632 (for Electron IPC)
# - WebSocket port 47633 (for browser)
```

#### Running Sidecar JVM Tests

```bash
# Run all sidecar tests
cd sidecar && clj -M:test

# Run specific test namespace
cd sidecar && clj -M:test -n logseq.sidecar.storage-test
cd sidecar && clj -M:test -n logseq.sidecar.sync-test

# Run multiple namespaces
cd sidecar && clj -M:test -n logseq.sidecar.storage-test -n logseq.sidecar.sync-test
```

**Current test coverage:**

*JVM Sidecar Tests:*
| Test File | Tests | Assertions | Status |
|-----------|-------|------------|--------|
| `storage_test.clj` | 11 | 22 | ✅ All passing |
| `sync_test.clj` | 6 | 13 | ✅ All passing |
| `outliner_test.clj` | 17 | 97 | ✅ All passing |
| `file_export_test.clj` | 4 | 26 | ✅ All passing |
| `protocol_test.clj` | 6 | 17 | ✅ All passing |
| `validation_test.clj` | 4 | 8 | ✅ All passing |
| `server_test.clj` | 18 | 27+ | ✅ All passing (some port conflicts in parallel) |

**JVM Core unit tests: 66 tests, 230 assertions**

*CLJS Sidecar Tests:*
| Test File | Tests | Assertions | Status |
|-----------|-------|------------|--------|
| `initial_sync_test.cljs` | 6 | 281 | ✅ All passing |
| `file_sync_test.cljs` | 6 | 39 | ✅ All passing |

**CLJS Sidecar tests: 12 tests, 320 assertions**

#### Key Sidecar Operations

**Graph Management:**
```clojure
;; Create graph with optional SQLite storage and soft references
(server/create-graph server "repo-id" {:storage-path ":memory:"  ; or "/path/to.db"
                                        :ref-type :soft})          ; enables lazy loading

;; List graphs
(invoke :thread-api/list-db [])

;; Remove graph (closes storage)
(server/remove-graph server "repo-id")
```

**Datom Sync (for populating from main process):**
```clojure
;; Datom format: [entity-id attr value tx added?]
;; added? = true for assertions, false for retractions
(invoke :thread-api/sync-datoms
        ["repo-id"
         [[1 :block/name "page1" 1000 true]
          [1 :block/uuid #uuid "..." 1000 true]]
         {:full-sync? true}])
```

**Queries:**
```clojure
(invoke :thread-api/q ["repo-id" ['{:find [?e] :where [[?e :block/name _]]}]])
(invoke :thread-api/pull ["repo-id" '[*] [:block/name "page1"]])
(invoke :thread-api/datoms ["repo-id" :avet :block/name])
```

**Outliner Operations (via `apply-outliner-ops`):**
```clojure
;; Block operations
(invoke :thread-api/apply-outliner-ops
        ["repo-id"
         [[:save-block [{:block/uuid uuid :block/content "Updated"} {}]]
          [:insert-blocks [[{:block/content "New"}] target-id {:sibling? true}]]
          [:delete-blocks [[block-id] {:children? true}]]
          [:move-blocks [[block-id] target-id {:sibling? true}]]
          [:move-blocks-up-down [[block-id] true]]  ; true=up, false=down
          [:indent-outdent-blocks [[block-id] true {}]]]  ; true=indent, false=outdent
         {}])

;; Page operations
(invoke :thread-api/apply-outliner-ops
        ["repo-id"
         [[:create-page ["Page Title" {:format :markdown}]]
          [:rename-page [page-uuid "New Title"]]
          [:delete-page [page-uuid]]]
         {}])

;; Batch import
(invoke :thread-api/apply-outliner-ops
        ["repo-id"
         [[:batch-import-edn [{:blocks [{:uuid uuid
                                          :title "Page"
                                          :children [{:content "Block"}]}]}
                              {}]]]
         {}])
```

**Page Sync Operations (Phase 2.3/2.4):**
```clojure
;; Delete page from sidecar (used by file watcher on file delete)
(invoke :thread-api/delete-page ["repo-id" "page-name" {}])

;; Get page trees for file serialization
(invoke :thread-api/get-page-trees ["repo-id" [page-id-1 page-id-2]])

;; Get file writes (markdown content) for pages
(invoke :thread-api/get-file-writes ["repo-id" [page-id-1 page-id-2] "/graph/path" {}])
```

#### WebSocket Path Status (ON HOLD)

The WebSocket implementation is **functional but incomplete**. Current state:
- ✅ WebSocket server starts and accepts connections
- ✅ Transit serialization works (with datascript-transit handlers)
- ✅ Browser client connects and can send requests
- ❌ **Data sync issue**: Browser has graph data in IndexedDB, sidecar has empty DB

**Why it's on hold:** The WebSocket path requires syncing data FROM the browser TO the sidecar, which partially defeats the lazy loading purpose. The Electron path is the primary target because:
1. Main process can parse files directly and sync to sidecar
2. This is the actual production architecture
3. Lazy loading via IStorage works correctly with synced data

**To fix WebSocket path (future work):**
1. Export graph datoms from browser's IndexedDB/web worker
2. Send to sidecar via `thread-api/sync-datoms` (new endpoint needed)
3. Or use FileSystem Access API to let sidecar access files directly

#### E2E Testing with Playwright (Recommended)

The project has a comprehensive e2e test framework in `clj-e2e/` using [Wally](https://github.com/logseq/wally) (Clojure Playwright wrapper):

```bash
# Start the web app
yarn watch

# Start the sidecar (optional, for sidecar tests)
cd sidecar && java -jar target/logsidian-sidecar.jar

# Run e2e tests
cd clj-e2e
clojure -M:test                                    # All tests
clojure -M:test -n logseq.e2e.sidecar-basic-test   # Sidecar tests only
```

**E2E test features:**
- Console log capture (detects errors automatically)
- Screenshot on failure
- Page/block operations
- Sidecar-specific tests in `test/logseq/e2e/sidecar_basic_test.clj`

#### Manual Browser Testing (WebSocket - ON HOLD)

If you need to test WebSocket connectivity manually:

```javascript
// Enable WebSocket sidecar in browser console
localStorage.setItem('sidecar-websocket-enabled', 'true');
location.reload();

// Or programmatically
frontend.sidecar.core.force_enable_websocket_sidecar_BANG_();
await frontend.sidecar.core.start_websocket_sidecar_BANG_();

// Verify connection
frontend.sidecar.websocket_client.connected_QMARK_();  // true
frontend.sidecar.websocket_client.status();            // connection details
```

**Note:** WebSocket connection will succeed but queries will return empty results because the sidecar doesn't have the graph data.

#### Key Sidecar Files

**JVM Sidecar (MIT License):**
| File | Description |
|------|-------------|
| `sidecar/src/logseq/sidecar/server.clj` | Main entry point, operation handlers, graph management |
| `sidecar/src/logseq/sidecar/storage.clj` | ✅ IStorage implementation with SQLite-JDBC backing |
| `sidecar/src/logseq/sidecar/outliner.clj` | ✅ Outliner ops (11 ops), affected pages tracking, page tree export |
| `sidecar/src/logseq/sidecar/file_export.clj` | ✅ Markdown serialization for file writes |
| `sidecar/src/logseq/sidecar/pipes.clj` | ✅ TCP socket server for Electron IPC |
| `sidecar/src/logseq/sidecar/websocket.clj` | WebSocket server (http-kit) - ON HOLD |
| `sidecar/src/logseq/sidecar/protocol.clj` | Transit serialization with datascript-transit handlers |

**JVM Sidecar Tests:**
| File | Description |
|------|-------------|
| `sidecar/test/logseq/sidecar/storage_test.clj` | ✅ 11 tests for IStorage/SQLite |
| `sidecar/test/logseq/sidecar/sync_test.clj` | ✅ 6 tests for datom sync + storage integration |
| `sidecar/test/logseq/sidecar/outliner_test.clj` | ✅ 17 tests for outliner + file sync (97 assertions) |
| `sidecar/test/logseq/sidecar/protocol_test.clj` | ✅ 6 tests for Transit serialization |
| `sidecar/test/logseq/sidecar/server_test.clj` | ✅ 18 tests for server integration |

**ClojureScript Client:**
| File | Description |
|------|-------------|
| `src/main/frontend/sidecar/core.cljs` | Main entry point, `start-db-backend!` |
| `src/main/frontend/sidecar/client.cljs` | IPC client for Electron |
| `src/main/frontend/sidecar/initial_sync.cljs` | ✅ Full graph sync on graph open |
| `src/main/frontend/sidecar/file_sync.cljs` | ✅ Incremental sync on file changes |
| `src/main/frontend/sidecar/websocket_client.cljs` | WebSocket client for browser (ON HOLD) |
| `src/main/frontend/sidecar/spawn.cljs` | JVM process spawning |

**ClojureScript Tests:**
| File | Description |
|------|-------------|
| `src/test/frontend/sidecar/initial_sync_test.cljs` | ✅ 6 tests for datom extraction, batching |
| `src/test/frontend/sidecar/file_sync_test.cljs` | ✅ 6 tests for page sync, entity extraction |

**E2E Tests:**
| File | Description |
|------|-------------|
| `clj-e2e/test/logseq/e2e/sidecar_basic_test.clj` | Sidecar-specific e2e tests |
| `clj-e2e/test/logseq/e2e/fixtures.clj` | Test fixtures with console log capture |

---

## Upstream Sync Process

This project is based on Logseq 0.10.15. To merge upstream fixes:

```bash
git remote add upstream https://github.com/logseq/logseq.git
git fetch upstream --tags
git merge 0.10.15  # or newer 0.10.x tag
# Resolve conflicts
git push origin master
```

**Note:** We intentionally stay on 0.10.x branch (file-based only). Do not merge 0.11.x+ which includes database graphs.

---

## Windows Development Notes

### Claude Code Cygpath Issues (IMPORTANT)

Claude Code's bash environment uses cygpath to translate Windows paths, which causes failures with yarn/npm commands. The path `X:\source\repos\` gets corrupted to `X:\x\source\repos\`.

**Key principles for avoiding path corruption:**

1. **Use the PowerShell build script** for multi-step builds:
   ```bash
   cd /x/source/repos/logsidian && pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/build.ps1
   ```

2. **Run node tools directly** instead of through yarn scripts:
   ```powershell
   # Instead of: yarn css:build
   node node_modules/postcss-cli/index.js tailwind.all.css -o static/css/style.css

   # Instead of: yarn gulp:build
   node node_modules/gulp/bin/gulp.js build

   # Instead of: yarn webpack-app-build
   node node_modules/webpack/bin/webpack.js --config webpack.config.js
   ```

3. **Use `--ignore-scripts`** with yarn install to prevent nested bash calls:
   ```powershell
   yarn install --ignore-scripts
   ```

4. **For complex multi-step builds**, create a PowerShell script under `./scripts/`:
   - Create the folder if it doesn't exist
   - Script can set Node version via PATH without changing global nvm
   - Run tools directly via node to avoid bash path corruption

**Commands that work directly in bash** (no PowerShell needed):
- `clojure` commands (Clojure CLI doesn't use cygpath)
- `git` commands
- `gh` (GitHub CLI)
- File operations (`cp`, `ls`, `cat`, etc.)

**nvm switching requires cmd.exe** (PowerShell nvm switch doesn't persist):
```bash
cmd.exe /c "nvm use 22.21.0"
```

### Running PowerShell Scripts from Claude Code

**IMPORTANT:** When running PowerShell scripts that use yarn/npm, start them in a **separate Windows Terminal** to avoid inheriting the corrupted environment from Claude Code's bash shell.

**Option 1: Use `wt.exe` to spawn a new terminal:**
```bash
wt.exe -d "X:\source\repos\logsidian" pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/build.ps1
```

**Option 2: Use `Start-Process` from PowerShell to spawn isolated process:**
```powershell
Start-Process -FilePath "pwsh" -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts/build.ps1" -WorkingDirectory "X:\source\repos\logsidian" -Wait
```

**Option 3: Run node.exe directly** (avoids yarn entirely):
```powershell
# In scripts/build.ps1, use direct node paths:
$nodePath = "C:\Users\johan\AppData\Local\nvm\v22.21.0\node.exe"
& $nodePath "node_modules/gulp/bin/gulp.js" buildNoCSS
& $nodePath "node_modules/postcss-cli/index.js" tailwind.all.css -o static/css/style.css
```

**Why this matters:** Claude Code's bash environment sets environment variables that corrupt Windows paths when yarn spawns child processes. Starting a fresh terminal or using direct node.exe avoids inheriting these variables.

### Complete Local Build Process (Windows)

**Recommended: Use the PowerShell build script:**
```bash
cd /x/source/repos/logsidian && pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/build.ps1
```

Options:
- `-SkipInstall` - Skip yarn install step (faster rebuilds)
- `-ElectronOnly` - Only build electron (skip CSS/webpack)
- `-Help` - Show help

The script automatically:
- Sets the correct Node version from `.nvmrc`
- Runs yarn install with `--ignore-scripts`
- Builds tldraw and ui packages directly
- Runs postcss, gulp, cljs, webpack
- Packages the Electron app

**Output:** `static/out/Logseq-win32-arm64/Logseq.exe` or `static/out/Logseq-win32-x64/Logseq.exe`

### Build Script Details (`scripts/build.ps1`)

The build script was created to work around yarn/npm path corruption in Claude Code's bash environment. It runs all node tools directly via `node path/to/script.js` instead of through yarn scripts.

**Location:** `scripts/build.ps1`

**Key techniques used:**
1. **Session-only Node version:** Reads `.nvmrc`, finds matching nvm directory, prepends to PATH
2. **`--ignore-scripts` flag:** Prevents yarn from running postinstall scripts through bash
3. **Direct node execution:** All tools (postcss, gulp, webpack, electron-forge) run via `node module/path.js`
4. **Handle hoisted modules:** Some dependencies (like zx) get hoisted by yarn workspaces - script searches multiple locations

**Build steps executed:**
| Step | Tool | Direct Path |
|------|------|-------------|
| 1 | yarn install | Uses `--ignore-scripts` to avoid bash |
| 1b | zx (tldraw build) | `node packages/tldraw/node_modules/zx/build/cli.js build.mjs` |
| 1c | parcel (ui build) | `node node_modules/parcel/lib/bin.js build --target ui` |
| 2 | postcss | `node node_modules/postcss-cli/index.js tailwind.all.css ...` |
| 3 | gulp | `node node_modules/gulp/bin/gulp.js build` |
| 4 | shadow-cljs | `yarn cljs:release-electron` (Clojure - works in bash) |
| 5 | webpack | `node node_modules/webpack/bin/webpack.js --config webpack.config.js` |
| 6 | electron-forge | `node static/node_modules/@electron-forge/cli/dist/electron-forge.js make` |

**Known issue:** If gulp's `clean` task fails with EBUSY (file locked), `resources/package.json` won't be copied to `static/`. Fix: `cp -r resources/* static/` manually.

### Native Module: rsapi

The `@logseq/rsapi-win32-x64-msvc` native module is required but NOT included in npm packages.

**For local builds**, copy from a GitHub release:
```bash
# Download release
gh release download <tag> --repo johanclawson/logsidian --pattern "Logseq-win32-x64-*.zip"

# Extract and copy rsapi module
# From: temp-extract/resources/app/node_modules/@logseq/rsapi-win32-x64-msvc/
# To:   static/out/Logseq-win32-x64/resources/app/node_modules/@logseq/
```

**GitHub Actions** builds rsapi automatically in the `build-rsapi-arm64` job.

### Post-Package File Copying

After `electron:make`, if files are missing, manually copy:

```bash
# CSS (if gulp:build failed on CSS step)
cp static/css/style.css static/out/Logseq-win32-x64/resources/app/css/

# Webpack bundles (if not copied by packager)
cp static/js/*-bundle.js static/out/Logseq-win32-x64/resources/app/js/
cp static/js/*.wasm static/out/Logseq-win32-x64/resources/app/js/

# rsapi native module (from GitHub release)
cp -r <extracted>/resources/app/node_modules/@logseq/rsapi-win32-x64-msvc \
      static/out/Logseq-win32-x64/resources/app/node_modules/@logseq/
```

### Debugging the Built App

Open DevTools in the Electron app: **Ctrl+Shift+I**

Common errors and fixes:
| Error | Cause | Fix |
|-------|-------|-----|
| `Cannot find module '@logseq/rsapi-win32-x64-msvc'` | Missing native module | Copy from GitHub release |
| `Failed to load db-worker-bundle.js` | Missing webpack bundles | Run `npm run webpack-app-build` and copy |
| Unstyled UI (no CSS) | Missing style.css | Run `npm run css:build` and copy |

### Install Dependencies

Via scoop:
```
scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
scoop bucket add extras
scoop bucket add java
scoop install java/openjdk clj-deps babashka leiningen nodejs-lts
```

Or via winget:
```
winget install --id CoreyButler.NVMforWindows
nvm install 22
nvm use 22
npm install -g yarn
winget install --id Microsoft.OpenJDK.21
```

---

## Related Resources

- **Upstream Logseq**: https://github.com/logseq/logseq
- **Logseq 0.10.15**: https://github.com/logseq/logseq/releases/tag/0.10.15
- **ARM64 Fork (archived)**: https://github.com/johanclawson/logseq-win-arm64

---

## Links

- **Main Repo**: https://github.com/johanclawson/logsidian (private)
- **Domain**: https://logsidian.com (reserved)
- **GitHub Org**: https://github.com/logsidian (reserved)

---

## Disclaimer

Logsidian is an independent open-source project. It is not affiliated with, endorsed by, or connected to Logseq Inc. or Dynalist Inc. (makers of Obsidian).

- Logseq is a trademark of Logseq Inc.
- Obsidian is a trademark of Dynalist Inc.
