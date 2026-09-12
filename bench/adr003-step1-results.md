# ADR-003 step 1 — worker stall vs graph size

Build: `perf/worker-stall-instrumentation` (453c2bb-dirty), packaged Electron 38,
headless (`--ozone-platform=headless`), isolated HOME and `--user-data-dir`,
graph copies under `~/.cache/lsbench`. VM: 4 cores, 18 GB RAM.
"Stall" = the longest a 20 ms heartbeat in the db worker fired late, i.e. how
long the worker's event loop was blocked.

## g-real (512 journals + 55 pages, 567 files) — 2026-09-11

### First open (fresh profile, "Add new graph")

| | |
|---|---|
| Parse, sum of per-file parse (start→finished) | 7.9 s (median 10 ms/file) |
| Gap between files (finished→next start) | **87.9 s** (median 141 ms, p99 430 ms) |
| Parse wall time (progress bar shown) | 97 s |
| Worker stall during parse | median 175 ms, max 525 ms per 1 s window |
| Search index rebuild after `:graph/added` | **3255 ms + 3395 ms** worker stall, 2624 restores |
| Done (quiet) | 173 s after launch |
| Peak memory, all Logseq processes | 1351 MB (renderer 774 MB) |

92 % of first-open time is per-file overhead, not parsing. Why (verified in
code; the split between the causes is not yet measured):

- The logged "parse" covers only `gp-mldoc/->edn` (`extract.cljc:278-282`).
  Everything else runs in the gap: the rest of `extract`, `build-file-tx`, and
  the transact, all synchronously in the worker (`thread-api/reset-file`,
  `db_worker.cljs:825`).
- **One SQLite write per file.** `thread-api/transact` sets `:skip-store? true`
  ("delay writes to the disk", `db_worker.cljs:600-603`). The `reset-file` path
  transacts with only `{:new-graph? :from-disk?}` (`graph_parser.cljs:187`), so
  every file stores to SQLite on its own.
- **Whole-UI re-render per file.** For `from-disk?`/`new-graph?` syncs the UI
  pipeline transacts into its DataScript copy and calls `re-render-root!` every
  time (`modules/outliner/pipeline.cljs:56-59`).
- Strictly one file in flight; the parallel loader has no callers.

Cheap confirmation: LSPERF timers around extract / transact / store, or
`:skip-store? true` at `graph_parser.cljs:187` with one flush at the end, and
no `re-render-root!` for `new-graph?`.

Rebuild path (`search/browser.cljs` rebuild-blocks-indice!): the worker builds
a Fuse index over all blocks, ships **every block to the UI thread** (strip
properties), which ships them all **back** for `search-upsert-blocks`.

Correction (review, 2026-09-12): I first called the 1568–1796 ms stall on every
fresh profile "GC + VACUUM on open". It is not GC on a file graph. Those `gc`
records belong to `logseq_db_Demo`, a **DB graph** the app builds on every
fresh profile (`db_worker.cljs` ~365-369). On the file graphs this fork is for,
the whole `gc` window on first open is 52–56 ms (GC itself 19–31 ms), and 0 on
reopen. Separate item: a file-only fork should not create or open a DB demo
graph.

### Reopen (same profile, last GC just set)

| Phase | elapsed | worker stall |
|---|---|---|
| open-sqlite | 233 ms | 75 ms |
| restore-conn | 209 ms | 189 ms |
| gc (skipped, <3 days) | 49 ms | 29 ms |
| **initial-data** | **1349 ms** | **1329 ms** (1248 restores, 778 ms restoring; 7620 datoms shipped) |
| later windows | — | ≤ 578 ms |

Peak memory 1159 MB. `get-initial-data` is the largest reopen stall.

## Named stalls, g-real reopen (build with per-thread-api timing)

Worker thread-api calls that blocked > 50 ms, in order after the db opened:

| call | blocks worker (sync) | end to end |
|---|---|---|
| `get-initial-data` | **1308 ms** | 1425 ms |
| `q` (three datalog queries) | 146, 137, 96 ms | — |
| `get-view-data` (journals view) | 102 ms | 103 ms |
| `get-block-refs-count` | 65 ms | 65 ms |
| `get-view-data` (view with filters) | 562 ms | 564 ms |
| `search-blocks` "möte" | 1 ms, then chunks (worker stall 1045 ms) | 1018 ms; hits visible after **1.6 s** |
| `search-blocks` "ab" | 0 ms (stall 338 ms) | 319 ms; hits after 0.8 s |

`search-blocks` is a missionary task: it returns at once and blocks the
worker in pieces afterwards, so its sync time says nothing.

**UI thread (renderer main thread, long tasks >= 100 ms):** the biggest is a
**5694 ms** task during app boot, 5.7–11.4 s after the renderer's time
origin, before the worker even opened the db. So it is startup, and it is **graph-independent**: a fresh profile
with only the demo graph shows the same task, 5125 ms starting at 5.5 s.
`main.js` is **35 MB**; parsing and evaluating it is the likely cause
(hypothesis — a CPU profile would confirm; outside step 1). *Refuted
2026-09-12: a 9 MB no-debug build still freezes ~5 s, see "UI boot freeze:
not the `--debug` build".* A fixed ~5 s
frozen UI on every start still counts against "never choke". After initial-data the UI thread never blocked longer than
572 ms (coincides with the 562 ms `get-view-data`).

## Named stalls, reopen: g-real vs g-10k

Worker thread-api calls, time the worker was blocked (sync):

| call | g-real | g-10k |
|---|---|---|
| `get-initial-data` | 1308 ms | **8465 ms** (93 007 datoms, 18 375 restores) |
| `get-view-data` journals list | 102 ms | **7236 ms** (and 5710 ms right after first open) |
| `get-view-data` view with filters | 562 ms | **5067 + 2421 ms** |
| `q` pull all files (`<get-files`), **on every search** | — | **6185, 5524 ms** |
| `q` pull `?h` | 137 ms | 2376 + 1283 ms |
| `apply-outliner-ops` (startup transact) | — | 696 ms |
| UI thread, biggest task after db open | 572 ms | 2043 ms |
| UI thread, boot task (graph-independent) | 5694 ms | 4832 ms |

Every one of the big ones is linear in graph size, and all are on the
everyday path: start, the journals home, search.

**Journals home.** `get-view-data` with `:journals?`
(`deps/db/src/logseq/db/common/view.cljs:438-442`) returns the ids and count of
**all** journals, unpaginated. `get-latest-journals`
(`deps/db/src/logseq/db/common/initial_data.cljs:258-268`) turns the lazy
`:block/journal-day` index slice into a `vec` (all of it), then does a
`d/entity` plus `journal?` check per journal — on a cold lazy db each one is a
restore from SQLite. The home page shows a handful of journals and pays for
all 10 000.

**Search at 10k.** `file-search` (`frontend/search.cljs:29-43`) fetches
**every file** (`db-async/<get-files`, `db/async.cljs:31-37`) and then
**drops** all `.md`/`.org` — a markdown-only graph pulls 10 000 paths to throw
all of them away, blocking the worker 5.5–6.2 s per query. Hits never appeared
within 30 s. Separately, the block index is empty (below), so there was
nothing to find.

## Baseline on the worktree build (2026-09-12, before step-2 changes)

Same code as step 1, rebuilt in `~/dev/logsidian-perf`; confirms parity.

| | step 1 | worktree baseline |
|---|---|---|
| g-real `get-initial-data` | 1308 ms | 1323 ms |
| g-real search "möte" / "ab", hits after | 1.6 / 0.8 s | 1.75 / 0.92 s |
| g-10k `get-initial-data` | 8465 ms | **9459 ms** |
| g-10k file-search `q` (all files) | 6185, 5524 ms | 4121, 5817 ms (plus 1056 ms at startup: watcher) |
| g-10k `get-view-data` journals | 7236 ms | 1042, 698 ms |
| g-10k `get-view-data` with filters | 5067 + 2421 ms | 3701 ms |

The journals-list cost varies a lot between runs (7.2 s vs ~1 s), probably
with what earlier calls had already restored. The date had rolled over, so
the app also created today's journal: `apply-outliner-ops` create-page took
7012 ms end to end at 10k.

## Step 2 changes (2026-09-12, worktree, uncommitted)

Designed and adversarially reviewed by a 12-agent workflow
(`bench/step2-designs.json`), implemented and code-reviewed by a 6-agent
workflow (`bench/step2-impl.json`):

- **journals-home:** `get-latest-journals` walks the `:block/journal-day`
  AVET index backwards lazily from today (`d/rseek-datoms`, `take-while` on the
  attribute) with an optional `:after` cursor; `get-view-data {:journals? true}`
  returns one page (default 10, cap 200) with `:more?` and `:cursor`; the
  journals list grows through Virtuoso `endReached` with an in-flight flag.
- **file-search:** new `frontend.worker.file-paths` reads `:file/path` from
  the AVET index and caches the non-md/org paths per repo, invalidated on
  file add/remove and on `reset-conn!`; `file-search` uses it instead of
  pulling every file.
- **reset-file timers (measurement):** one `LSPERF {"event":"reset-file"}`
  line per file with parse/mldoc/delete/build-tx/transact/store phases.
- **search rebuild tracing (measurement):** `api-error` records from
  `thread_api.cljc` (sync, transit-write, async) and `LSSEARCH` steps from
  `search/browser.cljs`.
- Deferred: **initial-data** (the review found that typing resolves page names
  in the UI db, so dropping pages from it can give a page a new uuid);
  **gc-on-open** rejected (the stall was the DB demo graph, see above);
  **first-open** fix waits for the reset-file timers.

Tests: `deps/db` initial-data tests 3 tests, 9 assertions, all pass (new
`get-latest-journals` test included). Frontend suite 326 tests, 2134
assertions, 0 failures, 1 error in `get-class-objects-test` — a DB-graph test
already tagged `^:fix-me` ("TODO: Async test"). **Pre-existing: clean master
fails it identically** ("DB write failed with invalid data" on the same
`:block/warning` retract); new
`frontend.worker.file-paths-test` 4 tests, 27 assertions, pass.

### After step 2: reopen + search (2026-09-12, `~/.cache/lsbench/after/`)

| worker blocked (sync) | g-10k before (worktree baseline; step 1) | g-10k after | g-real after |
|---|---|---|---|
| journals list, `get-view-data :journals?` | 1042 + 698 ms (7236 ms) | **< 50 ms (below the log threshold)** | < 50 ms (was 102 ms) |
| file search per query | 4121, 5817 ms (`q` pull all files) | **527 ms on the first query** (`get-file-paths`, cold AVET walk), then **0** (cached) | — |
| worker max in the search windows | 4385 / 6186 ms | 882 / 0 ms | 1003 / 301 ms |
| `get-initial-data` (not changed) | 9459 ms | 8689 ms | 1437 ms |
| `get-view-data :linked-references` (not changed) | 3701 ms | **6042 ms** | 567 ms |
| `q` pull `?h` (not changed) | 4294 ms | 2206 ms | 147 ms |

Both fixes hold at 10k. What still chokes on the startup path at 10k:
`get-initial-data` (8.7 s), linked references of today's journal (6.0 s) and
the `?h` query (2.2 s); the UI thread still has one 1834 ms task after the db
opens. Search at 10k still shows no block hits because the block index is
empty (next section).

### First open: where each file's time goes (reset-file timers, g-10k, first 1750 files)

| per file, in the worker (`thread-api/reset-file`) | median | p90 | share of worker time |
|---|---|---|---|
| **SQLite store** (1 store call per file) | **48 ms** | 84 ms | **49 %** |
| transact beyond the store (DataScript + listeners, incl. transit to the UI) | ~19 ms | — | ~24 % |
| extract (graph-parser, after mldoc) | 13 ms | 32 ms | 17 % |
| mldoc parse | 4 ms | 19 ms | 8 % |
| delete-blocks + build-tx | ~1 ms | — | 1 % |
| **total in the worker** | **87 ms** | 187 ms | |
| outside the worker (UI thread + transport) | 30 ms | 78 ms | |

Half of the worker's per-file time is the SQLite write, one per file, and it
grows with the db (median store 46 → 52 ms from file 0 to 1750). The parse
itself is about a tenth. Next first-open change: store in batches (flush on a
budget, and once at the end), plus several files in flight to remove the
~30 ms ping-pong. Note from the review: `:skip-store?` has no effect for file
graphs (the DataScript fork strips it), and file graphs index the full block
text in AVET `:block/title`, which makes each flush touch many leaves.

### Journals paging: scroll test (`followup/scroll-real`, `followup/scroll-10k`)

Six scrolls to the bottom of the journals home, visible journal dates after
each step:

- g-real: 2026-09-01 → 2026-05-21 → 2026-04-10 → 2026-03-24 → 2026-03-07 →
  2026-02-12. Paging loads well past the first page of 10; worker stall per
  step ≤ 128 ms.
- g-10k: 2026-09-04 → 2026-08-25 → (render gap) → 2026-08-15 → 2026-08-05 →
  2026-07-26. Paging works; one step stalled the worker 3768 ms, and that was
  **not the journals list** but `get-view-data :linked-references` (3618 ms
  sync) for a journal page that scrolled into view.

So the linked references of a single journal page cost seconds at 10k (5.9 s
for today's journal at startup, 3.6 s for one scrolled-in journal), scaling
with the graph rather than with the page's references. That is ADR-003's
planned next step (bounded direct refs).

The other recurring startup query, `q pull ?h [*] :in $ ?start ?today`
(1.6–3.1 s at 10k, ~0.15 s at 512), is a journals default query of the
"🔨 NOW" shape. Correction from the NOW design (`bench/now-query-design.json`):

- It is **not the built-in**. The graph copies — and Johan's real
  `config.edn:142-172`, byte-identical — override `:default-queries
  :journals` with the old 0.10.x template, which `merge-configs`
  (`state.cljs:443-453`) uses instead of the built-ins.
- That template filters on `[?p :block/journal? true]`, an attribute nothing
  writes any more (journal pages get `:block/type "journal"`), so **NOW and
  NEXT always return nothing and are hidden** — yet each run still costs
  1.6–3.1 s cold at 10k.
- Why it scales: the DataScript fork runs clauses strictly in order and
  substitutes a bound variable only when its relation has exactly one tuple,
  so `[?h :block/page ?p]` with ~77 task hits reads all ~58 000 `:block/page`
  datoms. Reordering does not help; `:db/index` on `:block/marker` does not
  help.
- Cost model (review): ~2 s when the db is cold (startup, and after every
  `re-render-root!`, which clears the query cache — so after every
  from-disk transaction: external edit, sync, reconcile); ~60 ms warm.
- Planned fix: a worker fast path in `thread-api/q` that recognises this
  query shape and answers it with index walks (journal-day range → the pages'
  blocks → marker check → pull), eager, compared on total time and restores.
  The `:block/journal?` → `:block/type "journal"` compatibility rewrite would
  make Johan's NOW/NEXT show tasks again — a visible behaviour change, kept
  separate and left to Johan (or fix the override in his `config.edn`).
- Implemented (`bench/now-impl.json`, 2 agents, review ok with 5 minors):
  `logseq.db.file-based.journal-window` matches the shape structurally
  (any clause order d/q accepts; page-constant clauses such as
  `[?p :block/journal? true]` are checked per page, so the legacy template
  stays empty exactly like d/q) and walks `:block/journal-day` range →
  pages' blocks → marker → `pull-many`, fully realized. `thread-api/q` tries
  it first for file graphs and falls back to the unchanged d/q on no match or
  error; one `LSPERF q-fastpath` line per hit with the restore delta.
  Tests: `deps/db` 12 tests, 2079 assertions, pass (differential against d/q
  over all 120 clause orders, ~45 near-miss shapes, a boundedness check with
  1 500 out-of-window pages, the fallback case). Open point from the review:
  the override's NEXT may have a shape the matcher does not accept; the
  `q-fastpath` lines will show which queries hit.
- **Measured** (`~/.cache/lsbench/now/`): both queries hit the fast path on
  every run. At 10k the NOW/NEXT pair went from **2.0–2.6 s** of blocked
  worker (cold) to **6–18 ms** (0.8–8 ms warm), 0 restores; at g-real from
  0.15–0.43 s to ~16 ms. No NOW/NEXT-shape call remains above the log
  threshold. The walk finds 0 pages because the legacy template's
  `[?p :block/journal? true]` fails on every page — the same empty result as
  d/q; behaviour unchanged, cost gone.

### Search index: every changed block scans the whole FTS table (2026-09-12)

Found by Codex (`gpt-6-astra`), verified against the code and measured on the
real 10k search db (69 938 rows, native SQLite 3.46 on a copy):

- The FTS triggers (`worker/search.cljs` `add-blocks-fts-triggers!`) delete
  with `DELETE from blocks_fts where id = old.id`. `id` is an ordinary FTS5
  column, not the rowid, so FTS5 cannot look it up: `SCAN blocks_fts`.
- `upsert-row!` does `ON CONFLICT (id) DO UPDATE` unconditionally, so even an
  unchanged row fires the update trigger (delete-by-id + insert).

| operation | median | plan |
|---|---|---|
| `DELETE FROM blocks_fts WHERE id = ?` (the trigger) | **68.8 ms** | `SCAN blocks_fts` |
| `DELETE ... WHERE rowid = ?` | **0.14 ms** | `INDEX 0:=` |
| unchanged upsert (fires the update trigger) | **70.3 ms** | |
| upsert with `WHERE ... IS NOT excluded...` | **0.11 ms** | |

So **every changed block costs ~70 ms in the search index at 10k** (more in
wasm on OPFS), on every edit, since the index now syncs per transaction.
Fix: give `blocks_fts` the `blocks` rowid and delete by rowid (~500×), skip
no-op upserts, migrate old indexes with one rebuild.

**Fix implemented** (`bench/fts-impl.json`, 2 agents, review ok with 3 minors):
rowid-aligned triggers, no-op upserts, schema version 2 in `search_meta` plus a
structural trigger check, migration = truncate + walk. Verified with native
SQLite on a copy of the real 10k search db after applying the new scheme:

| per row | before (scan by id) | after (by rowid) |
|---|---|---|
| changed upsert (update trigger) | 70.3 ms | **0.24 ms** |
| unchanged upsert | 70.3 ms | **0.09 ms** (no trigger) |
| block delete (delete trigger) | 68.8 ms | **0.15 ms** |

After 10 edits and 10 deletes: 0 mismatched rows between `blocks` and
`blocks_fts`, equal counts, search finds the edited rows. Rebuilding the FTS
table for 69 938 rows took 16 s natively (in the app: the sliced walk).

**Typing baseline before the fix** (`~/.cache/lsbench/typing-base2/`, harness
`LSBENCH_TYPE=40`: two 40-character bursts into an existing journal block,
editor focus checked; worker time per save, `apply-outliner-ops` sync):

| | 1st save | 2nd save (an UPDATE of the row) |
|---|---|---|
| g-real | 156 ms | 130 ms |
| g-10k | **467 ms** (cold) | 109 ms |

The FTS scan does not show clearly here (109 ms at 10k vs 130 ms at 512), so
either it is cheaper in wasm than natively or the edit path syncs differently;
the fixed build logs `slow-sync` per transaction and will tell. Separately, a
save costs 110–160 ms of worker time at any graph size, and ~0.5 s cold at
10k — its own item on the typing path (outliner pipeline, refs, file write).

**Where a save's 110–160 ms go** (code trace by an agent, 2026-09-12; not yet
timed): `apply-outliner-ops :save-block` builds the tx (fixed cost), and
`outliner-tx/transact!` makes **two DataScript txs per save** (the body plus an
empty `:batch-tx/exit?` tx) — each stores to the main SQLite db (usually a
one-row tail write, every ~32 datoms a full flush of dirty nodes). The main db
leaves `synchronous` at its default, so each commit likely pays an OPFS
`xSync`; the search db runs `NORMAL`. File graphs skip the worker pipeline
(refs are parsed on the UI thread). The listener runs the per-tx search sync
(the FTS scan above, the only graph-sized item) and transit-broadcasts the tx
to the UI. The page's markdown is re-serialized on every save, but in a
separate task ~1 s later (its cost grows with page size). Next: one LSPERF
`apply-ops` line splitting store / search / restores / rest.

The cold first save at 10k (467 ms) is **not** restores: the activity window
around it shows 55 restores costing 23 ms; the second save (109 ms) had none.
The likely rest is the FTS scan reading the search db cold (78 MB + 79 MB
WAL, first statement preparation) — to be confirmed after the fix removes the
scan.

Also: the 10k search db has a **79 MB WAL next to a 78 MB main file** —
checkpoints are not keeping it in check, consistent with Codex's leading
theory for the commit spikes (WAL auto-checkpoint in the committing
transaction; SAH-pool `xSync` flushes on every checkpoint).

### After the FTS fix (`~/.cache/lsbench/fts/`, build c176b50, 2026-09-12)

**Migration works.** On reopen, an index without a schema row and with the old
triggers opens with reason `"schema"`, then truncates and walks. The next
reopen trusts the result (schema 2, triggers current), and search hits.

| | rows | walk | slices | max / p99 slice | slices > 100 ms |
|---|---|---|---|---|---|
| g-real | 4 673 | 13.7 s | 369 | 390 / 234 ms | 11 |
| g-10k | 69 938 | 254 s | 5 836 | 757 / 269 ms | 266 |

The walk's commit spikes are unchanged: median slow slice 164 ms, of which
index is 7 ms and commit 156 ms, for 14 rows. A rebuild only inserts, so the
rowid fix cannot help it; this is step 4's target.

**Typing.** The worker time of a `save-block`, from the new `apply-ops` LSPERF
line; restores are given as ms (count). Each save is followed by two small
`transact` calls of 16–35 ms each, mostly store (6–17 ms).

| run | save | total | store (2 commits) | search sync (1 row) | restores | rest |
|---|---|---|---|---|---|---|
| g-real (after migration) | 1st | 156 | 6 | 17 | 30 (65) | 103 |
| | 2nd | 87 | 32 | 8 | 0 | 47 |
| g-10k (after migration) | 1st | 118 | 11 | 24 | 6 (13) | 77 |
| | 2nd | 100 | 43 | 11 | 0 | 46 |
| g-10k (trusted) | 1st | 185 | 8 | 48 | 36 (69) | 93 |
| | 2nd | 60 | 10 | 24 | 0 | 26 |

- **The 467 ms cold first save at 10k did not come back.** It now measures
  118–185 ms, and restores are at most 36 ms of it.
- **The FTS scan is gone, but a one-row search sync still takes 8–48 ms.**
  Its `slow-sync` lines put 11–41 ms of that in SQLite. A commit on the
  NORMAL search db does not fsync, which points at FTS5 automerge work inside
  the commit. Step 4 moves automerge out (`automerge=0` plus merge steps in a
  tick); the measurement will show whether that was the cause.
- **Store is 6–43 ms for the two commits** on the main db, which runs FULL
  (step 4 switches it to NORMAL).
- **The largest share is the rest, 26–103 ms**: the outliner and DataScript
  pipeline. It is outside step 4.

### Restored nodes do not survive GCs (`~/.cache/lsbench/gctest/10k`, 2026-09-12)

The harness ran `LSBENCH_GCTEST=1` on g-10k with a reopen and the no-debug
build, with the worker started with `--expose-gc`. It ran the same search
("möte") four times, with a forced `gc()` in the db worker before the third.

| run | restores | restore ms | search-blocks sync/total |
|---|---|---|---|
| cold | 1 438 | 450 | 96 / 689 ms (plus get-file-paths 334 ms, once) |
| warm (same query ~1 s later) | 653 | 169 | 54 / 485 ms |
| after `gc()` | 729 | 203 | 52 / 479 ms |
| warm again | 0 | 0 | 7 / 350 ms |

- **Ordinary GCs already evict about half the restored nodes.** They run
  during the search itself: the warm run repeated the same query and still
  restored 653 nodes.
- **A forced full GC evicts all of them.** Only back-to-back runs with no GC
  in between hit fully.
- This confirms Codex's reading of persistent-sorted-set 0.1.2: restored
  nodes are held only through `js/WeakRef`, there is no cache, and the fork's
  `accessed` hook is a no-op.

Restores are about a third of a warm search at 10k (169–203 of ~480 ms). They
also add up to 36 ms (69 restores) to the first save after an idle period.
**Decision: add a bounded, strongly held LRU of restored nodes.** It must be
invalidated on store, because stores reuse node addresses. The memory it
costs has to be measured against the 4 GB floor.

### UI boot freeze: not the `--debug` build (`bench/run-boot.sh`, 2026-09-12)

Three launches per build. Each used an empty profile with no graph open, so
only the app boot was measured. The boot task is the UI long task that
starts before 15 s.

| build | main.js | db-worker.js | longest boot task | boot tasks, summed |
|---|---|---|---|---|
| `release --debug` (today's `cljs:release-electron`) | 35 MB | 12 MB | 4.8 / 5.9 / 6.2 s | 5.9–7.3 s |
| `release` without `--debug` (`CLJS_DEBUG=0`) | 9 MB | 2 MB | 4.8 / 5.4 / 4.8 s | 6.1–6.7 s |

Dropping `--debug` shrinks main.js about 4×, but the longest boot task only
goes from 5.6 to 5.0 s (mean of three). **The ~5 s freeze is not script
size.** It is work done at boot (evaluation or initialisation), and needs a
CPU profile of that task to name it. The no-debug build is still worth
having for its size, but it is not the boot fix. That corrects the
"likely cause" noted under *Named stalls* and in the investigations list.

**What the boot task does** (V8 CPU profile, `LSBENCH_CPUPROF=1`, master's
`--debug` build, empty profile, `~/.cache/lsbench/bootprof/master`,
summarised with `bench/cpuprof-top.js`). Profiling stretches the task to
7.8 s; the proportions are what counts.

| inside the boot task | ms | share |
|---|---|---|
| main.js top level | 4 110 | 54 % |
| – shadow js requires (npm modules; tabler-icons-react ~470 self) | 820 | |
| – `frontend.handler/start!` (global shortcut commands 324) | 460 | |
| – tongue `build-dicts` (i18n dictionaries at load) | 395 | |
| – malli validators and closed schemas built at load | 510 | |
| – `cljs.cache` seeding | 177 | |
| code-editor.js module load | 1 960 | 26 % |
| – **instaparse grammar parsed at runtime** (`frontend.extensions.calc`) | 1 870 | |
| GC | 530 | 7 % |

The largest single item is the calculator block's grammar. `defparser`
precompiles a grammar at macro time only when it gets a string literal.
`(rc/inline "grammar/calc.bnf")` is a form, so instaparse falls back to
parsing the grammar at runtime, on every boot, in a module that is loaded
eagerly. Making it a literal at macro time should remove about 1.9 s
(profiled) from every start. The i18n dictionaries and the load-time malli
schemas are the next candidates (about 0.9 s). The fixes are in progress on
branch `perf/boot-lazy`.

### Search rebuild: where the slice budget goes (`now/reindex-10k`)

`slow-slice` split of the 258 slices over 100 ms: median 167 ms = **index 7 ms
+ commit 158 ms** for a median of **14 rows**; worst 732 ms (commit 725 ms).
The entity loop respects the deadline; the SQLite commit does not, and 158 ms
for 14 rows points at periodic spikes (WAL checkpoint or FTS5 segment merges),
not at row volume. Next: take checkpoints/merges off the slice path (e.g.
`wal_autocheckpoint`/FTS5 `automerge` tuning with an explicit idle
checkpoint) and count the commit inside the budget.

### First open at 10k after step 2: complete (all 10 057 files, `after/open-10k`)

| sum over 10 057 files | time | share of parse wall time (1412 s) |
|---|---|---|
| in the worker (`reset-file`) | 1057 s | 75 % |
|   of which transact | 816 s | 58 % |
|   of which SQLite store | **533 s** | **38 %** |
|   extract | 161 s | 11 % |
|   mldoc | 71 s | 5 % |
| UI thread + transport (remainder) | ~355 s | 25 % |

- Per file: median total 91 ms (p90 201, max 1363); store median 51 ms.
- **Longest worker stall during the whole first open: 1371 ms** (one file's
  reset-file). Before step 2 it was **5756 ms**, the journals list right
  after parsing — gone.
- The search rebuild started at 1447 s; this run still used the old harness
  (silence = done), so it closed the app mid-build: `blocks_fts` has 1 row.
- Reopen of this fresh profile (`after/reopen-10k-after`) repeats the
  remaining startup cost: `get-initial-data` 8.7 s, linked references 5.5 +
  3.4 s, the `?h` query 2.0 + 1.6 s; first file search 0.35 s.

### First open: the two threads take turns (thread CPU, 2026-09-12)

Sampled from `/proc/<renderer>/task/*/stat` during the g-10k first open: the
db worker thread (`DedicatedWorker`) runs at ~60 % CPU and the UI thread
(`Logseq`) at 45–60 %. Neither is saturated: one file is in flight at a time,
and each thread waits for the other about half the time. Removing per-file
work on either side helps, but the bigger lever is not waiting — keep several
files in flight, or parse and transact in batches.

## Investigations of what still blocks startup (2026-09-12, `bench/investigations.json`)

Three read-only investigations, each checked by a critic (all "needs-changes",
citations held; the critics flagged that several comparisons mixed builds and
profiles).

- **get-initial-data (8.5 s at 10k).** Decoded offline from the 10k storage:
  93 021 datoms = pages 62 842 (10 542 pages, incl. 488 ref-only) + files
  30 179 (`:file/content` only 6.9 MB). Pages are ~95 % of the ~21 MB of EAVT
  leaves restored; dropping file content from the reply saves only ~1–2 s of
  a ~10 s continuous worker block. The reopen reconcile (`load-graph-files!`)
  fires **10 057 `pull`s at once, uncapped**, plus 20 000 IPC calls, on every
  reopen. And persistent-sorted-set holds restored nodes through `js/WeakRef`
  (`:ref-type :strong` is never read; DataScript's `accessed` hook is a TODO),
  so a GC can throw away everything get-initial-data restored — which fits the
  0 → 13 800 restore variance of the reconcile across runs.
- **Search-db commit spikes.** WAL auto-checkpoint inside the committing
  transaction (copy + 2–3 `sah.flush`) sets the floor; FTS5 merges set the
  tail, amplified because every upsert statement flushes its own level-0
  segment (one segment per row). Proposal: one statement per commit
  (`json_each`; 3.2× fewer leaves, −31 % time in a replay), FTS5 merges and
  checkpoints moved to idle time.
- **UI boot freeze (~5 s, graph-independent).** The app is built with
  `release ... --debug` (`package.json:92`): main.js 36 MB instead of ~11 MB,
  compiled as a classic script without a code cache. Proposal: drop `--debug`
  (keep source maps), then stop eager `<script defer>` loading of code-editor,
  excalidraw and tldraw. *Measured 2026-09-12: without `--debug` main.js is
  9 MB but the longest boot task is still 4.8–5.4 s (see "UI boot freeze: not
  the `--debug` build"). The next step is a CPU profile of that task, not a
  smaller bundle; the deferred-scripts idea is still open.*

### Step 4 designs (2026-09-12, `bench/step4-designs.json`, both "needs-changes")

- **Search-db write path.** New facts: FTS5 flushes its pending data at every
  statement savepoint and whenever a written rowid goes backwards, so the
  per-row upserts create one segment per row (writeCounter 72 928 for
  69 938 rows); the wasm build defaults to `DEFAULT_WAL_SYNCHRONOUS=2`, so
  the **main db runs synchronous FULL: at least two OPFS flushes per save**
  (body tx + empty batch-exit tx). Design: one statement per commit
  (`json_each`), checkpoints and FTS5 merges out of the commit into a worker
  maintenance tick, main db `synchronous=NORMAL`. The critique: a PASSIVE
  checkpoint copies every frame and cannot be bounded, so moving it only
  moves the stall — keep each one small by checkpointing often; the step-0
  merge classifier is wrong (per-row FTS writes already look like merges).
- **Reopen reconcile.** Step 1: at most 16 files in flight, no transact of
  the pulled file map into the UI db. Steps 2-3 (compare in the worker,
  mtime+size) missed content writers (`native_fs.cljs:236`, case-only
  renames). The critique doubts step 1 bounds the stall: the lag appears
  when the pulls are cold, which points back at the weak node references.

Chosen for step 4: main db `synchronous=NORMAL`; search writes as one
statement per commit, `wal_autocheckpoint=0` with small frequent checkpoints
and bounded FTS5 merge steps in a maintenance tick; reconcile step 1. A node
or row cache waits for the GC experiment.

### Restored index nodes are only weakly held (Codex, verified 2026-09-12)

Codex (`gpt-6-astra`) read persistent-sorted-set 0.1.2 and the DataScript
fork; the load-bearing citations were checked by hand:

- `make-reference` always wraps in `js/WeakRef` (PSS:330-332);
  `node-child` installs restored children through it (PSS:429-445); the
  restored root is weak too (PSS:624-629); `set-address!` weakens stored
  inner nodes (PSS:340-345).
- `:ref-type :strong` appears only in the reported settings (PSS:1350) and is
  never read; `make-storage-adapter` ignores its opts (fork storage.cljs:75).
- The fork's `restore` rebuilds nodes from decoded data with no cache
  (storage.cljs:55-64) and `accessed` is `;; TODO: nil` (65-67); PSS calls
  `accessed` with an address only, and not on restores or root hits.
- Dirty (unflushed) nodes are held strongly, so a GC cannot lose unsaved
  changes.

So after any GC, point reads, index walks and the next transaction's path
copy can pay restores again. Not yet proven to be what causes the repeated
restores: that needs a controlled run (same search cold → warm → `gc()` in the
worker across a task boundary → again; expected warm ≈ 0, after-gc ≈ cold).
Harness: `LSBENCH_GCTEST=1` (starts the app with `--js-flags=--expose-gc`).

Cache options (address reuse on store makes naïve address memoization wrong):
a bounded LRU of restored clean nodes in the fork's `StorageAdapter`, cleared
around every store batch; or, smaller and in our own code, an LRU of *decoded
storage rows* in the worker's `new-sqlite-storage`, invalidated for every
upserted address — it saves the SQL read and transit decode (the dominant
restore cost) but still rebuilds nodes.

## Step 3 plan (2026-09-12)

Designed and reviewed by an 8-agent workflow (`bench/step3-designs.json`):

- **Implementing now:**
  - **linked references, step A.** `get-linked-references` uses rules that
    DataScript runs as `(parent ?p ?b)` with both variables unbound — a scan
    of every `:block/parent` datom, joined per nesting level, before the refs
    filter. Replace with an index walk over the page's aliases, refs and their
    subtrees/ancestors, same results (differential test against the old
    Datalog), with a cycle guard (also in `get-block-parents-until-top-ref`).
  - **search rebuild, bounded.** Index first-open content per transaction in
    the worker (drop the `:from-disk?` exclusion) and make the manual rebuild a
    worker-owned, resumable, batched walk that upserts straight into
    `blocks_fts` — no UI round trip, no minute-long stalls. Review fixes:
    integer cursor, `:reset-conn!` handling, dirty state on failure, a heal
    that removes stale rows.
- **Deferred:**
  - **first-open batching** — the review requires backpressure (64-file
    chunks can outrun the UI's event channel, buffer 1000) and UI-side
    per-file timers first; the gain is bounded by max(worker, UI) per file.
  - **bounded initial data** — phase 2 (worker-side ref resolution) can delete
    ref-only pages as written, and phase 1 only moves the content restores to
    right after boot (`load-graph-files!` reads every file on every reopen).
  - **linked references step B** (generation/cursor paging) and the
    **"🔨 NOW" query** (separate design in progress).

Measurement script: `bench/run-step3.sh`.

Implemented and reviewed by a 5-agent workflow (`bench/step3-impl.json`;
search-rebuild review: 1 major — one fsync'd search-db commit per DataScript
tx, fixed with `PRAGMA synchronous=NORMAL` on the search db — and 7 minors,
all fixed). Tests: `deps/db` 5 tests, 130 assertions, pass (new differential
`reference-test` against the old Datalog, incl. parent cycles); frontend
suite 341 tests, 2228 assertions, 0 failures, the same single pre-existing
`^:fix-me` error; new `search-indexer-test` 15 tests, 94 assertions, pass.
Left open: the alias lookup (`get-block-alias`) is still rule-based Datalog;
per-tx search-sync cost is not measured yet.

### After step 3: reopen + search (`~/.cache/lsbench/step3/`)

| worker blocked | g-10k before step 3 | g-10k after step 3 | g-real before → after |
|---|---|---|---|
| **linked references** (`get-view-data :linked-references`) | **5543 + 3443 ms** | **below the log threshold (< 50 ms sync)** | 567 ms → < 100 ms |
| search "möte", time to first hit | never (empty index) | **1.6 s** | 1.6 → 1.1 s |
| `get-initial-data` (not changed) | 8658 ms | 8460 ms | 1437 → 1506 ms |
| NOW-shape query `q ?h` (fast path in progress) | 2020 + 1598 ms | 2641 ms | 147 → 434 ms |

### After step 3: manual search rebuild at 10k (`step3/reindex-10k`)

| | old rebuild (step 2) | worker-owned walk (step 3) |
|---|---|---|
| longest single worker stall | **57 028 ms** | **978 ms** |
| total time | 121 s | 228 s |
| how the work is split | two ~1-minute calls | 5 696 slices; max 709 ms, p99 276 ms |
| worker answers other calls meanwhile | no | yes |

Same result, 69 938 rows. **The minute-long stalls are gone.** The walk takes
twice as long in total but never blocks the worker for more than about a
second — the trade ADR-003 asks for. The slice budget is not met yet, though:
it targets 8–25 ms per slice, but p99 is 276 ms and the worst 709 ms, most
likely restores from SQLite and the per-slice commit, which the budget does
not bound (minimum batch 8 blocks). Next: count restores and the commit
inside the budget, and allow smaller batches.

### After step 3: first-open cost of per-transaction indexing (`step3/open-10k`, first 737 files)

| per file, median | step 2 | step 3 |
|---|---|---|
| total in the worker | 83–91 ms | **110 ms** |
| transact beyond the store | ≈ 19–22 ms | **≈ 41 ms** |
| store | 46–51 ms | 45 ms |

Complete run, all 10 057 files (`step3/open-10k`):

| | step 2 | step 3 |
|---|---|---|
| parse wall time | 1412 s | **1723 s** (+22 %) |
| sum of `reset-file` in the worker | 1057 s | 1287 s |
|   of which transact | 816 s | 1051 s (+235 s: per-tx index sync) |
|   of which store | 533 s | 510 s |
| longest worker stall | 1371 ms | 1636 ms (the NOW-shape query after parsing, 1532 ms) |
| search index when first open ends | 1 row | **69 937 rows, complete** |

Reopening that profile (`step3/reopen-10k-s3`): search works at once ("möte"
hits after 1.5 s, "ab" 0.9 s); what still blocks is `get-initial-data`
(8.5 s) and the NOW-shape query (2.0 s).

Syncing the search index inside every transaction adds about **20 ms per
file** on first open — roughly 3–4 minutes more at 10 000 files. In return the
index is complete when parsing ends; before, the post-parse rebuild needed two
~1-minute stalls (121 s) and never got to finish in any run. Total time is
about the same, without the stalls. Could be cut later by syncing the index
once per batch of transactions during bulk load.

On open the search indexer found a consistent index (`action "trust"`) and did
not rebuild; the index came from the follow-up rebuild and is now kept
current per transaction. What still blocks the startup path at 10k:
`get-initial-data` (8.5 s) and the NOW-shape query (2.6 s); the UI thread
still has one ~2 s task after the db opens.

## Empty search index at 10k: reindex experiment (2026-09-12)

`LSBENCH_REINDEX=1` on the g-10k profile, three runs:

1. Via the command palette: Enter picked **"Create page 'Rebuild search
   index'"**, not the command. The new page and its block were indexed
   incrementally, so `blocks_fts` went 0 → 2. Incremental indexing works; the
   rebuild did not run. (Nothing was written to the graph copy on disk.)
2. Via the chord `mod+c mod+s`: "Starting to rebuild search indices!" logged,
   the 2 rows disappeared (so the truncate ran), then **0 rows**. No "Search
   indices rebuilt successfully!" notice, no search-related thread-api call
   logged, worker stall ≤ 485 ms. The `mod+s` half also fires
   `save-db-to-disk` (`export-db`), which explains a 4.5 s UI long task.
3. Same, with `unhandledrejection`/`error` listeners in the renderer: **no
   error surfaced**, 0 rows, worker stall ≤ 346 ms.

So at 10k the rebuild starts, truncates, and then the build step never runs in
the worker, without an error reaching the UI thread: a promise that never
settles, or an error caught and swallowed inside the chain. Next: log entry,
exit and exceptions of every search thread-api call (`thread_api.cljc`) and
each step of `search/browser.cljs` `rebuild-blocks-indice!`.

4. With that tracing (after-build, `after/reindex-10k`): `truncate-start` at
   84.1 s, `build-start` at 91.7 s, then **nothing**: no `build-done`, no
   `api-error`, no LSERR, and the following search got no answer at all (not
   even `search-blocks`). Meanwhile the renderer's RSS climbed steadily, about
   4 MB/s, from 786 to 1006 MB until the harness closed the app at 115 s.

**Revised conclusion:** the worker is not idle, it is **busy in one long
synchronous build** (Fuse over all pages, then every block realized and
transit-written in a single call). The worker heartbeat can only report a
stall after it ends, so a stall in progress is silent, and the harness read
the silence as idle and closed the app after 20 s. At 512 journals the build
takes 3.3 s and finishes; at 10k it has never been allowed to finish, so the
index stays empty. My first explanation ("the harness closed the app too
early") was right after all, for a reason I had not seen: silence means busy.
The harness now samples thread CPU from `/proc` (db worker = the renderer's
`DedicatedWorker` thread, UI = `Logseq`), and the reindex step waits for
`upsert-done`/`failed` instead of silence, to measure the full rebuild.

5. **Full rebuild, allowed to finish** (`followup/reindex-10k-full`):

| step | worker blocked | end to end |
|---|---|---|
| `search-build-blocks-indice` (Fuse over pages + lazy seq of 69 938 blocks, transit-written as one reply) | 10.3 s sync, then the transit write | **56.8 s** |
| `search-upsert-blocks` (all 69 938 rows shipped back, inserted into FTS in one transaction) | one continuous **57 s** stall | **56.3 s** |
| whole rebuild, chord → `upsert-done` | worker busy 149 s in total | **121 s** |

`blocks_fts` went 0 → **69 938 rows**, and search at 10k then works: "möte"
shows hits after 1.3 s. **The index was never broken; the rebuild is two
roughly one-minute worker stalls,** during which the worker answers nothing
(not even the UI's other queries). Every earlier run closed the app before it
finished.

## Search

### Measured: reopen + search, g-real (second reopen of the same profile)

| | worker stall | restores |
|---|---|---|
| initial-data | 1139 ms (first reopen: 1329 ms) | 1246 |
| First search, "möte" (pays the lazy Fuse build) | **836 ms** | 236 |
| Short query, "ab" (adds LIKE scan) | 292 ms | 142 |

Both searches returned results (99+ nodes).

Correction: I had predicted ~3.3 s for the first search. For file graphs
the Fuse index covers **pages only** (`:block/name` datoms;
`get-all-fuzzy-supported-blocks`, `worker/search.cljs:234-244` — tagged objects
only for DB graphs). The 3.3 + 3.4 s after first open is the FTS rebuild, which
ships every block worker → UI → worker.

### Code facts (`worker/search.cljs:355-387`)

- The Fuse index over pages is built lazily on the first search after start,
  inside the worker.
- Fuzzy is disabled entirely above 2500 pages ("fuzzy is too slow for large
  graphs") — a cliff, not a degradation.
- Every search counts all `:block/name` datoms to decide "large graph".
- Queries of <= 2 chars add a `title like '%..%'` scan of `blocks_fts`.

## g-10k (10 000 journals + 55 pages, 10 052 parsed files, 41 MB)

### First open (fresh profile) — 2026-09-11

| | g-real (567 files) | g-10k (10 052 files) |
|---|---|---|
| Read directory (click → first parse) | 2.7 s | **14.4 s** |
| Parse, sum (median/file) | 7.9 s (10 ms) | 91.5 s (6 ms) |
| Gap between files, sum (median) | 87.9 s (141 ms) | **1159 s = 19.3 min** (96 ms) |
| Worker stall during parse, max per 1 s window | 525 ms | 598 ms |
| Largest worker stall after parse | 3255 + 3395 ms (unattributed: build without per-call timing) | **5756 ms = `get-view-data` journals** (run 2, named; 15 808 restores, 4023 ms restoring). No search-index call > 50 ms. |
| Total, launch → quiet | 173 s | **1315 s = 21.9 min** |
| Peak memory, all Logseq processes | 1351 MB | **1593 MB** (renderer 949 MB) |

- The per-file gap is flat across the run (median 92–100 ms in every
  2000-file bucket): first open is linear in file count, ~0.1 s/file.
  Extrapolated, 100k files ≈ 2.7 h of gap alone.
- The worker stall during parse does not grow with graph size.
- Memory barely grows with graph size: lazy storage works.
- The search index build on a cold lazy db is mostly restores (4.0 of 5.8 s):
  a full scan pulls every node back from SQLite.
- The screenshot after first open shows an empty main area: a transient
  render state. The reopen screenshot shows the journals normally.
- **The g-10k search index stays empty: 0 rows in `blocks_fts`** (g-real:
  4672). I first blamed the harness for closing the app too early. **That is
  refuted:** the re-run waited for 20 s of silence on both threads after the
  5756 ms index-build stall, and the index is still empty. At 10k journals the
  app never fills its block search index, so search only finds page titles.
  Cause under investigation.

### Reopen (same profile, last GC just set) — the step-1 number

| Phase | g-real: worker stall | g-10k: worker stall |
|---|---|---|
| open-sqlite | 38–75 ms | 118 ms |
| restore-conn | 189–200 ms | 115 ms |
| gc (skipped) | 0–29 ms | 101 ms |
| **initial-data** | **1139–1329 ms** (1246 restores, 7620 datoms) | **8386 ms** (18 392 restores, 5531 ms restoring, **93 007 datoms**) |
| next windows | ≤ 637 ms | 1528 ms, 963 ms, … |

After initial-data the g-10k reopen had four more long worker stalls, 10–40 s
in: **8853, 7938, 5422 and 4304 ms** (10–16k restores each). Not yet
attributed. The journals home renders "Scheduled and Deadline", a datalog
`or` over `:block/scheduled`/`:block/deadline` via `thread-api/q`
(`db/async.cljs:200-235`). Neither attribute is indexed in the file schema
(`file_based/schema.cljs:63-66`), but DataScript resolves `[?b :attr ?v]`
through AEVT, so that query should scale with scheduled blocks rather than the
whole graph. That makes it a weak suspect. The build now logs every
thread-api call that blocks its thread > 50 ms or takes > 200 ms, with its
name and arguments (`common/thread_api.cljc` remote-function).

`get-initial-data` grows roughly linearly with graph size: 12× the datoms
gives 6.5× the stall, and two thirds of it is restoring nodes from SQLite.
Extrapolated to 100k journals: over a minute of blocked worker on every
reopen. This is the one stall on the everyday path that scales with the
graph.
