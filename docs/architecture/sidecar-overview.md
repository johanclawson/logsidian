# Sidecar Architecture Overview

This document describes the JVM sidecar architecture that enables lazy loading for large Logseq graphs.

## System Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                     Electron (Renderer)                      │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                 ClojureScript UI                     │    │
│  │              (queries go to sidecar)                 │    │
│  └───────────────────────┬─────────────────────────────┘    │
│                          │ Transit over IPC                  │
└──────────────────────────┼──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                   Electron Main Process                      │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              Node.js + mldoc                         │    │
│  │         (parses files, syncs to sidecar)             │    │
│  └───────────────────────┬─────────────────────────────┘    │
│                          │ Transit over TCP                  │
└──────────────────────────┼──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│                   JVM Sidecar                                │
│  ┌─────────────────────────────────────────────────────┐    │
│  │           DataScript + IStorage (lazy loading)       │    │
│  │                 SQLite-JDBC backing                  │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

## Components

### Electron Renderer (ClojureScript)
- **Location**: `src/main/frontend/`
- **Role**: UI rendering, user interaction
- **Queries**: Sends queries to sidecar via IPC

### Electron Main Process (Node.js)
- **Location**: `src/electron/electron/`
- **Role**: File system access, window management
- **Parsing**: Uses mldoc (OCaml→JS) to parse markdown
- **Bridge**: Relays IPC calls to sidecar TCP

### JVM Sidecar (Clojure)
- **Location**: `sidecar/`
- **Role**: DataScript queries, lazy loading, storage
- **Protocol**: Transit over TCP (port 47632)

## Key Concepts

### Why Can't Sidecar Parse Files?

Logseq's markdown parser (mldoc) is written in OCaml and compiled to JavaScript. It cannot run on the JVM. Therefore:

1. **Web Worker** parses files with mldoc
2. **Main Process** receives parsed datoms
3. **Sidecar** receives synced datoms for queries

### Lazy Loading via IStorage

```clojure
;; JVM DataScript supports IStorage protocol
(d/create-conn schema {:storage (sqlite-storage db-path)
                       :ref-type :soft})

;; :ref-type :soft enables automatic memory eviction
;; JVM GC unloads unused B-tree nodes when memory is low
;; Nodes reload transparently from SQLite on access
```

### Communication Flow

```
User Action → UI → IPC → Main Process → TCP → Sidecar
                                              ↓
                                         DataScript
                                              ↓
User sees result ← UI ← IPC ← Main Process ← TCP
```

## File Locations

| Component | Key Files |
|-----------|-----------|
| Sidecar Server | `sidecar/src/logseq/sidecar/server.clj` |
| IStorage | `sidecar/src/logseq/sidecar/storage.clj` |
| TCP Server | `sidecar/src/logseq/sidecar/pipes.clj` |
| Outliner Ops | `sidecar/src/logseq/sidecar/outliner.clj` |
| Client (CLJS) | `src/main/frontend/sidecar/client.cljs` |
| Initial Sync | `src/main/frontend/sidecar/initial_sync.cljs` |
| File Sync | `src/main/frontend/sidecar/file_sync.cljs` |

## Related Documents

- [ADR-001: JVM Sidecar](../decisions/001-jvm-sidecar.md) - Why we chose this approach
- [Master Plan](../plans/master-plan.md) - Implementation roadmap
- [Task: Hybrid Architecture](../tasks/hybrid-architecture.md) - Current implementation work
