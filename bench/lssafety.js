#!/usr/bin/env node
// lssafety.js - data-safety end-to-end harness for Logsidian file graphs.
//
// README
// ======
// The regression gate for changes that touch file writes and the reopen
// reconcile. The bar: an external or offline edit must never be lost from its
// file. A copy in logseq/bak/ does NOT count as preserved (it is reported, so a
// failure says whether the edit is recoverable).
//
// Every scenario generates a fresh small graph copy from a template (30
// journals, 10 pages with block refs, a "Race Page" with an id-less block, a
// Home page set as :default-home and favourite in logseq/config.edn), opens it
// once in the app (the "prime" launch: Add new graph -> parse), then acts,
// closes the app and compares every file on disk byte for byte against what
// it must contain. It records every file's sha256 before/after, lists the new
// files in logseq/bak/, diffs unexpected changes and captures the app's
// console errors (LSERR, console.error, pageerror).
//
//   1 offline-edit-reopen   Edits made while the app is closed (3 journals, 2
//     pages: append, change a block, delete a block) survive the reopen
//     reconcile byte for byte, and the app's search finds each new token (the
//     DB really took the edits in, it did not just leave the files alone).
//   2 idrepair-race         The known missing-id repair race: an early journal
//     J gains, offline, a ((uuid)) ref to a block B whose file (P) has no id::,
//     while P is also edited offline. On reopen the reconcile handles J first
//     (journals sort before pages) and the repair writes id:: into P from the
//     DB's OLD copy of P. Passes only if P's offline edit is still in P (an
//     added "id:: <uuid>" line is allowed). B's uuid is only known at runtime
//     (a file cannot name the DB uuid of an id-less block), so the prime launch
//     reads it from the DOM; :default-home is the Race Page so B is loaded in
//     the UI db when the reconcile runs (set-missing-block-ids! reads the UI db).
//   3 live-external-edit    With the app open and idle, a journal is rewritten
//     from outside (write-temp-and-rename, as OneDrive or another PC does). After
//     typing into a different page the external version must still be on disk
//     (and the typed page must hold the editor's text, see "Typed text").
//     Typing into the SAME page right after an external rewrite (race: before
//     the watcher's 2 s awaitWriteFinish fires) and after the UI showed it
//     (settled) is recorded as "policy": outcome merged / backup+overwrite /
//     typed-dropped, with the bak copies; the policy decides pass/fail later.
//   4 config-offline-then-delete-home  config.edn is edited offline (comment +
//     key) and the :default-home page's file is deleted offline. On reopen the
//     page delete runs set-config! (:default-home) from the DB's old config
//     before the reconcile has read the new one. config.edn must still carry
//     the offline edit (the app may change :default-home around it).
//   5 burst-writes          Several saves into one page within ~1 s while an
//     external writer appends to a different file: every typed segment (as the
//     editor had it) is in the page exactly once and the other file is exactly
//     base + appends.
//   6 typing-roundtrip      Typing into a journal, close, reopen: the typed text
//     (as the editor had it) is in the file exactly once, the reopen does not
//     rewrite the file, and no other file changed.
//   7 two-ops-one-flush     Type into block one, Enter, type into the new block,
//     Escape, all within ~1 s (one worker flush carries :save-block and
//     :insert-blocks; the ~1 s includes two editor read-backs). Both segments
//     (as the editor had them) on disk exactly once, no LSGUARD refusal, no new
//     bak file, no "not saved"/conflict notification (fail on each).
//   8 typing-through-external-rewrite  ~3 s of continuous typing with Enter
//     presses into a page while the harness rewrites that page's file from
//     outside mid-way (write-temp-and-rename). FAIL if the external tokens are
//     not on disk at the end (a stale proposal overwrote them). WARN if a token
//     the editor had (read back before each Enter/Escape) is neither in the
//     final file nor in any bak/conflict copy.
//   9 bak-unwritable        (optional, only with --only) logseq/bak is chmod 000,
//     then a same-page refusal is staged. Expects an error notification and the
//     typed text still visible in the UI; permissions are restored in finally.
//
// Write guard (LSGUARD): guarded builds print one console line per guarded
// outcome, LSGUARD {"path","result":"written"|"mismatch"|"exists"|"io-error",
// "copy"}. Every launch captures them (renderer and main-process console). A
// scenario that does not stage a conflict fails on any mismatch/exists/io-error
// record; scenarios that do (3, 8, 9) report them. Each record's conflict copy
// (logseq/bak/conflicts/... on guarded builds) is listed with whether it exists
// and holds the text the scenario expects it to. Older builds print nothing:
// the result then says "guard: absent" when the app wrote something, "guard:
// no-writes" when it did not. Notifications are captured as they appear
// (LSNOTE, a MutationObserver on .ui__notifications-content), so short-lived
// ones count too.
//
// Typed text: each typed segment is read back from the editor (the focused
// TEXTAREA's value, document.activeElement) once it is open and right before
// Escape or Enter leaves it; the segment's text is what that value gained (a
// block Enter just made counts as empty). The file checks of scenarios 3, 5, 6,
// 7 and 8 compare the file against this editor text (once, nothing else
// changed), not against the keys sent: a keystroke that never reached the
// editor (seen: a 'c' dropped mid-word while Enter's new block editor was being
// set up) is not a write-path failure. It is the WARN check "keystrokes lost
// before the editor had them", with both strings (also for an Enter that never
// reached it). When the editor cannot be read (not focused, another block, or
// its text changed under the typing), the intended text is assumed and the
// check details say so. result.json: segments[] {intended, actual, source
// editor|intended, differs, why}; tokens also gets the editor text of a segment
// that differs, so bak/conflict copies are searched for it.
//
// App errors: LSERR/pageerror records count in "app errors" (a warning, lserr
// column); console.error lines are listed. Each launch records when the harness
// asked the app to close (close_requested_wall, on the launch's own clock; add
// the launch's t0_ms for the scenario timeline). Errors at or after it are
// shutdown noise (worker calls still pending while the window is destroyed):
// counted as shutdown_errors (shutdn column), kept per launch under shutdown,
// not warned about.
//
// Statuses: pass / fail / error (harness trouble: the app could not be driven).
// Checks carry a severity: fail (gates), warn (reported), policy, info.
//
// usage: node lssafety.js [--only=a,b] [--out=DIR] [--timeout=SEC]
//                         [--same-page-delay=MS] [--list] [--selftest=DIR]
//   --out       run directory, must be under ~/.cache/lsbench
//               (default ~/.cache/lsbench/safety/<timestamp>)
//   --timeout   per scenario, default 900 s
//   --only      also the way to run optional scenarios (bak-unwritable)
//   --selftest  checks the pure helpers (template, edits, verify, guard, typed
//               segments, shutdown errors) in DIR; no app
// env: LSSAFETY_APP or LSBENCH_APP = app binary (default: the perf worktree's
//      build), LSSAFETY_PLAYWRIGHT = playwright module path.
// Exit code: 0 all pass, 1 any fail/error, 2 usage or refused path.
//
// Launch code copied (not shared) from lsbench.js so lsbench.js stays as is:
// Playwright _electron, --ozone-platform=headless, isolated HOME/XDG dirs and
// --user-data-dir, dialog stub + "Add new graph" for the first open, LSPERF /
// LSUI / LSSEARCH / LSERR console records, thread CPU from /proc (restricted
// here to the launched app's own process tree).
'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');

const HOME = os.homedir();
const BASE = path.join(HOME, '.cache', 'lsbench');
const DEFAULT_APP = path.join(HOME, 'dev/logsidian-perf/static/out/Logseq-linux-x64/Logseq');
const APP = process.env.LSSAFETY_APP || process.env.LSBENCH_APP || DEFAULT_APP;
const SCENARIOS = ['offline-edit-reopen', 'idrepair-race', 'live-external-edit',
  'config-offline-then-delete-home', 'burst-writes', 'typing-roundtrip',
  'two-ops-one-flush', 'typing-through-external-rewrite'];
// run only when named in --only
const OPTIONAL_SCENARIOS = ['bak-unwritable'];
const REFUSALS = ['mismatch', 'exists', 'io-error'];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const errText = (e) => String((e && e.message) || e);
// every await on the app is bounded: a wedged main thread must not hang the run
const within = (p, ms, what) => {
  let t;
  return Promise.race([p, new Promise((_, rej) => {
    t = setTimeout(() => rej(new Error(`${what}: no answer in ${ms} ms`)), ms);
    t.unref();
  })]).finally(() => clearTimeout(t));
};
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const log = (...a) => console.error(`[lssafety ${new Date().toISOString().slice(11, 19)}]`, ...a);

// ---- path safety ---------------------------------------------------------------
// realpath of the deepest existing ancestor plus the rest, so symlinks are seen
function realish(p) {
  let cur = path.resolve(p);
  const rest = [];
  while (!fs.existsSync(cur)) {
    rest.unshift(path.basename(cur));
    const up = path.dirname(cur);
    if (up === cur) break;
    cur = up;
  }
  return path.join(fs.realpathSync(cur), ...rest);
}
const under = (p, dir) => p === dir || p.startsWith(dir + path.sep);
// Refuses ~/OneDrive (Johan's real graph) anywhere in the path or its realpath,
// ~/.claude and the main checkout; with requireBase, anything outside ~/.cache/lsbench.
function assertSafePath(p, { requireBase = true } = {}) {
  const abs = path.resolve(p);
  const real = realish(abs);
  for (const x of [abs, real]) {
    if (/(^|\/)onedrive(\/|$)/i.test(x) || under(x, path.join(HOME, 'OneDrive'))) throw new Error(`refusing a path under ~/OneDrive: ${p}`);
    if (under(x, path.join(HOME, '.claude'))) throw new Error(`refusing a path under ~/.claude: ${p}`);
    if (under(x, path.join(HOME, 'dev', 'logsidian'))) throw new Error(`refusing a path in the main checkout: ${p}`);
  }
  if (requireBase && !under(real, realish(BASE)) || (requireBase && real === realish(BASE))) {
    throw new Error(`refusing a path outside ${BASE}: ${p}`);
  }
  return abs;
}

// ---- template graph ------------------------------------------------------------
const JOURNAL_DAYS = 30;
const PAGES = 10;
const two = (n) => String(n).padStart(2, '0');
const isoDay = (d) => `2026-07-${two(d)}`;
const journalRel = (d) => `journals/2026_07_${two(d)}.md`;
const pageName = (n) => `Page ${two(n)}`;
const pageRel = (name) => `pages/${name}.md`;
// deterministic uuids for blocks that carry id:: in their file
const uuidFor = (n) => `6a5afe00-0000-4000-8000-${String(n).padStart(12, '0')}`;
const RACE_PAGE = 'Race Page';
const RACE_B = 'Race target block B without id';

function configEdn({ home = 'Home' } = {}) {
  return [
    '{:meta/version 1',
    ' :preferred-format :markdown',
    ' :preferred-workflow :now',
    ' :hidden []',
    ' :journal/page-title-format "yyyy-MM-dd"',
    ' :journal/file-name-format "yyyy_MM_dd"',
    ' :file/name-format :triple-lowbar',
    ' :export/bullet-indentation :tab',
    ' :feature/enable-journals? true',
    ' :favorites ["home"]',
    ` :default-home {:page "${home}"}}`,
    '',
  ].join('\n');
}
// Files are written the way the app serialises them (tree->file-content: "- "
// bullets, tab indentation, "  " before a level-1 block's property lines, no
// trailing newline), so a page the app rewrites differs only where it must.
function journalContent(d) {
  const D = isoDay(d);
  const target = ((d - 1) % PAGES) + 1;
  const lines = [
    `- Journal ${D} note alpha`,
    `- Journal ${D} links [[${pageName(target)}]]`,
    `\t- Journal ${D} child block`,
    `- Journal ${D} block to change`,
    `- Journal ${D} block to delete`,
  ];
  if (d % 3 === 0) lines.push(`- Journal ${D} refers to ((${uuidFor(100 + target)}))`);
  return lines.join('\n');
}
function pageContent(n) {
  const N = pageName(n);
  return [
    `- ${N} block one plain text`,
    `- ${N} block two with id`,
    `  id:: ${uuidFor(100 + n)}`,
    `- ${N} block three links [[${pageName((n % PAGES) + 1)}]]`,
    `\t- ${N} child block`,
    `- ${N} block to change`,
    `- ${N} block to delete`,
    `- ${N} block four repair candidate without id`,
  ].join('\n');
}
function makeTemplate({ home = 'Home' } = {}) {
  const files = { 'logseq/config.edn': configEdn({ home }) };
  for (let d = 1; d <= JOURNAL_DAYS; d++) files[journalRel(d)] = journalContent(d);
  for (let n = 1; n <= PAGES; n++) files[pageRel(pageName(n))] = pageContent(n);
  files[pageRel('Home')] = ['- Safety graph home page', `- Start at [[${pageName(1)}]] or [[${isoDay(1)}]]`].join('\n');
  files[pageRel(RACE_PAGE)] = ['- Race page intro block', `- ${RACE_B}`, '- Race page tail block'].join('\n');
  return { files, home };
}
function writeFiles(dir, files) {
  for (const [rel, content] of Object.entries(files)) {
    const abs = path.join(dir, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content);
  }
}

// ---- edits (pure string helpers) -----------------------------------------------
function appendBlock(content, text) {
  return `${content}${!content || content.endsWith('\n') ? '' : '\n'}- ${text}`;
}
function topLevelIndex(lines, prefix) {
  const hits = [];
  lines.forEach((l, i) => { if (l.startsWith(`- ${prefix}`)) hits.push(i); });
  if (hits.length !== 1) throw new Error(`expected one block starting "${prefix}", found ${hits.length}`);
  return hits[0];
}
function replaceBlockLine(content, prefix, newText) {
  const lines = content.split('\n');
  lines[topLevelIndex(lines, prefix)] = `- ${newText}`;
  return lines.join('\n');
}
// removes a top-level block with its property lines and children
function deleteBlock(content, prefix) {
  const lines = content.split('\n');
  const i = topLevelIndex(lines, prefix);
  let j = i + 1;
  while (j < lines.length && lines[j] !== '' && !lines[j].startsWith('- ')) j++;
  lines.splice(i, j - i);
  return lines.join('\n');
}
const countOf = (s, t) => (t ? s.split(t).length - 1 : 0);

// ---- snapshots and diffs -------------------------------------------------------
function walk(dir, rel = '', out = []) {
  let ents = [];
  try { ents = fs.readdirSync(path.join(dir, rel), { withFileTypes: true }); } catch (_) { return out; }
  for (const e of ents) {
    const r = rel ? `${rel}/${e.name}` : e.name;
    if (e.isDirectory()) walk(dir, r, out);
    else if (e.isFile()) out.push(r);
  }
  return out;
}
function snapshotDir(dir) {
  const m = new Map();
  for (const r of walk(dir).sort()) {
    try {
      const buf = fs.readFileSync(path.join(dir, r));
      m.set(r, { sha: sha256(buf), size: buf.length, text: buf.toString('utf8') });
    } catch (_) { /* vanished meanwhile */ }
  }
  return m;
}
function classify(rel) {
  if (rel.startsWith('logseq/bak/')) return 'bak';
  if (rel.startsWith('logseq/.recycle/')) return 'recycle';
  if (rel.startsWith('logseq/version-files/')) return 'version';
  if (rel.split('/').some((s) => s.startsWith('.'))) return 'hidden';
  return 'graph';
}
function diffSnapshots(a, b) {
  const out = { added: [], removed: [], changed: [] };
  for (const [r, x] of b) {
    if (!a.has(r)) out.added.push(r);
    else if (a.get(r).sha !== x.sha) out.changed.push(r);
  }
  for (const r of a.keys()) if (!b.has(r)) out.removed.push(r);
  return out;
}
// LCS line diff, changed lines with one line of context, capped
function lineDiff(a, b, cap = 40) {
  const x = (a || '').split('\n');
  const y = (b || '').split('\n');
  if (x.length * y.length > 4e6) return [`(too large to diff: ${x.length} vs ${y.length} lines)`];
  const n = x.length; const m = y.length;
  const L = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) L[i][j] = x[i] === y[j] ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
  const ops = [];
  let i = 0; let j = 0;
  while (i < n || j < m) {
    if (i < n && j < m && x[i] === y[j]) { ops.push([' ', x[i]]); i++; j++; }
    else if (i < n && (j === m || L[i + 1][j] >= L[i][j + 1])) { ops.push(['-', x[i]]); i++; }
    else { ops.push(['+', y[j]]); j++; }
  }
  const keep = ops.map((o, k) => o[0] !== ' ' || (ops[k - 1] && ops[k - 1][0] !== ' ') || (ops[k + 1] && ops[k + 1][0] !== ' '));
  const lines = ops.filter((_, k) => keep[k]).map(([s, l]) => `${s}${JSON.stringify(l).slice(1, -1)}`);
  return lines.length > cap ? [...lines.slice(0, cap), `... ${lines.length - cap} more`] : lines;
}
const ID_LINE = /^\s*id:: [0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\s*$/i;
// drops "id:: <uuid>" lines of `actual` that `reference` does not have (the
// app's missing-id repair adds them; that is not a loss)
function stripAddedIdLines(actual, reference) {
  const ref = new Set(reference.split('\n').filter((l) => ID_LINE.test(l)).map((l) => l.trim()));
  return actual.split('\n').filter((l) => !(ID_LINE.test(l) && !ref.has(l.trim()))).join('\n');
}
const bakOf = (rel, bakNew) => {
  const stem = rel.replace(/\.[^./]+$/, '');
  return bakNew.filter((b) => b.path.startsWith(`logseq/bak/${stem}/`));
};

// ---- verification --------------------------------------------------------------
// Expectation kinds (path relative to the graph):
//   exact    {content, tokens}  file must equal content (added id:: lines -> warn)
//   contains {fragments}        every fragment must be in the file
//   absent   {severity}         file must not exist
//   typed    {base, target, typed:[..], tokens:[..], inserted?, note?}  typed page:
//            each token once, the target block line = base line + typed, every
//            other line kept; typed/tokens are the editor's text (typedFromSegments)
//   policy / ignore             excluded from the untouched-files check
function checkExpectation(e, after, bakNew) {
  const a = after.get(e.path);
  const sev = e.severity || 'fail';
  const name = `${e.kind} ${e.path}${e.why ? ` (${e.why})` : ''}`;
  const baks = bakOf(e.path, bakNew);
  if (e.kind === 'absent') {
    return { name, severity: sev, ok: !a, detail: a ? 'file exists' : 'absent' };
  }
  if (e.kind === 'exact') {
    if (!a) return { name, severity: sev, ok: false, detail: 'file missing', in_bak: baks.some((b) => b.text === e.content) ? 'exact' : 'no' };
    if (a.text === e.content) return { name, severity: sev, ok: true, detail: 'byte-identical' };
    if (stripAddedIdLines(a.text, e.content) === e.content) {
      return { name, severity: sev, ok: true, detail: 'identical except id:: lines the app added', warn: true };
    }
    const lost = (e.tokens || []).filter((t) => !a.text.includes(t));
    const inBak = baks.some((b) => b.text === e.content) ? 'exact'
      : (lost.length && baks.some((b) => lost.every((t) => b.text.includes(t))) ? 'tokens' : 'no');
    return { name, severity: sev, ok: false, detail: lost.length ? `edit lost (missing ${lost.join(', ')})` : 'content differs', in_bak: inBak, diff: lineDiff(e.content, a.text) };
  }
  if (e.kind === 'contains') {
    if (!a) return { name, severity: sev, ok: false, detail: 'file missing' };
    const missing = e.fragments.filter((f) => !a.text.includes(f));
    return { name, severity: sev, ok: !missing.length, detail: missing.length ? `missing ${JSON.stringify(missing)}` : 'all fragments present',
      exact: e.content != null ? a.text === e.content : undefined,
      in_bak: missing.length ? (baks.some((b) => missing.every((f) => b.text.includes(f))) ? 'yes' : 'no') : undefined,
      diff: e.content != null && a.text !== e.content ? lineDiff(e.content, a.text) : undefined };
  }
  if (e.kind === 'typed') {
    if (!a) return { name, severity: sev, ok: false, detail: 'file missing' };
    const counts = Object.fromEntries(e.tokens.map((t) => [t, countOf(a.text, t)]));
    const norm = (s) => s.split('\n').filter((l) => l.trim() && !ID_LINE.test(l)).map((l) => l.trim());
    const baseLines = norm(e.base);
    const idx = baseLines.findIndex((l) => l.replace(/^-\s*/, '').startsWith(e.target));
    const want = baseLines.slice();
    // inserted: whole lines expected right after the target block (Enter makes a sibling)
    const ins = e.inserted || [];
    if (idx >= 0) { want[idx] = (baseLines[idx] + e.typed.join('')).trim(); want.splice(idx + 1, 0, ...ins.map((l) => l.trim())); }
    const got = norm(a.text);
    const semantic = idx >= 0 && JSON.stringify(want) === JSON.stringify(got);
    const raw = e.base.split('\n');
    const ri = raw.findIndex((l) => l.replace(/^\s*-\s*/, '').startsWith(e.target));
    if (ri >= 0) { raw[ri] += e.typed.join(''); raw.splice(ri + 1, 0, ...ins); }
    const once = Object.values(counts).every((c) => c === 1);
    return { name, severity: sev, ok: once && semantic, token_counts: counts,
      detail: (!once ? 'typed text missing or duplicated' : (semantic ? 'typed once, other lines kept' : 'other lines changed')) + (e.note ? ` (${e.note})` : ''),
      byte_exact: a.text === raw.join('\n'), diff: semantic ? undefined : lineDiff(want.join('\n'), got.join('\n')) };
  }
  return { name, severity: 'info', ok: true, detail: e.kind };
}
function verify(before, after, expectations, { bakNew = [] } = {}) {
  const checks = expectations.map((e) => checkExpectation(e, after, bakNew));
  const covered = new Set(expectations.map((e) => e.path));
  let identical = 0;
  for (const [rel, b] of before) {
    if (classify(rel) !== 'graph' || covered.has(rel)) continue;
    const a = after.get(rel);
    if (!a) checks.push({ name: `untouched ${rel}`, severity: 'fail', ok: false, detail: 'file removed' });
    else if (a.sha !== b.sha) {
      const onlyIds = stripAddedIdLines(a.text, b.text) === b.text;
      checks.push({ name: `untouched ${rel}`, severity: onlyIds ? 'warn' : 'fail', ok: false,
        detail: onlyIds ? 'only id:: lines added' : 'unexpected change', diff: lineDiff(b.text, a.text) });
    } else identical++;
  }
  for (const rel of after.keys()) {
    if (classify(rel) === 'graph' && !before.has(rel) && !covered.has(rel)) {
      checks.push({ name: `new file ${rel}`, severity: 'warn', ok: false, detail: 'created by the app' });
    }
  }
  checks.push({ name: 'untouched files byte-identical', severity: 'info', ok: true, detail: `${identical} files` });
  return checks;
}
function newBaks(before, after, tokens = []) {
  const out = [];
  for (const [rel, x] of after) {
    if (classify(rel) !== 'bak') continue;
    if (before.has(rel) && before.get(rel).sha === x.sha) continue;
    out.push({ path: rel, sha: x.sha, size: x.size, text: x.text, tokens: tokens.filter((t) => x.text.includes(t)) });
  }
  return out;
}
function samePageOutcome({ finalText, extTokens, revertedText, typedToken, bakTexts }) {
  const ext = !!finalText && extTokens.every((t) => finalText.includes(t)) && !(revertedText && finalText.includes(revertedText));
  const typed = !!finalText && finalText.includes(typedToken);
  const extInBak = bakTexts.some((b) => extTokens.every((t) => b.includes(t)));
  const outcome = ext && typed ? 'merged'
    : !ext && typed ? (extInBak ? 'backup+overwrite' : 'overwrite-without-backup')
      : ext && !typed ? 'typed-dropped (refused or superseded)' : 'both-lost';
  return { outcome, external_survived: ext, typed_survived: typed, external_in_bak: extInBak };
}

// ---- typed segments (keystroke delivery) ---------------------------------------
// pre/post: the editor's value before the typing ('' for the block Enter just
// made) and App.editorValue() ({focused, value, block} or {focused: false})
// read right before leaving it. The segment's text is what post gained over
// pre. enterFrom: the editor read right before that Enter; if post is still
// that block, Enter never reached the editor (enter_missed) and pre is its
// value. Unreadable editor -> the intended text, source 'intended', why.
function segmentActual(intended, pre, post, { block = null, enterFrom = null } = {}) {
  const r = { intended, actual: intended, source: 'intended', differs: false };
  if (!post || !post.focused) {
    r.why = `editor not readable before leaving it (${(post && post.error) || 'no block editor focused'}): intended text assumed`;
    return r;
  }
  r.block = post.block;
  if (enterFrom && enterFrom.focused && post.block && post.block === enterFrom.block) { pre = enterFrom.value; r.enter_missed = true; }
  if (block && post.block !== block) { r.why = `the focused editor is block ${post.block}, not ${block}: intended text assumed`; return r; }
  if (pre == null) { r.why = 'editor value before the typing unknown: intended text assumed'; return r; }
  if (!post.value.startsWith(pre)) {
    r.why = `editor text changed under the typing (before ${JSON.stringify(pre.slice(-80))}, at leave ${JSON.stringify(post.value.slice(-120))}): intended text assumed`;
    return r;
  }
  r.actual = post.value.slice(pre.length);
  r.source = 'editor';
  r.differs = r.actual !== intended;
  return r;
}
// One editor session with several segments (scenario 8): the session's editor
// text is split over them by whitespace-led chunks. If the chunk counts differ
// (a lost space or segment) every segment is marked as differing (editor_text:
// the session's text); one whose text is not in it keeps the intended text.
function sessionSegments(intended, pre, post, opts = {}) {
  const whole = segmentActual(intended.join(''), pre, post, opts);
  const seg = (s, k, x) => ({ intended: s, actual: s, source: whole.source, differs: false,
    ...(k === 0 && whole.enter_missed ? { enter_missed: true } : {}), ...x });
  if (whole.source !== 'editor') return intended.map((s, k) => seg(s, k, { why: whole.why }));
  const chunks = (t) => t.match(/\s*\S+/g) || [];
  const got = chunks(whole.actual);
  const per = intended.map((s) => chunks(s).length);
  if (per.reduce((a, b) => a + b, 0) === got.length) {
    let i = 0;
    return intended.map((s, k) => { const a = got.slice(i, i + per[k]).join(''); i += per[k]; return seg(s, k, { actual: a, differs: a !== s }); });
  }
  return intended.map((s, k) => seg(s, k, { differs: true, editor_text: whole.actual,
    ...(whole.actual.includes(s.trim()) ? {} : { source: 'intended',
      why: `editor text ${JSON.stringify(whole.actual.slice(0, 200))} does not split into the ${intended.length} typed segments: intended text assumed` }) }));
}
// WARN, never a write-path failure: segments whose editor text differs from the keys sent
function keystrokeCheck(segs) {
  const lost = segs.filter((s) => s.differs || s.enter_missed);
  const assumed = segs.filter((s) => s.source !== 'editor');
  const list = lost.slice(0, 8).map((s) => `${s.enter_missed ? 'the Enter before it never reached the editor; ' : ''}typed ${JSON.stringify(s.intended)}, editor had ${
    s.editor_text != null ? `(whole session) ${JSON.stringify(s.editor_text.slice(0, 200))}` : JSON.stringify(s.actual)}`);
  if (lost.length > 8) list.push(`... ${lost.length - 8} more`);
  const parts = [lost.length
    ? `${lost.length}/${segs.length} segment(s): ${list.join('; ')} (keystroke delivery, not the write path: the file is checked against the editor text)`
    : `${segs.length - assumed.length}/${segs.length} segment(s) read back from the editor as typed`];
  if (assumed.length) parts.push(`${assumed.length} not read back, intended text assumed: ${[...new Set(assumed.map((s) => s.why))].slice(0, 3).join('; ')}`);
  return { name: 'keystrokes lost before the editor had them', severity: 'warn', ok: !lost.length, detail: parts.join('; '),
    segments: { total: segs.length, read_back: segs.length - assumed.length, assumed: assumed.length, differing: lost.length } };
}
// typed-expectation parts: the editor's text of each segment, tokens = each
// non-empty segment trimmed (to be in the file exactly once)
function typedFromSegments(segs) {
  const assumed = segs.filter((s) => s.source !== 'editor').length;
  return { typed: segs.map((s) => s.actual), tokens: segs.map((s) => s.actual.trim()).filter(Boolean),
    note: assumed ? `${assumed}/${segs.length} segment(s) not read back from the editor, intended text assumed` : undefined };
}
// occurrences of t not followed by a digit: a token that lost its last
// character ("qsw..n1") must not match a later one ("qsw..n10z")
const countToken = (s, t) => (t ? (s.match(new RegExp(`${t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![0-9])`, 'g')) || []).length : 0);

// errors (wall on the launch's clock) at or after its close request are shutdown noise
function splitShutdown(errors, closeWall) {
  const run = []; const shutdown = [];
  for (const e of errors) (closeWall != null && e.wall >= closeWall ? shutdown : run).push(e);
  return { run, shutdown };
}

// ---- write guard (LSGUARD) -----------------------------------------------------
function parseGuardLine(t) {
  if (!t.startsWith('LSGUARD ')) return null;
  try { const o = JSON.parse(t.slice(8)); return o && typeof o === 'object' ? o : { raw: t.slice(0, 300) }; } catch (_) { return { raw: t.slice(0, 300) }; }
}
// a path the guard printed (absolute or graph-relative) -> graph-relative, or null if outside
function graphRel(p, graph) {
  if (!p || typeof p !== 'string') return null;
  if (!path.isAbsolute(p)) return p.replace(/^\.\//, '');
  const abs = path.resolve(p);
  return abs.startsWith(graph + path.sep) ? abs.slice(graph.length + 1) : null;
}
// status: present (any LSGUARD line) / absent (the app wrote, no line) / no-writes
function guardReport(records, { appWrote = false } = {}) {
  const counts = {};
  for (const r of records) { const k = r.result || 'unparsed'; counts[k] = (counts[k] || 0) + 1; }
  const refusals = records.filter((r) => REFUSALS.includes(r.result));
  const status = records.length ? 'present' : (appWrote ? 'absent' : 'no-writes');
  return { status, counts, refusals };
}
// every record naming a copy: does it exist in `after`, which tokens it holds,
// and whether it holds the tokens expected for that page (expectByRel[rel])
function conflictCopies(records, after, graph, tokens = [], expectByRel = {}) {
  const out = [];
  for (const r of records) {
    if (!r.copy) continue;
    const rel = graphRel(r.copy, graph);
    const page = graphRel(r.path, graph);
    const a = rel ? after.get(rel) : null;
    const expected = (page && expectByRel[page]) || null;
    out.push({ launch: r.launch, result: r.result, page, copy: r.copy, rel, exists: !!a,
      tokens: a ? tokens.filter((t) => a.text.includes(t)) : [],
      expected, holds_expected: expected ? !!a && expected.every((t) => a.text.includes(t)) : null });
  }
  return out;
}

// ---- disk activity -------------------------------------------------------------
function dirSignature(dir) {
  const h = crypto.createHash('sha1');
  for (const r of walk(dir).sort()) {
    try { const st = fs.statSync(path.join(dir, r)); h.update(`${r}\0${st.size}\0${st.mtimeMs}\n`); } catch (_) {}
  }
  return h.digest('hex');
}
// true once nothing under dir changed (names, sizes, mtimes) for ms
async function diskQuiet(dir, ms, maxWait) {
  let sig = dirSignature(dir);
  let since = Date.now();
  const end = Date.now() + maxWait;
  while (Date.now() < end) {
    await sleep(300);
    const s = dirSignature(dir);
    if (s !== sig) { sig = s; since = Date.now(); } else if (Date.now() - since >= ms) return true;
  }
  return false;
}
async function waitFileContains(abs, tokens, maxMs) {
  const end = Date.now() + maxMs;
  while (Date.now() < end) {
    try { const t = fs.readFileSync(abs, 'utf8'); if (tokens.every((x) => t.includes(x))) return true; } catch (_) {}
    await sleep(250);
  }
  return false;
}

// ---- /proc helpers -------------------------------------------------------------
function procStat(pid) {
  const st = fs.readFileSync(`/proc/${pid}/stat`, 'utf8');
  return st.slice(st.lastIndexOf(')') + 2).split(' '); // [0]=state [1]=ppid ... [11]=utime [12]=stime
}
function descendants(root) {
  const kids = new Map();
  for (const p of fs.readdirSync('/proc')) {
    if (!/^\d+$/.test(p)) continue;
    try { const pp = procStat(p)[1]; if (!kids.has(pp)) kids.set(pp, []); kids.get(pp).push(p); } catch (_) {}
  }
  const out = [];
  const q = [String(root)];
  while (q.length) { const p = q.shift(); for (const c of kids.get(p) || []) { out.push(c); q.push(c); } }
  return out;
}
const exeIs = (pid, exe) => { try { return fs.readlinkSync(`/proc/${pid}/exe`) === exe; } catch (_) { return false; } };

// ---- app driver ----------------------------------------------------------------
let electronMod = null;
function electron() {
  if (electronMod) return electronMod;
  const candidates = [process.env.LSSAFETY_PLAYWRIGHT,
    path.join(HOME, 'dev/logsidian-perf/node_modules/playwright'),
    path.join(HOME, 'dev/logsidian/node_modules/playwright')].filter(Boolean); // last: read-only use
  for (const c of candidates) { try { electronMod = require(c)._electron; return electronMod; } catch (_) {} }
  throw new Error('playwright not found (set LSSAFETY_PLAYWRIGHT)');
}
const liveApps = new Set();

class App {
  constructor(ctx, label) {
    this.ctx = ctx;
    this.label = label;
    this.t0 = Date.now();
    this.records = [];
    this.lserr = [];
    this.guard = [];
    this.notes = [];
    this.consoleErrors = [];
    this.markers = {};
    this.lastActivity = Date.now();
    this.prevTicks = new Map();
    this.closed = false;
    // this launch's wall (ms since t0) when the harness asked the app to close
    this.closeRequestedWall = null;
    // t0 on the scenario timeline: wall + t0_ms = timeline t
    this.info = { label, t0_ms: this.t0 - ctx.t0 };
  }
  wall() { return Date.now() - this.t0; }

  async launch({ openDialogWith } = {}) {
    if (this.ctx.aborted) throw new Error('scenario aborted');
    const prof = this.ctx.profile;
    const home = path.join(prof, 'home');
    fs.mkdirSync(home, { recursive: true });
    this.consoleLog = fs.createWriteStream(path.join(this.ctx.out, `${this.label}-console.log`));
    this.app = await within(electron().launch({
      executablePath: APP,
      args: ['--no-sandbox', '--ozone-platform=headless', `--user-data-dir=${prof}/chromium`],
      env: { ...process.env, HOME: home, XDG_CONFIG_HOME: `${home}/.config`, XDG_DATA_HOME: `${home}/.local/share`,
        XDG_CACHE_HOME: `${home}/.cache`, XDG_STATE_HOME: `${home}/.local/state`, ELECTRON_ENABLE_LOGGING: '1' },
      timeout: 120000,
    }), 150000, 'electron.launch');
    liveApps.add(this);
    this.pid = this.app.process().pid;
    this.app.on('window', (w) => this.attach(w));
    for (const w of this.app.windows()) this.attach(w);
    // main-process console (Playwright >= 1.42): LSGUARD only, should a build log it there
    try {
      this.app.on('console', (m) => {
        const g = parseGuardLine(m.text());
        if (g) { this.guard.push({ wall: this.wall(), src: 'main', ...g }); this.lastActivity = Date.now(); }
      });
    } catch (_) {}
    if (openDialogWith) {
      await within(this.app.evaluate(({ dialog }, g) => {
        dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [g] });
      }, openDialogWith), 30000, 'stub dialog');
    }
    // the splash screen is its own window; wait for the one that renders the app
    const end = Date.now() + 120000;
    while (!this.page && Date.now() < end) {
      for (const w of this.app.windows()) {
        if (await within(w.$('#app-container, #main-container, .cp__sidebar-main-layout'), 5000, 'find window').catch(() => null)) this.page = w;
      }
      if (!this.page) await sleep(500);
    }
    if (!this.page) throw new Error('app window never rendered');
    this.info.window_ms = this.wall();
    this.page.on('pageerror', (e) => this.lserr.push({ wall: this.wall(), kind: 'pageerror', msg: errText(e).slice(0, 1500) }));
    this.page.on('crash', () => this.lserr.push({ wall: this.wall(), kind: 'crash', msg: 'renderer crashed' }));
    await this.observeUi();
    this.cpuTimer = setInterval(() => this.sampleCpu(), 1000);
    this.cpuTimer.unref();
    this.ctx.step(`${this.label}: window after ${this.info.window_ms} ms`);
    return this;
  }

  attach(w) {
    w.on('console', (m) => {
      const t = m.text();
      this.consoleLog.write(`${this.wall()}\t${m.type()}\t${t.slice(0, 600)}\n`);
      if (t.startsWith('LSPERF ') || t.startsWith('LSUI ')) {
        const ui = t.startsWith('LSUI ');
        try { this.records.push({ wall: this.wall(), ...(ui ? { event: 'ui' } : {}), ...JSON.parse(t.slice(ui ? 5 : 7)) }); } catch (_) {}
        this.lastActivity = Date.now();
      } else if (t.startsWith('LSSEARCH ')) {
        try { this.records.push({ wall: this.wall(), event: 'search', ...JSON.parse(t.slice(9)) }); } catch (_) {}
      } else if (t.startsWith('LSGUARD ')) {
        this.guard.push({ wall: this.wall(), ...parseGuardLine(t) });
        this.lastActivity = Date.now();
      } else if (t.startsWith('LSNOTE ')) {
        try { this.notes.push({ wall: this.wall(), ...JSON.parse(t.slice(7)) }); } catch (_) {}
      } else if (t.startsWith('LSERR ')) {
        try { this.lserr.push({ wall: this.wall(), ...JSON.parse(t.slice(6)) }); } catch (_) { this.lserr.push({ wall: this.wall(), msg: t.slice(0, 1500) }); }
      } else if (m.type() === 'error') {
        if (this.consoleErrors.length < 100) this.consoleErrors.push({ wall: this.wall(), msg: t.slice(0, 600) });
      }
      if (t.includes('initial-watcher')) this.markers.initialWatcher = this.markers.initialWatcher || Date.now();
      if (/Delete page:|Bak Error|backup|Write file failed|Write to the file|restore-graph!|graph\/added/i.test(t)) {
        this.records.push({ wall: this.wall(), log: t.slice(0, 300) });
      }
    });
  }

  async observeUi() {
    // release builds log nothing on their own: surface swallowed errors (LSERR)
    // and renderer long tasks (LSUI), as lsbench.js does
    await within(this.page.evaluate(() => {
      if (window.__lsui) return;
      window.__lsui = true;
      const show = (kind, x) => {
        const msg = x && (x.stack || x.message) ? `${x.message || ''}\n${x.stack || ''}` : String(x);
        console.log('LSERR ' + JSON.stringify({ kind, msg: msg.slice(0, 1500) }));
      };
      window.addEventListener('unhandledrejection', (e) => show('unhandledrejection', e.reason));
      window.addEventListener('error', (e) => show('error', e.error || e.message));
      // notifications (ui.cljs: .ui__notifications-content.notification-<status>),
      // logged once each as they appear, so short-lived ones are seen too
      const seen = new WeakSet();
      const scan = () => {
        for (const el of document.querySelectorAll('.ui__notifications-content[class*="notification-"]')) {
          const text = (el.innerText || '').trim();
          if (seen.has(el) || !text) continue;
          seen.add(el);
          const m = /notification-(\w+)/.exec(el.className);
          console.log('LSNOTE ' + JSON.stringify({ status: m ? m[1] : 'info', text: text.slice(0, 600) }));
        }
      };
      new MutationObserver(scan).observe(document.body, { childList: true, subtree: true, characterData: true });
      scan();
      new PerformanceObserver((list) => {
        for (const e of list.getEntries()) {
          if (e.duration >= 100) console.log('LSUI ' + JSON.stringify({ dur: Math.round(e.duration), start: Math.round(e.startTime) }));
        }
      }).observe({ type: 'longtask', buffered: true });
    }), 30000, 'observeUi').catch(() => {});
  }

  // Thread CPU of this app's renderer (db worker = DedicatedWorker, UI = Logseq):
  // a worker inside one long synchronous task reports nothing until it ends,
  // so silence alone can mean busy. Only this app's own process tree counts.
  sampleCpu() {
    try {
      const pct = { DedicatedWorker: 0, Logseq: 0 };
      const now = Date.now();
      for (const pid of descendants(this.pid)) {
        if (!exeIs(pid, APP)) continue;
        let cmd = '';
        try { cmd = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8'); } catch (_) { continue; }
        if (!cmd.includes('--type=renderer')) continue;
        for (const tid of fs.readdirSync(`/proc/${pid}/task`)) {
          try {
            const name = fs.readFileSync(`/proc/${pid}/task/${tid}/comm`, 'utf8').trim();
            if (!(name in pct)) continue;
            const st = fs.readFileSync(`/proc/${pid}/task/${tid}/stat`, 'utf8');
            const rest = st.slice(st.lastIndexOf(')') + 2).split(' ');
            const ticks = Number(rest[11]) + Number(rest[12]);
            const last = this.prevTicks.get(`${pid}/${tid}`);
            this.prevTicks.set(`${pid}/${tid}`, [ticks, now]);
            if (last && now > last[1]) pct[name] += (100 * (ticks - last[0]) / 100) / ((now - last[1]) / 1000);
          } catch (_) {}
        }
      }
      if (pct.DedicatedWorker > 50 || pct.Logseq > 50) {
        this.lastActivity = Date.now();
        this.records.push({ wall: this.wall(), event: 'cpu', worker: Math.round(pct.DedicatedWorker), ui: Math.round(pct.Logseq) });
      }
    } catch (_) {}
  }

  async quiet(ms, maxWait) {
    const end = Date.now() + maxWait;
    while (Date.now() < end) { if (Date.now() - this.lastActivity > ms) return true; await sleep(250); }
    return false;
  }

  async shot(name) {
    await within(this.page.screenshot({ path: path.join(this.ctx.out, `${this.label}-${name}.png`) }), 15000, 'screenshot').catch(() => {});
  }

  async escape() {
    for (let i = 0; i < 2; i++) { await within(this.page.keyboard.press('Escape'), 5000, 'Escape').catch(() => {}); await sleep(120); }
  }

  // First open of a fresh profile: stubbed folder dialog + "Add new graph"
  // (repo.cljs; the All graphs page is the fallback), then parse + quiet.
  async openGraph(maxMs = 300000) {
    const page = this.page;
    await sleep(5000);
    const clickText = (t) => within(page.evaluate((t) => {
      const el = [...document.querySelectorAll('button, a, [role=menuitem], [role=button]')]
        .find((e) => (e.innerText || '').trim().includes(t));
      if (!el) return false;
      el.click();
      return true;
    }, t), 10000, 'click text').catch(() => false);
    let clicked = false;
    for (const attempt of ['menu', 'graphs']) {
      if (attempt === 'graphs') await within(page.evaluate(() => { location.hash = '#/graphs'; }), 10000, 'goto graphs').catch(() => {});
      await sleep(1500);
      if (await clickText('Add new graph')) { clicked = attempt; break; }
      if (attempt === 'menu') {
        await within(page.evaluate(() => document.querySelector('.cp__graphs-selector a.item')?.click()), 10000, 'graph menu').catch(() => {});
        await sleep(800);
        if (await clickText('Add new graph')) { clicked = attempt; break; }
      }
    }
    const r = { clicked };
    if (!clicked) { await this.shot('open-failed'); return r; }
    // container.cljs shows "Parsing files n/total" until parsing ends
    const parsing = () => within(page.evaluate(() => document.body.innerText.includes('Parsing files')), 10000, 'parsing?').catch(() => false);
    const deadline = Date.now() + maxMs;
    const s0 = Date.now();
    let seen = false;
    while (!seen && Date.now() - s0 < 60000) { seen = await parsing(); if (!seen) await sleep(250); }
    r.parse_seen = seen;
    while (seen && Date.now() < deadline && (await parsing())) await sleep(500);
    r.parse_end_ms = this.wall();
    r.quiet = await this.quiet(8000, Math.max(20000, deadline - Date.now()));
    r.disk_quiet = await diskQuiet(this.ctx.graph, 3000, 60000);
    await this.shot('opened');
    this.info.open = r;
    this.ctx.step(`${this.label}: graph opened ${JSON.stringify(r)}`);
    return r;
  }

  // Reopen: the profile restores the graph; the reconcile (load-graph-files!)
  // then reads every file. Done when the build's LSPERF reconcile line arrives
  // (perf/step4-reconcile), else when the app went quiet (thread CPU, LSPERF,
  // LSUI) for long enough, then once the graph dir stopped changing.
  async waitReconcile(maxMs = 180000) {
    const start = Date.now();
    const deadline = start + maxMs;
    let how = null;
    while (Date.now() < deadline) {
      if (this.records.some((r) => r.event === 'reconcile')) { how = 'lsperf'; break; }
      const q = Date.now() - this.lastActivity;
      if (this.markers.initialWatcher && q > 8000 && Date.now() - this.markers.initialWatcher > 8000) { how = 'marker+quiet'; break; }
      if (Date.now() - start > 20000 && q > 10000) { how = 'quiet'; break; }
      await sleep(500);
    }
    // deferred id repairs and page deletes write after the line: settle on disk
    await sleep(2000);
    const quiet = await this.quiet(4000, Math.max(5000, deadline - Date.now()));
    const disk = await diskQuiet(this.ctx.graph, 3000, 60000);
    const r = { how: how || 'timeout', ms: Date.now() - start, quiet, disk_quiet: disk,
      record: this.records.find((x) => x.event === 'reconcile') || null };
    this.info.reconcile = r;
    await this.shot('reconciled');
    this.ctx.step(`${this.label}: reconcile ${r.how} after ${r.ms} ms`);
    return r;
  }

  async blockIdByText(text) {
    return within(this.page.evaluate((t) => {
      const root = document.querySelector('#main-content-container') || document;
      for (const el of root.querySelectorAll('.ls-block[blockid]')) {
        const c = el.querySelector('.block-content');
        if (c && c.innerText.trim().startsWith(t)) return el.getAttribute('blockid');
      }
      return null;
    }, text), 10000, 'blockIdByText').catch(() => null);
  }

  // route /page/:name (routes.cljs); waits until a block starting with waitText renders
  async gotoPage(name, waitText, maxMs = 30000) {
    await this.escape();
    await within(this.page.evaluate((n) => { location.hash = '#/page/' + encodeURIComponent(n); }, name), 10000, 'navigate');
    const end = Date.now() + maxMs;
    while (Date.now() < end) {
      const id = await this.blockIdByText(waitText);
      if (id) return id;
      await sleep(300);
    }
    await this.shot(`goto-failed-${name.replace(/\W+/g, '_')}`);
    return null;
  }

  async uiHasText(text) {
    return within(this.page.evaluate((t) => ((document.querySelector('#main-content-container') || document.body).innerText || '').includes(t), text), 10000, 'uiHasText').catch(() => false);
  }

  // click the block's .block-content (block.cljs) to open the editor (a TEXTAREA
  // inside the same .ls-block), append text at the end, Escape saves. The editor
  // is read before the typing and right before Escape: seg (segmentActual) is
  // the text it really got; it goes to ctx.segments.
  async typeInto(blockId, text, { fast = false } = {}) {
    const t0 = Date.now();
    if (!(await this.openEditor(blockId, { fast }))) return { ok: false, why: 'editor did not open', ms: Date.now() - t0 };
    const pre = await this.editorValue();
    await within(this.page.keyboard.type(text, { delay: fast ? 0 : 15 }), 20000, 'type');
    await sleep(fast ? 40 : 300);
    const post = await this.editorValue();
    await within(this.page.keyboard.press('Escape'), 5000, 'Escape');
    this.ctx.appWrote = true;
    const seg = segmentActual(text, pre.focused ? pre.value : null, post, { block: blockId });
    this.ctx.addSegment(seg);
    return { ok: true, ms: Date.now() - t0, seg };
  }

  // click .block-content until its TEXTAREA has focus, caret to the end
  async openEditor(blockId, { fast = false } = {}) {
    const page = this.page;
    const loc = () => page.locator(`#main-content-container .ls-block[blockid="${blockId}"] .block-content`).first();
    const editing = () => within(page.waitForFunction((id) => {
      const a = document.activeElement;
      return !!(a && a.tagName === 'TEXTAREA' && a.closest(`[blockid="${id}"]`));
    }, blockId, { timeout: fast ? 2000 : 4000, polling: 50 }), 8000, 'editing?').then(() => true).catch(() => false);
    let ok = false;
    for (let i = 0; i < 3 && !ok; i++) {
      await loc().scrollIntoViewIfNeeded({ timeout: 5000 }).catch(() => {});
      await loc().click({ timeout: 5000 }).catch(() => {});
      ok = await editing();
    }
    if (ok) await within(page.keyboard.press('Control+End'), 5000, 'Control+End');
    return ok;
  }

  // is any block editor (a TEXTAREA inside an .ls-block) focused?
  async editingAny() {
    return within(this.page.evaluate(() => {
      const a = document.activeElement;
      return !!(a && a.tagName === 'TEXTAREA' && a.closest('.ls-block'));
    }), 5000, 'editingAny').catch(() => false);
  }

  // what the focused block editor holds now (read before Escape/Enter leaves
  // it): {focused, value, block} or {focused: false[, error]}
  async editorValue() {
    return within(this.page.evaluate(() => {
      const a = document.activeElement;
      if (!(a && a.tagName === 'TEXTAREA' && a.closest('.ls-block'))) return { focused: false };
      const b = a.closest('[blockid]');
      return { focused: true, value: a.value, block: b ? b.getAttribute('blockid') : null };
    }), 5000, 'editorValue').catch((e) => ({ focused: false, error: errText(e) }));
  }

  notesSince(wall) { return this.notes.filter((n) => n.wall >= wall); }

  // Ctrl+K search (cmdk/core.cljs input.cp__cmdk-search-input). A hit is the
  // token inside a result group that has a header, i.e. not the "Create" group.
  async search(token, maxMs = 30000) {
    await this.escape();
    await within(this.page.keyboard.press('Control+k'), 5000, 'Control+k').catch(() => {});
    const input = await within(this.page.waitForSelector('input.cp__cmdk-search-input', { timeout: 10000 }), 15000, 'search input').catch(() => null);
    if (!input) return { token, hit: false, error: 'no search input' };
    await within(input.fill(token), 10000, 'fill').catch(() => {});
    const t0 = Date.now();
    let hit = false;
    while (!hit && Date.now() - t0 < maxMs) {
      hit = await within(this.page.evaluate((tok) => [...document.querySelectorAll('.search-results')].some((r) => {
        const head = r.parentElement && r.parentElement.firstElementChild;
        return head && head !== r && !/^\s*Create/.test(head.innerText || '') && (r.innerText || '').toLowerCase().includes(tok.toLowerCase());
      }), token), 10000, 'search poll').catch(() => false);
      if (!hit) await sleep(250);
    }
    await this.shot(`search-${token}`);
    await this.escape();
    return { token, hit, ms: Date.now() - t0 };
  }

  async notifications() {
    return within(this.page.evaluate(() => [...document.querySelectorAll('.ui__notifications')]
      .map((e) => (e.innerText || '').trim()).filter(Boolean)), 10000, 'notifications').catch(() => []);
  }

  // leave the editor, let the worker's 1 s write batch and the IPC writes land
  async settle() {
    await this.escape();
    await sleep(1500);
    const disk = await diskQuiet(this.ctx.graph, 3000, 30000);
    const quiet = await this.quiet(3000, 30000);
    return { disk, quiet };
  }

  async close() {
    if (this.closed || !this.app) return;
    this.closed = true;
    clearInterval(this.cpuTimer);
    const c = { ok: true };
    try { if (this.page) this.info.notifications = await this.notifications(); } catch (_) {}
    let kids = [];
    try { kids = descendants(this.pid); } catch (_) {}
    // errors from here on are shutdown noise (splitShutdown)
    this.closeRequestedWall = this.wall();
    c.requested_wall = this.closeRequestedWall;
    try { await within(this.app.close(), 30000, 'app.close'); } catch (e) { c.ok = false; c.error = errText(e); }
    // hard stop for anything of this app still alive (only our own tree, only this binary)
    let killed = 0;
    for (const p of [String(this.pid), ...kids]) {
      if (exeIs(p, APP)) { try { process.kill(Number(p), 'SIGKILL'); killed++; } catch (_) {} }
    }
    if (killed) { c.killed = killed; await sleep(1000); }
    liveApps.delete(this);
    this.info.close = c;
    try { this.consoleLog.end(); } catch (_) {}
    fs.writeFileSync(path.join(this.ctx.out, `${this.label}-records.json`), JSON.stringify(this.records, null, 1));
    this.ctx.step(`${this.label}: closed ${JSON.stringify(c)}`);
  }

  // errors at or after the close request are listed under shutdown, not in lserr/console_errors
  summary() {
    const le = splitShutdown(this.lserr, this.closeRequestedWall);
    const ce = splitShutdown(this.consoleErrors, this.closeRequestedWall);
    return { ...this.info, launched_wall_ms: this.info.window_ms, records: this.records.length,
      close_requested_wall: this.closeRequestedWall, lserr: le.run, guard: this.guard, notes: this.notes,
      console_errors: ce.run.slice(0, 30), shutdown_errors: le.shutdown.length + ce.shutdown.length,
      shutdown: { lserr: le.shutdown, console_errors: ce.shutdown.slice(0, 30) } };
  }
}

// ---- scenario context ----------------------------------------------------------
function makeCtx(name, root, opts, templateOpts = {}) {
  const dir = assertSafePath(path.join(root, name));
  if (fs.existsSync(dir)) throw new Error(`scenario dir exists already: ${dir}`);
  const ctx = { name, dir, graph: path.join(dir, 'graph'), profile: path.join(dir, 'profile'), out: path.join(dir, 'out'),
    opts, apps: [], aborted: false, timeline: [], t0: Date.now(), tokens: [],
    // stagesConflict: guard refusals are expected; copyExpect[rel]: tokens a
    // conflict copy of rel should hold; cleanup: run in runScenario's finally
    stagesConflict: false, copyExpect: {}, cleanup: [], appWrote: false,
    // every typed segment: intended vs what the editor had (segmentActual)
    segments: [] };
  for (const d of [ctx.graph, path.join(ctx.profile, 'home'), ctx.out]) fs.mkdirSync(assertSafePath(d), { recursive: true });
  ctx.tpl = makeTemplate(templateOpts);
  writeFiles(ctx.graph, ctx.tpl.files);
  ctx.abs = (rel) => {
    const abs = path.join(ctx.graph, rel);
    if (!abs.startsWith(ctx.graph + path.sep)) throw new Error(`path escapes the graph: ${rel}`);
    return assertSafePath(abs);
  };
  ctx.read = (rel) => fs.readFileSync(ctx.abs(rel), 'utf8');
  ctx.write = (rel, content) => fs.writeFileSync(ctx.abs(rel), content);
  // as OneDrive / another PC: write a hidden temp file next to it, then rename
  ctx.writeExternal = (rel, content) => {
    const abs = ctx.abs(rel);
    const tmp = path.join(path.dirname(abs), `.${path.basename(abs)}.lssafety~`);
    fs.writeFileSync(tmp, content);
    fs.renameSync(tmp, abs);
  };
  ctx.unlink = (rel) => fs.unlinkSync(ctx.abs(rel));
  ctx.snapshot = () => snapshotDir(ctx.graph);
  ctx.step = (msg) => { ctx.timeline.push({ t: Date.now() - ctx.t0, msg }); log(`${name}: ${msg}`); };
  ctx.tok = (tag) => { const t = `qs${tag}${crypto.randomBytes(4).toString('hex')}`; ctx.tokens.push(t); return t; };
  // the editor text of a segment that lost keystrokes joins the tokens, so bak
  // and conflict copies are searched for what the app really had
  ctx.addSegment = (s) => {
    ctx.segments.push(s);
    const a = s.actual.trim();
    if (a && s.actual !== s.intended && !ctx.tokens.includes(a)) ctx.tokens.push(a);
  };
  ctx.launch = async (label, o) => {
    const a = new App(ctx, label);
    ctx.apps.push(a);
    return a.launch(o);
  };
  return ctx;
}

// first open of the fresh graph copy; later launches restore it
async function prime(ctx, hook) {
  const a = await ctx.launch('prime', { openDialogWith: ctx.graph });
  const r = await a.openGraph();
  if (!r.clicked) throw new Error('could not click "Add new graph"');
  const extra = hook ? await hook(a) : undefined;
  await a.settle();
  await a.close();
  return extra;
}
async function reopen(ctx, label = 'reopen') {
  const a = await ctx.launch(label);
  await a.waitReconcile();
  return a;
}
function check(res, name, ok, detail, severity = 'fail', extra = {}) {
  res.checks.push({ name, severity, ok: !!ok, detail, ...extra });
}

// ---- scenarios -----------------------------------------------------------------
const scenarios = {};

scenarios['offline-edit-reopen'] = {
  async run(ctx, res) {
    await prime(ctx);
    const before = ctx.snapshot();
    const exp = [];
    const J1 = journalRel(3); const J2 = journalRel(10); const J3 = journalRel(20);
    const P1 = pageRel(pageName(3)); const P2 = pageRel(pageName(7));
    const tA = ctx.tok('a'); const tB = ctx.tok('b'); const tC = ctx.tok('c'); const tD = ctx.tok('d');
    const edits = [
      [J1, (s) => appendBlock(s, `Appended offline ${tA}`), [tA], 'append'],
      [J2, (s) => replaceBlockLine(s, `Journal ${isoDay(10)} block to change`, `Journal ${isoDay(10)} changed offline ${tB}`), [tB], 'change a block'],
      [J3, (s) => deleteBlock(s, `Journal ${isoDay(20)} block to delete`), [], 'delete a block'],
      [P1, (s) => appendBlock(s, `Page 03 appended offline ${tC}`), [tC], 'append'],
      [P2, (s) => deleteBlock(replaceBlockLine(s, 'Page 07 block to change', `Page 07 changed offline ${tD}`), 'Page 07 block to delete'), [tD], 'change + delete'],
    ];
    for (const [rel, f, tokens, why] of edits) {
      const content = f(ctx.read(rel));
      ctx.write(rel, content);
      exp.push({ path: rel, kind: 'exact', content, tokens, why: `offline ${why}` });
    }
    ctx.step('offline edits written');
    const b = await reopen(ctx);
    res.searches = [];
    for (const t of [tA, tB, tC, tD]) {
      const s = await b.search(t);
      res.searches.push(s);
      check(res, `search finds ${t}`, s.hit, s.error || (s.hit ? `hit after ${s.ms} ms` : 'not found'), 'fail');
    }
    const gone = await b.search(`Journal ${isoDay(20)} block to delete`, 5000);
    check(res, 'deleted block no longer found by search', !gone.hit, gone.hit ? 'still found (DB kept the deleted block)' : 'not found', 'info');
    await b.settle();
    await b.close();
    return { before, exp };
  },
};

scenarios['idrepair-race'] = {
  template: { home: RACE_PAGE },
  async run(ctx, res) {
    const P = pageRel(RACE_PAGE);
    const J = journalRel(1); // first journal in the reconcile's sort order
    const U = await prime(ctx, async (a) => {
      const id = await a.gotoPage(RACE_PAGE.toLowerCase(), RACE_B);
      await a.shot('race-page');
      return id;
    });
    if (!U) throw new Error(`could not read the uuid of "${RACE_B}" from the DOM`);
    res.block_b_uuid = U;
    const pAfterPrime = ctx.read(P);
    check(res, 'precondition: block B has no id:: in its file after the first open', !pAfterPrime.includes(U),
      pAfterPrime.includes(U) ? 'the first open already wrote id:: (race cannot trigger)' : 'ok', 'info');
    const before = ctx.snapshot();
    const tJ = ctx.tok('j'); const tP = ctx.tok('p'); const tQ = ctx.tok('q');
    const jContent = appendBlock(ctx.read(J), `Early journal refers to the race block ((${U})) ${tJ}`);
    const pContent = appendBlock(replaceBlockLine(pAfterPrime, 'Race page intro block', `Race page intro edited offline ${tQ}`), `Race page offline edit ${tP}`);
    ctx.write(J, jContent);
    ctx.write(P, pContent);
    ctx.step('offline edits written (J refers to B, P edited)');
    const b = await reopen(ctx);
    // the repair's write lands ~1 s after the repair (worker batch); give it room
    await sleep(3000);
    await diskQuiet(ctx.graph, 3000, 30000);
    const s = await b.search(tP);
    res.searches = [s];
    check(res, `search finds ${tP}`, s.hit, s.error || (s.hit ? `hit after ${s.ms} ms` : 'not found'), 'warn');
    await b.settle();
    await b.close();
    const exp = [
      { path: P, kind: 'exact', content: pContent, tokens: [tP, tQ], why: 'offline edit of the page whose block lacks id::' },
      { path: J, kind: 'exact', content: jContent, tokens: [tJ], why: 'offline edit adding the ((uuid)) ref' },
    ];
    res.after_hook = (after) => {
      const a = after.get(P);
      const text = a ? a.text : '';
      res.race = { p_has_offline_edit: text.includes(tP) && text.includes(tQ), p_has_id_for_b: text.includes(`id:: ${U}`) };
    };
    return { before, exp };
  },
};

scenarios['live-external-edit'] = {
  async run(ctx, res) {
    await prime(ctx);
    const before = ctx.snapshot();
    const exp = [];
    const b = await reopen(ctx);
    // A: external rewrite of a journal while idle, then typing elsewhere
    const JX = journalRel(15);
    const tE = ctx.tok('e'); const tE2 = ctx.tok('e');
    const jxContent = `- Journal ${isoDay(15)} rewritten externally ${tE}\n- Second external block ${tE2}\n`;
    ctx.writeExternal(JX, jxContent);
    ctx.step('external rewrite of the journal');
    await sleep(6000); // chokidar awaitWriteFinish (2 s) + reparse
    await b.quiet(3000, 30000);
    res.ui_saw_external = await (async () => {
      await b.gotoPage(isoDay(15), `Journal ${isoDay(15)} rewritten externally`, 20000);
      return b.uiHasText(tE);
    })();
    check(res, 'app shows the external journal edit', res.ui_saw_external, res.ui_saw_external ? 'visible' : 'not visible', 'info');
    const P5 = pageRel(pageName(5));
    const tF = ctx.tok('f');
    const id5 = await b.gotoPage(pageName(5).toLowerCase(), 'Page 05 block one');
    if (!id5) throw new Error('Page 05 did not render');
    const typed5 = ` typed ${tF}`;
    const r5 = await b.typeInto(id5, typed5);
    check(res, 'typed into a different page', r5.ok, r5.why || `${r5.ms} ms`, 'fail');
    const t5 = r5.ok ? typedFromSegments([r5.seg]) : { typed: [], tokens: [] };
    res.page05_written = await waitFileContains(ctx.abs(P5), t5.tokens, 20000);
    await b.settle();
    exp.push({ path: JX, kind: 'exact', content: jxContent, tokens: [tE, tE2], why: 'external rewrite while the app was open' });
    exp.push({ path: P5, kind: 'typed', base: before.get(P5).text, target: 'Page 05 block one', typed: t5.typed, tokens: t5.tokens, note: t5.note, why: 'typed page' });

    // B and C (policy): typing into the SAME page after an external rewrite
    res.policy = {};
    const samePage = async (n, key, settled) => {
      const rel = pageRel(pageName(n));
      const N = pageName(n);
      const tG = ctx.tok('g'); const tH = ctx.tok('h'); const tI = ctx.tok('i');
      const id = await b.gotoPage(N.toLowerCase(), `${N} block one`);
      if (!id) {
        res.policy[key] = { error: `${N} did not render` };
        check(res, `same page (${key}) could be driven`, false, `${N} did not render`, 'warn');
        return res.policy[key];
      }
      const base = ctx.read(rel);
      // block one stays identical so the click still finds it
      const ext = appendBlock(replaceBlockLine(base, `${N} block to change`, `${N} changed externally ${tG}`), `${N} appended externally ${tH}`);
      const disk0 = snapshotDir(ctx.graph);
      ctx.stagesConflict = true;
      ctx.copyExpect[rel] = [tI];
      ctx.writeExternal(rel, ext);
      const o = { page: rel, settled, external_tokens: [tG, tH], typed_token: tI };
      if (settled) {
        const end = Date.now() + 20000;
        while (Date.now() < end && !(await b.uiHasText(tG))) await sleep(300);
        o.ui_showed_external = await b.uiHasText(tG);
      } else {
        await sleep(ctx.opts.samePageDelayMs);
      }
      o.delay_ms = settled ? undefined : ctx.opts.samePageDelayMs;
      const id2 = (await b.blockIdByText(`${N} block one`)) || id;
      const r = await b.typeInto(id2, ` typed ${tI}`);
      o.typing = r;
      // judged by what the editor had (a keystroke lost on the way is not the app's write)
      const typedTok = (r.ok && r.seg.actual.trim()) || tI;
      o.typed_editor_text = typedTok;
      ctx.copyExpect[rel] = [typedTok];
      await sleep(6000);
      await b.settle();
      o.notifications = await b.notifications();
      const disk1 = snapshotDir(ctx.graph);
      const finalText = disk1.has(rel) ? disk1.get(rel).text : null;
      const baks = newBaks(disk0, disk1, [tG, tH, typedTok]).filter((x) => x.path.startsWith(`logseq/bak/pages/${N}/`));
      Object.assign(o, samePageOutcome({ finalText, extTokens: [tG, tH], revertedText: `${N} block to change`, typedToken: typedTok, bakTexts: baks.map((x) => x.text) }));
      o.bak_files = baks.map(({ text, ...x }) => x);
      o.final_diff_vs_external = finalText === ext ? [] : lineDiff(ext, finalText || '');
      res.policy[key] = o;
      const label = `same page (${settled ? 'settled' : `race, ${ctx.opts.samePageDelayMs} ms`})`;
      const facts = JSON.stringify({ outcome: o.outcome, external_survived: o.external_survived, typed_survived: o.typed_survived, external_in_bak: o.external_in_bak });
      // the external edit must survive (gate); what happens to the typed text is policy
      check(res, `${label}: external edit survived`, o.external_survived, facts, 'fail');
      check(res, `${label}: typed text survived (${o.outcome})`, o.typed_survived, facts, 'policy');
      exp.push({ path: rel, kind: 'policy' });
      return o;
    };
    await samePage(6, 'same_page_race', false);
    await samePage(8, 'same_page_settled', true);
    const s = await b.search(tE);
    res.searches = [s];
    check(res, `search finds the external journal edit ${tE}`, s.hit, s.error || (s.hit ? 'hit' : 'not found'), 'info');
    await b.close();
    return { before, exp };
  },
};

scenarios['config-offline-then-delete-home'] = {
  async run(ctx, res) {
    await prime(ctx);
    const before = ctx.snapshot();
    const CFG = 'logseq/config.edn';
    const HOMEP = pageRel('Home');
    const tK = ctx.tok('k');
    const base = ctx.read(CFG);
    if (!/\}\s*$/.test(base)) throw new Error('config.edn does not end with }');
    const comment = `;; lssafety offline edit ${tK}`;
    const key = ':ui/show-brackets? true';
    const cfg = base.replace(/\}\s*$/, `\n ${comment}\n ${key}}\n`);
    ctx.write(CFG, cfg);
    ctx.unlink(HOMEP);
    ctx.step('offline: config.edn edited, Home.md deleted');
    const b = await reopen(ctx);
    await sleep(3000);
    await diskQuiet(ctx.graph, 3000, 30000);
    await b.settle();
    await b.close();
    return { before, exp: [
      { path: CFG, kind: 'contains', fragments: [comment, key], content: cfg, why: 'offline config edit (the app may change :default-home around it)' },
      { path: HOMEP, kind: 'absent', severity: 'warn', why: 'deleted offline; recreated by the app?' },
    ] };
  },
};

scenarios['burst-writes'] = {
  async run(ctx, res) {
    await prime(ctx);
    const before = ctx.snapshot();
    const b = await reopen(ctx);
    const P2 = pageRel(pageName(2));
    const EXT = journalRel(25);
    const id = await b.gotoPage(pageName(2).toLowerCase(), 'Page 02 block one');
    if (!id) throw new Error('Page 02 did not render');
    const tX = ctx.tok('x'); const tL = ctx.tok('l');
    let ext = ctx.read(EXT);
    // external writer: 6 appends, 150 ms apart, to a different file
    const writer = (async () => {
      for (let k = 1; k <= 6; k++) {
        const add = `\n- External append ${k} ${tX}n${k}`;
        fs.appendFileSync(ctx.abs(EXT), add);
        ext += add;
        await sleep(150);
      }
    })();
    const segs = []; const rounds = [];
    const t0 = Date.now();
    for (let k = 1; k <= 4; k++) {
      const r = await b.typeInto(id, ` b${k}${tL}`, { fast: true });
      rounds.push(r);
      if (r.ok) segs.push(r.seg);
    }
    res.burst = { span_ms: Date.now() - t0, rounds };
    await writer;
    check(res, 'all typing rounds reached the editor', rounds.every((r) => r.ok), JSON.stringify(rounds.map((r) => r.ok)), 'warn');
    if (!segs.length) throw new Error('no typing round reached the editor');
    const { typed, tokens, note } = typedFromSegments(segs);
    res.typed_written = await waitFileContains(ctx.abs(P2), tokens, 20000);
    await sleep(3000);
    await b.settle();
    await b.close();
    return { before, exp: [
      { path: EXT, kind: 'exact', content: ext, tokens: [1, 2, 3, 4, 5, 6].map((k) => `${tX}n${k}`), why: 'external appends during the burst' },
      { path: P2, kind: 'typed', base: before.get(P2).text, target: 'Page 02 block one', typed, tokens, note, why: 'burst of saves' },
    ] };
  },
};

scenarios['typing-roundtrip'] = {
  async run(ctx, res) {
    await prime(ctx);
    const before = ctx.snapshot();
    const J = journalRel(12);
    const tM = ctx.tok('m');
    const b = await reopen(ctx);
    const id = await b.gotoPage(isoDay(12), `Journal ${isoDay(12)} note alpha`);
    if (!id) throw new Error(`journal ${isoDay(12)} did not render`);
    const r = await b.typeInto(id, ` typed ${tM}`);
    if (!r.ok) throw new Error(`typing failed: ${r.why}`);
    const tt = typedFromSegments([r.seg]);
    // search for the token as the editor had it (the segment's last word)
    const sTok = r.seg.actual.trim().split(/\s+/).pop() || tM;
    res.typed_written = await waitFileContains(ctx.abs(J), tt.tokens, 20000);
    await b.settle();
    await b.close();
    const mid = ctx.snapshot();
    res.after_first_close_sha = mid.has(J) ? mid.get(J).sha : null;
    const c = await reopen(ctx, 'reopen2');
    const s = await c.search(sTok);
    res.searches = [s];
    check(res, `search finds ${sTok} after reopen`, s.hit, s.error || (s.hit ? 'hit' : 'not found'), 'warn');
    await c.settle();
    await c.close();
    res.after_hook = (after) => {
      const a = after.get(J); const m = mid.get(J);
      check(res, 'reopen did not rewrite the typed journal', a && m && a.sha === m.sha,
        a && m && a.sha === m.sha ? 'byte-identical across the reopen' : 'changed by the reopen', 'fail',
        { diff: a && m && a.sha !== m.sha ? lineDiff(m.text, a.text) : undefined });
    };
    return { before, exp: [
      { path: J, kind: 'typed', base: before.get(J).text, target: `Journal ${isoDay(12)} note alpha`, typed: tt.typed, tokens: tt.tokens, note: tt.note, why: 'typed journal' },
    ] };
  },
};

// Type, Enter, type, Escape inside one worker flush (1 s): :save-block and
// :insert-blocks travel together; the second must not be refused by the guard.
scenarios['two-ops-one-flush'] = {
  async run(ctx, res) {
    await prime(ctx);
    const before = ctx.snapshot();
    const N = pageName(4); const P = pageRel(N);
    const b = await reopen(ctx);
    const id = await b.gotoPage(N.toLowerCase(), `${N} block one`);
    if (!id) throw new Error(`${N} did not render`);
    const t1 = ctx.tok('s'); const t2 = ctx.tok('t');
    const s1 = ` one ${t1}`; const s2 = `two ${t2}`;
    const disk0 = snapshotDir(ctx.graph);
    const w0 = b.wall();
    if (!(await b.openEditor(id, { fast: true }))) throw new Error('editor did not open');
    const pre1 = await b.editorValue();
    const t0 = Date.now();
    await within(b.page.keyboard.type(s1, { delay: 0 }), 10000, 'type 1');
    const post1 = await b.editorValue(); // block one, right before Enter leaves it
    await within(b.page.keyboard.press('Enter'), 5000, 'Enter'); // :editor/new-block (shortcut/config.cljs)
    await within(b.page.keyboard.type(s2, { delay: 0 }), 10000, 'type 2');
    await sleep(40);
    const post2 = await b.editorValue(); // the new block, right before Escape leaves it
    await within(b.page.keyboard.press('Escape'), 5000, 'Escape');
    ctx.appWrote = true;
    const opsMs = Date.now() - t0;
    const seg1 = segmentActual(s1, pre1.focused ? pre1.value : null, post1, { block: id });
    const seg2 = segmentActual(s2, '', post2, { enterFrom: post1 }); // Enter at the end: an empty block
    ctx.addSegment(seg1); ctx.addSegment(seg2);
    res.two_ops = { ops_ms: opsMs, segments: [seg1, seg2] };
    check(res, 'both ops inside ~1 s', opsMs <= 1100, `${opsMs} ms (with 2 editor read-backs)`, 'warn');
    const tt = typedFromSegments([seg1, seg2]);
    res.typed_written = await waitFileContains(ctx.abs(P), tt.tokens, 20000);
    await sleep(3000);
    await b.settle();
    const disk1 = snapshotDir(ctx.graph);
    const baks = newBaks(disk0, disk1, ctx.tokens);
    check(res, 'no new bak file', !baks.length, baks.length ? baks.map((x) => x.path).join(', ') : 'none', 'fail');
    const shown = [...b.notesSince(w0).map((n) => `${n.status}: ${n.text}`), ...(await b.notifications())];
    const bad = shown.filter((t) => /not saved|conflict/i.test(t));
    res.two_ops.notifications = shown;
    check(res, 'no "not saved"/conflict notification', !bad.length, bad.length ? JSON.stringify(bad).slice(0, 400) : 'none', 'fail');
    await b.close();
    return { before, exp: [
      // the editor's text; an Enter that never reached the editor left both segments in block one
      { path: P, kind: 'typed', base: before.get(P).text, target: `${N} block one`,
        typed: seg2.enter_missed ? [seg1.actual, seg2.actual] : [seg1.actual], inserted: seg2.enter_missed ? [] : [`- ${seg2.actual}`],
        tokens: tt.tokens, note: tt.note, why: 'type, Enter, type in one flush' },
    ] };
  },
};

// ~3 s of typing with Enter presses while the page's file is rewritten from outside.
scenarios['typing-through-external-rewrite'] = {
  async run(ctx, res) {
    await prime(ctx);
    const before = ctx.snapshot();
    const N = pageName(9); const P = pageRel(N);
    const b = await reopen(ctx);
    const id = await b.gotoPage(N.toLowerCase(), `${N} block one`);
    if (!id) throw new Error(`${N} did not render`);
    ctx.stagesConflict = true;
    const tW = ctx.tok('w'); const tG = ctx.tok('g'); const tH = ctx.tok('h');
    const extTokens = [tG, tH];
    const rw = { typed: [], segments: [], skipped: 0, reopened: 0, ext_at_ms: null };
    res.rewrite = rw;
    const external = () => {
      const cur = ctx.read(P);
      let ext;
      try { ext = appendBlock(replaceBlockLine(cur, `${N} block to change`, `${N} changed externally ${tG}`), `${N} appended externally ${tH}`); }
      catch (_) { ext = appendBlock(appendBlock(cur, `${N} changed externally ${tG}`), `${N} appended externally ${tH}`); }
      ctx.writeExternal(P, ext);
      rw.ext_at_ms = Date.now() - t0;
      rw.external_content = ext;
      ctx.step(`external rewrite of ${P} after ${rw.ext_at_ms} ms of typing`);
    };
    // Each editor session (open or Enter -> Enter, Escape or losing the editor)
    // is read back right before it is left; its tokens are what the editor's
    // value gained (sessionSegments). A session whose editor was lost (an
    // external change reset it) cannot be read: intended tokens assumed.
    let sess = null;
    const begin = async (blockId) => {
      const v = await b.editorValue();
      sess = { pre: v.focused ? v.value : null, segs: [], opts: { block: blockId } };
    };
    const leave = async () => {
      let post = null;
      if (sess && sess.segs.length) {
        post = await b.editorValue();
        for (const s of sessionSegments(sess.segs, sess.pre, post, sess.opts)) { rw.segments.push(s); ctx.addSegment(s); }
      }
      sess = null;
      return post;
    };
    if (!(await b.openEditor(id))) throw new Error('editor did not open');
    ctx.appWrote = true;
    await begin(id);
    const t0 = Date.now();
    for (let k = 1; Date.now() - t0 < 3000 && k <= 80; k++) {
      if (rw.ext_at_ms == null && Date.now() - t0 >= 1500) external();
      // an external change can reset the editor; keys sent to a non-editing
      // page would fire shortcuts, so reopen block one first
      if (!(await b.editingAny())) {
        await leave();
        const id2 = (await b.blockIdByText(`${N} block one`)) || id;
        if (!(await b.openEditor(id2, { fast: true }))) { rw.skipped++; await sleep(100); continue; }
        rw.reopened++;
        await begin(id2);
      }
      if (!sess) await begin(null);
      const tok = `${tW}n${k}z`;
      await within(b.page.keyboard.type(` ${tok}`, { delay: 10 }), 10000, 'type');
      rw.typed.push(tok);
      sess.segs.push(` ${tok}`);
      if (k % 3 === 0) {
        const post = await leave();
        await within(b.page.keyboard.press('Enter'), 5000, 'Enter');
        sess = { pre: '', segs: [], opts: { enterFrom: post } }; // Enter at the end: an empty block
      }
    }
    if (rw.ext_at_ms == null) external();
    rw.span_ms = Date.now() - t0;
    await leave();
    await within(b.page.keyboard.press('Escape'), 5000, 'Escape').catch(() => {});
    check(res, 'typing kept going across the rewrite', rw.typed.length >= 4 && rw.skipped < 5, JSON.stringify({ typed: rw.typed.length, skipped: rw.skipped, reopened: rw.reopened }), 'warn');
    await sleep(6000);
    await b.settle();
    rw.notifications = b.notes.map((n) => `${n.status}: ${n.text}`);
    await b.close();
    res.after_hook = (after) => {
      const a = after.get(P);
      const text = a ? a.text : '';
      const baks = newBaks(before, after, ctx.tokens);
      const missingExt = extTokens.filter((t) => !text.includes(t));
      check(res, 'external rewrite on disk at the end', !missingExt.length,
        missingExt.length ? `missing ${missingExt.join(', ')} (overwritten by a stale proposal?)` : 'both external tokens present', 'fail',
        { in_bak: missingExt.length ? (baks.some((x) => missingExt.every((t) => x.text.includes(t))) ? 'yes' : 'no') : undefined });
      // accounted by what the editor had: a keystroke lost before the editor is
      // the keystroke check's business, not a lost write
      const tokOf = (s) => s.actual.trim();
      const toks = rw.segments.map(tokOf).filter(Boolean);
      const never = rw.segments.filter((s) => !tokOf(s)).map((s) => s.intended.trim());
      const assumed = rw.segments.filter((s) => s.source !== 'editor').length;
      const inCopies = (t) => baks.some((x) => countToken(x.text, t) > 0);
      const inFile = toks.filter((t) => countToken(text, t) > 0);
      const inCopy = toks.filter((t) => !countToken(text, t) && inCopies(t));
      const lost = toks.filter((t) => !countToken(text, t) && !inCopies(t));
      const dup = toks.filter((t) => countToken(text, t) > 1);
      rw.final = { typed_in_file: inFile.length, typed_in_copy_only: inCopy, typed_lost: lost, typed_duplicated: dup,
        never_reached_editor: never, not_read_back: assumed, bak_files: baks.map((x) => x.path) };
      const note = assumed ? `; ${assumed}/${rw.segments.length} not read back from the editor, intended token assumed` : '';
      check(res, 'every typed token the editor had in the final file or a bak/conflict copy', !lost.length,
        `${lost.length ? `lost ${lost.length}/${toks.length}: ${lost.join(', ')}` : `${inFile.length} in file, ${inCopy.length} only in a copy`}${note}`, 'warn');
      if (dup.length) check(res, 'typed tokens once in the final file', false, `duplicated: ${dup.join(', ')}`, 'warn');
    };
    return { before, exp: [{ path: P, kind: 'policy' }] };
  },
};

// Optional: logseq/bak unwritable while a same-page refusal is staged.
scenarios['bak-unwritable'] = {
  async run(ctx, res) {
    await prime(ctx);
    const before = ctx.snapshot();
    const N = pageName(7); const P = pageRel(N);
    const bakDir = ctx.abs('logseq/bak');
    fs.mkdirSync(bakDir, { recursive: true });
    const mode0 = fs.statSync(bakDir).mode & 0o777;
    const restore = () => { try { fs.chmodSync(bakDir, mode0 || 0o755); } catch (_) {} };
    ctx.cleanup.push(restore);
    const tG = ctx.tok('g'); const tH = ctx.tok('h'); const tI = ctx.tok('i');
    const o = {};
    res.bak_unwritable = o;
    const b = await reopen(ctx);
    try {
      const id = await b.gotoPage(N.toLowerCase(), `${N} block one`);
      if (!id) throw new Error(`${N} did not render`);
      ctx.stagesConflict = true;
      const base = ctx.read(P);
      const ext = appendBlock(replaceBlockLine(base, `${N} block to change`, `${N} changed externally ${tG}`), `${N} appended externally ${tH}`);
      fs.chmodSync(bakDir, 0o000);
      ctx.step('logseq/bak is mode 000');
      const w0 = b.wall();
      ctx.writeExternal(P, ext);
      await sleep(ctx.opts.samePageDelayMs); // before the watcher's awaitWriteFinish: the save is refused
      const id2 = (await b.blockIdByText(`${N} block one`)) || id;
      o.typing = await b.typeInto(id2, ` typed ${tI}`);
      if (!o.typing.ok) throw new Error(`typing failed: ${o.typing.why}`);
      const end = Date.now() + 15000;
      let errs = [];
      while (Date.now() < end) {
        errs = b.notesSince(w0).filter((n) => n.status === 'error');
        if (errs.length) break;
        await sleep(300);
      }
      await sleep(3000);
      o.notifications = b.notesSince(w0);
      o.dom_notifications = await b.notifications();
      o.guard = b.guard.filter((g) => g.wall >= w0);
      o.ui_has_typed = await b.uiHasText(o.typing.seg.actual.trim() || tI);
      await b.shot('bak-unwritable');
      check(res, 'error notification shown', errs.length > 0, errs.length ? errs[0].text.slice(0, 200) : JSON.stringify(o.dom_notifications).slice(0, 200), 'fail');
      check(res, 'typed text still visible in the UI', o.ui_has_typed, o.ui_has_typed ? 'visible' : 'gone (reparsed?)', 'fail');
      check(res, 'guard logged a refusal', o.guard.some((g) => REFUSALS.includes(g.result)), JSON.stringify(o.guard.map((g) => g.result)), 'info');
    } finally {
      restore();
    }
    await b.settle();
    await b.close();
    return { before, exp: [
      { path: P, kind: 'contains', fragments: [tG, tH], why: 'external rewrite survives the refused save' },
    ] };
  },
};

// ---- runner --------------------------------------------------------------------
async function runScenario(name, root, opts) {
  const def = scenarios[name];
  const res = { scenario: name, status: 'error', reasons: [], warnings: [], checks: [], app: APP };
  let ctx = null;
  const started = Date.now();
  let runP = null;
  try {
    ctx = makeCtx(name, root, opts, def.template);
    res.dir = ctx.dir;
    const s0 = ctx.snapshot();
    res.template_files = s0.size;
    let timer;
    runP = def.run(ctx, res);
    const out = await Promise.race([runP, new Promise((_, rej) => {
      timer = setTimeout(() => { ctx.aborted = true; rej(new Error(`scenario timeout after ${opts.timeoutSec} s`)); }, opts.timeoutSec * 1000);
    })]).finally(() => clearTimeout(timer));
    // no app of this scenario is running any more (each run closes its own)
    for (const a of ctx.apps) await a.close().catch(() => {});
    const after = ctx.snapshot();
    const { before, exp } = out;
    // what the first open did to the template (the baseline is after it)
    const pd = diffSnapshots(s0, before);
    const primeChanged = pd.changed.filter((r) => classify(r) === 'graph').concat(pd.removed.filter((r) => classify(r) === 'graph'));
    res.prime_changes = pd;
    if (primeChanged.length) check(res, 'first open left existing files alone', false, primeChanged.join(', '), 'warn');
    const bakNew = newBaks(before, after, ctx.tokens);
    res.checks.push(...verify(before, after, exp, { bakNew }));
    // keystroke delivery (keys sent vs editor text), apart from the write path
    if (ctx.segments.length) res.checks.push(keystrokeCheck(ctx.segments));
    if (res.after_hook) { res.after_hook(after); delete res.after_hook; }
    // write guard: refusals gate unless the scenario staged a conflict; copies are reported
    const guardRecs = [].concat(...ctx.apps.map((a) => a.guard.map((g) => ({ launch: a.label, ...g }))));
    const gr = guardReport(guardRecs, { appWrote: ctx.appWrote || primeChanged.length > 0 });
    res.guard = { status: gr.status, counts: gr.counts, staged_conflict: ctx.stagesConflict, refusals: gr.refusals,
      copies: conflictCopies(guardRecs, after, ctx.graph, ctx.tokens, ctx.copyExpect) };
    const refusalText = gr.refusals.map((r) => `${r.result} ${r.path}${r.copy ? ` -> ${r.copy}` : ''}`).join('; ');
    if (ctx.stagesConflict) {
      check(res, `guard: ${gr.status} (conflict staged, refusals allowed)`, true, refusalText || JSON.stringify(gr.counts), 'info');
    } else {
      check(res, 'no LSGUARD refusal (mismatch/exists/io-error)', !gr.refusals.length,
        gr.refusals.length ? refusalText : `guard: ${gr.status} ${JSON.stringify(gr.counts)}`, 'fail');
    }
    for (const c of res.guard.copies) {
      check(res, `conflict copy ${c.rel || c.copy} (${c.result} ${c.page})`, c.exists && c.holds_expected !== false,
        !c.exists ? (c.rel ? 'named by LSGUARD but not on disk' : 'outside the graph')
          : `holds ${JSON.stringify(c.tokens)}${c.expected ? `; expected ${JSON.stringify(c.expected)}` : ''}`, 'warn');
    }
    const d = diffSnapshots(before, after);
    res.changed_files = d;
    res.bak_new = bakNew.map(({ text, ...x }) => x);
    res.recycle_new = d.added.filter((r) => classify(r) === 'recycle');
    res.files = {};
    for (const r of new Set([...before.keys(), ...after.keys()])) {
      res.files[r] = { before: before.has(r) ? before.get(r).sha : null, after: after.has(r) ? after.get(r).sha : null };
    }
    const failed = res.checks.filter((c) => !c.ok && c.severity === 'fail');
    res.reasons = failed.map((c) => `${c.name}: ${c.detail}${c.in_bak && c.in_bak !== 'no' ? ` (copy in logseq/bak: ${c.in_bak})` : ''}`);
    res.warnings = res.checks.filter((c) => (!c.ok && c.severity === 'warn') || c.warn).map((c) => `${c.name}: ${c.detail}`);
    res.policy_checks = res.checks.filter((c) => c.severity === 'policy').map((c) => `${c.name}: ${c.detail}`);
    res.status = failed.length ? 'fail' : 'pass';
  } catch (e) {
    res.status = 'error';
    res.reasons.push(`harness: ${errText(e)}`);
    log(`${name}: ERROR ${e && e.stack || e}`);
  } finally {
    if (ctx) {
      ctx.aborted = true;
      for (const f of ctx.cleanup) { try { f(); } catch (_) {} }
      for (const a of ctx.apps) await a.close().catch(() => {});
      // a timed-out run may still be awaiting; let it fail against the closed app
      if (runP) await within(runP.catch(() => {}), 60000, 'abandoned run').catch(() => {});
      for (const a of ctx.apps) await a.close().catch(() => {});
      res.launches = ctx.apps.map((a) => a.summary());
      // before each launch's close request; later ones are shutdown noise (per launch under shutdown)
      res.lserr_count = res.launches.reduce((n, l) => n + l.lserr.length, 0);
      res.shutdown_errors = res.launches.reduce((n, l) => n + l.shutdown_errors, 0);
      if (res.lserr_count) res.warnings.push(`app errors (LSERR/pageerror): ${res.lserr_count}`);
      res.segments = ctx.segments;
      res.timeline = ctx.timeline;
      res.tokens = ctx.tokens;
      // after a harness error the verification block never ran: still say whether the guard spoke
      if (!res.guard) {
        const recs = [].concat(...ctx.apps.map((a) => a.guard.map((g) => ({ launch: a.label, ...g }))));
        const gr = guardReport(recs, { appWrote: ctx.appWrote });
        res.guard = { status: gr.status, counts: gr.counts, staged_conflict: ctx.stagesConflict, refusals: gr.refusals };
      }
    }
    res.duration_ms = Date.now() - started;
    if (ctx) fs.writeFileSync(path.join(ctx.dir, 'result.json'), JSON.stringify(res, null, 1));
  }
  return res;
}

function summaryTable(results) {
  const rows = results.map((r) => [r.scenario, r.status.toUpperCase(),
    `${r.checks.filter((c) => c.ok && c.severity === 'fail').length}/${r.checks.filter((c) => c.severity === 'fail').length}`,
    String(r.warnings.length), String(r.policy_checks ? r.policy_checks.length : 0), String(r.bak_new ? r.bak_new.length : 0),
    String(r.lserr_count || 0), String(r.shutdown_errors || 0), r.guard ? r.guard.status : '-', `${Math.round(r.duration_ms / 1000)}s`, (r.reasons[0] || '').slice(0, 110)]);
  const head = ['scenario', 'status', 'gates', 'warn', 'policy', 'bak', 'lserr', 'shutdn', 'guard', 'time', 'first reason'];
  const w = head.map((h, i) => Math.max(h.length, ...rows.map((r) => r[i].length)));
  const fmt = (r) => r.map((c, i) => (i === r.length - 1 ? c : c.padEnd(w[i]))).join('  ');
  return [fmt(head), fmt(w.map((n) => '-'.repeat(n))), ...rows.map(fmt)].join('\n');
}

async function main(argv) {
  const o = { only: null, out: null, timeoutSec: 900, samePageDelayMs: 500, list: false, selftest: null };
  for (const a of argv) {
    let m;
    if ((m = a.match(/^--only=(.+)$/))) o.only = m[1].split(',').map((s) => s.trim()).filter(Boolean);
    else if ((m = a.match(/^--out=(.+)$/))) o.out = m[1];
    else if ((m = a.match(/^--timeout=(\d+)$/))) o.timeoutSec = Number(m[1]);
    else if ((m = a.match(/^--same-page-delay=(\d+)$/))) o.samePageDelayMs = Number(m[1]);
    else if (a === '--list') o.list = true;
    else if ((m = a.match(/^--selftest=(.+)$/))) o.selftest = m[1];
    else { console.error(`unknown argument ${a}\n(see the header of lssafety.js)`); return 2; }
  }
  if (o.list) { console.log([...SCENARIOS, ...OPTIONAL_SCENARIOS.map((s) => `${s} (optional: --only)`)].join('\n')); return 0; }
  if (o.selftest) return selftest(o.selftest);
  const names = o.only || SCENARIOS;
  const unknown = names.filter((n) => !scenarios[n]);
  if (unknown.length) { console.error(`unknown scenario(s): ${unknown.join(', ')}; known: ${[...SCENARIOS, ...OPTIONAL_SCENARIOS].join(', ')}`); return 2; }
  if (!fs.existsSync(APP)) { console.error(`no app binary at ${APP} (set LSSAFETY_APP)`); return 2; }
  let root;
  try {
    root = assertSafePath(o.out || path.join(BASE, 'safety', new Date().toISOString().replace(/[:.]/g, '-')));
  } catch (e) { console.error(errText(e)); return 2; }
  fs.mkdirSync(root, { recursive: true });
  log(`app ${APP}\n  run dir ${root}\n  scenarios ${names.join(', ')}`);
  const results = [];
  for (const n of names) {
    log(`== ${n}`);
    const r = await runScenario(n, root, o);
    results.push(r);
    console.log(`LSSAFETY_RESULT ${JSON.stringify({ scenario: r.scenario, status: r.status, reasons: r.reasons, warnings: r.warnings, policy: r.policy_checks || [],
      guard: r.guard ? r.guard.status : null, guard_counts: r.guard ? r.guard.counts : null,
      lserr: r.lserr_count || 0, shutdown_errors: r.shutdown_errors || 0, result: path.join(r.dir || root, 'result.json') })}`);
  }
  const table = summaryTable(results);
  fs.writeFileSync(path.join(root, 'summary.json'), JSON.stringify({ app: APP, root, results: results.map((r) => ({
    scenario: r.scenario, status: r.status, reasons: r.reasons, warnings: r.warnings, policy: r.policy_checks || [],
    guard: r.guard ? r.guard.status : null, lserr: r.lserr_count || 0, shutdown_errors: r.shutdown_errors || 0,
    duration_ms: r.duration_ms })) }, null, 1));
  fs.writeFileSync(path.join(root, 'summary.txt'), `${table}\n`);
  console.log(`\n${table}\n\nresults: ${root}`);
  return results.every((r) => r.status === 'pass') ? 0 : 1;
}

// ---- self-test of the pure helpers (no app) ------------------------------------
function selftest(dir) {
  const assert = require('assert');
  const abs = path.resolve(dir);
  assertSafePath(abs, { requireBase: false });
  // the guard itself
  for (const bad of [path.join(HOME, 'OneDrive/Logseq'), path.join(HOME, 'OneDrive'), path.join(HOME, '.claude/x'), path.join(HOME, 'dev/logsidian/x'), '/tmp/x']) {
    assert.throws(() => assertSafePath(bad), `guard must refuse ${bad}`);
  }
  assert.doesNotThrow(() => assertSafePath(path.join(BASE, 'safety', 'x')));
  assert.throws(() => assertSafePath(BASE), 'the base itself is not a run dir');
  const g = path.join(abs, `graph-${process.pid}`);
  if (fs.existsSync(g)) throw new Error(`${g} exists`);
  const tpl = makeTemplate();
  writeFiles(g, tpl.files);
  const s0 = snapshotDir(g);
  assert.strictEqual(s0.size, 1 + JOURNAL_DAYS + PAGES + 2);
  assert.ok(s0.get('logseq/config.edn').text.includes(':default-home {:page "Home"}'));
  assert.ok(!s0.get(pageRel(RACE_PAGE)).text.includes('id::'));
  // edits
  const j = s0.get(journalRel(20)).text;
  const jd = deleteBlock(j, `Journal ${isoDay(20)} block to delete`);
  assert.ok(!jd.includes('block to delete') && jd.includes('block to change'));
  const pd = deleteBlock(s0.get(pageRel(pageName(4))).text, 'Page 04 block two with id');
  assert.ok(!pd.includes('id::') && pd.includes('block three'), 'delete removes the property line too');
  assert.ok(pd.includes('\t- Page 04 child block'));
  const jr = replaceBlockLine(j, `Journal ${isoDay(20)} block to change`, 'X');
  assert.ok(jr.split('\n').includes('- X'));
  assert.throws(() => replaceBlockLine(j, 'nope', 'x'));
  assert.strictEqual(appendBlock('- a', 'b'), '- a\n- b');
  assert.strictEqual(appendBlock('- a\n', 'b'), '- a\n- b');
  // verify: exact / id-only additions / loss with bak / untouched / typed
  const P = pageRel(RACE_PAGE);
  const want = appendBlock(s0.get(P).text, 'offline tokx');
  fs.writeFileSync(path.join(g, P), want);
  const b1 = snapshotDir(g);
  let c = verify(s0, b1, [{ path: P, kind: 'exact', content: want, tokens: ['tokx'] }]);
  assert.ok(c.every((x) => x.ok), JSON.stringify(c));
  const withId = want.replace(`- ${RACE_B}`, `- ${RACE_B}\n  id:: ${uuidFor(999)}`);
  fs.writeFileSync(path.join(g, P), withId);
  c = verify(s0, snapshotDir(g), [{ path: P, kind: 'exact', content: want, tokens: ['tokx'] }]);
  assert.ok(c[0].ok && c[0].warn, 'added id:: line is not a loss');
  // the race: P rewritten from the old copy + id, the edit only in bak
  fs.writeFileSync(path.join(g, P), s0.get(P).text.replace(`- ${RACE_B}`, `- ${RACE_B}\n  id:: ${uuidFor(999)}`));
  fs.mkdirSync(path.join(g, 'logseq/bak/pages/Race Page'), { recursive: true });
  fs.writeFileSync(path.join(g, 'logseq/bak/pages/Race Page/2026-09-12T00_00_00.000Z.Desktop.md'), want);
  const s2 = snapshotDir(g);
  const baks = newBaks(b1, s2, ['tokx']);
  assert.strictEqual(baks.length, 1);
  c = verify(b1, s2, [{ path: P, kind: 'exact', content: want, tokens: ['tokx'] }], { bakNew: baks });
  assert.ok(!c[0].ok && c[0].in_bak === 'exact' && /tokx/.test(c[0].detail), JSON.stringify(c[0]));
  // untouched file changed -> fail, only id added -> warn, new file -> warn
  const J5 = journalRel(5);
  fs.writeFileSync(path.join(g, J5), s0.get(J5).text.replace('note alpha', 'note ALPHA'));
  fs.writeFileSync(path.join(g, 'pages/New.md'), '- new');
  c = verify(s0, snapshotDir(g), []);
  assert.ok(c.some((x) => x.name === `untouched ${J5}` && x.severity === 'fail' && !x.ok));
  assert.ok(c.some((x) => x.name === 'new file pages/New.md' && x.severity === 'warn'));
  assert.ok(!c.some((x) => x.name.includes('logseq/bak')), 'bak is not a graph file');
  // typed: once + other lines kept (tab->spaces tolerated, id line tolerated)
  const T = pageRel(pageName(2));
  const base = s0.get(T).text;
  const typedOk = base.replace('- Page 02 block one plain text', '- Page 02 block one plain text b1tok b2tok').replace('\t- Page 02 child', '    - Page 02 child');
  fs.writeFileSync(path.join(g, T), typedOk);
  const e = { path: T, kind: 'typed', base, target: 'Page 02 block one', typed: [' b1tok', ' b2tok'], tokens: ['b1tok', 'b2tok'] };
  c = checkExpectation(e, snapshotDir(g), []);
  assert.ok(c.ok && !c.byte_exact, JSON.stringify(c));
  fs.writeFileSync(path.join(g, T), `${typedOk}\n- dup b1tok`);
  c = checkExpectation(e, snapshotDir(g), []);
  assert.ok(!c.ok && c.token_counts.b1tok === 2);
  fs.writeFileSync(path.join(g, T), typedOk.replace('- Page 02 block to delete\n', ''));
  c = checkExpectation(e, snapshotDir(g), []);
  assert.ok(!c.ok && c.detail === 'other lines changed', JSON.stringify(c));
  // contains + absent
  c = checkExpectation({ path: 'logseq/config.edn', kind: 'contains', fragments: [':favorites ["home"]', ';; nope'] }, snapshotDir(g), []);
  assert.ok(!c.ok && /nope/.test(c.detail));
  c = checkExpectation({ path: 'pages/Gone.md', kind: 'absent' }, snapshotDir(g), []);
  assert.ok(c.ok);
  // config offline edit shape
  const cfg = s0.get('logseq/config.edn').text.replace(/\}\s*$/, '\n ;; lssafety offline edit t\n :ui/show-brackets? true}\n');
  assert.ok(/:default-home \{:page "Home"\}\n ;; lssafety offline edit t\n :ui\/show-brackets\? true\}\n$/.test(cfg), cfg);
  // same-page outcomes
  assert.strictEqual(samePageOutcome({ finalText: 'g h i', extTokens: ['g', 'h'], typedToken: 'i', bakTexts: [] }).outcome, 'merged');
  assert.strictEqual(samePageOutcome({ finalText: 'x i', extTokens: ['g', 'h'], typedToken: 'i', bakTexts: ['g h'] }).outcome, 'backup+overwrite');
  assert.strictEqual(samePageOutcome({ finalText: 'g h', extTokens: ['g', 'h'], typedToken: 'i', bakTexts: [] }).outcome, 'typed-dropped (refused or superseded)');
  // diffs
  assert.deepStrictEqual(lineDiff('a\nb\nc', 'a\nB\nc'), [' a', '-b', '+B', ' c']);
  assert.strictEqual(stripAddedIdLines(`x\n  id:: ${uuidFor(1)}\ny`, 'x\ny'), 'x\ny');
  assert.strictEqual(classify('logseq/bak/pages/a/1.md'), 'bak');
  assert.strictEqual(classify('journals/.x.md.lssafety~'), 'hidden');
  assert.strictEqual(countOf('ab ab', 'ab'), 2);
  // typed with an inserted sibling (scenario 7)
  const T4 = pageRel(pageName(4));
  const b4 = s0.get(T4).text;
  fs.writeFileSync(path.join(g, T4), b4.replace('- Page 04 block one plain text', '- Page 04 block one plain text one s1tok\n- two t2tok'));
  c = checkExpectation({ path: T4, kind: 'typed', base: b4, target: 'Page 04 block one', typed: [' one s1tok'], inserted: ['- two t2tok'], tokens: ['s1tok', 't2tok'] }, snapshotDir(g), []);
  assert.ok(c.ok && c.byte_exact, JSON.stringify(c));
  // typed segments: the file is checked against the editor's text, not the keys sent
  const ed = (value, block = 'B1') => ({ focused: true, value, block });
  let sg = segmentActual('two qstcdc4d61a', '', ed('two qstdc4d61a', 'B2'), { enterFrom: ed('x one s1tok') });
  assert.ok(sg.source === 'editor' && sg.actual === 'two qstdc4d61a' && sg.differs && !sg.enter_missed, JSON.stringify(sg));
  sg = segmentActual(' ok', 'a', ed('a ok'), { block: 'B1' });
  assert.ok(sg.source === 'editor' && sg.actual === ' ok' && !sg.differs, JSON.stringify(sg));
  sg = segmentActual(' ok', 'a', { focused: false });
  assert.ok(sg.source === 'intended' && sg.actual === ' ok' && /not readable/.test(sg.why), JSON.stringify(sg));
  sg = segmentActual(' ok', 'a', ed('a ok', 'B2'), { block: 'B1' });
  assert.ok(sg.source === 'intended' && /B2/.test(sg.why), JSON.stringify(sg));
  sg = segmentActual(' ok', 'a', ed('reparsed'));
  assert.ok(sg.source === 'intended' && /changed under/.test(sg.why), JSON.stringify(sg));
  sg = segmentActual('two t', '', ed('x one stwo t'), { enterFrom: ed('x one s') });
  assert.ok(sg.enter_missed && sg.source === 'editor' && sg.actual === 'two t', JSON.stringify(sg));
  // sessions of several tokens (scenario 8)
  let ss = sessionSegments([' qa1z', ' qa2z', ' qa3z'], 'x', ed('x qa1z qa2 qa3z'));
  assert.deepStrictEqual(ss.map((s) => [s.actual, s.differs]), [[' qa1z', false], [' qa2', true], [' qa3z', false]]);
  ss = sessionSegments([' qa1z', ' qa2z'], 'x', ed('x qa1zqa2z')); // a lost space: found, but the session differs
  assert.ok(ss.every((s) => s.source === 'editor' && s.differs && s.editor_text === ' qa1zqa2z'), JSON.stringify(ss));
  ss = sessionSegments([' qa1z', ' qa2z'], 'x', ed('x qa1z'));
  assert.deepStrictEqual(ss.map((s) => s.source), ['editor', 'intended']);
  ss = sessionSegments([' qa1z'], 'x', { focused: false });
  assert.ok(ss[0].source === 'intended' && /not readable/.test(ss[0].why));
  // the keystroke check: a WARN with both strings, never a fail; fallbacks said so
  let kc = keystrokeCheck([segmentActual('two qstcdc4d61a', '', ed('two qstdc4d61a')), segmentActual(' ok', 'a', { focused: false })]);
  assert.ok(!kc.ok && kc.severity === 'warn' && kc.detail.includes('"two qstcdc4d61a"') && kc.detail.includes('"two qstdc4d61a"')
    && /intended text assumed/.test(kc.detail), kc.detail);
  kc = keystrokeCheck([segmentActual(' ok', 'a', ed('a ok'))]);
  assert.ok(kc.ok && kc.severity === 'warn', kc.detail);
  // run 1's case: the file holds what the editor had -> the file check passes
  const k1 = segmentActual(' one s1tok', 'Page 04 block one plain text', ed('Page 04 block one plain text one s1tok'), { block: 'B1' });
  const k2 = segmentActual('two qstcdc4d61a', '', ed('two qstdc4d61a', 'B2'), { enterFrom: ed('Page 04 block one plain text one s1tok') });
  const kt = typedFromSegments([k1, k2]);
  assert.deepStrictEqual(kt.tokens, ['one s1tok', 'two qstdc4d61a']);
  fs.writeFileSync(path.join(g, T4), b4.replace('- Page 04 block one plain text', '- Page 04 block one plain text one s1tok\n- two qstdc4d61a'));
  const e4 = { path: T4, kind: 'typed', base: b4, target: 'Page 04 block one', typed: [k1.actual], inserted: [`- ${k2.actual}`], tokens: kt.tokens };
  c = checkExpectation(e4, snapshotDir(g), []);
  assert.ok(c.ok && c.byte_exact && !kt.note, JSON.stringify(c));
  c = checkExpectation({ ...e4, inserted: ['- two qstcdc4d61a'], tokens: ['one s1tok', 'two qstcdc4d61a'] }, snapshotDir(g), []);
  assert.ok(!c.ok, 'against the keys sent the same file fails (the old check)');
  // a write that lost what the editor had still fails
  fs.writeFileSync(path.join(g, T4), b4.replace('- Page 04 block one plain text', '- Page 04 block one plain text one s1tok'));
  c = checkExpectation(e4, snapshotDir(g), []);
  assert.ok(!c.ok && c.token_counts['two qstdc4d61a'] === 0, JSON.stringify(c));
  // not read back: the intended text is assumed and the detail says so
  const kf = typedFromSegments([segmentActual(' one s1tok', null, { focused: false })]);
  c = checkExpectation({ path: T4, kind: 'typed', base: b4, target: 'Page 04 block one', typed: kf.typed, tokens: kf.tokens, note: kf.note }, snapshotDir(g), []);
  assert.ok(c.ok && /intended text assumed/.test(c.detail), JSON.stringify(c));
  // trailing blanks of a segment are not a changed line (the file side is trimmed too)
  c = checkExpectation({ path: T4, kind: 'typed', base: b4, target: 'Page 04 block one', typed: [' one s1tok '], tokens: ['one s1tok'] }, snapshotDir(g), []);
  assert.ok(c.ok, JSON.stringify(c));
  // token matching: a token that lost its last character does not match a later one
  assert.strictEqual(countToken('x qswn10z y', 'qswn1'), 0);
  assert.strictEqual(countToken('x qswn1 y', 'qswn1'), 1);
  assert.strictEqual(countToken('qswn1zqswn2z', 'qswn2z'), 1);
  // errors at or after the close request are shutdown noise
  const sp = splitShutdown([{ wall: 100 }, { wall: 5000 }, { wall: 5600 }], 5000);
  assert.deepStrictEqual([sp.run.length, sp.shutdown.length], [1, 2]);
  assert.strictEqual(splitShutdown([{ wall: 9e9 }], null).shutdown.length, 0, 'no close request: nothing is shutdown');
  const fake = new App({ t0: Date.now() }, 'fake');
  fake.lserr = [{ wall: 10, kind: 'error', msg: 'mid-run' }, { wall: 900, kind: 'pageerror', msg: 'ExceptionInfo' }];
  fake.consoleErrors = [{ wall: 950, msg: 'Unexpected webworker error' }, { wall: 20, msg: 'early' }];
  fake.closeRequestedWall = 800;
  const fsum = fake.summary();
  assert.ok(fsum.lserr.length === 1 && fsum.console_errors.length === 1 && fsum.shutdown_errors === 2
    && fsum.shutdown.lserr[0].msg === 'ExceptionInfo' && fsum.close_requested_wall === 800, JSON.stringify(fsum));
  // write guard records
  assert.deepStrictEqual(parseGuardLine('LSGUARD {"path":"pages/a.md","result":"mismatch","copy":"logseq/bak/conflicts/a.md"}'),
    { path: 'pages/a.md', result: 'mismatch', copy: 'logseq/bak/conflicts/a.md' });
  assert.strictEqual(parseGuardLine('LSPERF {}'), null);
  assert.ok(parseGuardLine('LSGUARD {bad').raw);
  assert.strictEqual(graphRel(path.join(g, 'logseq/bak/conflicts/x.md'), g), 'logseq/bak/conflicts/x.md');
  assert.strictEqual(graphRel('/elsewhere/x.md', g), null);
  assert.strictEqual(guardReport([], { appWrote: true }).status, 'absent');
  assert.strictEqual(guardReport([], {}).status, 'no-writes');
  const gr = guardReport([{ result: 'written' }, { result: 'mismatch', path: 'p' }, { result: 'io-error' }]);
  assert.ok(gr.status === 'present' && gr.refusals.length === 2 && gr.counts.written === 1, JSON.stringify(gr));
  fs.mkdirSync(path.join(g, 'logseq/bak/conflicts/pages'), { recursive: true });
  fs.writeFileSync(path.join(g, 'logseq/bak/conflicts/pages/Page 09.md'), '- proposal w1tok');
  const cc = conflictCopies([{ path: path.join(g, 'pages/Page 09.md'), result: 'mismatch', copy: path.join(g, 'logseq/bak/conflicts/pages/Page 09.md') },
    { path: 'pages/Page 09.md', result: 'mismatch', copy: 'logseq/bak/conflicts/gone.md' }, { path: 'x', result: 'written' }],
  snapshotDir(g), g, ['w1tok', 'w2tok'], { 'pages/Page 09.md': ['w1tok'] });
  assert.strictEqual(cc.length, 2);
  assert.ok(cc[0].exists && cc[0].holds_expected === true && cc[0].tokens.join() === 'w1tok', JSON.stringify(cc[0]));
  assert.ok(!cc[1].exists && cc[1].holds_expected === false, JSON.stringify(cc[1]));
  assert.strictEqual(classify('logseq/bak/conflicts/pages/Page 09.md'), 'bak');
  for (const n of [...SCENARIOS, ...OPTIONAL_SCENARIOS]) assert.ok(scenarios[n] && typeof scenarios[n].run === 'function', `scenario ${n} defined`);
  console.log(`selftest ok (${g})`);
  return 0;
}

module.exports = { makeTemplate, appendBlock, replaceBlockLine, deleteBlock, snapshotDir, verify, checkExpectation,
  lineDiff, stripAddedIdLines, samePageOutcome, assertSafePath, classify, newBaks,
  parseGuardLine, graphRel, guardReport, conflictCopies, segmentActual, sessionSegments, keystrokeCheck,
  typedFromSegments, countToken, splitShutdown };

if (require.main === module) {
  const stop = async (sig) => {
    log(`${sig}: closing ${liveApps.size} app(s)`);
    for (const a of [...liveApps]) await a.close().catch(() => {});
    process.exit(130);
  };
  process.on('SIGINT', () => stop('SIGINT'));
  process.on('SIGTERM', () => stop('SIGTERM'));
  main(process.argv.slice(2)).then((code) => process.exit(code), (e) => {
    console.error('FAILED:', e && e.stack || e);
    process.exit(1);
  });
}
