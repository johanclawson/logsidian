# ADR-002: Thin Worker Architecture - Post-Mortem

**Status:** Rejected
**Date:** 2025-12-17
**Deciders:** Johan Clawson
**Related:** [ADR-001: JVM Sidecar](./001-jvm-sidecar.md), [Thin Worker Architecture Plan](../plans/thin-worker-architecture.md)

## Summary

After extensive code analysis, the proposed "Thin Worker Architecture" (removing DataScript from the web worker and using only the JVM sidecar for queries) has been determined to be **architecturally infeasible** with the current codebase design.

---

## What Was Attempted

### Original Goal

Transform the current hybrid architecture into a "thin worker" model:

| Component | Current | Proposed |
|-----------|---------|----------|
| Worker DataScript | Full graph (~500MB) | LRU cache (~1000 entities) |
| Sidecar DataScript | Full graph (lazy) | Full graph (lazy) - unchanged |
| Memory (100k blocks) | ~1GB combined | ~130MB combined |

### Proposed Benefits

- 8x reduction in RAM usage for large graphs
- Lazy loading via IStorage (JVM-only feature)
- Worker becomes thin: only mldoc parsing, WebGPU inference, search indexing

---

## Investigation Methodology

Five parallel code analysis agents examined:

1. **Cache Coherence** - Sync mechanisms, invalidation paths
2. **Entity References** - d/entity behavior, reference chains
3. **Undo Stack** - Entity requirements for undo validation
4. **Reactive Queries** - Query refresh mechanisms
5. **Transaction Conflicts** - Ordering, atomicity guarantees

---

## Critical Blockers Discovered

### BLOCKER 1: DataScript Entity Resolution is Eager (CRITICAL)

**Location:** `deps/db/src/logseq/db/common/entity_plus.cljc`

DataScript's `d/entity` does not lazy-load missing entities. When accessing reference attributes on a cached entity, if the target entity isn't in the cache, it returns `nil`.

```clojure
;; Entity A (in cache) references Entity B (not in cache)
(:block/page entity-a)  ;; Returns db-id (e.g., 999)
(d/entity db 999)       ;; Returns nil! Entity B not loaded
```

**Affected Code Paths:**

| Function | File | Impact |
|----------|------|--------|
| `sort-by-order-recursive` | `frontend/db/model.cljs:182` | Wrong block order |
| `recur-replace-uuid-in-block-title` | `deps/db/frontend/content.cljs:180` | UUIDs shown instead of page names |
| `get-block-deep-last-open-child-id` | `frontend/db/model.cljs:227` | Navigation breaks |
| `with-pages` | `frontend/db/model.cljs:151` | Blocks lose page context |
| Block component refs | `frontend/components/block.cljs:99` | Missing tags/properties |

**Root Cause:** These functions assume `d/entity` always returns complete data. With partial cache, reference chains break silently.

---

### BLOCKER 2: Reactive Query Refresh Only Sees Worker Cache (CRITICAL)

**Location:** `frontend/worker/react.cljs:31-95`

The `get-affected-queries-keys` function computes which queries need refresh based **only** on entities in the worker's database:

```clojure
(defn get-affected-queries-keys [{:keys [tx-data db-after]}]
  (let [block-entities (keep (fn [block-id]
                               (d/entity db-after block-id))  ;; nil if not cached!
                             blocks)]
    ;; Only generates keys for cached entities
    affected-keys))
```

**Consequences:**

1. Transactions affecting uncached blocks generate **zero affected keys**
2. Cached queries **never refresh** for uncached entity changes
3. UI shows stale results permanently

**Example:**
- Block #8500 (not in worker cache) gets new reference
- `get-affected-queries-keys` returns `[]` (empty)
- Query showing references **never updates**

---

### BLOCKER 3: Undo Validation Requires Pinned Entities (CRITICAL)

**Location:** `frontend/undo_redo.cljs:207-252`

The undo system validates operations by checking entity state via synchronous `d/entity` calls:

```clojure
;; get-reversed-datoms validation checks:
(d/entity @conn e)                    ;; Line 218: Check entity exists
(:block/_parent entity)               ;; Line 171: Get all children
(d/entity @conn before-parent)        ;; Line 198: Check old parent exists
```

**Requirements:**

| Requirement | Value | Conflict |
|-------------|-------|----------|
| Undo stack depth | 100 operations | Must pin entities |
| Entities per operation | 5-10 average | ~1000 entities minimum |
| Parent chain depth | 3-5 levels | More entities needed |
| Access latency | <1ms | Sidecar round-trip ~10-50ms |

**Conflict:** LRU eviction would remove entities still needed for undo validation. Evicting an entity breaks all undo operations that reference it.

---

### BLOCKER 4: One-Way Sync Architecture (CRITICAL)

**Location:** `frontend/sidecar/file_sync.cljs`, `frontend/sidecar/routing.cljs`

The sync architecture is strictly worker → sidecar:

```clojure
;; routing.cljs:117-125
;; "Write operations (:thread-api/transact, :thread-api/apply-outliner-ops)
;;  are intentionally NOT included here. The sidecar only receives data during
;;  initial sync..."
```

**Missing Infrastructure:**

1. No sidecar → worker push notifications
2. No cache invalidation on transact
3. No query result invalidation
4. No subscription/watch API

**Impact:** Worker cannot know when sidecar data changes, making cache coherence impossible.

---

### BLOCKER 5: Multi-Page Transaction Atomicity Lost (HIGH)

**Location:** `frontend/sidecar/file_sync.cljs:188-211`

File syncs are per-page, not atomic:

```clojure
;; Move block from Page A to Page B creates 2 datoms:
;; Worker: Single atomic d/transact!

;; Sidecar sync:
;; T1: Page A sync → [:db/retract block-id :block/parent page-a-id]
;; T2: Page B sync → [:db/add block-id :block/parent page-b-id]
;; Between T1-T2: Block is ORPHANED in sidecar!
```

---

### BLOCKER 6: Initial Sync Race Condition (HIGH)

**Location:** `frontend/sidecar/initial_sync.cljs:88-145`

```
T1: Extract datoms (snapshot)
T2: User edits (after snapshot)
T3: Sync completes, queries routed to sidecar
→ Sidecar missing T2 edits, queries return stale data
```

---

## Why These Are Fundamental

These aren't implementation bugs—they're **architectural mismatches**:

| Thin Worker Assumes | Reality |
|---------------------|---------|
| Lazy entity loading | DataScript is eager |
| Cache coherence via push | Architecture is pull-only |
| Async undo validation OK | Must be synchronous (<1ms) |
| Queries are independent | Queries depend on cached entities |
| Partial cache serves reads | Reference chains require complete data |

**The codebase was designed with worker as the single source of truth.** Every component assumes complete DataScript access.

---

## Alternatives Considered

### Alternative A: Dual-Cache with Sync

Keep full DataScript in both worker and sidecar, sync bidirectionally.

**Rejected:** Still duplicates memory, gains only lazy loading benefit.

### Alternative B: Cache Query Results, Not Entities

Cache `d/q` results instead of entities in worker.

**Challenges:**
- Query result invalidation is complex
- `d/pull` and `d/entity` still need entity access
- Doesn't solve undo requirements

### Alternative C: Hybrid with Larger Worker Cache

Keep worker cache at 10,000+ entities, use sidecar for cold queries.

**Status:** Most viable path forward (see Recommendations).

---

## Lessons Learned

1. **Entity Resolution is Eager:** DataScript's design assumes complete database. Partial caching violates this assumption at every level.

2. **Reference Chains Break Silently:** `d/entity` returning `nil` propagates through the codebase without errors, causing subtle UI bugs.

3. **Undo is Architecturally Critical:** The undo system's need for synchronous entity access creates a hard constraint on cache eviction.

4. **Reactive Queries Need Complete Views:** The affected-keys computation fundamentally requires seeing all affected entities.

5. **One-Way Sync Prevents Cache Coherence:** Without sidecar → worker notifications, cache invalidation is impossible.

---

## Recommendations

### Short-Term: Fix Current Hybrid Architecture

The existing hybrid mode has known issues that should be fixed first:

1. **Incremental sync never reaches sidecar** - After initial sync, edits go to worker but sidecar never gets updates
2. **In-memory storage only** - Sidecar uses `:memory:` instead of persistent SQLite
3. **Strict mode defaults to crash** - Sidecar failure crashes app instead of fallback

See: [Plan File](file:///C:/Users/johan/.claude/plans/merry-crunching-sloth.md)

### Medium-Term: Optimize Existing Architecture

1. **Query-Level Routing:** Route expensive queries to sidecar, keep simple ones in worker
2. **Selective Sync:** Only sync pages user has opened (not full graph)
3. **Lazy Loading via Sidecar:** Use sidecar for cold data, worker for hot data
4. **Memory Optimization:** Profile and reduce worker memory footprint

### Long-Term: Re-Architecture Required

If thin worker is truly needed, requires:

1. **Rewrite reactive query system** to work with remote entities
2. **Async undo validation** with ~50ms latency tolerance
3. **Bidirectional sync** with push notifications
4. **Reference-aware entity loading** that fetches chains

**Estimated effort:** 6-12 months, significant risk

---

## Conclusion

The thin worker architecture is **not viable** with the current codebase design. The proposal required DataScript to behave like a lazy-loading database with remote entity resolution, but DataScript is fundamentally an in-memory, eager-loading database.

The path forward is to:
1. Fix the current hybrid architecture issues
2. Optimize within the existing constraints
3. Accept that worker needs substantial memory for large graphs
4. Consider re-architecture only if memory becomes a critical blocker

---

## References

- [ADR-001: JVM Sidecar](./001-jvm-sidecar.md)
- [Thin Worker Architecture Plan](../plans/thin-worker-architecture.md)
- [Master Plan](../plans/master-plan.md)
- [DataScript IStorage docs](https://github.com/tonsky/datascript/blob/master/docs/storage.md)
