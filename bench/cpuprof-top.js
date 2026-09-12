// Summarises a V8 .cpuprofile (CDP Profiler.stop output, as lsbench.js writes
// with LSBENCH_CPUPROF=1) without DevTools: where the main thread spent its time,
// and what ran in its longest stretch without an idle sample (the boot long task).
//
// usage: node cpuprof-top.js <file.cpuprofile> [fromMs toMs] [--origin=ms] [--top=n]
//   fromMs toMs  also summarise only the samples in this window (ms after profile start;
//                lsbench's result.cpuprof.longtasks[].prof_from_ms/prof_to_ms)
//   --origin=ms  result.cpuprof.start_origin_ms: also print times relative to the
//                renderer's time origin, the clock LSUI long-task starts use
//   --top=n      rows per table (default 30)
//
// Time per sample is the gap to the next sample (the last one runs to endTime),
// as DevTools counts it. (idle) is the only sample that means the thread was
// free; (program) is native renderer work outside JS (style, layout, parsing,
// compiling) and (garbage collector) is GC, so both count as busy and are
// reported next to the JS total.
const fs = require('fs');

const args = process.argv.slice(2);
const flag = (name) => { const a = args.find((x) => x.startsWith(`--${name}=`)); return a ? Number(a.slice(name.length + 3)) : null; };
const [file, fromArg, toArg] = args.filter((a) => !a.startsWith('--'));
if (!file || (fromArg != null && !(Number(toArg) > Number(fromArg)))) {
  console.error('usage: node cpuprof-top.js <file.cpuprofile> [fromMs toMs] [--origin=ms] [--top=n]');
  process.exit(2);
}
const ORIGIN = flag('origin');
const TOP = flag('top') || 30;

const prof = JSON.parse(fs.readFileSync(file, 'utf8'));
const nodes = new Map(prof.nodes.map((n) => [n.id, n]));
const kids = (n) => n.children || [];

// ---- static tree facts ------------------------------------------------------
const hasParent = new Set(prof.nodes.flatMap(kids));
const parentOf = new Map();
for (const n of prof.nodes) for (const c of kids(n)) parentOf.set(c, n.id);
const roots = prof.nodes.filter((n) => !hasParent.has(n.id)).map((n) => n.id);
// parents before children; walked backwards it gives children before parents
const preorder = [];
for (const st = [...roots]; st.length;) { const id = st.pop(); preorder.push(id); st.push(...kids(nodes.get(id))); }

const SPECIAL = { '(idle)': 'idle', '(program)': 'program', '(garbage collector)': 'gc', '(root)': 'root' };
const cat = (id) => SPECIAL[nodes.get(id).callFrame.functionName] || 'js';

// one entry per distinct function (name + position), shared by all its call sites
const fns = [];
const fnIndex = new Map();
const fnOf = new Map();
for (const n of prof.nodes) {
  const cf = n.callFrame;
  const key = `${cf.functionName}\t${cf.url}\t${cf.lineNumber}\t${cf.columnNumber}`;
  if (!fnIndex.has(key)) {
    fnIndex.set(key, fns.length);
    const base = (cf.url || '').split(/[?#]/)[0].split('/').pop();
    // CDP lines/columns are 0-based; a release main.js is a few huge lines, so
    // the column is what locates the function
    fns.push({ name: cf.functionName || '(anonymous)', cat: cat(n.id),
      loc: base ? `${base}:${cf.lineNumber + 1}:${cf.columnNumber + 1}` : '' });
  }
  fnOf.set(n.id, fnIndex.get(key));
}

// ---- sample times (ms after profile start) ------------------------------------
const N = prof.samples.length;
const t = new Float64Array(N);
const dur = new Float64Array(N);
for (let i = 0, acc = 0; i < N; i++) { acc += prof.timeDeltas[i] || 0; t[i] = acc / 1000; }
const profMs = (prof.endTime - prof.startTime) / 1000;
for (let i = 0; i < N; i++) dur[i] = Math.max(0, (i + 1 < N ? t[i + 1] : profMs) - t[i]);
const firstAt = (ms) => { let i = 0; while (i < N && t[i] < ms) i++; return i; };

// ---- aggregation over a sample range [lo, hi) --------------------------------
function summarise(lo, hi) {
  const self = new Map(); // node id -> ms
  const by = { js: 0, program: 0, gc: 0, idle: 0, root: 0 };
  for (let i = lo; i < hi; i++) {
    const id = prof.samples[i];
    self.set(id, (self.get(id) || 0) + dur[i]);
    by[cat(id)] += dur[i];
  }
  const fnSelf = new Float64Array(fns.length);
  for (const [id, ms] of self) fnSelf[fnOf.get(id)] += ms;
  // inclusive time per node, then per function counting a recursive function
  // once per stack (only its outermost frame on the path adds its subtree)
  const sub = new Map();
  for (let k = preorder.length - 1; k >= 0; k--) {
    const id = preorder[k];
    let s = self.get(id) || 0;
    for (const c of kids(nodes.get(id))) s += sub.get(c);
    sub.set(id, s);
  }
  const fnTotal = new Float64Array(fns.length);
  const onPath = new Int32Array(fns.length);
  for (const st = roots.map((id) => [id, false]); st.length;) {
    const [id, exit] = st.pop();
    const f = fnOf.get(id);
    if (exit) { onPath[f]--; continue; }
    if (!sub.get(id)) continue;
    if (!onPath[f]) fnTotal[f] += sub.get(id);
    onPath[f]++;
    st.push([id, true]);
    for (const c of kids(nodes.get(id))) st.push([c, false]);
  }
  const busy = by.js + by.program + by.gc + by.root;
  return { lo, hi, self, by, busy, fnSelf, fnTotal };
}

// ---- printing ----------------------------------------------------------------
const f1 = (x) => x.toFixed(1);
const col = (x, w = 9) => f1(x).padStart(w);
const pct = (x, of) => (of ? (100 * x / of).toFixed(1) : '-').padStart(5);
const signed = (x) => (x < 0 ? `-${f1(-x)}` : `+${f1(x)}`);
const at = (ms) => `${f1(ms)} ms${ORIGIN != null ? ` (origin${signed(ORIGIN + ms)})` : ''}`;
const fnLabel = (f) => `${fns[f].name}${fns[f].loc ? `  ${fns[f].loc}` : ''}`;

function table(s, key, title) {
  const rows = fns.map((_, f) => f).filter((f) => fns[f].cat !== 'idle' && fns[f].cat !== 'root' && s[key][f] > 0)
    .sort((a, b) => s[key][b] - s[key][a]).slice(0, TOP);
  console.log(`-- top ${TOP} by ${title} (% of busy time) --`);
  console.log('   self ms     %   total ms     %  function  location');
  for (const f of rows) {
    console.log(`${col(s.fnSelf[f], 10)} ${pct(s.fnSelf[f], s.busy)} ${col(s.fnTotal[f], 10)} ${pct(s.fnTotal[f], s.busy)}  ${fnLabel(f)}`);
  }
}

function composition(s) {
  return `busy ${f1(s.busy)} ms = JS ${f1(s.by.js)} + (program) ${f1(s.by.program)} + GC ${f1(s.by.gc)}` +
    `${s.by.root ? ` + (root) ${f1(s.by.root)}` : ''}; idle ${f1(s.by.idle)} ms`;
}

function report(s, title) {
  const a = s.lo < N ? t[s.lo] : profMs;
  const b = s.hi > s.lo ? t[s.hi - 1] + dur[s.hi - 1] : a;
  console.log(`\n== ${title}: ${at(a)} .. ${at(b)}, ${s.hi - s.lo} samples ==`);
  console.log(composition(s));
  table(s, 'fnSelf', 'self time');
  table(s, 'fnTotal', 'total (inclusive) time');
}

// the heaviest distinct call paths: with minified names, the callers are often
// what makes a leaf recognisable
function stacks(s, n = 5, depth = 14) {
  const top = [...s.self].filter(([id]) => cat(id) !== 'idle').sort((a, b) => b[1] - a[1]).slice(0, n);
  console.log(`-- heaviest ${n} call paths by self time (leaf first) --`);
  for (const [id, ms] of top) {
    const frames = [];
    for (let x = id; x != null && cat(x) !== 'root'; x = parentOf.get(x)) frames.push(fnLabel(fnOf.get(x)));
    console.log(`${col(ms, 10)} ${pct(ms, s.busy)}  ${frames.slice(0, depth).join('\n                   <- ')}` +
      `${frames.length > depth ? `\n                   <- ... ${frames.length - depth} more` : ''}`);
  }
}

console.log(`${file}\n${f1(profMs)} ms profile, ${N} samples (mean interval ${N ? f1(profMs * 1000 / N) : '-'} µs),` +
  ` ${prof.nodes.length} nodes, ${fns.length} functions${ORIGIN != null ? `; profile start = time origin ${signed(ORIGIN)} ms` : ''}`);
const whole = summarise(0, N);
report(whole, 'whole profile');

if (fromArg != null) {
  const from = Number(fromArg), to = Number(toArg);
  const w = summarise(firstAt(from), firstAt(to));
  report(w, `window ${from}..${to} ms`);
  stacks(w);
}

// contiguous runs without an (idle) sample: one is a task, or back-to-back
// tasks with less than a sampling interval between them
const runs = [];
for (let i = 0; i < N;) {
  if (cat(prof.samples[i]) === 'idle') { i++; continue; }
  let j = i;
  while (j < N && cat(prof.samples[j]) !== 'idle') j++;
  runs.push({ lo: i, hi: j, ms: t[j - 1] + dur[j - 1] - t[i] });
  i = j;
}
runs.sort((a, b) => b.ms - a.ms);
console.log(`\n== longest busy runs (no (idle) sample), ${runs.length} in all ==`);
for (const r of runs.slice(0, 5)) {
  console.log(`${col(r.ms, 10)} ms  ${at(t[r.lo])} .. ${at(t[r.hi - 1] + dur[r.hi - 1])}  ${composition(summarise(r.lo, r.hi))}`);
}
if (runs.length) {
  const s = summarise(runs[0].lo, runs[0].hi);
  report(s, `longest busy run (${f1(runs[0].ms)} ms)`);
  stacks(s);
}
