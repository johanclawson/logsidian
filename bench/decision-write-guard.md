# Decision memo: write guard scope, reconcile cap, shipping gates

Written by a Fable arbiter agent on 2026-09-12 after Codex's guard design
(write-guard-design.md) and review findings; Johan delegated the decision.
Adopted as the plan of record.

# Decision: write guard scope, reconcile cap, and what gates shipping (2026-09-12)

## Decision

1. Build a **boundary-only compare-and-refuse guard** now: the Electron `writeFile` handler gets an `expected` argument (the renderer's `:file/content`, already computed as `old-content` by both graph producers), compares raw bytes synchronously, and refuses on mismatch. On refusal: keep disk, save the proposed content to `logseq/bak/` as a conflict copy, notify, reparse the file from disk. No write-kind flag, no worker envelope, no per-file state module. Codex commits 2 and 3 (confirmed base, envelope through batching) and the sync/diff/git migrations are deferred.
2. **Ship the reconcile with the eager fan-out**: file-phase bound = all files. Keep `<map-bounded`, the per-file catch, the stale-run guard, error counting, the LSPERF line, and the repair deferral; the 16-cap stays a one-line constant for the H2 experiment on the 10k bench.
3. **Conflict policy for user edits is refuse + conflict copy + reparse**, for all writes.
4. The perf batch (search, journals paging, linked refs, NOW fast path, FTS fix, boot fixes) ships to the launcher **without** the reconcile commits and does not wait for the guard. The guard ships next, on harness evidence; reconcile commits after it.

## Reasons

- Every risky producer (missing-id repairs, config rewrites, batched page saves, direct edits) already converges on `fs/node.cljs write-file-impl!` → Electron `writeFile`, and both `alter-files` and `write-file-aux!` already supply the DB's last-seen content. Today the code *detects* the mismatch and writes anyway (backup only). Turning that into a refusal in the main process closes findings 1, 2, 4, 5 of the second review at once, for every producer, with ~60 changed lines in three files. The full envelope design fixes the same window more rigorously but touches twelve files across worker, pipeline, renderer and Electron, and the second review's findings do not require it for a single-graph user.
- `:file/content` is a faithful base: `utils/read-file` is `Buffer.toString()`, which keeps BOM and CRLF, so re-encoding as UTF-8 round-trips and exact comparison will not refuse Windows-edited files.
- The cap is an unmeasured experiment: no LSPERF reconcile line exists in any run log, the critique predicted it would not bound the stall, and Johan's ~555 files never showed the 10k reopen problem. Its only demonstrated effect is widening the repair window from seconds to the whole run. With the guard the window is harmless for safety, but one shipped configuration is easier to test than two.
- Refusal beats overwrite for this setup. Whatever lands at the path is what OneDrive fans out to the other PCs and what Jarvis reads next. Overwriting reverts the external edit everywhere and sends the loss to a `.bak` nobody looks at; refusing keeps the loss local, in the same `bak` directory, with a notification to the person who typed it. The realistic conflict is Jarvis appending to today's journal while Johan types: refusal costs at most the last one-second batch of typing, recoverable from the copy; overwrite silently drops Jarvis's block from the system of record.

## Rejected alternatives

- **Full six-commit guard first.** Highest rigor, most churn in upstream code (worker batching, pipeline, sync/diff/git callers), weeks of work against a residual TOCTOU it cannot close either. Keep as the H3 follow-up if the harness shows the base going stale between batch and write.
- **Keep the 16-cap and rely on deferral.** Deferral covers only the reconcile's own repairs; live watcher repairs during the run (OneDrive delivering files at reopen) still had the whole-run window.
- **Keep overwrite + backup for user edits.** Familiar, but wrong for a synced, agent-written graph (above).
- **Distinguish user from automatic writes in the MVP.** Requires a flag through the worker channel and message. The cost of not distinguishing is a spurious conflict copy and notification on a refused automatic repair, which is rare and informative.
- **Block the perf batch on the guard.** The perf commits do not touch the write path; the launcher runs upstream's overwrite behaviour today, so the batch does not change data-safety risk.

## Residual risks (accepted)

- External write between compare and replace (TOCTOU, milliseconds, synchronous in main). Documented by an adversarial harness test, not claimed safe.
- Writers that pass `skip-compare?` or no `old-content` (sync, diff, git restore, assets) keep upstream overwrite behaviour. Johan uses none of them on graph files.
- Renderer `:file/content` still advances before write success; a refused write reparses disk, which corrects it. Two flushes for one file within a single IPC round trip could self-refuse; the harness A→B→C scenario checks this.
- Graph binding (review finding 6) is not done: single-graph user, and the stale-run guard stops a switched run.
- Unsent editor text at the moment of a refusal follows upstream's from-disk reparse behaviour.

## Next steps, in order

1. **Ship perf batch to the launcher** (commits up to c176b50 plus boot fixes; no reconcile commits). Gate: existing unit suite green; reopen a copy of Johan's graph and `git status` in the copy shows zero changes and `logseq/bak/` gains no file; `search`, journals, linked refs verified by hand. Soak: Johan runs it; lsgit hourly commits and any new `logseq/bak/` file are the canaries. Any bak file not explained by an in-app edit reverts the launcher.
2. **Commit A — settle `worker-call`** (`db/transact.cljs:12`): catch synchronous throw, reject the deferred. Test: throw, reject, encoded error, success each settle once.
3. **Commit B — guarded `writeFile`**: `E/handler.cljs` takes `expected` (string, `:absent`, or nil); nil = unguarded (legacy). Compare `readFileSync` bytes with `Buffer.from(expected,"utf8")`, no trim; `:absent` uses flag `wx`. Return `{:result :written|:mismatch|:exists|:io-error ...}`; never write on mismatch; drop the catch-side backup-and-notify for guarded writes. `fs/node.cljs`: pass `old-content` (or `:absent` when the DB has no content and the file is new) instead of comparing itself; on `:mismatch`/`:exists` call `backupDbFile`-style save of the *proposed* content via `backup-file` to `:backup-dir`, notify with the copy's path, then `handle-changed!`-equivalent from-disk reparse of the path; do not call `ok-handler`. Unit: exact match writes; BOM/CRLF/trailing-newline differences refuse and leave bytes; `wx` collision leaves bytes; nil expected behaves as before.
4. **Commit C — reconcile: eager fan-out**, unknown-path repairs always deferred (review finding 3), exact compare in `<paths-matching-db` (finding 4). Existing `<map-bounded` tests stay.
5. **Harness scenarios that must pass on a graph copy, comparing raw bytes** (E = external bytes, U = proposed):
   - Offline edit, reopen, no repair: path == E, no bak file.
   - Offline edit of page P referenced by a journal: P == E after reconcile; repair, if any, serializes E plus `id::`; bak holds nothing derived from A.
   - External edit while running before the batch flushes (pause flush): path == E; bak == U; DB shows E.
   - A→B→C inside one second: one write, final C, no bak file.
   - Offline `config.edn` edit plus home-page file deleted: config == Econfig.
   - CRLF file and BOM file edited in-app: written, no refusal.
   - Adversarial write after compare: documents the TOCTOU, expected to fail, marked as such.
6. **Ship guard + reconcile** after 5 passes and after one week of step-1 soak with zero unexplained bak files. Then the H2 cap experiment on the 10k bench, then Codex commits 2–3 only if the soak or harness shows the renderer base going stale.


---

# Follow-up decision: guard findings 1, 3, 5 (Fable arbiter, 2026-09-12)

# Decision: guard follow-ups 1, 3, 5 (2026-09-12)

Arbiter memo after Codex's review of perf/write-guard (0d99e12). Findings 2, 4, 6, 7, 8 and the inverted `file-writes-finished?` predicate are being fixed. This memo decides 1, 3 and 5. Paths: `F/ = src/main/frontend/`, `E/ = src/electron/electron/`.

## Verified facts the decision rests on

- `db/set-file-content!` (F/db.cljs:66) goes through `outliner-op/transact!` to the worker; the renderer's `:file/content` advances only when the worker syncs back. Two `:write-files` messages handled back to back both read base A.
- `write-files!` (F/worker/file.cljs:183) dedupes by `[repo page-id outliner-op]`. Type, then Enter, inside one 1 s flush = `:save-block` + `:insert-blocks` = two messages with identical content C. The second reaches Electron with expected A while disk is C: **self-refusal on ordinary typing**, a "not saved" warning and a conflict copy every time. With a renderer stall > 1 s spanning two flushes the second message carries newer ops and the refusal's reparse resets the page to the first write, dropping those ops from the DB (into bak). The harness never triggers this: `typeInto` only produces `:save-block`.
- Worker→renderer messages (`post-message`, Comlink replies) share `js/self.postMessage`, so a proposal serialized before a reset always arrives before that reset's reply.
- Editor autosave commits 450 ms after the last keystroke; flush cadence 1 s; refusal recovery window (copy IPC + event + stat + read + worker reset) ~100–300 ms.
- Upstream's Electron catch side backs up the *proposed* content on a write error; the guarded branch dropped that.

## Finding 1: speculative base — implement now, gates the guard

Regression: yes, and frequent (every type+Enter second), not the exotic ordering Codex led with. The stale-overwrite-of-E ordering is real but needs a proposal serialized pre-reset and handled post-reset, which the shared channel prevents unless `alter-files` itself re-advances `:file/content` (it does today).

Smallest change, three parts, no per-path queue, no envelope module:

1. **Coalesce per page in the worker.** In `write-files!` replace `distinct-by #(take 3 %)` with: group by `[repo page-id]`, keep the *last* tuple (its op decides `blocks-just-deleted?`, which is correct for every op sequence), `dissoc-request!` the others. Extract a pure `coalesce-page-writes [pages] -> {:keep [...] :drop-ids #{...}}` for the test.
2. **Stamp the base at serialization.** In `save-tree-aux!`: `base = (:file/content (d/entity db file-db-id))`; post `{:request-id .. :page-id .. :repo .. :files [[path content]] :base base}`; then `(ldb/transact! conn [{:file/path path :file/content content}] {:skip-refresh? true})` so the next serialization (same flush, later flush, or after a stall) sees this proposal as its base. A reset from disk overwrites it with E, so a proposal serialized before the reset carries a pre-E base and refuses; one serialized after carries E and writes.
3. **Use the stamp in the renderer.** `handle :write-files` passes `:base`; `alter-files` takes `{:base base}` and, when the key is present, uses `base` as `:old-content` (nil → `{:absent true}` as now) and calls with `update-db? false` — the worker already advanced `:file/content`, and re-advancing it here is what would let a refused stale proposal cause a second reparse. `alter-file` (config, CSS, direct edits) keeps `original-content`; two config writes inside one worker round trip can still self-refuse — human-paced, accepted.

Tests (node build): coalesce keeps the last tuple and drops the other ids; `save-tree-aux!` posts `:base` equal to the pre-call `:file/content` and leaves `:file/content` = proposal (with-redefs `post-message`, datascript conn); `alter-files` with `:base` sends it as `:old-content` and does not transact; without `:base` unchanged. Add an `LSGUARD {path,result,copy}` console line in `write-file-impl!` for every guarded outcome; the harness captures it like LSPERF.

## Finding 3: edits committed during recovery — implement, does not gate

Regression: no. Upstream's watcher resets the page from disk on any external change to the page being typed and loses in-flight ops the same way; the guard only moves that reset earlier (the refusal) and loses ≤ 450 ms of committed keystrokes instead of the whole external edit. Likelihood: needs a refusal on the page being typed *and* an autosave inside the ~200 ms window — a fragment lost perhaps weekly under Jarvis-appends-while-typing, with a warning that names a copy lacking the last words.

Smallest change: make the copy honest. `:thread-api/reset-file` gets opt `:snapshot-before? true`: before parsing, serialize the page bound to the file with the same code `save-tree-aux!` uses (`otree/blocks->vec-tree` + `common-file/tree->file-content`, context from `worker-state/get-context`) and return `{:tx .. :snapshot s}`. `<reparse-from-disk!` passes the opt; if `s` differs from the refused proposal, `backupConflictFile` it too and name both copies in the notice. Atomic with the reset inside the worker, so no window remains. Test: page with block X, reset with content lacking X → snapshot contains X, DB does not. Editor textarea text follows upstream (accepted earlier).

## Finding 5: failed copy / io-error terminal — part now, part accepted

Regression: one part, yes: a guarded `io-error` no longer preserves the proposal anywhere (upstream's catch-side backup did). No retry and no quit barrier are upstream behaviour (`file-writes-finished?` only gates graph switch). Likelihood: low on Linux (no Windows-side locks reach it); ENOSPC or an unwritable `logseq/bak` are the cases.

Implement now (gates, trivial): on `"io-error"` in `write-file-impl!`, `backupConflictFile` the proposal, name it in the error notice, no reparse (disk untouched after the finding-6 temp+rename fix; DB keeps the proposal; the next edit of the page rewrites the whole file, which is the natural retry). Implement with it (~15 lines, not gating): `page-file-saved` carries `:outcome` (`:written` `:refused` `:failed`); the worker keeps `:failed` and copy-failed requests in `*failed-writes`, and `file-writes-finished?` (after the predicate fix) reports them so graph switch warns. Accepted residual: quit with a failed write whose conflict copy also failed loses it, as upstream; revisit if any `LSGUARD io-error` line appears in a soak log.

## Shipping gates for the guard

Finding 1 (all three parts), 5's io-error copy, plus the contained fixes underway. Findings 3 and 5's failed-write tracking ship in the same series if ready; they do not hold the guard. The perf batch is unaffected.

## lssafety.js additions

- Capture `LSGUARD` records; every scenario asserts zero `mismatch`/`exists`/`io-error` records unless it stages a conflict.
- 7 `two-ops-one-flush` (fail): type into block one, Enter, type into the new block, Escape, all fast; both tokens on disk once, no LSGUARD refusal, no new bak file, no "not saved" notification.
- 8 `typing-through-external-rewrite` (finding 1 stale ordering + finding 3): type continuously with Enter presses for 3 s while the page is rewritten externally mid-way. Fail: external tokens on disk at the end (never overwritten by a stale proposal). Warn, becoming fail once the snapshot lands: every typed token is in the final file or in some conflict copy.
- Promote `external_survived` in scenario 3's same-page cases from `policy` to `fail`; `typed_survived` stays `policy`.
- Optional 9 `bak-unwritable`: `chmod 000 logseq/bak`, stage a same-page refusal; expect an error notification and the typed text still in the UI (no reparse).
