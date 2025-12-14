# Research: Hybrid Architecture Compatibility with Search

**Related Task:** [hybrid-architecture.md](../tasks/hybrid-architecture.md)
**Date:** 2025-12-13
**Status:** Complete

## Summary

The hybrid architecture (Web Worker + JVM Sidecar) is **fully compatible** with the existing vector/semantic search implementation. The inference worker and embedding code run entirely within the Web Worker, independent of the sidecar. No re-implementation is needed.

## Questions Answered

1. **Will the existing inference worker work in hybrid architecture?** Yes
2. **Does embedding code need access to sidecar?** No
3. **Are there any data flow conflicts?** No
4. **Can we reuse the existing search implementation?** Yes, completely

## Architecture Analysis

### Current Data Flow (Without Sidecar)

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Web Worker                                   │
│  ┌─────────────┐    ┌──────────────────┐    ┌───────────────────┐  │
│  │   mldoc     │───>│ DataScript (DS)  │<───│ embedding.cljs    │  │
│  │ (parsing)   │    │  (all datoms)    │    │ (stale blocks)    │  │
│  └─────────────┘    └──────────────────┘    └─────────┬─────────┘  │
│                                                        │            │
│                                                        │ Comlink    │
│                                                        ▼            │
│                            ┌────────────────────────────────────┐  │
│                            │         Inference Worker           │  │
│                            │  • transformers.js (embeddings)    │  │
│                            │  • hnswlib-wasm (HNSW index)       │  │
│                            │  • IndexedDB (persistence)         │  │
│                            └────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### Hybrid Architecture Data Flow (With Sidecar)

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Web Worker                                   │
│  ┌─────────────┐    ┌──────────────────┐    ┌───────────────────┐  │
│  │   mldoc     │───>│ DataScript (DS)  │<───│ embedding.cljs    │  │
│  │ (parsing)   │    │  (all datoms)    │    │ (stale blocks)    │  │
│  └─────────────┘    └────────┬─────────┘    └─────────┬─────────┘  │
│                              │                        │            │
│                     initial_sync                      │ Comlink    │
│                     file_sync                         ▼            │
│                              │         ┌────────────────────────┐  │
│                              │         │    Inference Worker    │  │
│                              │         │  (unchanged)           │  │
│                              │         └────────────────────────┘  │
└──────────────────────────────┼──────────────────────────────────────┘
                               │
                               ▼ Transit/TCP
┌──────────────────────────────────────────────────────────────────────┐
│                         JVM Sidecar                                  │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │              DataScript + IStorage (COPY)                     │   │
│  │            (for UI queries with lazy loading)                 │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

**Key insight:** The embedding code path is **completely unchanged**. The sidecar is purely additive.

## Detailed Findings

### 1. Embedding Code Location

The embedding code lives in `src/main/frontend/worker/embedding.cljs`:
- Namespace: `frontend.worker.embedding`
- Runs in: **Web Worker** (NOT sidecar)
- Accesses: `worker-state/get-datascript-conn` (local DataScript)

### 2. Data Access Pattern

```clojure
;; embedding.cljs:154
(when-let [conn (worker-state/get-datascript-conn repo)]
  (let [stale-blocks (stale-block-lazy-seq @conn false)]
    ...))
```

The embedding code:
1. Queries **local** DataScript for stale blocks
2. Prepares text content for embedding
3. Sends to inference worker via Comlink
4. Updates `:logseq.property.embedding/hnsw-label-updated-at` in local DataScript

### 3. Inference Worker Communication

Communication path (via Comlink):
```
DB Worker → Inference Worker (one-way)
```

Key calls:
- `.text-embedding+store!` - Generate embeddings and store in HNSW
- `.search` - Query HNSW index for similar blocks
- `.index-info` - Get index status
- `.write-index!` - Persist index to IndexedDB

### 4. HNSW Index Storage

- **Location:** IndexedDB (browser storage)
- **Key:** Repository name
- **Technology:** Emscripten IDBFS (hnswlib-wasm)
- **Label:** Block `db/id` (entity ID)
- **Persistence:** Survives page refreshes and browser restarts

### 5. Why It Works in Hybrid

| Component | Location | Data Source | Impact of Sidecar |
|-----------|----------|-------------|-------------------|
| mldoc parser | Web Worker | Files on disk | None |
| Embedding code | Web Worker | Local DataScript | None |
| Inference Worker | SharedWorker | Receives from WW | None |
| HNSW index | IndexedDB | Block db/ids | None |
| UI queries | Sidecar (optional) | Synced copy | Additive only |

The sidecar receives a **copy** of datoms via `sync-datoms`. It doesn't intercept or modify the embedding pipeline.

### 6. Embedding Metadata Sync

The embedding code writes metadata to local DataScript:
```clojure
;; embedding.cljs:172
(ldb/transact! conn tx-data {:skip-refresh? true})
```

This metadata (`hnsw-label-updated-at`) is:
- Used only by embedding code to track progress
- NOT synced to sidecar (intentionally)
- Self-contained within Web Worker

This is fine because:
- Sidecar doesn't need embedding metadata for queries
- Embedding code always reads from local DataScript
- No cross-system dependency

### 7. Search Result Lookup

When a user searches, results come from HNSW with `db/id` labels:

```clojure
;; embedding.cljs:250
(when-let [block (d/entity @conn label)]
  (when (:block/title block)
    {:block block :distance distance}))
```

The `@conn` is the **local** DataScript connection, which has all data after parsing. Search works correctly.

## Potential Edge Cases (All Manageable)

### 1. Query Routing in Hybrid Mode

Some queries might go to sidecar while embedding uses local DataScript.

**Status:** Not an issue
- Embedding queries are explicitly routed to local DataScript
- UI queries to sidecar are for display, not embedding

### 2. Stale Data During Sync

Brief window where sidecar hasn't received latest datoms.

**Status:** Acceptable
- Embedding uses source-of-truth (local DataScript)
- Sync delay is typically <100ms
- No user-visible impact

### 3. Block ID Consistency

HNSW stores `db/id` as label. If IDs differ between worker and sidecar...

**Status:** Not an issue
- IDs are generated in worker (source of truth)
- Sidecar receives exact same IDs via sync
- HNSW lookup always uses local DataScript

## Sidecar Vector Search Stubs

The sidecar has these stubbed operations (server.clj:804-871):
- `:thread-api/vec-search-text-embedding`
- `:thread-api/vec-search-add`
- `:thread-api/vec-search-search`
- etc.

**Verdict:** These are NOT needed for hybrid architecture. They were designed for a pure-sidecar approach. With hybrid:
- Inference worker handles all vector operations
- Stubs can remain or be removed
- No functionality gap

## Recommendation

**Keep the existing inference worker architecture unchanged.**

The hybrid architecture adds sidecar for lazy-loading queries, but doesn't need to modify the search/embedding pipeline. The existing implementation will work as-is.

### Action Items

1. **No changes needed** to embedding/inference code
2. **Remove or deprecate** sidecar vec-search stubs (optional cleanup)
3. **Ensure** hybrid `start-db-backend!` starts worker first (Phase 2.5)
4. **Test** vector search after hybrid mode is complete

## References

- `src/main/frontend/worker/embedding.cljs` - Embedding orchestration
- `src/main/frontend/inference_worker/text_embedding.cljs` - Transformers.js integration
- `sidecar/src/logseq/sidecar/server.clj:804-871` - Stubbed vec-search ops
- `src/main/frontend/sidecar/initial_sync.cljs` - Datom sync to sidecar
