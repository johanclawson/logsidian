# High-effort Codex reviews of RC3 (gpt-6-astra, high effort, 2026-09-12 15:43-15:59)

## Review 1: the write guard (through HEAD 0a12cf8)

Reviewed through HEAD `0a12cf8`. The range now contains 39 commits; the additional HEAD change during review affected only the benchmark build script. No files were changed, and no builds, tests, or app processes were run. Evidence below is from code inspection and two small, pure-logic Node probes.

`F/` means `src/main/frontend/`; `E/` means `src/electron/electron/`.

1. **[HIGH] A queued automatic ID repair can overwrite external text while the guard reports `written`.**

   **Locations:** [F/handler/file_based/property.cljs:34](/home/johan/dev/logsidian-perf/src/main/frontend/handler/file_based/property.cljs:34), [F/worker/file/reset.cljs:117](/home/johan/dev/logsidian-perf/src/main/frontend/worker/file/reset.cljs:117), [outliner/core.cljs:625](/home/johan/dev/logsidian-perf/deps/outliner/src/logseq/outliner/core.cljs:625).

   ID repair constructs a complete `:block/title` from the renderer’s DB, then submits `save-block!`. At current HEAD, reparsing preserves UUIDs, and `save-block` merges that submitted title over the worker’s current entity.

   Concrete ordering: renderer sends a reset containing external text E; before receiving its DB update, another live watcher event builds an ID repair from old title A. The worker processes reset E, then repair A+`id::`. Serialization now reads **base E**, although the repair has restored A. Disk equals E, so the guard accepts A+`id::`, losing the external text without a conflict copy. Reconcile’s exact precheck does not protect mutations already constructed in the renderer.

   **Minimal fix:** Make automatic repairs carry UUID/property/value and construct the modified title from the worker’s current block. Alternatively, carry the original block content/generation and reject stale repairs at transaction time.

2. **[HIGH] Failure to save the recovery snapshot silently loses edits made after the proposal.**

   **Locations:** [F/worker/db_worker.cljs:1067](/home/johan/dev/logsidian-perf/src/main/frontend/worker/db_worker.cljs:1067), [F/fs/watcher_handler.cljs:244](/home/johan/dev/logsidian-perf/src/main/frontend/fs/watcher_handler.cljs:244).

   Proposal B is copied successfully. The user commits C before recovery. The worker synchronously snapshots C and resets the page to E. Only afterward does the renderer attempt to persist C.

   If that second copy fails, the catch logs the error and returns nil. The notice still says the user’s version was saved, naming B’s copy; it never says that C was lost. Recovery is detached from the write outcome, so this failure also never enters `*failed-writes`.

   The synchronous snapshot/reset correctly closes the edit-interleaving window **inside the worker**. It does not close the preservation window afterward.

   **Minimal fix:** Persist the snapshot as an unresolved recovery record before replacing the page, retaining it until conflict-copy acknowledgment. Surface copy failure explicitly and keep the failure tracked. An asynchronous pre-copy needs a generation check before reset.

3. **[HIGH] Speculative stamps survive restart, but unresolved-save identity does not; reopen can discard the only remaining edit.**

   **Locations:** [F/worker/file.cljs:193](/home/johan/dev/logsidian-perf/src/main/frontend/worker/file.cljs:193), [SQLite connection:15](/home/johan/dev/logsidian-perf/deps/db/src/logseq/db/common/sqlite.cljs:15), [F/fs/watcher_handler.cljs:561](/home/johan/dev/logsidian-perf/src/main/frontend/fs/watcher_handler.cljs:561).

   **Yes, the unwritten proposal can persist as `:file/content`.** This is a storage-backed DataScript transaction; `:skip-refresh?` does not disable persistence.

   For an existing file, stamp B → quit/crash before writing → reopen with disk A causes reconciliation to back up B through **ordinary, six-file retention**, then reset to A. Backup failure is swallowed. There is no persistent pending-save record or specific recovery warning.

   For a **new file**, the outcome is worse: its persisted `:file/path` is absent from disk, so reopen classifies it as deleted and calls normal page deletion, without preserving its pending content.

   A refusal whose first conflict copy failed also leaves its stamp unresolved until subsequent recovery or reopen. The memo’s accepted quit limitation remains real; reopening does not reliably rescue it.

   **Minimal fix:** Persist pending proposal/base/status alongside the stamp. Before reopen resets or deletes such a page, preserve its current content in the unpruned conflict tree and notify. Keep confirmed disk content distinct from pending content.

4. **[HIGH] Failures before entering the guard bypass the new copy-and-notice handling.**

   **Locations:** [F/fs/node.cljs:251](/home/johan/dev/logsidian-perf/src/main/frontend/fs/node.cljs:251), [F/fs.cljs:110](/home/johan/dev/logsidian-perf/src/main/frontend/fs.cljs:110).

   `write-file!` awaits parent-directory creation before calling `write-file-impl!`. If that fails—for example, a new page’s parent cannot be created—the outer filesystem wrapper merely logs and returns an `io-error` object.

   Consequently, `<handle-failed-write!` never runs: there is no conflict-copy attempt, persistent user notice, or `LSGUARD` record. The worker records `:failed`, but the user discovers that only on graph switching. Closing directly can feed the new-file loss in finding 3.

   **Minimal fix:** Route failures across the entire guarded operation, including preparation, through the same preservation-and-notification handler. Preserve a structured failure result afterward.

5. **[MEDIUM] Rollback handles one failed proposal, but not a chain of failures or stamp ownership.**

   **Location:** [F/worker/file.cljs:195](/home/johan/dev/logsidian-perf/src/main/frontend/worker/file.cljs:195).

   With disk A, serialize B/base A, then C/base B. Suppose both writes encounter an I/O failure before changing disk, and acknowledgments arrive in order. Rolling back B does nothing because the stamp is C. Rolling back C restores **B**, which was never written. The next healthy save therefore self-refuses against disk A. The pure-logic probe reproduced this transition.

   Additionally, equality of content does not establish ownership: an older failed proposal can match a newer identical proposal or a reset containing identical bytes and incorrectly roll that stamp back. Existing tests cover only a *different* newer stamp.

   **Minimal fix:** Identify stamps by request/generation, invalidate ownership on reset, and retain a confirmed base across failed chains. Serializing outstanding writes per file avoids speculative dependency chains.

6. **[MEDIUM] The Electron handler defeats permission preservation.**

   **Locations:** [E/handler.cljs:106](/home/johan/dev/logsidian-perf/src/electron/electron/handler.cljs:106), [E/handler.cljs:148](/home/johan/dev/logsidian-perf/src/electron/electron/handler.cljs:148).

   `writable?` returns `fs.accessSync`’s successful value, which is `undefined`, not true. Thus `(not (writable? path))` succeeds even for writable files. With automatic chmod enabled, a `0600` or `0640` journal becomes `0644`; the guard then faithfully copies that already-altered mode. This is an inherited bug, but the new mode-preservation test bypasses the real handler. [Node documentation](https://nodejs.org/api/fs.html#fsaccesssyncpath-mode).

   **Minimal fix:** Explicitly return true after successful `accessSync`, and test replacement through the handler with a restrictive original mode.

7. **[LOW] Temporary-file cleanup is best effort, despite the unconditional guarantee.**

   **Location:** [E/write_guard.cljs:123](/home/johan/dev/logsidian-perf/src/electron/electron/write_guard.cljs:123).

   A failed rename attempts one unlink and suppresses every cleanup error. If a scanner or sync process also holds the temporary file, it can remain indefinitely. The test mocks rename failure but lets cleanup succeed.

   On Windows, an open handle without delete sharing can prevent rename; merely having a file open does not always prevent it. `EPERM`/`EBUSY` reaches the guard’s failure path, preserving the existing destination, but there is no rename retry. OneDrive-specific frequency was not established here. [Windows sharing semantics](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew).

   **Minimal fix:** Report leftover temporary paths and retry cleanup with a bound. Any delayed write retry must repeat the disk comparison.

8. **[LOW] Pruning still invokes recursive deletion after checking the entry type.**

   **Location:** [E/backup_file.cljs:59](/home/johan/dev/logsidian-perf/src/electron/electron/backup_file.cljs:59).

   The **pre-existing bug is confirmed**: the old implementation sorted directories together with files and recursively removed older entries. Backing up root `pages.md` could therefore remove backup directories belonging to `pages/*`.

   The new `isFile` filter fixes that ordinary collision and does not broaden deletion paths. However, if another process replaces a selected file with a directory between enumeration and removal, `removeSync` still recursively deletes it. Thus “only files” is not enforced by the deletion operation itself.

   **Minimal fix:** Use `unlinkSync`, which fails on a directory, rather than recursive removal.

The remaining checks were substantially better than the MVP:

- Per-page coalescing retains the last tuple with the highest request ID, and stamped `alter-files` avoids re-advancing the DB. I found no ordinary type+Enter or successful A→B→C self-refusal in that path.
- Existing-file replacement prepares and fsyncs a sibling, compares again synchronously, then renames. Stable symlinks resolve to their targets. New-file hard-link publication refuses existing destinations. The fallback direct `wx` creation preserves exclusivity but loses atomic publication.
- Conflict names use randomness plus `wx`; ordinary pruning excludes the conflict tree. Conflict copies themselves are not fsynced.
- Explicit refusal recovery does not invoke deletion. Its initial ENOENT probe retries; an ENOENT during the subsequent read exits with a warning instead. Normal watcher/reconcile deletion still retains the pre-existing restored-file unlink hazard.
- The live-page cleanup predicate is corrected. Failed-write tracking and graph-switch warning work for reported outcomes, but do not provide durable recovery or cover finding 2.
- I found **no remaining accidental Error-as-success Promesa coercion** in the reviewed guard/reconcile changes. The error binding is now a plain `let`, bounded-map handlers return nil, and intended rejections remain explicit.
- The added tests cover individual components, but not the decisive cross-boundary orderings above.

**Verdict:** I would not ship this to the daily graph yet, even after the current safety harness and manifest gate pass. The boundary replacement is much safer, but a stale automatic repair can still erase external journal text with a successful guard result, and preservation failures can still lose user edits silently. Fix findings 1–4 and add deterministic coverage for those orderings before treating the harness pass as evidence that the owner’s bar is met.

## Review 2: the save fixes (A) and the promesa patch (B)

A still has paths that silently lose edits or restore them into the wrong context. B correctly fixes the identified drain-state leak.

Read-only review; no builds or test runners were run. JavaScript probes compared the jar implementation with the override. Existing gaps are distinguished below from new behavior.

1. **[HIGH] A — Enter can rescue only half the text; paste can silently disappear.**  
   [editor.cljs:421](/home/johan/dev/logsidian-perf/src/main/frontend/handler/editor.cljs:421), [editor.cljs:2259](/home/johan/dev/logsidian-perf/src/main/frontend/handler/editor.cljs:2259), [op.cljs:191](/home/johan/dev/logsidian-perf/deps/outliner/src/logseq/outliner/op.cljs:191).  
   **Evidence:** Enter splits `prefix|suffix`, saves `prefix`, then inserts `suffix` using the old target’s numeric ID. If the worker reparsed that block, saving rescues `prefix` under a new ID, but `:insert-blocks` still resolves the old ID and silently skips insertion. The suffix never enters the transaction. Similarly, `paste-blocks` captures `target-block'` before rescuing: a missing UI entity leaves that target nil even after successful rescue, so pasted blocks are skipped.  
   **Minimal fix:** Resolve insertion targets by UUID inside the worker transaction, including newly rescued targets. Recompute paste placement after rescue. Retain the complete editor/paste buffer until every constituent operation succeeds.

2. **[HIGH] A — “Last sent” incorrectly means “safely saved,” including during concurrent saves.**  
   [editor.cljs:271](/home/johan/dev/logsidian-perf/src/main/frontend/handler/editor.cljs:271), [editor.cljs:306](/home/johan/dev/logsidian-perf/src/main/frontend/handler/editor.cljs:306), [editor.cljs:328](/home/johan/dev/logsidian-perf/src/main/frontend/handler/editor.cljs:328).  
   **Evidence:** The atom advances before the worker acknowledges anything—and the normal path advances it before parsing. Send text `T`; parsing/request/transaction fails; the block disappears; the next save of `T` returns nil because it equals “last sent.” There is no rollback. With an outstanding rescue, a concurrent blur saving identical text also returns nil instead of awaiting that rescue, allowing `escape-editing` to clear the buffer before its outcome is known.  
   **Minimal fix:** Track pending and acknowledged revisions per graph/block/edit session. Identical pending saves must return the existing promise. Failures must retain recoverable text and permit retry; update state using revision checks so older outcomes cannot supersede newer edits.

3. **[HIGH] A — Rescue can write an old graph’s text into an unrelated page in the current graph.**  
   [editor.cljs:311](/home/johan/dev/logsidian-perf/src/main/frontend/handler/editor.cljs:311), [core.cljs:532](/home/johan/dev/logsidian-perf/deps/outliner/src/logseq/outliner/core.cljs:532), [events.cljs:90](/home/johan/dev/logsidian-perf/src/main/frontend/handler/events.cljs:90).  
   **Evidence:** Rescue carries only the old page’s numeric ID. Parsing and transaction dispatch use the current graph. Graph switching changes the current repo without clearing or binding the editor snapshot to its origin. A delayed save from graph G1 after switching to G2 finds its UUID absent; if G2’s entity at the old page ID is a page, rescue appends there. The worker checks only `:block/name`, not page identity.  
   **Minimal fix:** Capture the originating repo and stable page identity with the edit. Pass them through parsing and dispatch, and verify page identity before insertion. Never interpret another graph’s numeric IDs.

4. **[HIGH] A — CodeMirror still silently drops edits when its block disappears. Existing gap, uncovered by these fixes.**  
   [code.cljs:28](/home/johan/dev/logsidian-perf/src/main/frontend/handler/code.cljs:28), [code.cljs:35](/home/johan/dev/logsidian-perf/src/main/frontend/handler/code.cljs:35), [code.cljs:470](/home/johan/dev/logsidian-perf/src/main/frontend/extensions/code.cljs:470).  
   **Evidence:** The embedded-code save path looks up the current entity, reconstructs content from its `:block/raw-title`, then calls `save-block-if-changed!` directly. When lookup returns nil, that helper has no original content and does nothing. Neither new missing-block entry point runs. Meanwhile, CodeMirror has already marked the value as its saved baseline; blur subsequently clears its context and potentially the editor state.  
   **Minimal fix:** Preserve the original full block snapshot and code-region metadata in the CodeMirror context. Reconstruct against that snapshot when necessary and use the common recovery-aware save path. Advance the saved baseline only after success.

5. **[HIGH] A — Some exits still clear text without reaching rescue. Existing gap.**  
   [editor.cljs:1559](/home/johan/dev/logsidian-perf/src/main/frontend/handler/editor.cljs:1559), [components/editor.cljs:756](/home/johan/dev/logsidian-perf/src/main/frontend/components/editor.cljs:756), [state.cljs:1390](/home/johan/dev/logsidian-perf/src/main/frontend/state.cljs:1390).  
   **Evidence:** With `:select-code-block-mode` active, outside-click handling proceeds to `escape-editing`: that action is absent from its protected-action list. `save-current-block!` skips saving because an editor action exists, returns nil, and escape clears the text. Direct `clear-edit!` callers also bypass recovery—for example page-reference activation and block-control clicks. Unmount does not provide a final save. Choosing a language uses the repaired path; dismissing the picker is still unsafe.  
   **Minimal fix:** Centralize exit behavior around an explicit save/recover/discard result. A skipped save must not count as successful persistence. Reserve unconditional clearing for deliberate discard/deletion.

6. **[MEDIUM] A — The deletion safeguard does not reliably prevent resurrection or duplication.**  
   [editor.cljs:264](/home/johan/dev/logsidian-perf/src/main/frontend/handler/editor.cljs:264), [core.cljs:570](/home/johan/dev/logsidian-perf/deps/outliner/src/logseq/outliner/core.cljs:570).  
   **Evidence:** The worker rescues every missing file block marked `:user-edit?`, regardless of whether disappearance came from reparse or deliberate deletion. The UI safeguard is one global pair: save A’s edited text, save B, then delete A; A’s remaining editor snapshot now compares against its initial text and can resurrect the already-saved, deliberately deleted text. A forced stale save after a reparse can likewise append text already present under the replacement UUID. Sequential rescues without another reparse do reuse the original UUID, so those alone do not create multiple blocks.  
   **Minimal fix:** Use per-block revision tracking plus reparse/deletion provenance. Where identity or deletion intent is ambiguous, retain a visible recovery copy rather than automatically restoring the original UUID into the active outline.

7. **[MEDIUM] A — Unrecoverable saves report only 500 characters and resolve as though handling succeeded.**  
   [core.cljs:582](/home/johan/dev/logsidian-perf/deps/outliner/src/logseq/outliner/core.cljs:582), [db_worker.cljs:910](/home/johan/dev/logsidian-perf/src/main/frontend/worker/db_worker.cljs:910).  
   **Evidence:** When the page is gone, the notification contains only the first 500 characters; the complete text is only console-logged. The worker catches `:notification`, broadcasts it, and does not reject the operation. Awaiting escape/command flows can therefore clear the editor despite no successful save. This is visible failure, but inadequate recovery for a long journal entry.  
   **Minimal fix:** Retain the complete text in accessible recovery storage and return an explicit unsuccessful-save result that prevents ordinary clearing.

8. **[MEDIUM] B — Exceptions during task completion can still abort a drain. Inherited upstream issue, not introduced by the reset.**  
   [promise.js:315](/home/johan/dev/logsidian-perf/src/main/promesa/impl/promise.js:315), [promise.js:328](/home/johan/dev/logsidian-perf/src/main/promesa/impl/promise.js:328).  
   **Evidence:** `resolveTask` runs outside the handler’s `try`. Promesa’s ClojureScript `p/finally` uses `.handle`, putting the user callback in `task.complete`. A probe with a pending promise, a throwing completion callback, and a following `.then` left that following promise pending in both versions. A throwing returned thenable getter similarly left the affected promise and its following sibling pending.  
   **Minimal fix:** Separately guard completion callbacks and thenable assimilation, report/reject appropriately, and ensure remaining tasks drain. Do not mistake this for another `rcause` reset problem.

9. **[LOW] B — Falsy rejection reasons remain incorrectly treated as success. Inherited upstream issue.**  
   [promise.js:264](/home/johan/dev/logsidian-perf/src/main/promesa/impl/promise.js:264), [promise.js:323](/home/johan/dev/logsidian-perf/src/main/promesa/impl/promise.js:323).  
   **Evidence:** Both functions test `if (cause)`. A patched-source probe with `throw null` resolved to `undefined`. Resetting `rvalue` correctly prevents reuse of the preceding task’s value, but cannot repair this separate error-state representation. I found no demonstrated application dependency on falsy throws.  
   **Minimal fix:** Represent rejection explicitly, independently of the reason’s truthiness.

**B’s claimed fix checks out.** The jar comparison shows only the explanatory header and two resets. The macros start from `pt/-promise nil`, which reaches shared `NULL_PROMISE`. In probes matching the regression scenarios, the original produced `[a, error, error]` and rejected the neighbour of the synchronous throw; the patch produced `[a, error, c]` and `[caught-error, neighbour]`. Thus both regression tests would fail without the patch.

Both success and rejection handlers assign `rvalue`; resetting both variables before each task is sufficient for this leak. `task` is reassigned, promise state/value are fixed for the drain, and asynchronous continuations capture `resolveTask` parameters. Mixed map/bind/flatten and cancellation-recovery probes passed. `mcat`, `hmap`, and public `handle` use these same corrected paths.

Shadowing is confirmed by `deps.edn` ordering, `:deps true`, Shadow’s duplicate-resource handling, and existing app/worker release inputs containing the resets. I found no application code relying on cross-chain error contamination; static review cannot prove universal absence.

**A’s UUID resolution is sound for maps carrying a UUID:** a mismatching numeric ID is rejected before UUID lookup. ID-only maps and the unchanged Entity shortcut lack that identity guarantee. **DB-graph behavior is not entirely unchanged:** ordinary valid saves retain their path and rescue insertion is disabled, but stale-ID resolution and missing-block warning/notification behavior now also affect DB graphs.

No LOW findings for A. No new HIGH, MEDIUM, or LOW defects found in B; findings 8–9 are inherited follow-ups.

**Verdicts: A — ship with fixes; do not deploy unchanged. B — ship.**

## Review 3: block identity

Codex hit its usage limit (until 20:43); a Fable review stands in, Codex re-runs after 20:45.

### Stand-in: Fable, 2026-09-12

Reviewed 424246d (design bench/decision-block-identity.md + implementation),
read from the commit's own sources; nbb probes of the pure matcher and of
parse-file twice on a conn.

1. [HIGH] Rule 5 hands a deleted block's uuid to the next changed block in
   the gap; the editor then writes the user's text into that block.
   block_identity.cljs:155-159 (match-gap zips left-old/left-new by level, no
   content check); editor.cljs:349-380 and outliner core.cljs:599-640 resolve
   by uuid with no check that the entity still holds the editor's start text.
   Probes: "- a\n- b\n- c" -> "- a\n- c typed" gives "c typed" <- old[b];
   insert before an edited block shifts uuids; a page-properties pre-block
   added externally takes the first block's uuid. Worst case: user edits b on
   PC1, PC2 deletes b and edits c, OneDrive delivers; u_b now titles
   "c typed"; the user's save overwrites it -> PC2's edit destroyed, b
   resurrected, silently, and the guard passes (the re-parse stamped
   :file/content with disk). Before 424246d the text was appended (nothing
   lost). Fix: pair leftovers only when counts are equal and a similarity
   holds (same first line, or common prefix+suffix >= 50 %); never pair a
   pre-block with a non-pre-block; plus a :base-title save guard (the editor
   passes its start/last-sent text; a mismatch with the entity's current
   title takes the keep-user-edit path instead of overwriting).
2. [MEDIUM] Undo after a from-disk re-parse rewrites live entities
   (undo_redo.cljs:180-191 reverse-datoms without value checks;
   gen-undo-ops! ignores from-disk txs). Fix: drop undo/redo ops whose eids
   intersect a from-disk tx's eids (modules/outliner/pipeline.cljs:56-59).
3. [MEDIUM] Rule 5 degrades to positional zipping when editor-stored titles
   differ from parser titles (editor trims; parser does not; writer adds
   collapsed::). Fix: string/trimr keys; an nbb parity test that serialises
   a parsed page with the writer, re-parses, asserts the fast path.
4. [LOW] Dead macro handling; orphaned macro entities per kept block.
5. [LOW] Collapse race window (not a regression).
6. [LOW] updated-at/created-at of kept blocks not refreshed.
7. [LOW] Performance plausible, unmeasured in the app (nbb: 5 716 blocks,
   fast path 138 ms, anchor path 524 ms).
Clean: no uuid issued twice; implementer decisions verified; retain/retract
path; search upserts; upstream 0.10.9 claim verified.
Verdict: do not ship as is; ship with fixes 1 and 2 (3 in the same change).

RC4 round 1 (branch rc4/fixes) fixed 1 (a0e558e, 9bb07be), 2 (5ea2d9f) and 3 (0b37eb1).
