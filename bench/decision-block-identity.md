# Decision memo: stable block identity across file re-parses (Fable arbiter, 2026-09-12)

Adopted as the plan. Implementation on branch fix/block-identity (built on
fix/inode-save); Codex (gpt-6-astra, high effort) reviews the design and the
code. Memo as written by the arbiter:

## 1. Current behaviour

- `frontend.worker.file.reset/reset-file!` (`src/main/frontend/worker/file/reset.cljs:99-119`) calls `logseq.graph-parser/parse-file` (`deps/graph-parser/src/logseq/graph_parser.cljs:154-202`). `parse-file-data` (:64-128) -> `extract/extract` -> `extract-pages-and-blocks` (`deps/graph-parser/src/logseq/graph_parser/extract.cljc:220-270`) -> `gp-block/extract-blocks` (`block.cljs:723-794`) -> `construct-block` (:637-640) -> `get-custom-id-or-new-id` (:544-555): a block gets the uuid of its `id::`/`custom-id::` property, else `(uuid-fn)` = `d/squuid`. Nothing consults the db.
- `parse-file` then calls `delete-blocks-fn` with the *new* uuids as `retain-uuid-blocks` (:185-188). `get-blocks-to-delete` (:34-57) walks the old page's `:block/_page` and, per `retract-blocks-tx` (:13-19), emits `retractAttribute` ops (keeping the entity, hence the `:db/id`) for blocks whose uuid is in the retain set, and `retractEntity` for all others. `build-file-tx` (:130-152) orders `delete-blocks` before the new block maps, which upsert by `:block/uuid`. So today only `id::` blocks survive; every other block is retracted and re-created with a new uuid and eid. The retract set `file-schema/retract-attributes` (`deps/db/src/logseq/db/file_based/schema.cljs:106-120`) lacks `:block/refs` (upstream 0.10.9 had it), so retained blocks accumulate stale refs.
- Downstream: worker pipeline `invoke-hooks` short-circuits on `:from-disk?` (`worker/pipeline.cljs:512-522`); the UI just transacts the same datoms and `re-render-root!`; `search/get-blocks-from-datoms-impl` (`worker/search.cljs:1036-1056`) treats a retracted `:block/uuid` datom as a delete and everything else as an upsert keyed by `(str uuid)`; `undo-redo/gen-undo-ops!` ignores the tx (no `:outliner-op`).
- The editor's pending save resolves `[:block/uuid ...]` in the UI db (`handler/editor.cljs:1482-1517`), and the id repair `set-missing-block-ids!` (`fs/watcher_handler.cljs:40-56`) saves by uuid too: both miss after a re-parse.

**Upstream 0.10.x kept uuids.** `frontend.handler.common.file/reset-file!*` (0.10.9) set `[:extract-options :resolve-uuid-fn] diff-merge-uuids-2ways` when `:fs/reset-event` was `:fs/local-file-change`. That fn used `@logseq/diff-merge` (`frontend.fs.diff-merge`, still in `src/main/frontend/fs/diff_merge.cljs:26-50`): old blocks from the db and new blocks from the AST reduced to `{body level uuid}`, diff-match-patch on a blocks->chars encoding, and `attach_uuids` keeps the base uuid for EQUAL ops. In this fork the hook survives in `extract.cljc:228-241` (`resolve-uuid-fn`, default `(constantly nil)`, and `attach-block-ids-if-match` :179-192) but reset-file! moved into the worker and never sets it; `diff_merge.cljs` needs the UI db and DOM and is only used by `fs/sync.cljs`; `reset.cljs:86-93` still documents `:fs/reset-event` that nothing reads. The logic exists and is bypassed.

## 2. Matching algorithm (pure, per file)

Inputs: `old` = pre-order walk of the page bound to `file-path` (children via `(ldb/sort-by-order (:block/_parent e))`), each `{uuid title level has-id?}`; `new` = `extract-blocks` output in file order, `has-id?` = property `:id`/`:custom-id` present. Key = `:block/title` string (raw content incl. its own property lines, excluding children). Rules:

1. **Explicit ids win.** New blocks with `has-id?` are untouchable; collect `E` = their uuids. Old blocks with `has-id?` or with uuid in E leave the pool.
2. **Fast path.** If old and new key sequences (pool members only) are equal, map 1:1.
3. **Unique anchors (patience).** Keys occurring exactly once in old and once in new are matched, regardless of position (moved blocks keep their uuid). LIS over the anchors' old positions picks the monotone subset that partitions both sequences into gaps.
4. **Gap, duplicates.** Within a gap, equal keys are matched FIFO in order.
5. **Gap, edited blocks.** Leftover old and new blocks of the gap are paired in order while both remain and `level` is equal; anything else gets its fresh uuid.
6. Never assign one old uuid twice; `fix-block-id-if-duplicated!` still runs after as a guard. If the file's page name changed (`title::`), skip matching.

Complexity: O(total title bytes) hashing, O(n+m) maps and gaps, O(k log k) LIS. A 5 000-block page is a few ms.

## 3. Code changes

- New `deps/graph-parser/src/logseq/graph_parser/block_identity.cljs`: `(reuse-block-uuids old-blocks new-blocks)` implementing 1-6; `(page-blocks-for-identity db file-path)`.
- `extract.cljc:236-241`: apply it when `(:reuse-uuids? options)`, before `fix-block-id-if-duplicated!`.
- `reset.cljs:106`: `:reuse-uuids? true` in `:extract-options` for every reset-file! (first load has no old page: a no-op).
- `graph_parser.cljs:13-19` `retract-blocks-tx`: also retract `:block/refs`, and only attributes the old entity has.

Result: unchanged and edited blocks keep uuid and `:db/id`, so editor state, selection, collapsed state and search rows survive; search sees upserts.

## 4. fix/inode-save mitigations

Keep `779fbd5` (stale `:db/id` resolved by uuid; gone block skipped/kept) as the safety net for blocks deleted externally while edited and for mis-pairs. `72df969` (re-insert at page end) becomes the rare path. The id repair saves into the retained entity. Same-page policy becomes "merged".

## 5. Test plan

Unit tests of the pure fn: unchanged file; one block edited; insert at top; block moved incl. children; swapped duplicates; duplicates plus one edited; `id::` block whose uuid also exists on an old id-less block; page renamed. `parse-file` twice on a conn: equal uuids and `:db/id`s, stale `:block/refs` gone, `retractEntity` only for deleted blocks; external append keeps all prior eids. Editor: a pending save after a from-disk tx finds the entity. Harness: idrepair-race passes with the ref resolving; scenarios 3 and 8 "merged" with no duplicate block; new 10 agent-append-while-editing and 11 ref-survives-external-edit.

## 6. Risks

Rule-5 mis-pairing in a gap with both an edit and an insert (visible only via refs to id-less blocks). Undo: reversed datoms of earlier ops now target live eids (consider dropping undo entries for a re-parsed page). Larger tx for big pages (mitigated by the has-attr filter; measure with the LSPERF reset-file line). Last-writer-wins on a block edited both in the app and externally (the watcher's bak copy remains the recovery).
