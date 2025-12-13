# Phase 2.5: Hybrid Worker + Sidecar Architecture

**Worktree:** Main (`X:\source\repos\logsidian`)
**Branch:** `feature-sidecar`
**Status:** In Progress
**Priority:** 1 (CRITICAL - blocks everything else)
**Estimate:** 2-3 days

## Problem

The current sidecar implementation starts EITHER the sidecar OR the web worker, but we need BOTH:

- **Web Worker:** Parses files with mldoc (OCaml→JavaScript, cannot run on JVM)
- **JVM Sidecar:** Handles queries with lazy loading via IStorage (JVM-only feature)

Without both running, the sidecar has an empty database and E2E tests fail.

## Solution

Modify `start-db-backend!` to:
1. Always start the web worker FIRST (for file parsing)
2. Start sidecar in parallel
3. Sync datoms to sidecar after parsing completes (via `:graph/added` event)

## Implementation Steps

### Step 1: Write Failing Test

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

### Step 2: Modify `start-db-backend!`

**File:** `src/main/frontend/sidecar/core.cljs`

**Current (broken):**
```clojure
(cond
  use-ipc-sidecar?
  (-> (start-sidecar!) ...)  ;; Only starts sidecar!

  use-websocket-sidecar?
  (-> (start-websocket-sidecar!) ...)

  :else
  (start-web-worker-fn))
```

**Target (correct):**
```clojure
;; Always start worker for parsing, optionally sync to sidecar
(-> (start-web-worker-fn)
    (p/then (fn [_]
              (when (or use-ipc-sidecar? use-websocket-sidecar?)
                (start-sidecar-after-worker!)))))
```

### Step 3: Verify Sync Flow

The Phase 2.3 code already handles sync on `:graph/added`:
1. Worker starts and parses files
2. Worker populates frontend DataScript
3. `:graph/added` event fires
4. `initial_sync.cljs` syncs datoms to sidecar
5. Sidecar now has data for queries

### Step 4: Run Tests

```bash
# Unit tests
yarn cljs:test && yarn cljs:run-test -n frontend.sidecar.hybrid-test

# E2E tests (after building)
npx playwright test --config e2e-electron/playwright.config.ts
```

### Step 5: Enable Skipped E2E Tests

Remove `test.skip` from:
- "can create a new page"
- "can create a block"

## Files to Modify

| File | Change |
|------|--------|
| `src/main/frontend/sidecar/core.cljs` | Modify `start-db-backend!` |
| `src/test/frontend/sidecar/hybrid_test.cljs` | Create new test file |
| `e2e-electron/tests/sidecar-smoke.spec.ts` | Enable skipped tests |

## Success Criteria

- [ ] Unit test passes: worker AND sidecar both running
- [ ] E2E test passes: "can create a new page"
- [ ] E2E test passes: "can create a block"
- [ ] No console errors related to empty sidecar DB

## Related Documentation

- Master Plan: Phase 2.5 section
- Existing code: `src/main/frontend/sidecar/initial_sync.cljs`
- Existing tests: `src/test/frontend/sidecar/initial_sync_test.cljs`
