# ADR-001: JVM Sidecar for Performance

**Status:** Accepted
**Date:** 2024-12-09
**Deciders:** Johan Clawson

## Context

Logseq's performance degrades significantly with large graphs (10k+ blocks):
- Startup times of minutes
- High memory usage (GBs)
- UI lag during operations

The root cause is that the entire graph is loaded into memory at startup, and JavaScript lacks the memory management capabilities to efficiently handle lazy loading.

## Decision

Implement a **JVM sidecar** process that handles DataScript queries and storage, using:

1. **DataScript IStorage** - JVM-only protocol enabling lazy loading with soft references
2. **SQLite-JDBC** - Persistent storage for lazy-loaded datoms
3. **Transit serialization** - Already used in Logseq worker communication
4. **Soft References** - JVM GC automatically evicts unused data under memory pressure

The sidecar runs alongside the Electron app and handles:
- DataScript queries (`q`, `pull`, `pull-many`, `datoms`)
- Transactions (`transact`)
- Graph CRUD operations
- Lazy loading via IStorage

## Alternatives Considered

### Progressive Loading (ClojureScript)
- Load pages on-demand without full graph in memory
- **Rejected**: Conflicts with full-text search requirements and backlink queries

### SQLite FTS5 (Browser)
- Use sqlite-wasm for storage and search
- **Rejected**: Still lacks soft references for automatic memory eviction

### Logseq's CLJS IStorage Fork
- Logseq has ported IStorage to ClojureScript
- **Rejected**: JavaScript has no equivalent to Java's `SoftReference` - once loaded, data stays in memory

## Consequences

### Positive
- Lazy loading via IStorage dramatically reduces startup time
- Soft references allow JVM to automatically manage memory
- Clojure compatibility means easy code sharing
- Battle-tested DataScript on JVM

### Negative
- Requires bundling JRE (~50-80MB with jlink)
- Additional process to manage
- IPC overhead for queries (mitigated by batching)

### Risks
- JVM startup time (mitigated by AppCDS ~200ms)
- Complexity of hybrid architecture (worker + sidecar)

## References

- [DataScript IStorage docs](https://github.com/tonsky/datascript/blob/master/docs/storage.md)
- [Master Plan](../plans/master-plan.md)
- [Sidecar Architecture](../architecture/sidecar-overview.md)
