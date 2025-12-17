# Thin Worker Architecture Plan

> **STATUS: REJECTED (2025-12-17)**
>
> This architecture was thoroughly investigated and determined to be **infeasible** due to fundamental DataScript design constraints. See [ADR-002: Thin Worker Post-Mortem](../decisions/002-thin-worker-postmortem.md) for detailed analysis.
>
> This document is preserved for historical reference only.

---

## Overview

This plan describes a hybrid architecture where the JVM sidecar becomes the single source of truth for all data, while the browser worker remains thin - handling only browser-specific APIs (mldoc parsing, WebGPU inference, search indexing).

**Goal:** Support large graphs (100k+ blocks) with lazy loading while maintaining fast parsing and vector search.

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                       SIDECAR (JVM)                             │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │           DataScript + IStorage (Lazy Loading)            │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │  │
│  │  │ All Queries │  │ Transactions│  │ SQLite      │        │  │
│  │  │ (q, pull,   │  │ (from AST   │  │ Persistence │        │  │
│  │  │  datoms)    │  │  extract)   │  │             │        │  │
│  │  └─────────────┘  └─────────────┘  └─────────────┘        │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │ Undo        │  │ Reactive    │  │ File        │              │
│  │ Validation  │  │ Push        │  │ Export      │              │
│  │ Queries     │  │ (WebSocket) │  │ (page→file) │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
└─────────────────────────────────────────────────────────────────┘
                              │
                       WebSocket/IPC
                              │
┌─────────────────────────────────────────────────────────────────┐
│                    WORKER (Browser) - THIN                      │
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │   mldoc     │  │  WebGPU     │  │  Search     │              │
│  │  (OCaml→JS) │  │  Inference  │  │  Index      │              │
│  │             │  │             │  │  (sql.js)   │              │
│  │ • Parse MD  │  │ • GPU model │  │             │              │
│  │ • Parse Org │  │ • HNSW idx  │  │ • FTS5      │              │
│  │ • Return AST│  │ • Embed gen │  │ • Upsert    │              │
│  └──────┬──────┘  └─────────────┘  └─────────────┘              │
│         │                                                       │
│         │ AST     ┌─────────────┐                               │
│         └────────▶│ Undo Stack  │  (tx-data only, no entities)  │
│                   │ (100 ops)   │                               │
│                   └─────────────┘                               │
│                                                                 │
│              ❌ NO FULL DATASCRIPT                               │
└─────────────────────────────────────────────────────────────────┘
```

---

## Data Flow

### File Change → Storage

```
File Change Detected
        │
        ▼
┌───────────────────┐
│ Worker: mldoc     │  Parse file → AST (fast, V8 JIT)
└───────┬───────────┘
        │ AST (via IPC)
        ▼
┌───────────────────┐
│ Sidecar: Extract  │  AST → pages/blocks → DataScript transactions
└───────┬───────────┘
        │
        ▼
┌───────────────────┐
│ Sidecar: Store    │  DataScript → SQLite (lazy persistence)
└───────┬───────────┘
        │
        ▼
┌───────────────────┐
│ Sidecar: Push     │  WebSocket → notify affected queries
└───────────────────┘
```

### Query Flow

```
UI Component needs data
        │
        ▼
┌───────────────────┐
│ Frontend: Route   │  All queries → sidecar
└───────┬───────────┘
        │ WebSocket/IPC
        ▼
┌───────────────────┐
│ Sidecar: Query    │  DataScript q/pull/datoms (lazy-loaded)
└───────┬───────────┘
        │ Result
        ▼
┌───────────────────┐
│ Frontend: Render  │  Update UI with result
└───────────────────┘
```

### Undo/Redo Flow (Async Validation)

```
User presses Ctrl+Z
        │
        ▼
┌───────────────────┐
│ Worker: Pop Stack │  Get tx-data from undo stack (local, fast)
└───────┬───────────┘
        │ Entity IDs to validate
        ▼
┌───────────────────┐
│ Sidecar: Validate │  Check entities exist, children exist (~50ms)
└───────┬───────────┘
        │ Validation result
        ▼
┌───────────────────┐
│ Sidecar: Apply    │  Apply reversed transaction
└───────┬───────────┘
        │
        ▼
┌───────────────────┐
│ Worker: Update    │  Push to redo stack, restore cursor
└───────────────────┘
```

### Vector Search Flow

```
User triggers embedding indexing
        │
        ▼
┌───────────────────┐
│ Sidecar: Query    │  Find stale blocks (hnsw-label = 0)
└───────┬───────────┘
        │ Block IDs + content (batched)
        ▼
┌───────────────────┐
│ Worker: Embed     │  Generate embeddings via WebGPU (70x faster)
└───────┬───────────┘
        │ Embeddings
        ▼
┌───────────────────┐
│ Worker: Index     │  Update HNSW index (browser IndexedDB)
└───────┬───────────┘
        │ Mark blocks as indexed
        ▼
┌───────────────────┐
│ Sidecar: Update   │  Set hnsw-label-updated-at = timestamp
└───────────────────┘
```

---

## Memory Footprint Comparison

### Current Architecture (Full Duplication)

| Component | Location | Size |
|-----------|----------|------|
| DataScript (full graph) | Worker | ~500MB |
| DataScript (full graph) | Sidecar | ~500MB |
| Undo stack | Worker | ~10MB |
| HNSW index | Worker | ~50MB |
| Search index | Worker | ~20MB |
| **Total RAM** | | **~1GB+** |

### Thin Worker Architecture

| Component | Location | Size |
|-----------|----------|------|
| mldoc (JS) | Worker | ~2MB |
| Undo stack (tx-data only) | Worker | ~10MB |
| HNSW index | Worker | ~50MB |
| Search index (sql.js) | Worker | ~20MB |
| **Worker Total** | | **~80MB RAM** |
| | | |
| DataScript (hot entities) | Sidecar | ~50MB |
| SQLite (full graph) | Sidecar (disk) | ~500MB |
| **Sidecar Total** | | **~50MB RAM** |
| | | |
| **Combined Total** | | **~130MB RAM** |

**Improvement:** ~8x reduction in RAM usage for large graphs.

---

## Component Responsibilities

| Component | Location | Responsibility |
|-----------|----------|----------------|
| **mldoc** | Worker | Parse .md/.org files → AST |
| **Extract** | Sidecar | AST → pages/blocks/properties → DataScript |
| **DataScript** | Sidecar | All queries, lazy-loaded via IStorage |
| **SQLite** | Sidecar | Persistent storage, large graph support |
| **Undo Stack** | Worker | Store tx-data for 100 operations (no entities) |
| **Undo Validation** | Sidecar | Entity existence checks (async, ~50ms) |
| **WebGPU Inference** | Worker | Embedding generation (GPU, 70x faster) |
| **HNSW Index** | Worker | Vector similarity search (browser IndexedDB) |
| **Search Index** | Worker | Full-text search (sql.js WASM) |
| **Reactive Push** | Sidecar | WebSocket notifications for query invalidation |
| **File Export** | Sidecar | page-tree → markdown file content |

---

## Why Each Component Lives Where It Does

### Worker (Browser-Only APIs)

| Component | Why Browser |
|-----------|-------------|
| mldoc | OCaml→JS, requires V8 JIT for performance |
| WebGPU | `navigator.gpu` API only exists in browser |
| HNSW | Uses hnswlib-wasm, persists to IndexedDB |
| Search | sql.js WASM, FTS5 full-text search |
| Undo Stack | UI-thread state, must be fast for Ctrl+Z |

### Sidecar (JVM Advantages)

| Component | Why JVM |
|-----------|---------|
| DataScript | IStorage for lazy loading (JVM-only feature) |
| SQLite | Native JDBC, no WASM overhead |
| Queries | Lazy loading means only hot entities in RAM |
| Extract | Pure Clojure, no JS dependency |
| File Export | Direct file system access |
| Validation | Can query lazy-loaded entities on demand |

---

## Key Design Decisions

### 1. No Full DataScript in Worker

**Current:** Worker has full DataScript copy for undo/redo validation and vector search.

**New:** Worker has NO DataScript. Validation and block fetching happen via sidecar queries.

**Trade-off:** +50ms latency for undo validation (still feels instant to users).

### 2. Async Undo Validation

**Current:**
```clojure
;; Synchronous d/entity on worker DataScript
(d/entity @conn e)  ;; Check entity exists
```

**New:**
```clojure
;; Async query to sidecar
(p/let [exists? (sidecar/<entity-exists? repo e)]
  (when exists? ...))
```

**Latency:** ~50ms for validation query, acceptable for undo/redo UX.

### 3. Vector Search Fetches from Sidecar

**Current:**
```clojure
;; Query worker DataScript for stale blocks
(d/datoms db :avet :hnsw-label-updated-at 0)
```

**New:**
```clojure
;; Query sidecar for stale blocks + content
(sidecar/<q repo '[:find ?e ?content
                   :where [?e :hnsw-label-updated-at 0]
                          [?e :block/title ?content]])
```

**Benefit:** No need for full graph in worker memory.

### 4. Keep mldoc in Worker

**Rationale:**
- mldoc is battle-tested (years of edge case fixes)
- V8 JIT makes it fast (~5ms for typical files)
- Rewriting in Clojure would take 2-3 months
- AST transfer is small (~10KB per file)

### 5. WebSocket Reactive Push

**Current:** Worker DataScript listener → BroadcastChannel → UI refresh.

**New:** Sidecar DataScript listener → WebSocket push → UI refresh.

**Benefit:** Single source of truth, no sync issues.

---

## Implementation Phases

### Phase 1: Async Undo Validation (1-2 weeks)

**Goal:** Prove undo/redo works with async sidecar validation.

**Files to modify:**
- `src/main/frontend/undo_redo.cljs` - Replace `d/entity` with sidecar queries
- `sidecar/src/logseq/sidecar/server.clj` - Add validation query handler

**New sidecar operation:**
```clojure
:thread-api/validate-undo
  Input: {:entity-ids [e1 e2 ...]
          :check-children? true}
  Output: {:valid? true/false
           :errors [...]}
```

**Test:** Measure undo latency, should be <100ms.

### Phase 2: Vector Search from Sidecar (1 week)

**Goal:** Vector search fetches block content from sidecar instead of worker DataScript.

**Files to modify:**
- `src/main/frontend/worker/embedding.cljs` - Query sidecar for stale blocks
- `sidecar/src/logseq/sidecar/server.clj` - Add batch block fetch handler

**New sidecar operation:**
```clojure
:thread-api/get-blocks-for-embedding
  Input: {:repo "..." :limit 100}
  Output: [{:db/id 123 :block/title "..." :block/refs [...]} ...]
```

### Phase 3: Remove Worker DataScript (1 week)

**Goal:** Worker no longer initializes or maintains full DataScript.

**Files to modify:**
- `src/main/frontend/worker/db_worker.cljs` - Remove DataScript init for queries
- `src/main/frontend/worker/db_listener.cljs` - Remove query-related listeners
- `src/main/frontend/sidecar/routing.cljs` - Route ALL queries to sidecar

**Keep in worker:**
- mldoc parsing
- Undo stack (tx-data only)
- HNSW index + WebGPU inference
- Search index (sql.js)

### Phase 4: WebSocket Reactive Push (1 week)

**Goal:** Sidecar pushes query invalidation notifications to frontend.

**Files to modify:**
- `sidecar/src/logseq/sidecar/server.clj` - Add DataScript listener for tx
- `sidecar/src/logseq/sidecar/websocket.clj` - Broadcast affected query keys
- `src/main/frontend/db/react.cljs` - Handle push notifications

**Flow:**
1. Sidecar transacts
2. Sidecar listener computes affected query keys
3. Sidecar pushes `{:event :db-changed :affected-keys [...]}`
4. Frontend refreshes affected query result atoms

### Phase 5: Testing & Optimization (1 week)

**Goal:** Verify large graph support and optimize hot paths.

**Tests:**
- [ ] Open 100k block graph - memory stays under 200MB
- [ ] Undo/redo latency < 100ms
- [ ] Vector search indexing works with sidecar data
- [ ] Reactive queries update within 200ms of change
- [ ] E2E tests pass

**Optimizations:**
- Batch sidecar queries where possible
- Cache hot entities in sidecar (LRU)
- Prefetch related entities for undo validation

---

## Success Criteria

| Metric | Target |
|--------|--------|
| Worker RAM (100k blocks) | < 100MB |
| Sidecar RAM (100k blocks) | < 100MB |
| Total RAM (100k blocks) | < 200MB |
| Undo latency | < 100ms |
| Query latency | < 50ms (p95) |
| Reactive update latency | < 200ms |
| Graph open time (cold) | < 3s |
| Graph open time (warm) | < 500ms |

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Undo latency too high | UX degradation | Prefetch validation data, cache recent entities |
| WebSocket disconnects | Stale UI | Reconnect logic, full refresh on reconnect |
| Vector search perf | Slow indexing | Batch fetches, parallel processing |
| mldoc AST transfer overhead | Slow file sync | Already small (~10KB), can compress if needed |
| SQLite query perf | Slow lazy loading | Add indexes, tune IStorage cache size |

---

## Future Considerations

### Optional: Clojure Parser

If AST transfer becomes a bottleneck, consider:
- Minimal Clojure parser for common cases (1-2 weeks)
- Fall back to mldoc for edge cases
- Eliminates IPC for parsing

### Optional: Search Index in Sidecar

If sql.js becomes a bottleneck:
- Move FTS to sidecar SQLite
- Single SQLite for all data
- More complex but unified storage

### Optional: HNSW in Sidecar

If HNSW memory is too high:
- Java HNSW library (hnswlib-java)
- GPU inference stays in browser
- Embeddings sent to sidecar for indexing

---

## References

- [IStorage Implementation](../architecture/istorage.md)
- [Sidecar Server](../../sidecar/src/logseq/sidecar/server.clj)
- [Current Undo/Redo](../../src/main/frontend/undo_redo.cljs)
- [Vector Search](../../src/main/frontend/worker/embedding.cljs)
- [Reactive Queries](../../src/main/frontend/db/react.cljs)
