// Summarize one lsbench result file (open.json or reopen.json).
// usage: node lssummary.js <result.json> [minMs=100]
const r = require(require('path').resolve(process.argv[2]));
const min = Number(process.argv[3] || 100);
const recs = r.records || [];

console.log(`mode ${r.mode}  launched ${r.launched_ms} ms  done ${r.done_ms} ms  quiet ${r.quiet}`);
if (r.parse_start_ms) console.log(`parse ${r.parse_start_ms} → ${r.parse_end_ms} ms (${((r.parse_end_ms - r.parse_start_ms) / 1000).toFixed(1)} s)`);

console.log('\n-- worker open path');
for (const x of recs.filter((x) => x.phase && x.phase !== 'activity')) {
  console.log(`  ${x.phase.padEnd(20)} elapsed ${String(x['elapsed-ms']).padStart(6)}  stall ${String(x['max-lag-ms']).padStart(6)}  restores ${x.restores}${x.datoms != null ? `  datoms ${x.datoms}` : ''}`);
}

console.log(`\n-- worker thread-api calls, sync or total >= ${min} ms (slowest 15)`);
recs.filter((x) => x.api && x.thread === 'worker' && Math.max(x['sync-ms'], x['total-ms']) >= min)
  .sort((a, b) => b['sync-ms'] - a['sync-ms'] || b['total-ms'] - a['total-ms']).slice(0, 15)
  .forEach((x) => console.log(`  ${x.api.replace('thread-api/', '').padEnd(26)} sync ${String(x['sync-ms']).padStart(6)}  total ${String(x['total-ms']).padStart(6)}  @${x.wall}  ${(x.args || '').replace(/logseq_local_[^"]*/, 'REPO').slice(0, 70)}`));

const acts = recs.filter((x) => x.phase === 'activity' && x['max-lag-ms'] >= min);
console.log(`\n-- worker activity windows with stall >= ${min} ms: ${acts.length}`);
acts.sort((a, b) => b['max-lag-ms'] - a['max-lag-ms']).slice(0, 10)
  .forEach((x) => console.log(`  stall ${String(x['max-lag-ms']).padStart(6)}  restores ${String(x.restores).padStart(6)} (${x['restore-ms']} ms)  @${x.wall}`));

// UI long tasks: split boot (before the worker opened the db) from the rest
const firstOpen = recs.find((x) => x.phase === 'open-sqlite');
const ui = recs.filter((x) => x.event === 'ui');
const bootEnd = firstOpen ? firstOpen.wall : 0;
const bootUi = ui.filter((x) => x.wall <= bootEnd + 1000 && x.start < 15000);
const laterUi = ui.filter((x) => !bootUi.includes(x));
console.log(`\n-- UI long tasks: ${ui.length} total`);
console.log(`  boot (buffered, before db open): ${bootUi.map((x) => x.dur).sort((a, b) => b - a).slice(0, 5).join(', ')} ms`);
const later = laterUi.map((x) => x.dur).sort((a, b) => b - a);
console.log(`  after db open: n=${later.length}  top ${later.slice(0, 8).join(', ')} ms  sum ${(later.reduce((a, b) => a + b, 0) / 1000).toFixed(1)} s`);

// per-file phases of the first-open path (worker thread-api/reset-file timers)
const rf = recs.filter((x) => x.event === 'reset-file');
if (rf.length) {
  console.log(`\n-- reset-file phases per file (n=${rf.length})`);
  for (const k of ['mldoc-ms', 'parse-ms', 'extract-ms', 'delete-ms', 'build-tx-ms', 'transact-ms', 'store-ms', 'store-calls', 'total-ms']) {
    const v = rf.map((x) => x[k]).filter((n) => typeof n === 'number').sort((a, b) => a - b);
    if (!v.length) continue;
    const q = (p) => v[Math.floor(p * (v.length - 1))];
    const sum = v.reduce((a, b) => a + b, 0);
    console.log(`  ${k.padEnd(12)} median ${String(q(0.5)).padStart(5)}  p90 ${String(q(0.9)).padStart(5)}  max ${String(v[v.length - 1]).padStart(6)}  sum ${k.endsWith('-ms') ? (sum / 1000).toFixed(1) + ' s' : sum}`);
  }
}

// worker fast path for the journals NOW/NEXT query shape (thread-api/q)
const fp = recs.filter((x) => x.event === 'q-fastpath');
if (fp.length) {
  console.log(`\n-- q fast path hits (${fp.length})`);
  fp.slice(0, 10).forEach((x) => console.log(`  @${x.wall} pages ${x.pages} blocks ${x.blocks} hits ${x.hits} ms ${x.ms} restores ${x.restores}`));
}

// thread CPU samples (>50% busy seconds) from /proc: catches a worker that is
// busy in one long synchronous task and therefore silent in LSPERF
const cpu = recs.filter((x) => x.event === 'cpu');
if (cpu.length) {
  const longest = (key) => {
    let best = { len: 0, from: 0, to: 0 }, run = null;
    for (const x of cpu.filter((c) => c[key] > 50)) {
      if (run && x.wall - run.to <= 1600) run.to = x.wall; else run = { from: x.wall, to: x.wall };
      const len = run.to - run.from + 1000;
      if (len > best.len) best = { len, from: run.from, to: run.to };
    }
    return best;
  };
  console.log('\n-- thread CPU (seconds sampled > 50% busy)');
  for (const key of ['worker', 'ui']) {
    const n = cpu.filter((c) => c[key] > 50).length;
    const b = longest(key);
    console.log(`  ${key.padEnd(6)} busy ${n} s; longest run ${(b.len / 1000).toFixed(0)} s (@${b.from}→${b.to})`);
  }
}

const errs = recs.filter((x) => x.event === 'api-error' || x.event === 'err');
if (errs.length) {
  console.log(`\n-- errors (${errs.length})`);
  errs.slice(0, 10).forEach((x) => console.log(`  @${x.wall} ${x.event === 'err' ? `renderer ${x.kind}` : `${x.thread} ${x.api} [${x.stage}]`}: ${(x.msg || '').split('\n')[0].slice(0, 200)}`));
}
// per save: worker time split into store / search sync / restores (apply-ops)
const ops = recs.filter((x) => x.event === 'apply-ops');
if (ops.length) {
  console.log(`\n-- apply-outliner-ops (${ops.length})`);
  ops.slice(0, 12).forEach((x) => console.log(`  @${x.wall} ${String(x.op).padEnd(12)} total ${String(x['total-ms']).padStart(4)}  store ${String(x['store-ms']).padStart(3)} (${x['store-calls']})  search ${String(x['search-ms']).padStart(3)} (${x['search-rows']} rows)  restores ${x['restore-ms']} ms (${x.restores})`));
}

// reopen reconcile (UI side, one line per run)
recs.filter((x) => x.event === 'reconcile').forEach((x) => {
  const { wall, event, ...rest } = x;
  console.log(`\n-- reconcile @${wall}\n  ${JSON.stringify(rest)}`);
});

// numeric fields of rate-limited lines: count, sum and max per field
const aggregate = (lines) => {
  const out = {};
  for (const x of lines) for (const [k, v] of Object.entries(x)) {
    if (typeof v !== 'number' || k === 'wall') continue;
    const a = out[k] || (out[k] = { sum: 0, max: -Infinity });
    a.sum += v; a.max = Math.max(a.max, v);
  }
  return Object.entries(out).map(([k, a]) => `${k} sum ${Math.round(a.sum)} max ${Math.round(a.max)}`).join(', ');
};

const steps = recs.filter((x) => x.event === 'search');
if (steps.length) {
  console.log('\n-- search rebuild steps');
  // progress, slow-slice, slow-sync and maint lines can number in the
  // hundreds: summarize them
  const many = new Set(['progress', 'slow-slice', 'slow-sync', 'maint']);
  steps.filter((x) => !many.has(x.step))
    .forEach((x) => { const { wall, event, step, ...rest } = x; console.log(`  @${wall} ${step} ${JSON.stringify(rest)}`); });
  for (const s of ['slow-sync', 'maint']) {
    const l = steps.filter((x) => x.step === s);
    if (l.length) console.log(`  ${s}: ${l.length} lines; ${aggregate(l)}`);
  }
  const prog = steps.filter((x) => x.step === 'progress');
  if (prog.length) {
    const last = prog[prog.length - 1];
    console.log(`  progress: ${prog.length} lines, last @${last.wall} ${last.pct}% rows ${last.rows} slices ${last.slices}`);
  }
  const slow = steps.filter((x) => x.step === 'slow-slice');
  if (slow.length) {
    const med = (k) => { const v = slow.map((x) => x[k]).sort((a, b) => a - b); return v[v.length >> 1]; };
    const max = (k) => Math.max(...slow.map((x) => x[k]));
    console.log(`  slow slices (> 100 ms): ${slow.length}; median ms ${med('ms')} (index ${med('index-ms')}, commit ${med('commit-ms')}, rows ${med('rows')}); max ms ${max('ms')} (index ${max('index-ms')}, commit ${max('commit-ms')})`);
  }
}
if (r.reindex) console.log(`\n-- reindex window: ${JSON.stringify(r.reindex)}`);

if (r.searches) {
  console.log('\n-- searches');
  for (const s of r.searches) console.log(`  "${s.q}": hits after ${s.nodes_ms ?? 'never'} ms  worker ${s.worker_max_ms} ms  ui ${s.ui_max_ms} ms  restores ${s.restores}  ${(s.apis || []).join('; ')}`);
}
