# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

**Logsidian** - "Obsidian's speed with Logseq's blocks, files stay yours."

A high-performance fork of Logseq 0.10.15 (file-based only) with a JVM sidecar for lazy loading large graphs.

| Item | Value |
|------|-------|
| **Base** | Logseq 0.10.15 |
| **License** | AGPL-3.0 (app), MIT (sidecar) |
| **Status** | Early Development |

---

## Quick Reference

| Task | Command |
|------|---------|
| Dev server | `yarn watch` (http://localhost:3001) |
| Desktop dev | `yarn watch` then `yarn dev-electron-app` |
| Run tests | `bb dev:lint-and-test` |
| Build sidecar | `cd sidecar && clj -T:build uberjar` |
| Run sidecar | `cd sidecar && java -jar target/logsidian-sidecar.jar` |
| Sidecar tests | `cd sidecar && clj -M:test` |
| E2E tests | `npx playwright test --config e2e-electron/playwright.config.ts` |
| Production build | See "Windows Builds" below |

**Requirements:** Node.js 22.x, Java 21, Clojure CLI 1.11+, Yarn

---

## Windows Builds

**Use isolated PowerShell to avoid path corruption:**
```bash
cmd.exe /c "cd /d X:\source\repos\logsidian && pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\build.ps1"
```

Options: `-SkipInstall`, `-ElectronOnly`, `-Help`

**Output:** `static/out/Logseq-win32-{arch}/Logseq.exe`

> **Note:** See global CLAUDE.md for detailed path corruption issues with yarn/npm in Claude Code's bash environment.

### Native Binaries (arm64/x64)

Pre-built native binaries are stored in `native-binaries/`:
- `native-binaries/win32-arm64/` - ARM64 binaries
- `native-binaries/win32-x64/` - x64 binaries

**Critical binary:** `rsapi.win32-{arch}-msvc.node` - Required for file parsing.

The build script auto-detects architecture and copies the correct binaries. If E2E tests fail with rsapi errors, manually copy:
```bash
# For arm64:
cp native-binaries/win32-arm64/rsapi.win32-arm64-msvc.node static/out/Logseq-win32-arm64/resources/app/node_modules/@logseq/rsapi/
```

---

## Architecture

```
Renderer (CLJS UI) ──IPC──> Main Process ──TCP──> JVM Sidecar
                              │                      │
                         mldoc parser          DataScript + IStorage
                         (file parsing)        (lazy loading, SQLite)
```

**Key insight:** mldoc (OCaml→JS) can only run in JavaScript. Worker parses files, sidecar handles queries with lazy loading.

### Source Organization

```
src/main/frontend/          # ClojureScript frontend
src/main/frontend/sidecar/  # Sidecar client (IPC, sync)
src/electron/electron/      # Electron main process
sidecar/src/logseq/sidecar/ # JVM sidecar server (Clojure)
deps/                       # Internal CLJS libraries (db, outliner, graph-parser)
e2e-electron/               # Playwright E2E tests
clj-e2e/                    # Browser E2E tests (Wally)
```

### Key Sidecar Files

| Component | Location |
|-----------|----------|
| Server & handlers | `sidecar/src/logseq/sidecar/server.clj` |
| IStorage/SQLite | `sidecar/src/logseq/sidecar/storage.clj` |
| Outliner ops | `sidecar/src/logseq/sidecar/outliner.clj` |
| File export | `sidecar/src/logseq/sidecar/file_export.clj` |
| Initial sync | `src/main/frontend/sidecar/initial_sync.cljs` |
| File sync | `src/main/frontend/sidecar/file_sync.cljs` |

---

## Testing

```bash
# Unit tests
bb dev:lint-and-test        # All linters + tests
yarn test                   # Unit tests only
cd sidecar && clj -M:test   # Sidecar JVM tests

# Focus testing
# Add ^:focus metadata to test, then:
bb dev:test -i focus

# E2E tests (build app first)
npx playwright test --config e2e-electron/playwright.config.ts
```

**Test file naming:** Must end with `_test.cljs` (e.g., `my_feature_test.cljs`)

---

## GitHub Actions

| Workflow | Trigger |
|----------|---------|
| `build-windows.yml` | `version.cljs` changes or manual |
| `test-sidecar-ci.yml` | Push to `feature-sidecar-cicd` or manual |
| `sync-upstream.yml` | Weekly (Mon 6:00 UTC) or manual |

Manual options: `test_release`, `skip_x64`, `skip_arm64`

### What CI Builds

The `build-windows.yml` workflow produces:
- **Sidecar JAR** (~27 MB) - JVM backend
- **Minimal JRE** (~35 MB) - Custom runtime via jlink
- **MSI Installer** (~310 MB) - Includes bundled JRE (no Java required)

### Bundled JRE

The app automatically finds the bundled JRE at `{resources}/jre/bin/java.exe`. Falls back to `JAVA_HOME` or system `PATH` if not found.

---

## Documentation

| Looking for... | Location |
|----------------|----------|
| Build/develop guide | This file |
| Master plan & phases | `docs/plans/master-plan.md` |
| Architecture details | `docs/architecture/` |
| Task documents | `docs/tasks/` |
| Original Logseq docs | `docs/upstream/` |
| ADRs | `docs/decisions/` |

---

## Code Conventions

- Keywords via `logseq.common.defkeywords/defkeyword`
- File-specific code in `file_based/` directories
- Worker and frontend namespaces must stay separate
- Test metadata: `^:focus`, `^:benchmark`, `^:sidecar`, `^:smoke`

---

## REPL Setup (VSCode + Calva)

1. Run `yarn watch` (nREPL on port 8701)
2. `Cmd+Shift+P` → "Calva: Connect to a Running REPL Server"
3. Select: logseq → shadow-cljs → :app → localhost:8701

---

## Sidecar API Quick Reference

```clojure
;; Graph management
:thread-api/list-db, :thread-api/sync-datoms

;; Queries
:thread-api/q, :thread-api/pull, :thread-api/datoms

;; Outliner operations
:thread-api/apply-outliner-ops
;; Ops: :save-block, :insert-blocks, :delete-blocks, :move-blocks,
;;      :move-blocks-up-down, :indent-outdent-blocks,
;;      :create-page, :rename-page, :delete-page, :batch-import-edn

;; File sync
:thread-api/delete-page, :thread-api/get-page-trees, :thread-api/get-file-writes
```

---

## Upstream Sync

```bash
git fetch upstream --tags
git merge 0.10.x  # Stay on file-based only, avoid 0.11.x+
```

---

## Links

- **Upstream**: https://github.com/logseq/logseq
- **Base version**: https://github.com/logseq/logseq/releases/tag/0.10.15

---

## Disclaimer

Logsidian is independent and not affiliated with Logseq Inc. or Dynalist Inc.
