# Phase 2.5: Hybrid Worker + Sidecar Architecture

**Worktree:** Main (`X:\source\repos\logsidian`)
**Branch:** `feature-sidecar`
**Status:** Bug Fixed - Awaiting E2E Verification
**Priority:** 1 (CRITICAL - blocks everything else)
**Estimate:** < 1 hour (E2E test run)

## Current Status Summary

### Bug Fix Applied (2024-12-14)

**Problem:** `browser.cljs:183` was checking `(= type :sidecar)` but `start-db-backend!` returns `{:type :hybrid :sidecar :ipc}`. The sidecar setup code never executed.

**Fix:** Changed to `(or (= type :sidecar) sidecar)` - checks for either pure sidecar mode OR truthy sidecar key.

**Files Changed:**
| File | Change |
|------|--------|
| `src/main/frontend/persist_db/browser.cljs:182-187` | Fixed type check logic |
| `src/test/frontend/sidecar/hybrid_type_test.cljs` | New unit tests for type handling |
| `e2e-electron/tests/sidecar-smoke.spec.ts:311,332` | Enabled skipped tests |

**Tests:** Unit tests pass (7 tests, 10 assertions, 0 failures)

---

### Code Implementation (Complete)

| Component | File | Status |
|-----------|------|--------|
| `start-db-backend!` | `src/main/frontend/sidecar/core.cljs:215` | Starts worker FIRST, then sidecar |
| Initial sync trigger | `src/main/frontend/handler/events.cljs:72` | Syncs on `:graph/added` |
| Graph switch sync | `src/main/frontend/handler/events.cljs:113` | Syncs on graph switch |
| Browser integration | `src/main/frontend/persist_db/browser.cljs:177` | Calls `start-db-backend!` |
| Sidecar setup check | `src/main/frontend/persist_db/browser.cljs:182-187` | **FIXED** type check |
| File change sync | `src/main/frontend/sidecar/file_sync.cljs` | Incremental sync |
| Batch datom sync | `src/main/frontend/sidecar/initial_sync.cljs` | 5000 datoms/batch |

---

## Remaining Work

### Run E2E Tests

```bash
# 1. Build the app
cmd.exe /c "cd /d X:\source\repos\logsidian && pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\build.ps1"

# 2. Build sidecar
cd sidecar && clj -T:build uberjar

# 3. Run E2E tests (sidecar starts automatically via fixtures)
npx playwright test --config e2e-electron/playwright.config.ts
```

### If Tests Fail

Check:
1. Is sidecar JAR built? `sidecar/target/logsidian-sidecar.jar`
2. Is app built? `static/out/Logseq-win32-x64/Logseq.exe`
3. Console logs for:
   - `:hybrid-starting-worker {}`
   - `:hybrid-worker-started {}`
   - `:hybrid-sidecar-connected {:type :ipc}`
   - `:sidecar-initial-sync-complete {...}`

---

## The Bug (Fixed)

**Original code (`browser.cljs:183`):**
```clojure
(p/then (fn [{:keys [type]}]
          (when (= type :sidecar)   ;; BUG: Returns :hybrid, not :sidecar!
            ;; This setup never happens
            ...)))
```

**`start-db-backend!` returns:**
```clojure
{:type :hybrid :worker true :sidecar :ipc}    ;; Actual
{:type :sidecar :port port}                   ;; What old code expected
```

**Fixed code:**
```clojure
(p/then (fn [{:keys [type sidecar]}]
          ;; Run sidecar setup when sidecar is actually active
          (when (or (= type :sidecar) sidecar)
            ...)))
```

---

## Architecture Diagram

```
                     Electron (Renderer)
  ┌─────────────────────────────────────────────────────┐
  │                 ClojureScript UI                     │
  └───────────────────────┬─────────────────────────────┘
                          │ IPC
┌─────────────────────────▼──────────────────────────────┐
│                   Electron Main Process                 │
│  ┌─────────────────────────────────────────────────┐   │
│  │              browser.cljs → sidecar/core.cljs   │   │
│  │   start-db-backend! → worker + sidecar          │   │
│  └───────────────────────┬─────────────────────────┘   │
│                          │                              │
│           ┌──────────────┴──────────────┐              │
│           ▼                              ▼              │
│  ┌─────────────────┐          ┌──────────────────────┐ │
│  │   Web Worker    │          │   TCP → Sidecar      │ │
│  │  (mldoc parser) │          │   (queries)          │ │
│  └────────┬────────┘          └──────────┬───────────┘ │
└───────────┼────────────────────────────────┼───────────┘
            │                                │
            │ datoms                         │ TCP
            ▼                                ▼
┌───────────────────────┐     ┌──────────────────────────┐
│ Frontend DataScript   │────▶│        JVM Sidecar       │
│ (after parsing)       │sync │   DataScript + IStorage  │
└───────────────────────┘     │   SQLite-JDBC backing    │
                              └──────────────────────────┘
```

---

## Key Files

| File | Purpose |
|------|---------|
| `src/main/frontend/sidecar/core.cljs` | Main entry point, `start-db-backend!` |
| `src/main/frontend/persist_db/browser.cljs` | Sidecar setup on startup |
| `src/main/frontend/sidecar/initial_sync.cljs` | Full graph sync to sidecar |
| `src/main/frontend/sidecar/file_sync.cljs` | Incremental file change sync |
| `src/main/frontend/handler/events.cljs` | `:graph/added` handler triggers sync |
| `src/test/frontend/sidecar/hybrid_type_test.cljs` | Unit tests for type handling |
| `e2e-electron/tests/sidecar-smoke.spec.ts` | E2E tests (now enabled) |

---

## Success Criteria

- [x] `start-db-backend!` starts worker FIRST, then sidecar
- [x] Initial sync triggers on `:graph/added` event
- [x] Datoms batch-synced to sidecar (5000/batch)
- [x] File changes incrementally synced
- [x] Browser.cljs type check handles `:hybrid` type
- [x] Unit tests for type handling pass (7 tests, 10 assertions, 0 failures)
- [x] E2E tests enabled (no longer skipped)
- [ ] E2E test: "can create a new page" - BLOCKED by rsapi infrastructure issue
- [ ] E2E test: "can create a block" - BLOCKED by rsapi infrastructure issue
- [ ] No console errors related to empty sidecar DB

**Note:** E2E tests cannot run due to pre-existing rsapi error in Electron app startup.
This is an infrastructure issue, not related to the hybrid architecture fix.

---

## Related Documentation

- Master Plan: Phase 2.5 section - `docs/plans/master-plan.md`
- Architecture: `docs/architecture/sidecar-overview.md`
- ADR: `docs/decisions/001-jvm-sidecar.md`
