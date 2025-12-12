# Electron E2E Tests

End-to-end tests for the Logsidian Electron app with sidecar integration.

These tests use Playwright's Electron support to launch the actual desktop app and verify sidecar functionality.

## Prerequisites

1. **Electron app built:**
   ```powershell
   pwsh -File scripts/build.ps1
   ```

2. **Sidecar JAR built:**
   ```bash
   cd sidecar && clj -T:build uberjar
   ```

3. **Copy style.css to built app** (if not present):
   ```bash
   cp static/css/style.css static/out/Logseq-win32-x64/resources/app/css/
   ```

## Running Tests

```bash
# From repo root
npx playwright test --config e2e-electron/playwright.config.ts

# With headed mode (see the app)
npx playwright test --config e2e-electron/playwright.config.ts --headed

# Specific test
npx playwright test --config e2e-electron/playwright.config.ts -g "app launches"
```

## Test Structure

- `tests/sidecar-smoke.spec.ts` - Basic smoke tests for sidecar integration
  - **app launches and shows UI** - Verifies app starts, sidecar connects, UI renders
  - **no critical console errors on startup** - Checks for sidecar/socket/transit errors
  - **can create a new page** (SKIPPED) - Requires `:thread-api/apply-outliner-ops`
  - **can create a block** (SKIPPED) - Requires `:thread-api/apply-outliner-ops`

## Current Status

| Test | Status | Notes |
|------|--------|-------|
| App launches | ✅ PASSING | Sidecar connects, handshake completes |
| No critical errors | ✅ PASSING | Filters expected errors |
| Create page | ⏸️ SKIPPED | Needs hybrid architecture (see below) |
| Create block | ⏸️ SKIPPED | Needs hybrid architecture (see below) |

## 🚨 Architecture Issue: Why Tests Are Skipped

**The "create page" and "create block" tests fail** because the sidecar currently **REPLACES** the web worker, but the web worker is needed for **file parsing** (mldoc).

**Root cause:**
- mldoc (the markdown/org-mode parser) is an OCaml library compiled to JavaScript
- It ONLY runs in JavaScript environments (browser/Node.js) - cannot run on JVM
- Without the web worker, no file parsing happens → sidecar has empty database

**Solution:** Implement hybrid architecture where:
1. Web worker starts FIRST for file parsing
2. Sidecar syncs datoms AFTER parsing completes
3. Queries go to sidecar (fast, memory-efficient)

See `C:\Users\johan\.claude\plans\logsidian-tdd-master-plan.md` Phase 2.5 for implementation details.

## Known Sidecar Operations Not Yet Implemented

The following operations trigger errors during tests but don't prevent the app from loading:

- `:thread-api/apply-outliner-ops` - Required for block editing
- `:thread-api/get-view-data` - Unknown operation
- `:thread-api/q` with certain query formats - Parse errors

These are secondary issues - the primary blocker is the hybrid architecture above.

## Why Playwright Node.js (not Wally/Java)?

The existing `clj-e2e/` tests use Wally, a Clojure wrapper for Playwright Java. However, **Playwright Java does not support Electron** ([issue #830](https://github.com/microsoft/playwright-java/issues/830)).

Playwright's Electron support is only available in the Node.js/JavaScript version, which is why these tests are written in TypeScript.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   Test Runner (Playwright)                   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │           Tests (TypeScript/Node.js)                 │   │
│  │     sidecar-smoke.spec.ts                           │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│                     Playwright Electron                     │
│                           │                                 │
└───────────────────────────┼─────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                     Electron App                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                 ClojureScript UI                     │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│                    IPC → TCP Socket                         │
│                           │                                 │
└───────────────────────────┼─────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                     JVM Sidecar                              │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              DataScript + SQLite                     │   │
│  │         (lazy loading with soft references)          │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Screenshots

Test screenshots are saved to `e2e-electron/screenshots/`:
- `first-window.png` - Splash screen
- `dom-content-loaded.png` - After DOM loads
- `app-ready.png` - After UI is visible
- `app-launched.png` - After test passes

## Debugging

Open DevTools in the Electron app during tests:
- Add `await page.pause();` in test to pause
- Use `--headed` flag to see the app
- Check `clj-e2e/error-reports/playwright-electron/` for HTML reports
