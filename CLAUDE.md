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
