# File write path and a compare-and-write guard (Codex design, 2026-09-12)

Read-only design by Codex (gpt-6-astra) against branch perf/step4-reconcile.
It is the traced write path of a page/config save (worker -> batching -> renderer
-> fs protocol -> Electron) plus a proposed guard. Line numbers are as of that
branch. The decision on scope is recorded in adr003-step1-results.md.

The design should make every write carry an immutable expected base, compare it at the Electron boundary, and treat a mismatch as a failed save. For user edits, preserve the proposed content in a conflict copy; for automatic edits, discard the stale proposal and reparse disk.

**One limitation is fundamental:** ordinary filesystem APIs cannot atomically compare content and replace a file against an uncooperative external writer. A synchronous main-process read/compare/write closes the current asynchronous race, but OneDrive or an automation agent can still write between the comparison and replacement. This design substantially improves safety; it cannot honestly promise the literal “NO automatic write” guarantee under arbitrary concurrent external writes.

Assumption: this proposal targets Electron file-based graphs. I made no file changes and ran no compilers or tests. Paths and line numbers below refer to this checkout; `F/` means `src/main/frontend/`, and `E/` means `src/electron/electron/`.

**1. Current write path**

| Hop | Location | Data available and current behavior |
|---|---|---|
| Read disk | `E/handler.cljs:99`, `E/utils.cljs:217` | `readFile` returns a decoded string; `readFileRaw` returns bytes. The string reader logs errors and can return nil. |
| Reparse into DB | `F/worker/db_worker.cljs:933` → `F/worker/file/reset.cljs:99` → `deps/graph-parser/src/logseq/graph_parser.cljs:65` | Parser builds `:file/content` from the original supplied content at line 84. It has not yet serialized the page. |
| Missing-ID repair | `F/fs/watcher_handler.cljs:29`, `:39`, `:62` | Finds referenced blocks, then invokes the property handler. Lookup and repair lack an explicit repo. Live watcher events repair immediately. |
| Construct repair transaction | `F/handler/file_based/property.cljs:23` → `F/modules/outliner/ui.cljc:10` → `F/db/transact.cljs:32` | Property handler looks up current-graph entities. Macro constructs operations; dispatch reads current repo later. `:outliner-op :save-block` does not distinguish repair from user editing. |
| Apply transaction | `F/worker/db_worker.cljs:782` | Worker API already receives repo explicitly and selects its connection. |
| Schedule page save | `F/worker/pipeline.cljs:479` → `F/worker/file.cljs:199` | Pipeline has the transaction report, including `:db-before`. It forwards only repo, page ID, and tx metadata. |
| Batch | `F/worker/file.cljs:42`, `:51`, `:179`, `:209` | Channel stores repo/page/op/time/request ID; waits 1 second. Deduplication includes operation, so one page can survive more than once. No expected content or write classification travels here. |
| Choose connection | `F/worker/db_worker.cljs:1078` | Flush selects the first entry’s repo connection for the entire collection. Context is also read from shared worker state. |
| Serialize | `F/worker/file.cljs:148` → `:134` → `:110` | Serializes the then-current DB tree. Can access its file entity but does not send `:file/content`. For a new file, `:79` creates the DB path/link before disk creation succeeds. |
| Worker → renderer | `F/worker/file.cljs:126` → `F/handler/worker.cljs:15` | Sends `{repo, page-id, request-id, files [[path new-content]]}`. No expected base. Renderer calls `alter-files`. |
| Renderer updates/writes | `F/handler/file_based/file.cljs:262`, `:232` | Looks up old content from the renderer DB, starts DB updates without awaiting them, then writes. This is neither the transaction’s captured base nor an acknowledged disk base. |
| Filesystem abstraction | `F/fs.cljs:87` → `F/fs/protocol.cljs:20` | Options can carry `old-content`, but wrapper callbacks/catches can turn failures into successful promise completion. |
| Node backend | `F/fs/node.cljs:104` → `:20` | Stats and reads disk, compares **trimmed** strings, then writes regardless. Missing/read-error states collapse toward empty content. |
| Electron write | `E/handler.cljs:123` | IPC contains only repo/path/new content. Calls `writeFileSync` without comparison or exclusive creation. Catch saves a backup of proposed content and notifies, without a reliable failure result. |
| Backup / acknowledgment | `F/fs/node.cljs:43`, `E/handler.cljs:78`; `F/handler/worker.cljs:18`, `:26` → `F/worker/db_worker.cljs:840` | Mismatch backup happens after writing and is conditional on the diff containing deletions. Both renderer success and failure acknowledge “page-file-saved.” |

Config writes take a **different producer path**, then join the same filesystem boundary:

`F/handler/common/page.cljs:161`—home-page deletion triggers config updates at `:174`  
→ `F/handler/config.cljs:17`—reads current-repo config and rewrites it  
→ `F/handler/file_based/file.cljs:227`—`set-file-content!`  
→ `:139`—`alter-file` updates/parses DB **before** writing  
→ `:108`—`write-file-aux!` looks up “original” content **after** that update  
→ filesystem protocol → Node → Electron.

Config therefore needs the same write envelope, but cannot rely solely on fixing worker page serialization.

**2. Proposed commits**

The following are proposed APIs and changes, not existing functions.

**Commit 1 — Bind operations to their graph and settle dispatch failures**

Changes:

- `F/db/model.cljs:62`: add `get-block-by-uuid [repo id]`.
- `F/db/file_based/model.cljs:82`, `:120`: add explicitly named repo-aware lookup functions; avoid ambiguous arity changes to the existing `[file-path title?]` API.
- `F/fs/watcher_handler.cljs:29`, `:39`: change helpers to `missing-id-blocks [repo ref-ids]` and `set-missing-block-ids! [repo content]`.
- `F/handler/file_based/property.cljs:23`: add `batch-set-block-property-aux! [repo col opts]`; use repo-bound lookups.
- `F/modules/outliner/ui.cljc:10`: add `transact-for-repo! [repo opts & body]`. Capture repo before constructing operations; nested transactions must reject a different repo.
- `F/db/transact.cljs:32`: change internal dispatch to `apply-outliner-ops [repo conn ops opts]`, including the node-test branch.
- `F/handler/common/page.cljs:161`, `F/handler/config.cljs:17`, `:33`: provide repo-aware delete/config entry points. Reconcile uses these; UI convenience wrappers capture current repo once.
- `F/db/transact.cljs:12`: replace the detached `go`/deferred bridge with a promise chain that catches synchronous invocation exceptions, asynchronous rejection, and the existing `:ex-data` failure result.
- `F/state.cljs:45`: outstanding worker requests need rejection on worker termination and a bounded timeout. Timeout means **outcome unknown**, not permission to retry a write.

Unit tests:

- Switch from graph A to B between lookup, operation construction, and dispatch; only A changes.
- Same UUIDs in both graphs do not cross-contaminate repairs.
- Synchronous throw, promise rejection, encoded error, success, worker termination, and timeout all settle exactly once.

**Commit 2 — Introduce a confirmed file base**

Add a worker-owned file-save state module, for example `F/worker/file_save_state.cljs`, keyed by:

```clojure
[repo graph-session canonical-relative-path]
```

It holds:

```clojure
{:base-state :unknown        ; or :present / :absent
 :base-content raw-text
 :generation generation
 :reconciled? false
 :in-flight nil
 :pending nil}
```

Rules:

- `:base-content` is the exact content last successfully ingested from disk or acknowledged as written. Never trim, strip BOM, or normalize line endings.
- Cached `:file/content` on reopen is not evidence of reconciliation in the new session. Start unverified.
- Empty string, absent file, unknown file, and failed read are distinct.
- A disk read only establishes a usable base once its content has been successfully ingested. Failed parsing keeps writes blocked.
- A same-content reopen read must still mark the file reconciled.
- Ordinary model transactions never advance the confirmed base.
- Successful writes advance it to precisely the emitted content.
- Keep proposed direct-file edits separately until success; stop treating `:file/content` as a speculative save buffer.

Changes:

- `F/worker/db_worker.cljs:933`, `F/worker/file/reset.cljs:99`: register successful disk ingestion, including read/session generation.
- `F/fs/watcher_handler.cljs:62`, `:119`, `:344`: register unchanged files too; remove trimmed add-event comparison.
- `F/worker/pipeline.cljs:479`: pass the transaction report into save scheduling. It provides the before-transaction file mapping and content; the confirmed-base state verifies that content is actually a usable disk base.
- `F/handler/config.cljs:17`: capture config content and its generation together **before** rewriting it. Submit both with the proposed config.
- `F/handler/file_based/file.cljs:139`, `:262`: eliminate optimistic advancement of confirmed file content.

For bytes: UTF-8 decoding/re-encoding preserves valid UTF-8 BOM and CRLF. Verify round-trip equality against raw bytes; reject unsupported/invalid encodings rather than silently replacing invalid byte sequences. If arbitrary encodings must be supported, carry raw bytes and explicit encoding instead.

Unit tests:

- BOM, CRLF, trailing spaces, final newline, and empty file remain distinct.
- Cached-but-unreconciled, missing, permission error, and parse error remain distinct.
- Failed save never advances the base.
- Old read or acknowledgment cannot replace a newer generation.
- Config proposal captures the pre-change content.

**Commit 3 — Carry the base through batching**

Use a write envelope:

```clojure
{:repo repo
 :session session
 :path path
 :request-ids ids
 :base-generation generation
 :expected {:state :present :content exact-base}
 :content proposed-content
 :write-kind :automatic
 :reason :missing-id-repair}
```

Other expected states are `:absent` and `:unknown`; unknown cannot authorize replacement.

Changes:

- `F/worker/file.cljs:25`, `:42`, `:179`, `:199`: replace page/op tuple deduplication with per-repo/session/file pending saves.
- `F/worker/file.cljs:110`, `:134`, `:148`: pass the envelope through serialization and large-page requeueing.
- `F/worker/db_worker.cljs:1078`: partition by repo/session and use that repo’s connection and captured formatting context.
- `F/worker/file.cljs:79`: make generated destinations provisional until exclusive creation succeeds.
- `F/handler/worker.cljs:15`: forward envelopes unchanged.
- `F/worker/db_worker.cljs:840`: replace unconditional saved acknowledgment with `file-write-result [repo session request-ids result]`.

Batch semantics:

1. Several transactions against base **A** produce one final proposal **C**, still expecting **A**.
2. Only one write per file may be in flight.
3. Edits arriving during an in-flight write remain pending.
4. After acknowledged **A → B**, a pending proposal may expect **B** only if it descends from that same uninterrupted local edit history.
5. A disk reparse changes the generation and invalidates old queued proposals. Never substitute a fresh base under a stale proposal.
6. Failed writes invalidate dependent proposals; no automatic retry of stale serialized text.
7. Keep all request IDs until each has a terminal outcome.

Classification:

- Tx metadata records origin, for example `:file/write-kind` and `:file/write-reason`; the queue materializes these into the envelope.
- Default unclassified work to automatic.
- Repairs, reconciliation effects, background maintenance, plugin/background agent changes, and config changes caused by another operation are automatic.
- Direct page edits, undo/redo of those edits, and direct config edits carry user provenance.
- Page-delete-triggered config updates remain automatic even when the page deletion was a user action.
- Mixed batches retain “contains user changes” for conflict preservation. Classification never relaxes the comparison.

Unit tests:

- A→B→C within 1 second produces one write expecting A, proposing C.
- Different outliner operations on one file do not cause duplicate writes.
- Mixed-repo batches select correct connections and formatting.
- In-flight success, mismatch, and reparse invalidate or advance descendants correctly.
- Automatic/user mixed batches preserve user content on conflict.

**Commit 4 — Enforce the comparison in Electron**

Changes:

- `F/fs/protocol.cljs:20`: retain the existing opts argument but require the envelope for managed writes.
- `F/fs.cljs:87`: return structured outcomes; do not swallow write failures.
- `F/fs/node.cljs:20`, `:104`: remove renderer-side comparison and late DB fallback. Forward expected state/content to Electron.
- `E/handler.cljs:123`: implement a guarded handler, internally factored as `<compare-and-write! [repo path envelope]`.
- `E/utils.cljs:217`: add a strict snapshot reader that distinguishes ENOENT from all other errors.
- `F/fs.cljs:209`: replace stat-then-create with exclusive creation.

Boundary behavior:

| Condition | Action |
|---|---|
| Unknown/unreconciled base | Refuse; request disk ingestion |
| Expected present; disk absent | Refuse; reconcile deletion |
| Expected present; bytes differ | Refuse; return mismatch |
| Expected present; bytes equal | Perform replacement |
| Expected absent | Create with `wx`; EEXIST is a conflict |
| Read/write error | Return failure; do not claim success |

Compare raw disk bytes with the UTF-8 bytes of the captured base. No trimming. Check before chmod or any destination mutation.

Serialize app writes by canonical destination across windows. For replacement, prepare a uniquely created sibling temporary file, then perform the final comparison and replacement without an intervening asynchronous yield. Atomic rename helps avoid partial-file exposure; **it does not make the comparison atomic against external processes**. Exclusive creation must target the final destination; ordinary rename must not bypass `wx`.

Return explicit outcomes such as `:written`, `:mismatch`, `:exists`, `:unverified`, and `:io-error`.

Close bypasses as part of this commit:

- Existing `writeFile` must reject unmanaged replacement of graph files; adding a new guarded endpoint alone is insufficient.
- Remove `skip-compare?` as a graph-file escape hatch.
- Migrate direct IPC producers at `F/handler/file_based/editor.cljs:322` and `F/handler/editor.cljs:1528`.
- Update sync/diff/git callers, including `F/fs/sync.cljs:1451`, `:1501`, `:1528`, `F/components/diff.cljs:75`, `:92`, and `F/components/file_based/git.cljs:82`.
- Generated assets use exclusive creation. Explicit conflict resolution still compares against the version the resolution UI actually showed.

Unit tests:

- Exact match writes; every mismatch preserves destination bytes.
- BOM/CRLF/whitespace-only differences reject.
- ENOENT, EACCES, read failure, and write failure stay distinguishable.
- `wx` collision leaves existing content untouched.
- Multiple windows serialize correctly.
- Legacy IPC and `skip-compare?` cannot bypass protection.
- Temporary-file and rename failures never report success.

**Commit 5 — Recover conflicts without losing either version**

Changes:

- `F/handler/worker.cljs:15`: consume structured outcomes instead of acknowledging every completion as saved.
- `F/handler/file_based/file.cljs:108`, `:139`, `:227`, `:232`, `:262`: use the same result-driven path for page and config producers.
- `F/fs/watcher_handler.cljs:62`: add `<reconcile-file! [repo session path reason]`, shared by watcher events and rejected writes.
- Add conflict preservation at the Electron boundary using exclusive creation under an ignored directory, such as `logseq/conflicts/`, with original path and request metadata.

On automatic mismatch:

1. Keep destination bytes untouched.
2. Block further proposals for that generation.
3. Schedule a fresh read and reparse.
4. Recompute any still-needed repair from the new DB state.

On user or mixed mismatch:

1. Keep destination untouched.
2. Save the proposed serialized content exactly as a conflict copy.
3. Preserve any newer unsent editor changes too.
4. Only then reparse the original and notify with the conflict-copy location.

If conflict-copy creation fails, retain the dirty editor state and block destructive reparse of that state. Show an unsaved error.

I recommend this over backup-and-overwrite because OneDrive propagates the authoritative path. Preserving its externally updated content avoids publishing a stale replacement to the other PC and automation agent.

Coalesce config updates caused by one operation into one proposal. Otherwise the two page-delete config updates can conflict with each other. Do not replay stale whole-config strings after rejection.

Unit tests:

- Automatic mismatch reparses disk without replaying stale output.
- User/mixed mismatch preserves exact proposed bytes before resetting DB/editor state.
- Conflict-copy failure retains unsaved edits.
- Two config key changes compose into one proposal.
- Missing or invalid external config blocks writes without inventing replacement content.
- Late acknowledgments cannot clear newer dirty state.

**Commit 6 — Simplify deferred repair machinery**

Changes:

- `F/fs/watcher_handler.cljs:195`, `:222`, `:246`: remove `plan-id-repairs` safety gating and `<paths-matching-db` preflight comparison once boundary protection is active.
- Replace whole-run eligibility with repo/path-bound repair intents retried after successful ingestion.
- Retain deduplication and optionally end-of-run scheduling to reduce churn.
- A repair target without a known path is unresolved; it must not receive today’s “clean run permits it” exception.
- Preserve the pending repair intent across reparse so an unchanged referencing file need not emit another watcher event.
- Report separately “repair transaction submitted” and “repair persisted.”

Keep these branch changes:

- `9941904`: concurrency caps and removal of the UI DB transact from `<get-file`.
- `54af94f`: stale-run cancellation and performance measurements.
- `869b4a4`: useful failure reporting and repair deduplication, but not its preflight safety argument.
- `c787d81`: `<map-bounded` settlement fixes.

Graph binding makes cancellation less critical for correctness, but cancellation still avoids wasted work and stale notifications.

Unit tests:

- Repair target outside the current reconcile window remains untouched until ingested.
- Failed target retries after a successful read.
- Cross-file references converge without a second change to the referencing file.
- Stopped runs and graph switches cannot repair another graph.
- Existing bounded-concurrency and rejection tests remain valid.

**3. Failure modes this does not cover**

- **External TOCTOU:** another process writes after the final comparison but before replacement. Strict prevention requires all writers to participate in a shared locking/version protocol, or prohibiting automatic in-place replacement.
- OneDrive overwriting a successful app save afterward, remote conflict resolution, or delayed remote updates.
- Crash durability across file data, directory rename, DB persistence, and acknowledgment. Lost acknowledgment requires inspection/reconciliation, not blind retry.
- Rename, unlink, copy-overwrite, graph migration, and recycle operations outside the guarded write endpoint. They need separate protection before claiming graph-wide data safety.
- Serializer/parser bugs that lose already-seen information.
- Unsupported encodings, symlink/hard-link alias races, and unusual filesystem behavior unless explicitly supported and tested.
- A continuously changing external file may never become stable enough to ingest or save. Safety means remaining blocked.

**4. Electron data-safety harness**

Run Playwright against a **graph copy**, with isolated app state. The harness should compare raw bytes, capture write outcomes, and expose deterministic barriers before ingestion, batch flush, final comparison, and acknowledgment. Avoid timing-only sleeps.

Let `A` be the original file, `E` the externally edited bytes, and `U` the proposed user serialization.

| Scenario | Exact expected disk result |
|---|---|
| Offline edit, then reopen | Original path remains exactly `E`; DB ingests `E`. With no required repair, no replacement occurs. |
| External edit while running, automatic save queued | Pause flush; write `E`; resume. Original remains exactly `E`; stale automatic proposal is rejected. |
| External edit while running, user save queued | Original remains exactly `E`; conflict copy equals exactly `U`; DB follows `E` after preservation. |
| ID-repair race across files | Journal references block in page P outside the first 16 reconcile slots. P remains exactly `E` until ingested. A later repair produces the fixture’s expected serialization of `E` with the required ID; it never produces a repair of stale `A`. |
| Second external edit after repair scheduling | Change P to `E2` before comparison. Rejected attempt leaves exactly `E2`; any later repair must derive from `E2`. |
| Offline config edit plus home-page deletion | Pause config ingestion while delete side effects run. Config remains byte-for-byte external `Econfig`; stale config proposals reject. The deleted page stays absent. |
| Several writes inside batching window | A→B→C produces final `C`, with one replacement expecting `A`. |
| Same batch plus external edit | Insert `E` before comparison. Original remains `E`; user-containing batch creates a conflict copy equal to `C`; automatic-only batch does not overwrite. |
| Edits during in-flight write | First save acknowledges B; next descendant may write C expecting B. Insert E between them: original stays E and C becomes a conflict copy when user-originated. |
| Generated destination collision | Pre-create destination with X immediately before creation. Destination stays exactly X; creation reports conflict or chooses a fresh exclusive name. |
| Graph switch with identical UUID/path | A’s pending work never changes B’s files or DB. |
| Formatting-only external edits | Add BOM, change LF to CRLF, add trailing spaces/newline. Each mismatch preserves the exact external bytes. |
| Permission or parse failure | Destination stays unchanged; base remains unverified; no saved acknowledgment. |
| Lost acknowledgment | Inspect disk to establish actual outcome; never replay with an invented base. |

Also include an adversarial test that writes externally **after final comparison**. That test documents the remaining TOCTOU limitation; it must not be presented as guaranteed safe.

**5. Risks to existing behavior**

- Saves can now be refused visibly; conflict-copy handling and accurate dirty-state indicators are essential.
- Automatic ID/config changes may be delayed or abandoned after reconciliation.
- Existing sync, diff, git restore, import, and plugin flows that relied on unconditional overwrite need explicit bases.
- Exact comparison increases conflicts for formatting-only edits intentionally.
- Per-file serialization may increase latency for large pages.
- Atomic replacement can affect inode identity, permissions, and watcher event patterns.
- Repo-aware transaction changes touch many callers; wrappers must capture repo once without retaining late current-repo lookups.
- The guard protects unseen content, but it does not by itself make page serialization preserve all formatting.

---

# Review findings on the reconcile deferral (Codex, 869b4a4)

The commit does **not** meet the stated content-preservation bar. These include remaining pre-existing paths, as requested. All inspection was read-only; no builds or tests were run.

1. **[HIGH] `src/main/frontend/fs/watcher_handler.cljs:277` — The comparison does not protect the actual write.**  
   All target checks finish before repairs are submitted. The worker then batches writes for 1,000 ms (`worker/file.cljs:51,207`). An external edit after a target’s check can therefore be overwritten: `fs/node.cljs:43` writes even when its own comparison detects a mismatch; the mismatch only causes a backup. Held-back repairs retried later have exactly this window, and another reconcile can start after the last `current-run?` check.  
   **Fix:** Carry the reconciled content/version through the write queue and reject conflicting writes at the filesystem boundary. Coordinate checking and replacement against concurrent changes; retain conflicted repairs for reconciliation.

2. **[HIGH] `src/main/frontend/fs/watcher_handler.cljs:80` — Live watcher events still bypass reconcile deferral.**  
   `handle-changed!` invokes `<handle-changed` without `on-id-repair` (`:163`), so a live event immediately calls `set-missing-block-ids!`. While the capped reconcile is processing journals, a watcher event containing a reference to an unreconciled page can still save that page from stale DB content—the original overwrite scenario.  
   **Fix:** Make deferral depend on active reconciliation state for the graph, rather than the caller supplying a callback. Apply the filesystem conflict guard to immediate repairs too.

3. **[HIGH] `src/main/frontend/fs/watcher_handler.cljs:278` — Unknown target paths explicitly bypass validation.**  
   `safe?` accepts every repair with a nil path. This is unsafe even when `clean?` is true: hidden/unsupported files are not recorded as skipped, and “clean” does not establish that an unknown destination was reconciled. Moreover, `worker/file.cljs:136` can allocate a filename for a page without a file, through `transact-file-tx-if-not-exists!`; that function does not check whether the generated destination already exists on disk.  
   **Fix:** Defer unknown-path repairs until their actual destination is resolved and reconciled. Newly allocated destinations need an exclusive creation check.

4. **[HIGH] `src/main/frontend/fs/watcher_handler.cljs:234` — Trimming accepts unreconciled content differences.**  
   Comparing trimmed strings treats leading indentation and trailing whitespace/newlines as reconciled. For example, disk content beginning with spaces and DB content without them passes this check, although the spaces can affect Markdown interpretation. A repair then serializes the DB tree and can erase that disk-only text. Matching the existing filesystem comparison does not satisfy the user’s stricter requirement.  
   **Fix:** Compare exact content against the successfully reconciled snapshot. Any normalization must preserve the original text or be an explicitly authorized transformation.

5. **[HIGH] `src/main/frontend/fs/watcher_handler.cljs:433` — Page deletion can overwrite configuration before configuration is reconciled.**  
   The deletion phase precedes file loading. Deleting the configured home page calls `set-config!` twice (`handler/common/page.cljs:174`). That reads configuration from the DB (`handler/config.cljs:20`) and saves it through `set-file-content!`, which writes to disk. Offline edits to `logseq/config.edn` can therefore be overwritten before the file phase reads them. Missing-ID deferral does not cover this path.  
   **Fix:** Give disk-reconciliation deletes a mode that avoids these writes, or defer their configuration changes until configuration has been reconciled and apply the same conflict protection.

6. **[HIGH] `src/main/frontend/fs/watcher_handler.cljs:285` — The graph check does not bind the repair transaction to that graph.**  
   The property batch accepts no graph argument. `db/transact.cljs:41` builds a request that reads `state/get-current-repo` when invoked, and `worker-call` invokes it later inside `async/go` (`:15`). Switching graphs between validation and request execution can submit operations constructed from graph A to graph B. Shared UUIDs, such as in copied graphs, make unintended modification possible.  
   **Fix:** Capture and pass the graph explicitly through block lookup, transaction construction, worker dispatch, and disk writing. Validate the run generation at dispatch rather than relying only on the earlier check.

7. **[MEDIUM] `src/main/frontend/fs/watcher_handler.cljs:266` — Failed repairs can disappear from the retry queue.**  
   The entire graph entry is removed before planning or saving. The outer catch (`:293`) logs and returns zero without restoring outstanding refs. A failed transaction therefore loses its repairs; the next reconcile normally sees the referring file unchanged and does not collect them again. Likewise, `keep model/get-block-by-uuid` drops currently unresolved refs, including refs whose target failed to load.  
   **Fix:** Retain pending/in-flight refs until confirmed completion. Restore uncompleted refs on failure, and retain unresolved refs when target reconciliation is incomplete. Merge restoration with concurrently collected refs.

8. **[MEDIUM] `src/main/frontend/fs/watcher_handler.cljs:486` — Actual loading failures still do not reliably reach the warning or planner.**  
   `alter-file` catches reparse failures (`handler/file_based/file.cljs:187`), after which `handle-add-and-change!` returns true. Page deletion also catches failures internally (`handler/common/page.cljs:185`). These leave `errors`, `delete-errors`, and `failed-paths` unchanged. The repair comparison can hold back a mismatching known file, but does not update the warning counters. Separately, failed directory enumeration becomes `[nil nil]` (`watcher_handler.cljs:403`), allowing a supposedly clean repair phase—and potentially a success notice—after the read error.  
   **Fix:** Propagate explicit per-file/deletion outcomes; terminate reconciliation on enumeration failure. Derive planner eligibility and notices from those outcomes, including intentional skips.

9. **[MEDIUM] `src/main/frontend/fs/watcher_handler.cljs:497` — Superseded runs can still announce completion.**  
   Notification suppression checks only skipped counters. If a graph switch or newer run occurs after all items have been admitted, both counters can remain zero. The old run then emits a warning or “is loaded” notice despite `current-run?` being false.  
   **Fix:** Require `current-run?` at notification emission, while still clearing the old run’s loading notification.

10. **[MEDIUM] `src/main/frontend/fs/watcher_handler.cljs:290` — The awaited repair transaction has an exception path that never settles.**  
    `db/transact.cljs:13` creates a deferred promise, then invokes the worker request inside an unguarded `async/go`. A synchronous exception there—for example, `state.cljs:48` throwing because the worker disappeared—exits the go block without resolving or rejecting that deferred. The new repair-phase catch cannot catch an indefinitely pending promise, so completion and notification cleanup hang.  
    **Fix:** Catch exceptions inside `worker-call` and reject its deferred on every failure path. Worker shutdown should also settle outstanding requests.

**No LOW findings.** I found no additional concrete arity, unresolved-symbol, or missing-require defect in the added functions.

The new test’s equality assertions are meaningful: an always-run or always-defer planner would fail them. However, it explicitly endorses running unknown-path repairs on clean runs and does not exercise any filesystem, watcher, retry, or overlap protection above. Static inspection found no demonstrated Node-loading blocker; existing Node tests already import much of this handler’s dependency tree, including editor and global-config handlers. Actual loading remains unverified under the no-build/no-test constraint.

---

# Review of the guard MVP (Codex, perf/write-guard 0d99e12, 2026-09-12)

Findings 2, 4, 6, 7, 8 are being fixed on perf/write-guard; 1, 3 and 5 go to
a second arbiter decision (decision-write-guard-2.md).

The branch does **not yet meet the owner’s data-preservation bar**. Findings below are from static review at HEAD `0d99e12`. No files were changed, and no builds or tests were run.

Paths below use `F/ = src/main/frontend/` and `E/ = src/electron/electron/`.

1. **[HIGH] The expected base remains speculative and is not bound to the proposal.**  
   **Location:** `F/handler/file_based/file.cljs:164,274`; `F/worker/file.cljs:126,183`.

   Capturing `original-content` before `alter-file` updates the DB fixes the immediate config bug. It does **not** make that value the last successfully read/written content: earlier writes still advance `:file/content` before disk success. Worker messages still contain only the proposal; `alter-files` supplies their base later.

   Two writes can capture A before the renderer receives its DB update: A→B succeeds, then A→C self-refuses against B. Different outliner operations survive the batch deduplication at worker line 183, so duplicate writes are possible within one flush. Conversely, B→C is safe when B was successfully written and IPC ordering is maintained; advancing the renderer base alone does not guarantee that.

   The more dangerous ordering is a queued proposal derived from A reaching `alter-files` after a refusal’s reparse has installed E. It acquires expected E and can replace E with stale content derived from A. There is no generation check or invalidation of old proposals.

   **Fix:** Bind each proposal to an immutable, confirmed base; serialize writes per file; advance that base only on successful write/ingestion; invalidate queued proposals across refusal/reparse. Coalesce same-file writes regardless of outliner operation.

2. **[HIGH] Conflict copies can be silently deleted or overwritten.**  
   **Location:** `E/handler.cljs:128`; `E/backup_file.cljs:28,56,60,62`.

   Conflict copies use exactly the ordinary backup directory and retention policy. Six later ordinary backups or conflicts can prune the **only copy** of refused user text. Filenames have only timestamp precision to milliseconds, and `writeFileSync` uses replacement mode, so same-millisecond copies for one path can overwrite each other.

   The explicit reparse does await backup completion, which is correct. However, the returned path is not a durable preservation guarantee under this naming and retention policy.

   **Fix:** Use a separate conflict tree, such as `logseq/bak/conflicts/`, outside ordinary pruning traversal. Never automatically prune unresolved conflicts. Create collision-resistant names with `wx` and retry collisions. Merely skipping pruning during conflict creation is insufficient if later ordinary backups still prune that directory.

3. **[HIGH] The reparse can discard edits newer than the conflict copy.**  
   **Location:** `F/fs/node.cljs:59,71`; `F/handler/events.cljs:191`; `F/fs/watcher_handler.cljs:181,185`.

   The copy preserves the already-serialized proposal B. While its backup IPC and subsequent stat/read/worker pull are pending, the user can commit C into the same page. The reparse then resets the page from disk E with `backup? false`; neither C nor current editor text is captured or checked.

   A pending page-save entry contains a page ID, not C’s snapshot. If serialization occurs after the reset, it serializes the replacement state. C can therefore disappear without ever reaching either disk or the conflict copy. Keeping the editor open introduces another possibility: old input can later save into the reparsed page.

   **Fix:** Coordinate recovery per file with an edit generation. Preserve newer committed and editor content before applying disk state, and reject/restart a reparse whose generation changed. Invalidate older queued saves. A notification about preserving B does not establish preservation of C.

4. **[HIGH] A transient missing file can trigger deletion of the file after it returns.**  
   **Location:** `F/fs/watcher_handler.cljs:179,187`; supporting path `F/handler/common/page.cljs:208`.

   Recovery converts `file-exists? = false` directly into a normal unlink event. `file-exists?` also converts **all stat failures** to false (`F/fs.cljs:222`), not just ENOENT.

   The unlink handler calls normal page deletion. Its `after-page-deleted!` continuation checks whether the path exists and, if so, unlinks it. A concrete ordering is: sync client temporarily removes the path → recovery observes absence → sync client restores the external file → page-deletion continuation deletes that restored file. This bypasses the write guard entirely.

   **Fix:** Distinguish ENOENT from access/I/O errors, retry transient absence, and use a disk-origin DB deletion path that never deletes the filesystem path. Recheck absence before committing the DB deletion.

5. **[HIGH] Failed preservation still clears save tracking; no retry or close protection remains.**  
   **Location:** `F/fs/node.cljs:72,78,130`; `F/handler/worker.cljs:18`; `F/worker/db_worker.cljs:840`.

   If conflict-copy creation fails, recovery correctly skips its explicit reparse, but resolves normally. Guarded I/O errors are also swallowed through the supplied error handler. Both paths reach `page-file-saved`, which removes pending requests. Even the renderer’s outer failure branch acknowledges them.

   Consequently, the worker has no outstanding record requiring preservation or retry. A warning does not prevent graph switching, subsequent watcher reparses, or closing the app with user text only in memory.

   **Quit clarification:** I found `file-writes-finished?` used for **graph switching**, not a quit barrier. The inspected close handler destroys the window (`E/window.cljs:82`). Moreover, `file-writes-finished?` already has an inverted cleanup predicate at worker line 832: it removes requests for entities that **exist**. That pre-existing defect would also undermine simply retaining failed requests.

   **Fix:** Propagate structured outcomes through the filesystem wrapper and worker acknowledgment. Track unresolved failures separately from successful writes; require successful conflict preservation before treating a refusal as terminal. Correct the cleanup predicate and make actual close/switch behavior honor unresolved preservation.

6. **[HIGH] `io-error` can follow destructive partial writes, with no recovery copy.**  
   **Location:** `E/write_guard.cljs:89,101`; `F/fs/node.cljs:123`.

   The documented guarantee “Nothing is written unless the result is written” is false. Replacement `writeFileSync` truncates the destination before completing its writes; ENOSPC can leave an empty or partial file and return `io-error`. A failed post-write `statSync` can likewise report failure after a completed write.

   Read-time EISDIR/EACCES correctly avoid replacement. The failure after opening/truncating is the dangerous case, and the guarded path no longer attempts the legacy error-side backup. Combined with finding 5, the proposal can be acknowledged without a complete persistent copy.

   **Fix:** Prepare replacement content in a sibling temporary file before the final synchronous compare-and-rename sequence; preserve failure state and cleanup appropriately. Keep exclusive final-path creation for absent files. Distinguish an uncertain/post-write failure from a refusal that left disk untouched. This still cannot eliminate the accepted external compare/replace race.

7. **[MEDIUM] Refused direct-file writes still execute success-side config/CSS updates.**  
   **Location:** `F/handler/file_based/file.cljs:177,185,189`.

   `alter-file` ignores the write outcome and continues applying the proposed content. A refused `config.edn` write still calls `restore-repo-config!` with that rejected proposal. A refused custom CSS write transacts the proposal again. These continuations race with the detached from-disk reparse, so the rejected version can become active again—or remain active if reparse fails.

   **Fix:** Return and inspect the structured write result. Run proposal-based post-write actions only for `"written"`; on refusal, apply configuration/styles from the successfully ingested disk content and await recovery completion.

8. **[MEDIUM] Newly settled worker rejections escape fire-and-forget callers.**  
   **Location:** `F/db/transact.cljs:30`; callers `F/handler/editor.cljs:1113,1129` and `F/components/content.cljs:49`.

   The settlement implementation handles synchronous throw, rejected promise, encoded `:ex-data`, and success without an apparent double-settlement path. However, copied references/embeds discard the promise from `set-blocks-id!`, and heading-menu callbacks discard the transaction promise. Worker unavailability/rejection now reaches these as unhandled rejections; copy actions continue without confirming ID persistence.

   **Fix:** Await required transactions before their dependent UI action, and attach a user-visible rejection handler at fire-and-forget UI boundaries. I found no legitimate requirement to preserve the old hanging behavior.

**No additional [LOW] findings.**

Other checks:

- Buffer comparison is exact for valid UTF-8, including BOM, CRLF, whitespace and final newlines. Invalid UTF-8 round-trips can refuse unchanged files; they do not silently authorize replacement.
- `{absent: true}` survives the renderer/main bean conversions; `wx` handles creation collisions. Comparison precedes chmod, with no asynchronous yield. Nil-expected Electron callers retain the previous branch.
- Eager reconciliation, the opt-in 16 cap, unknown-path deferral, exact pre-repair string comparison, retry-queue restoration and final superseded-run notice check match the decision. The installed Promesa macro wraps `p/do` in promise context, supporting the restoration catch.
- A from-disk reset itself suppresses save hooks (`F/worker/pipeline.cljs:517`), so I found no unconditional reparse→save loop. Immediate missing-ID repairs and outstanding editor saves can still initiate further writes.
- `electron.write-guard` is available to the node test build: `deps.edn:1` includes `src/electron`, and the namespace requires only Node `fs`. Static inspection does not substitute for the deferred integration scenarios.