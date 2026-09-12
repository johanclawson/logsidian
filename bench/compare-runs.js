// Side-by-side comparison of lsbench result files (reopen.json / open.json):
// the search walk, its slow slices and maintenance ticks, per-save worker
// time, thread CPU and search latency — the numbers step comparisons need.
// usage: node compare-runs.js label=path/to/reopen.json [label=...]
const path = require('path');

const runs = process.argv.slice(2).map((a) => {
  const i = a.indexOf('=');
  const [label, file] = i > 0 ? [a.slice(0, i), a.slice(i + 1)] : [path.basename(path.dirname(a)), a];
  return { label, r: require(path.resolve(file)) };
});
if (!runs.length) { console.error('usage: node compare-runs.js label=reopen.json ...'); process.exit(2); }

const q = (v, p) => { if (!v.length) return null; const s = [...v].sort((a, b) => a - b); return s[Math.floor(p * (s.length - 1))]; };
const fmt = (x) => (x == null ? '-' : typeof x === 'number' ? String(Math.round(x)) : String(x));

function summarize(r) {
  const recs = r.records || [];
  const search = recs.filter((x) => x.event === 'search');
  const done = search.filter((x) => x.step === 'upsert-done').pop();
  const slow = search.filter((x) => x.step === 'slow-slice');
  const maint = search.filter((x) => x.step === 'maint');
  const syncs = search.filter((x) => x.step === 'slow-sync');
  const maintMax = (k) => (maint.length ? Math.max(...maint.map((x) => (typeof x[k] === 'number' ? x[k] : 0))) : null);
  const maintSum = (k) => (maint.length ? maint.reduce((a, x) => a + (typeof x[k] === 'number' ? x[k] : 0), 0) : null);
  const saves = recs.filter((x) => x.event === 'apply-ops' && x.op === 'save-block');
  const cpu = recs.filter((x) => x.event === 'cpu');
  const phases = recs.filter((x) => x.phase);
  const searches = r.searches || [];
  return {
    'walk ms': done && done.ms,
    'walk rows': done && done.rows,
    'walk slices': done && done.slices,
    'max slice ms': done && done['max-slice-ms'],
    'p99 slice ms': done && done['p99-slice-ms'],
    'slices >100ms': slow.length,
    'slow median commit': q(slow.map((x) => x['commit-ms']), 0.5),
    'slow max ms': slow.length ? Math.max(...slow.map((x) => x.ms)) : null,
    'slow-sync lines': syncs.length,
    'slow-sync max ms': syncs.length ? Math.max(...syncs.map((x) => x['max-ms'] || x.ms)) : null,
    'maint lines': maint.length,
    'maint ckpts': maintSum('checkpoints') ?? maintSum('ckpts'),
    'maint ckpt max ms': maintMax('ckpt-max-ms') ?? maintMax('ckpt-ms-max'),
    'maint busy tick max': maintMax('busy-tick-max-ms'),
    'maint idle tick max': maintMax('idle-tick-max-ms'),
    'maint merge max ms': maintMax('merge-max-step-ms'),
    'maint l0 drops': maintSum('l0-drops'),
    'saves': saves.map((x) => `${x['total-ms']}/${x['store-ms']}/${x['search-ms']}`).join(' ') || '-',
    'worker busy s': cpu.filter((c) => c.worker > 50).length,
    'ui busy s': cpu.filter((c) => c.ui > 50).length,
    // the run.log summary line carries these; the result file only has the
    // worker phase/activity records they are computed from
    'worker max stall': phases.length ? Math.max(...phases.map((x) => x['max-lag-ms'] || 0)) : null,
    'search hits ms': searches.map((s) => `${s.q}:${s.nodes_ms ?? 'never'}`).join(' ') || '-',
    'restores': phases.reduce((a, x) => a + (x.restores || 0), 0),
  };
}

const rows = runs.map(({ label, r }) => ({ label, s: summarize(r) }));
const keys = Object.keys(rows[0].s);
const w0 = Math.max(...keys.map((k) => k.length));
const widths = rows.map(({ label, s }) => Math.max(label.length, ...keys.map((k) => fmt(s[k]).length)));
console.log(''.padEnd(w0) + '  ' + rows.map(({ label }, i) => label.padStart(widths[i])).join('  '));
for (const k of keys) console.log(k.padEnd(w0) + '  ' + rows.map(({ s }, i) => fmt(s[k]).padStart(widths[i])).join('  '));
console.log('\nsaves = save-block apply-ops total/store/search ms per save');
