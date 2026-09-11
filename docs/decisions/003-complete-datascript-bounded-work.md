# ADR-003: Complete DataScript over SQLite, with bounded work

**Status:** Accepted
**Date:** 2026-09-11
**Deciders:** Johan Claeson
**Replaces the direction of:** [ADR-001: JVM Sidecar](./001-jvm-sidecar.md), [ADR-002: Thin Worker Post-Mortem](./002-thin-worker-postmortem.md)
**Reviews:** two adversarial reviews by Codex (gpt-6-astra, medium effort), 2026-09-11

## Goal

A Logseq for the AI-agent era that handles an unlimited number of notes, with
markdown files as the source of truth, on desktop and mobile.

Made precise for this decision: **the everyday paths never do work
proportional to the size of the graph** — startup, opening a page, typing,
backlinks, tasks and search. User-written `{{query}}` and plugin Datalog get
guardrails and honest feedback, not a guarantee (see *Known limits*).

## Context — what is actually in the tree

- The base commit `0f9eecf` is labelled "Logseq 0.10.15 base" but is an
  upstream DB-era snapshot: its `resources/package.json` says `0.11.0`, and it
  matches upstream master `53ae283594` (2025-12-01) apart from about 17 paths.
- Every repo, file graphs included, is a DataScript connection in the db
  worker over SQLite in OPFS, through DataScript `IStorage`
  (`src/main/frontend/worker/db_worker.cljs:263-283`; schema chosen by
  `ldb/get-schema`, `deps/db/src/logseq/db.cljs:628-633`).
- Lazy restore works in ClojureScript and is synchronous: the DataScript fork
  (`logseq/datascript` 3971e2d, `storage.cljs`) calls `restore-data-from-addr`,
  a synchronous sqlite-wasm `.exec` (`db_worker.cljs:158-172`), and
  persistent-sorted-set 0.1.2 restores B-tree children on demand behind
  `js/WeakRef` (`persistent_sorted_set.cljs:330-336,429-445`). ADR-002's
  premise that this is JVM-only is wrong.
- `d/datoms` and `d/index-range` are lazy slices (`datascript/db.cljc:756,774`):
  `(take 50 (d/datoms db :avet :block/refs id))` reads the boundary paths and
  the leaves it needs, not the whole range.
- On restart a file graph is restored from SQLite rather than reparsed
  (`src/main/frontend/handler/repo.cljs:74`,
  `src/main/frontend/persist_db/browser.cljs:223-231`).
- Upstream removed file graphs from logseq/logseq (PR #12276, 2025-12-29). Its
  markdown interop is one-way (Markdown Mirror); two-way sync is a draft that
  keeps the DB authoritative.
- Logsidian ships no mobile app (`build-android.yml` and
  `build-ios-release.yml` have never run), and the tree's mobile target is
  DB-graph only. The same graph is edited from Logseq on Android.

## Decision

1. **One source for every answer.** The worker's complete DataScript db over
   SQLite `IStorage`. No working-set split. Datalog, `{{query}}` and the
   plugin API keep whole-graph semantics. SQLite FTS remains a derived index;
   "one source" means one authoritative db generation, not one physical
   structure.
2. **Bound the work, not the store.** The hot global views — linked
   references, tasks and NOW/NEXT, all pages, recent pages, search result
   assembly — become bounded, paginated walks over DataScript's indexes. Each
   request returns one batch and a cursor, materialized inside the worker
   before serialization, under candidate, byte and time budgets. Global counts
   become incrementally maintained or "N+".
3. **Generation-invalidated pagination.** Every batch carries the db
   generation; a continuation against a changed generation is rejected and
   restarted. Generations are never concatenated. An old db value is not a
   durable snapshot here: storage reuses node addresses
   (`on conflict(addr) do update`, `db_worker.cljs:147`) and modified leaves
   keep their `_address`.
4. **Provenance and freshness on every answer** — which store, which
   generation, and the reconcile state ("reconciliation incomplete" when the
   pending set is not yet known). No silently partial answers.
5. **Admission control for heavy queries.** `{{query}}` runs only when
   visible and is not re-run on every transaction.
6. **Everything the app needs lives in the db worker** (sqlite-wasm), so
   desktop and mobile share it. A separate process is allowed only for agents
   (an on-disk derived index plus MCP), since agents run on desktop.
7. **Markdown stays the source of truth and stays readable by Logseq OG on
   Android.** No index metadata in user files unless OG-compatible.

## Rejected alternatives

| Alternative | Why |
|---|---|
| SQL/FTS as the primary index for global views, DataScript only for open pages (Codex, review 1) | Two stores with different completeness. Whole-graph Datalog, `{{query}}` and plugins would need Datalog-to-SQL. Codex withdrew it in review 2 |
| Rebase on the upstream DB version and return to files | Upstream removed file graphs; only one-way markdown export exists |
| Rewrite in Go | ~188k lines; mldoc is OCaml compiled to JS (the JVM/GraalJS attempt took 7.4 s p95 per 50 KB file); no DataScript/Datalog in Go; a Go desktop GUI still means a webview (Wails) |
| Move to Tauri now | Plugin incompatibility (`docs/architecture/tauri-migration-research.md`, deferred 2026-01-09). Electron's overhead is constant, not per note |
| grep instead of indexes for app views | Measured on this VM (ripgrep, backlinks query): 0.8 s at 10k notes, 5.9 s at 100k, 17 s at 300k — about 57 µs per file, linear in file count. Fine for agents at small scale, too slow for the UI |

## Known limits and open problems

- `d/q` is synchronous and cannot be interrupted (`db_worker.cljs:426-429`). A
  pathological user query can still stall the worker until cooperative
  budgeting inside the evaluator, or execution isolation, exists.
- Linked references are not "direct refs plus children": aliases,
  include/exclude filters, ancestor refs through `has-ref`, and grouping and
  sorting by page and date all apply. AVET order is entity-id order, so
  correctly ordered bounded batches need an ordering index or an explicit
  change in behaviour.
- `:block/marker` has no index in the file schema
  (`deps/db/src/logseq/db/file_based/schema.cljs:47`); walking tasks needs an
  index and a migration.
- Open-time maintenance: `gc-sqlite-dbs!` scans the whole `kvs` table and runs
  `VACUUM` when the last GC is older than three days
  (`db_worker.cljs:248-261`, `deps/db/src/logseq/db/sqlite/gc.cljs`).
- `get-block-and-children` counts all children before deciding a page is large
  (`deps/db/src/logseq/db/common/initial_data.cljs:220-222`).
- Fuzzy search builds a Fuse index over all pages
  (`src/main/frontend/worker/search.cljs:273-301`), and each text search also
  fetches all files (`src/main/frontend/handler/search.cljs:35`).
- `get-initial-data` sends every page's datoms and every file's full
  `:file/content` to the UI thread (`initial_data.cljs:58-61,357-368`).
- `load-graph-files!` string-compares every file at startup and discards the
  stored mtime (`src/main/frontend/fs/watcher_handler.cljs:141,185-197`).
- First open is a serial loop that re-renders the root after every file
  (`src/main/frontend/handler/file_based/repo.cljs:179-214`,
  `src/main/frontend/modules/outliner/pipeline.cljs:56-59`). The parallel
  loader is not wired in: `parallel_load.cljs` has no callers and the
  parse-worker is not built.
- Open in any design: whole-page serialization on save
  (`src/main/frontend/worker/file.cljs:148`), watcher bursts without
  backpressure, the HNSW embeddings index held whole, storage capacity, and a
  whole-database export (`.exportFile`, `db_worker.cljs:103`).
- The CLI/MCP cannot share the app's OPFS store (exclusive SAH-pool locking).
  Agents need their own derived store or must go through the index owner.

## Plan

1. **Measure first:** the maximum db-worker event-loop stall during an
   unchanged-graph reopen as graph size grows — a heartbeat in the worker,
   restore count/time/bytes at `restore-data-from-addr`, one record per phase —
   with and without a GC timestamp old enough to trigger maintenance.
2. **Quick wins independent of the architecture:** GC and `VACUUM` off the
   open path; limit before counting on page open; mtime+size reconcile, run in
   the background, resumable, with a visible generation; file content out of
   initial data; no per-file re-render on first open.
3. **Prototype (about 1–2 weeks):** a read-only endpoint for 50 direct
   references with a full-key cursor, generation invalidation, budgets and a
   bounded materialized response, plus a snapshot litmus test (read an old db
   value after a flush and GC).
4. **Linked references on the bounded walk**, with differential tests against
   today's `get-linked-references`.
5. **Deciding experiment:** latency of an unrelated edit during an
   adversarial, correctness-checked paginated traversal at increasing graph
   sizes, also on the Android runtime. Keep complete DataScript if latency and
   memory stay within budget with correct results; otherwise test SQL
   composite views against the same workload.

## Corrections to earlier documents

- ADR-002: the `IStorage` premise (see its 2026-09-11 update).
- CLAUDE.md: the base is not 0.10.15, and the tree does contain SQLite and
  DB-graph code (corrected in the same change as this ADR).
