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

**Use the build script for reliable builds:**
```bash
# From Claude Code bash (isolated PowerShell to avoid path corruption):
cmd.exe /c "cd /d X:\source\repos\logsidian && pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\build.ps1"

# Options:
#   -SkipInstall    Skip yarn install step (faster rebuilds)
#   -ElectronOnly   Only build electron (skip CSS and webpack)
#   -Help           Show help message
```

**Output:** `static/out/Logseq-win32-{arch}/Logseq.exe`

**What the build script does:**
1. Sets Node.js version from `.nvmrc` via PATH (not nvm use)
2. Installs dependencies with `--ignore-scripts` to avoid path corruption
3. Builds tldraw and ui packages directly (avoids yarn script bash calls)
4. Runs gulp, PostCSS, ClojureScript, and webpack builds
5. Packages Electron app and copies native binaries

**Native binaries:** The script auto-detects architecture and copies pre-built binaries from `native-binaries/win32-{arch}/` if present.

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
| Early Splash Screen | `resources/electron-entry.js` | Instant visual feedback |
| Node.js 22 Compile Cache | `resources/electron-entry.js` | 30-50% faster startup |
| Direct Function Invocation | `shadow-cljs.edn` (`:fn-invoke-direct`) | 10-30% faster |
| Disabled Logging | `shadow-cljs.edn` (`goog.debug.LOGGING_ENABLED`) | ~5-10% faster |
| No Source Maps | `shadow-cljs.edn` (`:source-map false`) | Smaller bundles |
| Webpack Production Mode | `webpack.config.js` | Tree shaking enabled |
| Deferred DB/Git Operations | `src/electron/electron/core.cljs` | Faster window display |

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

**Architecture:**
```
┌─────────────────────────────────────────────────────────────┐
│                     Electron (Renderer)                      │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                 ClojureScript UI                     │    │
│  │         (existing Logseq frontend code)              │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │                                  │
│                    Transit over IPC                          │
│                           │                                  │
└───────────────────────────┼─────────────────────────────────┘
                            │
┌───────────────────────────┼─────────────────────────────────┐
│                     JVM Sidecar                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              DataScript + IStorage                   │    │
│  │         (lazy loading with soft references)          │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │                                  │
│                      File System                             │
│                           │                                  │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              Markdown/Org-mode Files                 │    │
│  │           (source of truth, unchanged)               │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Key Components:**
1. **DataScript IStorage**: JVM-only protocol enabling lazy loading with soft references
2. **Transit Serialization**: Already used in Logseq worker communication
3. **Named Pipes (Windows)**: Fast IPC with sub-millisecond latency
4. **Soft References**: JVM garbage collector automatically evicts unused data

### Why JVM?

- **DataScript IStorage is JVM-only**: The lazy loading protocol only exists in JVM DataScript
- **Soft References**: JVM GC can automatically manage memory pressure
- **Clojure compatibility**: Same language as ClojureScript, easy code sharing
- **Proven technology**: DataScript on JVM is battle-tested

### Project Structure

```
X:\source\repos\
├── logsidian/                    # Main app (AGPL-3.0)
│   └── (this repo)
│
├── logsidian-sidecar/            # JVM sidecar (MIT)
│   └── (separate repo - original code)
│
└── logseqWinArm64/               # ARM64 fork reference
    └── docs/hybrid-architecture-revised.md
```

### Implementation Phases

1. **Phase 0: Validation** - Test JVM startup time, IPC latency
2. **Phase 1: Read Path** - Lazy loading for queries
3. **Phase 2: Write Path** - Transaction handling
4. **Phase 3: Integration** - Full file-based graph support

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

**ALWAYS use PowerShell wrappers for these commands:**

```bash
# Instead of: yarn install
pwsh -NoProfile -Command "cd 'X:\source\repos\logsidian'; npm install"

# Instead of: yarn gulp:build
pwsh -NoProfile -Command "cd 'X:\source\repos\logsidian'; npm run gulp:build"

# Instead of: yarn css:build
pwsh -NoProfile -Command "cd 'X:\source\repos\logsidian'; npm run css:build"

# Instead of: yarn cljs:release-electron
pwsh -NoProfile -Command "cd 'X:\source\repos\logsidian'; npm run cljs:release-electron"

# Instead of: yarn webpack-app-build
pwsh -NoProfile -Command "cd 'X:\source\repos\logsidian'; npm run webpack-app-build"
```

**Commands that work directly in bash** (no PowerShell needed):
- `clojure` commands (Clojure CLI doesn't use cygpath)
- `git` commands
- `gh` (GitHub CLI)
- File operations (`cp`, `ls`, `cat`, etc.)

**nvm switching requires cmd.exe** (PowerShell nvm switch doesn't persist):
```bash
cmd.exe /c "nvm use 22.21.0 && cd /d X:\source\repos\logsidian\static && npm install"
```

### Complete Local Build Process (Windows x64)

**Recommended: Use the build script** (handles all path corruption issues):

```bash
# Full build from scratch
cmd.exe /c "cd /d X:\source\repos\logsidian && pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\build.ps1"

# Skip dependency install (faster rebuilds)
cmd.exe /c "cd /d X:\source\repos\logsidian && pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\build.ps1 -SkipInstall"

# Only rebuild ClojureScript
cmd.exe /c "cd /d X:\source\repos\logsidian && pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\build.ps1 -SkipInstall -ElectronOnly"
```

**Output:** `static/out/Logseq-win32-x64/Logseq.exe`

The build script (`scripts/build.ps1`) automatically:
- Sets correct Node.js version via PATH
- Builds tldraw and ui packages directly (avoids bash path corruption)
- Runs gulp, PostCSS, ClojureScript, and webpack builds
- Copies native binaries from `native-binaries/` if present
- Packages Electron app with electron-forge

### Native Module: rsapi

The `@logseq/rsapi-win32-x64-msvc` native module is required but NOT included in npm packages.

**Option 1: Pre-built binaries** (recommended)

Place pre-built binaries in `native-binaries/win32-{arch}/`:
```
native-binaries/
├── win32-x64/
│   ├── rsapi.win32-x64-msvc.node
│   ├── keytar.node
│   └── electron-deeplink.node
└── win32-arm64/
    ├── rsapi.win32-arm64-msvc.node
    ├── keytar.node
    └── electron-deeplink.node
```

The build script auto-detects architecture and copies these to the final app.

**Option 2: Download from GitHub release**
```bash
gh release download <tag> --repo johanclawson/logsidian --pattern "Logseq-win32-x64-*.zip"
# Extract and copy rsapi module to native-binaries/win32-x64/
```

**GitHub Actions** builds rsapi automatically in the `build-rsapi-arm64` job.

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
winget install --id Microsoft.OpenJDK.17
```

---

## Related Resources

- **Architecture Document**: `X:\source\repos\logseqWinArm64\docs\hybrid-architecture-revised.md`
- **ARM64 Fork**: https://github.com/johanclawson/logseq-win-arm64
- **Upstream Logseq**: https://github.com/logseq/logseq
- **Logseq 0.10.15**: https://github.com/logseq/logseq/releases/tag/0.10.15

---

## Links

- **Main Repo**: https://github.com/johanclawson/logsidian (private)
- **Sidecar Repo**: https://github.com/johanclawson/logsidian-sidecar (private)
- **Domain**: https://logsidian.com (reserved)
- **GitHub Org**: https://github.com/logsidian (reserved)

---

## Disclaimer

Logsidian is an independent open-source project. It is not affiliated with, endorsed by, or connected to Logseq Inc. or Dynalist Inc. (makers of Obsidian).

- Logseq is a trademark of Logseq Inc.
- Obsidian is a trademark of Dynalist Inc.
