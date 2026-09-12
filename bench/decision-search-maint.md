# Memo: search-db maintenance regression (step 4) — Fable arbiter, 2026-09-12

(Codex was unavailable: usage limit until ~15:28.)

## 1. Diagnosis: confirmed, with one refinement

**Timing.** The maint line 16 ms after the 3340 ms slice (`wall 186941`) shows `wal-log-max 3228, wal-ckpt 3228, ckpt-pending 3227, ckpt-ms 0`: the tick found 3227 frames written by that one commit and already backfilled. Same after the 5416 ms slice (`237676`: `ckpt-pending-max 3750`, two checkpoints totalling 87 ms). A normal slice writes ~23 frames. So both commits wrote ~3.2-3.7k pages and the inline autocheckpoint copied them. At the tick's measured 0.164 ms/frame that copy is ~0.5-0.6 s; the other ~2.8-4.8 s is the merge. Commit time scales with rows indexed so far (3325 ms at ~36k rows, 5400 ms at ~60k: ~0.09 ms/row): these are level-2 crises that re-merge essentially the whole index. The 200-380 ms slow slices every ~4.5 s are level-1 crises; the 263 `l0-drops` (every ~0.6 s) are level-0 crises, mostly under 100 ms.

**Source.** `fts5_index.c:4998-5013` (`fts5IndexCrisismerge`): `while(aLevel[iLvl].nSeg >= nCrisis) { fts5IndexMergeLevel(p,&pStruct,iLvl,0); fts5StructurePromote(...); iLvl++; }` — `pnRem=0` means unbounded, and the loop walks upward, so a level-0 fold that pushes level 1 to 16 merges level 1, and so on. It runs on every commit: `sqlite3Fts5IndexSync` (`:6760`) -> `fts5IndexFlush` -> `fts5FlushOneHash`, which calls `fts5IndexAutomerge` then `fts5IndexCrisismerge` (`:5748-5749`). Autocheckpoint: `vdbeapi.c:723` (`doWalCallbacks`, "called after a transaction has been committed") is invoked from `sqlite3Step` at `:860-862` when the COMMIT statement returns `SQLITE_DONE` in autocommit, i.e. inside the same `sqlite3_step`, so inside the slice's commit-ms; `main.c:2461-2477` (`sqlite3WalDefaultHook`) runs a PASSIVE `sqlite3_wal_checkpoint` once `nFrame >= 2000`.

**Refinement: N stuck at 1 is a symptom, not the cause.** A merge step at N=1 still costs ~17 ms median and ~25 WAL frames: its fixed cost exceeds the 10 ms "slow" threshold, so N can only halve. But even a fat tick cannot keep up: the tick contributed 7% of the walk's WAL frames (7238 of 104084), and `fts5IndexMerge` (`:4926-4960`) always picks the level with the most segments, which during a walk is level 0. Merge work is intrinsic (LSM amplification ~ number of levels); the walk emits 24 commits/s and the baseline spent ~40% of its time merging. A 250 ms tick at a 30-50 ms budget supplies 12-20% duty. B is arithmetically out.

## 2. Choice: (A) automerge=4 back on; the tick keeps checkpoints and the idle drain

- Automerge is the only mechanism with work proportional to writes, done in the writer's transaction. Its quantum is `64 x nWork x nLevel` leaves (`:4977-4996`, `FTS5_WORK_UNIT 64`), so levels stay small and crisis never triggers. That is the baseline's p99 269 / max 757 ms, which included inline checkpoints; with tick checkpoints the tail should be at or under that.
- Typing: `nWork` is 0 for nearly every small save, so the per-save path equals automerge=0. The 11-24 -> 7 ms gain was probably the checkpoint move, which stays.
- First open (thousands of per-file commits) is a walk in disguise and gets the same cascade today; A fixes it without special-casing.
- C (switch config around a walk) misses first open and imports; a pressure-driven switch is the follow-up only if typing measurably regresses. D: raising crisismerge only postpones a bigger stall; the 2000-frame fallback is ~15% of the stall, leave it.

Expected: no slice > 1 s; walk ~200-230 s (the 153 s borrowed unfinished merge work plus two whole-index merges; still under the 254 s baseline thanks to tick checkpoints).

## 3. Changes, verification, risks

- `search.cljs:167`: `{"automerge" 4 "crisismerge" 16 "usermerge" 4}`; docstring `:157-166`.
- `search_indexer.cljs:717`: merge steps only when idle and no checkpoint is due (no busy-time steps: they cost 17 ms + 25 frames for one leaf).
- `:96-101`: `maint-merge-n-initial 16`, `maint-merge-n-min 4`, `maint-merge-slow-ms 40`, `maint-merge-fast-ms 10`.
- `l0-drops` now also counts automerge folds.
- Keep `search-wal-autocheckpoint 2000` and `db_worker.cljs:388`.

Next walk should show: `wal-log-max` <= ~800 on every line; `l0-max` <= 8; no busy-line merge steps; `merge-idle-steps` and `ckpt-final` after upsert-done; `merge-n-max` >= 8 in the drain; slow-slice max <= ~800 ms, p99 ~250-300 ms, no multi-second outliers. Typing: second save 7-10 ms with an occasional <= 100 ms save.

Risks: the walk ~30-50% slower than the 153 s run; `l0 drops` rises and needs reinterpretation; quantum size grows with nLevel (~1 s bursts at ~1M rows, same as stock FTS5).
