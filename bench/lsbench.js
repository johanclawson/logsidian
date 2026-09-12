// Drives the instrumented Logsidian build with Playwright for ADR-003 step 1.
// Always runs against an isolated profile: its own HOME and --user-data-dir,
// and a graph COPY under ~/.cache/lsbench — never the real graph.
//
// usage: node lsbench.js <mode> <graphDir> <profileDir> <outDir> [timeoutSec]
//   explore  launch, screenshot, list clickable texts, quit
//   open     stub the folder dialog, click "Add new graph", wait for parsing to finish
//   reopen   relaunch the same profile (graph restored from SQLite), collect LSPERF,
//            then run searches (LSBENCH_QUERIES, default "möte,ab")
//
// Two kinds of stall records:
//   LSPERF  from the db worker (20 ms heartbeat; phases, 1 s activity windows,
//           and per thread-api calls when the build has them)
//   LSUI    long tasks (>= 100 ms) on the renderer main thread, via PerformanceObserver
// "Quiet" means neither thread has reported anything for the given time.
//
// LSBENCH_CPUPROF=1 also records a V8 CPU profile of the app window's main
// thread from boot to LSBENCH_CPUPROF_SEC (default 25) s -> <outDir>/boot.cpuprofile
// (open in DevTools > Performance, or summarise with cpuprof-top.js).
const { _electron: electron } = require('/home/johan/dev/logsidian/node_modules/playwright');
const fs = require('fs');
const path = require('path');

const [mode, graph, profile, outDir, timeoutArg] = process.argv.slice(2);
const TIMEOUT = (Number(timeoutArg) || 600) * 1000;
// the perf worktree's build, never the main checkout the `logsidian` launcher runs
const APP = process.env.LSBENCH_APP || '/home/johan/dev/logsidian-perf/static/out/Logseq-linux-x64/Logseq';
if (!['explore', 'open', 'reopen'].includes(mode)) { console.error('mode?'); process.exit(2); }
if (graph && graph.includes('OneDrive')) { console.error('refusing to touch the real graph'); process.exit(2); }
fs.mkdirSync(`${profile}/home`, { recursive: true });
fs.mkdirSync(outDir, { recursive: true });

const t0 = Date.now();
const wall = () => Date.now() - t0;
const records = [];
let lastActivity = 0; // last LSPERF or LSUI record, either thread

// every console line goes to a file as it arrives, so a killed run still
// shows how far the app got
const consoleLog = fs.createWriteStream(path.join(outDir, `${mode}-console.log`));
function attach(page) {
  page.on('console', (m) => {
    const t = m.text();
    consoleLog.write(`${wall()}\t${m.type()}\t${t.slice(0, 400)}\n`);
    if (t.startsWith('LSPERF ') || t.startsWith('LSUI ')) {
      const ui = t.startsWith('LSUI ');
      try {
        records.push({ wall: wall(), ...(ui ? { event: 'ui' } : {}), ...JSON.parse(t.slice(ui ? 5 : 7)) });
        lastActivity = Date.now();
      } catch (_) {}
    } else if (t.startsWith('LSSEARCH ') || t.startsWith('LSERR ')) {
      // search-rebuild steps (search/browser.cljs) and swallowed renderer errors
      const kind = t.startsWith('LSSEARCH ') ? 'search' : 'err';
      try { records.push({ wall: wall(), event: kind, ...JSON.parse(t.slice(t.indexOf(' ') + 1)) }); } catch (_) {}
    } else if (/restore-graph!|parsing|graph\/added|Error/i.test(t)) {
      records.push({ wall: wall(), log: t.slice(0, 240) });
    }
  });
}

// how long a record's stall lasted, and when it began (harness clock)
const dur = (r) => r['elapsed-ms'] ?? r['total-ms'] ?? r.dur ?? 0;
const lag = (r) => (r.event === 'ui' ? r.dur : r['max-lag-ms'] ?? r['sync-ms'] ?? 0) || 0;
const overlaps = (r, a, b) => r.wall >= a && r.wall - dur(r) <= b;

// Thread CPU from /proc, sampled every second. The db worker is the renderer's
// "DedicatedWorker" thread, the UI is its "Logseq" thread. A worker stuck in one
// long synchronous task emits no LSPERF record until the stall ends, so without
// this a busy worker looks exactly like an idle one to quiet().
const HZ = 100;
const prevTicks = new Map();
function threadCpu() {
  const pct = { DedicatedWorker: 0, Logseq: 0 };
  const now = Date.now();
  for (const pid of fs.readdirSync('/proc')) {
    if (!/^\d+$/.test(pid)) continue;
    try {
      if (fs.readlinkSync(`/proc/${pid}/exe`) !== APP) continue;
      if (!fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').includes('--type=renderer')) continue;
      for (const tid of fs.readdirSync(`/proc/${pid}/task`)) {
        const name = fs.readFileSync(`/proc/${pid}/task/${tid}/comm`, 'utf8').trim();
        if (!(name in pct)) continue;
        const st = fs.readFileSync(`/proc/${pid}/task/${tid}/stat`, 'utf8');
        const rest = st.slice(st.lastIndexOf(')') + 2).split(' ');
        const ticks = Number(rest[11]) + Number(rest[12]); // utime + stime
        const last = prevTicks.get(`${pid}/${tid}`);
        prevTicks.set(`${pid}/${tid}`, [ticks, now]);
        if (last && now > last[1]) pct[name] += (100 * (ticks - last[0]) / HZ) / ((now - last[1]) / 1000);
      }
    } catch (_) { /* process or thread went away */ }
  }
  return pct;
}
setInterval(() => {
  const pct = threadCpu();
  if (pct.DedicatedWorker > 50 || pct.Logseq > 50) {
    lastActivity = Date.now();
    records.push({ wall: wall(), event: 'cpu', worker: Math.round(pct.DedicatedWorker), ui: Math.round(pct.Logseq) });
  }
}, 1000).unref();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function quiet(ms, maxWait) {
  // wait until neither thread has reported for `ms`, bounded by maxWait
  const end = Date.now() + maxWait;
  while (Date.now() < end) { if (lastActivity && Date.now() - lastActivity > ms) return true; await sleep(500); }
  return false;
}

async function observeUi(page) {
  await page.evaluate(() => {
    if (window.__lsui) return;
    window.__lsui = true;
    // release builds log nothing on their own: surface swallowed errors, e.g. a
    // rejected worker call inside a p/do! chain with no catch
    const show = (kind, x) => {
      const msg = x && (x.stack || x.message) ? `${x.message || ''}\n${x.stack || ''}` : String(x);
      console.log('LSERR ' + JSON.stringify({ kind, msg: msg.slice(0, 1500) }));
    };
    window.addEventListener('unhandledrejection', (e) => show('unhandledrejection', e.reason));
    window.addEventListener('error', (e) => show('error', e.error || e.message));
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) {
        if (e.duration >= 100) console.log('LSUI ' + JSON.stringify({ dur: Math.round(e.duration), start: Math.round(e.startTime) }));
      }
    }).observe({ type: 'longtask', buffered: true });
  }).catch(() => {});
}

// ---- LSBENCH_CPUPROF: name the ~5 s boot long task ----------------------------
// The task starts ~5.5 s after the renderer's time origin, before the db worker
// opens the graph, and a smaller bundle does not shorten it, so it is work done
// at boot. LSUI can only say that it happened; a sampling profile of the
// renderer main thread says what ran.
//
// Attach timing: every window is profiled from the moment Playwright reports
// it. Playwright's Electron loader holds back the app's 'ready' event until it
// has connected, so the main window (created on 'ready' by window.cljs
// create-main-window!, which calls loadURL straight away) arrives as a new target
// that Playwright pauses, initialises and resumes before it emits 'window'.
// Profiler.start therefore lands a few ms to ~100 ms after the document starts,
// possibly before index.html even commits (the profile then starts before the
// time origin: start_origin_ms < 0). A task at ~5.5 s is covered either way; the
// numbers recorded below say by how much. The splash window is profiled too but
// closes during boot, which discards its profile.
const CPUPROF = !!process.env.LSBENCH_CPUPROF;
const CPUPROF_SEC = Number(process.env.LSBENCH_CPUPROF_SEC) || 25;
const cpuprof = { sec: CPUPROF_SEC, interval_us: 500, windows: [] }; // becomes result.cpuprof
const profs = []; // { page, info, session, ready, closed }
let appPage = null; // the window that renders the app, once the harness has found it
let profStopped = null;
const errText = (e) => String((e && e.message) || e);
// a CDP call can wait on a busy (or wedged) main thread; never let it hang the run
const within = (p, ms, what) => Promise.race([p, new Promise((_, rej) =>
  setTimeout(() => rej(new Error(`${what}: no answer in ${ms} ms`)), ms).unref())]);

function profStart(w) {
  if (profs.some((p) => p.page === w)) return;
  const info = { window: profs.length, url: w.url() };
  const p = { page: w, info };
  profs.push(p);
  cpuprof.windows.push(info);
  w.once('close', () => { p.closed = true; });
  p.ready = (async () => {
    try {
      p.session = await w.context().newCDPSession(w);
      await p.session.send('Profiler.enable');
      await p.session.send('Profiler.setSamplingInterval', { interval: cpuprof.interval_us });
      await p.session.send('Profiler.start');
      info.start_wall_ms = wall();
    } catch (e) { info.error = errText(e); return; }
    // The page's own reading of the start, in ms after its time origin. Only an
    // upper bound: the evaluate runs when the main thread is next free (late if
    // a long task is already running), and it reads whatever document is loaded
    // then, perhaps the blank one before index.html (see href/origin). The
    // clock mapping at stop time is the exact figure.
    for (let i = 0; i < 3 && !(info.start_eval && info.start_eval.now != null); i++) {
      try {
        info.start_eval = await within(w.evaluate(() =>
          ({ now: Math.round(performance.now()), origin: performance.timeOrigin, href: location.href.slice(0, 120) })),
        30000, 'start evaluate');
        info.start_eval.wall_ms = wall();
      } catch (e) { info.start_eval = { error: errText(e) }; await sleep(200); } // navigated meanwhile
    }
  })();
}

// Where the page's time origin sits on the profile's clock. Profile timestamps
// (µs), the page's performance.now() and Node's process.hrtime all read
// CLOCK_MONOTONIC on Linux, so one evaluate bracketed by two hrtime reads pins
// the origin to within half its round trip. Best of 5.
async function originOnProfileClock(page) {
  let best = null;
  for (let i = 0; i < 5; i++) {
    const a = process.hrtime.bigint();
    const now = await within(page.evaluate(() => performance.now()), 30000, 'clock evaluate');
    const b = process.hrtime.bigint();
    const rtt = Number(b - a) / 1e6;
    if (!best || rtt < best.rtt_ms) best = { rtt_ms: +rtt.toFixed(2), origin_us: Number((a + b) / 2n) / 1000 - now * 1000 };
  }
  return best;
}

// Stops every profile once (timer or end of run, whichever comes first) and
// writes the app window's as boot.cpuprofile. Never throws: failures land in
// cpuprof.error / windows[i].error.
function profStop(why) {
  profStopped = profStopped || (async () => {
    try {
      Object.assign(cpuprof, { stop_reason: why, stop_wall_ms: wall() });
      const got = [];
      for (const p of profs) {
        await p.ready;
        if (!p.session || p.info.error) continue;
        if (p.closed) { p.info.closed = true; continue; }
        try {
          // map the clock before stopping: if the page is busy the evaluate
          // waits, and the profile keeps sampling meanwhile
          const clock = await originOnProfileClock(p.page).catch((e) => ({ error: errText(e) }));
          const { profile } = await within(p.session.send('Profiler.stop'), 60000, 'Profiler.stop');
          const i = p.info;
          Object.assign(i, { samples: profile.samples.length, duration_ms: Math.round((profile.endTime - profile.startTime) / 1000) });
          if (clock.error) i.clock_error = clock.error;
          else Object.assign(i, { clock_rtt_ms: clock.rtt_ms,
            start_origin_ms: Math.round((profile.startTime - clock.origin_us) / 1000),
            end_origin_ms: Math.round((profile.endTime - clock.origin_us) / 1000) });
          got.push({ p, profile });
        } catch (e) { if (p.closed) p.info.closed = true; else p.info.error = errText(e); }
        p.session.detach().catch(() => {});
      }
      // before the harness has found the app window (timer fired very early),
      // the biggest profile is the best guess
      const main = appPage ? got.find((g) => g.p.page === appPage)
        : got.sort((a, b) => b.profile.samples.length - a.profile.samples.length)[0];
      for (const g of got) {
        g.p.info.file = g === main ? 'boot.cpuprofile' : `boot-w${g.p.info.window}.cpuprofile`;
        fs.writeFileSync(path.join(outDir, g.p.info.file), JSON.stringify(g.profile));
      }
      if (main) {
        const { start_wall_ms, start_origin_ms, end_origin_ms, clock_error } = main.p.info;
        Object.assign(cpuprof, { file: 'boot.cpuprofile', app_window: main.p.page === appPage,
          start_wall_ms, start_origin_ms, end_origin_ms, ...(clock_error ? { clock_error } : {}) });
      } else {
        const own = profs.find((p) => p.page === appPage);
        cpuprof.error = `no profile of the app window${own && own.info.error ? `: ${own.info.error}` : ''}`;
      }
    } catch (e) { cpuprof.error = errText(e); }
  })();
  return profStopped;
}

(async () => {
  const app = await electron.launch({
    executablePath: APP,
    // the tool shell has no X/Wayland session; Chromium's headless ozone
    // platform renders offscreen and still supports screenshots
    args: ['--no-sandbox', '--ozone-platform=headless', `--user-data-dir=${profile}/chromium`,
      // the GC experiment calls gc() inside the db worker
      ...(process.env.LSBENCH_GCTEST ? ['--js-flags=--expose-gc'] : [])],
    env: { ...process.env, HOME: `${profile}/home`, ELECTRON_ENABLE_LOGGING: '1' },
    timeout: 120000,
  });
  if (CPUPROF) {
    // first thing after launch, before any await, so no window slips past
    app.on('window', profStart);
    for (const w of app.windows()) profStart(w);
    // measured from t0 (just before launch), the zero of every *_wall_ms figure
    setTimeout(() => profStop(`${CPUPROF_SEC} s timer`), Math.max(0, CPUPROF_SEC * 1000 - wall())).unref();
  }
  app.on('window', attach);
  for (const w of app.windows()) attach(w);
  if (graph) {
    await app.evaluate(({ dialog }, g) => {
      dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [g] });
    }, graph);
  }
  // the splash screen is its own window; wait for the one that renders the app
  let page;
  const end = Date.now() + 120000;
  while (!page && Date.now() < end) {
    for (const w of app.windows()) {
      if (await w.$('#app-container, #main-container, .cp__sidebar-main-layout').catch(() => null)) page = w;
    }
    if (!page) await sleep(500);
  }
  page = page || (await app.firstWindow());
  if (CPUPROF) { appPage = page; profStart(page); } // no-op unless its 'window' event was missed
  await observeUi(page);
  const result = { mode, graph, launched_ms: wall() };
  if (CPUPROF) result.cpuprof = cpuprof; // shared object: the partial checkpoints show progress
  // checkpoint every 2 s: a run stopped from outside keeps its records
  setInterval(() => fs.writeFileSync(path.join(outDir, `${mode}.partial.json`),
    JSON.stringify({ ...result, partial_ms: wall(), records }, null, 1)), 2000).unref();

  if (mode === 'explore') {
    await sleep(8000);
    await page.screenshot({ path: path.join(outDir, 'explore.png') });
    result.texts = await page.evaluate(() =>
      [...document.querySelectorAll('button, a, [role=button], .cp__left-sidebar *')]
        .map((e) => (e.innerText || e.title || '').trim()).filter((t) => t && t.length < 60).slice(0, 120));
    result.hash = await page.evaluate(() => location.hash);
  }

  if (mode === 'open') {
    await sleep(5000);
    // "Add new graph" lives in the graph menu (repo.cljs repos-footer); the All
    // graphs page is the fallback. Click through the DOM: a Playwright pointer
    // click on the popup button retries forever while the popup layer animates.
    const clickText = (t) => page.evaluate((t) => {
      const el = [...document.querySelectorAll('button, a, [role=menuitem], [role=button]')]
        .find((e) => (e.innerText || '').trim().includes(t));
      if (!el) return false;
      el.click();
      return true;
    }, t);
    let clicked = false;
    for (const attempt of ['menu', 'graphs']) {
      if (attempt === 'graphs') await page.evaluate(() => { location.hash = '#/graphs'; });
      await sleep(1500);
      if (await clickText('Add new graph')) { clicked = attempt; break; }
      if (attempt === 'menu') {
        // the handler is on the inner <a>, not the selector div (repo.cljs graphs-selector)
        await page.evaluate(() => document.querySelector('.cp__graphs-selector a.item')?.click());
        await sleep(800);
        if (await clickText('Add new graph')) { clicked = attempt; break; }
      }
    }
    result.clicked = clicked;
    result.click_ms = wall();
    await page.screenshot({ path: path.join(outDir, 'after-click.png') });
    if (clicked) {
      // container.cljs shows "Parsing files n/total" until parsing ends
      const parsing = () => page.evaluate(() => document.body.innerText.includes('Parsing files')).catch(() => false);
      const deadline = Date.now() + TIMEOUT;
      let seen = false;
      const s0 = Date.now();
      while (!seen && Date.now() - s0 < 60000) { seen = await parsing(); if (!seen) await sleep(250); }
      result.parse_seen = seen;
      result.parse_start_ms = wall();
      while (seen && Date.now() < deadline && (await parsing())) await sleep(500);
      result.parse_end_ms = wall();
      await page.screenshot({ path: path.join(outDir, 'parse-done.png') });
      // the search index rebuild after parsing ships every block worker -> UI
      // -> worker; wait for both threads, not just the worker
      result.quiet = await quiet(20000, Math.max(30000, deadline - Date.now()));
      result.done_ms = wall();
    }
    await page.screenshot({ path: path.join(outDir, 'open-done.png') });
  }

  if (mode === 'reopen') {
    result.quiet = await quiet(10000, TIMEOUT);
    result.done_ms = wall();
    await page.screenshot({ path: path.join(outDir, 'reopen.png') });

    // Search after reopen. The first query pays the lazy Fuse build over pages
    // (worker/search.cljs fuzzy-search, skipped above 2500 pages); a query of
    // <= 2 chars adds a LIKE scan of blocks_fts.
    // Optional: scroll the journals home to the bottom n times and record which
    // journal dates are visible after each step. With working paging the dates
    // keep getting older; if endReached never loads more, they stop after the
    // first page (10 journals).
    if (process.env.LSBENCH_SCROLL) {
      const n = Number(process.env.LSBENCH_SCROLL) || 5;
      result.scroll = [];
      for (let i = 0; i < n; i++) {
        const start = wall();
        await page.evaluate(() => {
          const c = document.querySelector('#main-content-container') || document.scrollingElement;
          c.scrollTop = c.scrollHeight;
        });
        await sleep(2000);
        const titles = await page.evaluate(() =>
          [...document.querySelectorAll('.journal-item')]
            .map((e) => (e.querySelector('h1, .page-title, .title') || e).innerText.split('\n')[0].trim())
            .filter(Boolean));
        result.scroll.push({ i, visible: titles.length, first: titles[0], last: titles[titles.length - 1],
          worker_max_ms: Math.max(0, ...records.filter((r) => r.phase && overlaps(r, start, wall())).map(lag)) });
      }
      await page.screenshot({ path: path.join(outDir, 'scroll.png') });
      await page.evaluate(() => {
        const c = document.querySelector('#main-content-container') || document.scrollingElement;
        c.scrollTop = 0;
      });
      await sleep(1000);
    }

    // Optional: does a GC in the db worker throw away restored index nodes?
    // (persistent-sorted-set holds restored nodes through js/WeakRef only.)
    // Same search cold, warm, then gc() in the worker across a task boundary,
    // then the same search again; restores per window. Needs the app started
    // with --js-flags=--expose-gc (set below when LSBENCH_GCTEST is on).
    if (process.env.LSBENCH_GCTEST) {
      const q = process.env.LSBENCH_GCTEST_QUERY || 'möte';
      const one = async (label) => {
        await page.keyboard.press('Escape');
        await page.keyboard.press('Control+k');
        const input = await page.waitForSelector('input.cp__cmdk-search-input', { timeout: 10000 }).catch(() => null);
        if (!input) return { label, error: 'no search input' };
        const start = wall();
        await input.fill(q);
        await sleep(1500);
        const end = Date.now() + 60000;
        while (Date.now() < end && Date.now() - lastActivity < 3000) await sleep(250);
        const stop = wall();
        await page.keyboard.press('Escape');
        await sleep(500);
        const win = records.filter((r) => overlaps(r, start, stop));
        return { label, start, stop,
          restores: win.filter((r) => r.phase).reduce((a, r) => a + (r.restores || 0), 0),
          restore_ms: Math.round(win.filter((r) => r.phase).reduce((a, r) => a + (r['restore-ms'] || 0), 0)),
          apis: win.filter((r) => r.api).map((r) => `${r.api.replace('thread-api/', '')} ${r['sync-ms']}/${r['total-ms']}ms`) };
      };
      result.gctest = [];
      result.gctest.push(await one('cold'));
      result.gctest.push(await one('warm'));
      const workers = page.workers();
      const gcRan = [];
      for (const w of workers) gcRan.push(await w.evaluate(() => { if (typeof gc === 'function') { gc(); return true; } return false; }).catch((e) => String(e)));
      await sleep(1500); // let the worker reach later tasks before the next search
      result.gctest.push({ label: 'gc', workers: workers.length, gc_ran: gcRan });
      result.gctest.push(await one('after-gc'));
      result.gctest.push(await one('warm-again'));
    }

    // Optional: type into the first block of today's journal (graph COPY) and
    // measure the edit path: thread-api calls (apply-outliner-ops includes the
    // per-transaction search sync), worker stalls and UI long tasks.
    if (process.env.LSBENCH_TYPE) {
      // An existing block with text, so every save UPDATEs its search row (a new
      // empty block's first save is an INSERT and skips the delete path). Two
      // bursts with a pause: each burst ends in its own save.
      const n = Number(process.env.LSBENCH_TYPE) || 40;
      // Pick by block id and re-resolve before every click: React replaces the
      // block's DOM when the editor opens and after each save, so a held element
      // handle goes stale ("not attached to the DOM").
      const blockId = await page.evaluate(() => {
        for (const el of document.querySelectorAll('.journal-item .ls-block[blockid]')) {
          const c = el.querySelector('.block-content');
          if (c && c.innerText.trim().length > 3) return el.getAttribute('blockid');
        }
        return null;
      }).catch(() => null);
      const target = () => page.locator(`.ls-block[blockid="${blockId}"] .block-content`).first();
      if (!blockId) {
        result.typing = { error: 'no non-empty journal block to type into' };
      } else try {
        result.typing = { n, blockId, bursts: [] };
        const editing = () => page.evaluate(() => document.activeElement && document.activeElement.tagName === 'TEXTAREA').catch(() => false);
        for (let burst = 0; burst < 2; burst++) {
          await target().scrollIntoViewIfNeeded({ timeout: 5000 }).catch(() => {});
          await target().click({ timeout: 5000 }).catch(() => {});
          await sleep(800);
          if (!(await editing())) { await target().click({ timeout: 5000 }).catch(() => {}); await sleep(800); }
          if (!(await editing())) {
            // not in the editor: typing would scroll the page, not edit a block
            result.typing.bursts.push({ editing: false });
            continue;
          }
          await page.keyboard.press('End');
          const start = wall();
          for (let i = 0; i < n; i++) {
            await page.keyboard.type(i % 8 === 7 ? ' ' : 'x');
            await sleep(90);
          }
          await sleep(1500); // the editor saves after a short idle
          await page.keyboard.press('Escape');
          await sleep(3500);
          const stop = wall();
          const win = records.filter((r) => overlaps(r, start, stop));
          result.typing.bursts.push({ start, stop,
            apis: win.filter((r) => r.api).map((r) => `${r.api.replace('thread-api/', '')} ${r['sync-ms']}/${r['total-ms']}ms`),
            worker_max_ms: Math.max(0, ...win.filter((r) => r.phase).map(lag)),
            ui_max_ms: Math.max(0, ...win.filter((r) => r.event === 'ui').map(lag)),
            ui_longtasks: win.filter((r) => r.event === 'ui').length,
            slow_syncs: win.filter((r) => r.event === 'search' && r.step === 'slow-sync').map((r) => `${r.ms}ms/${r.rows}rows/${r.deletes}del`) });
        }
        await page.screenshot({ path: path.join(outDir, 'typing.png') });
      } catch (e) {
        // record and carry on, so the run still closes the app and writes its result
        result.typing.error = String((e && e.message) || e);
      }
    }

    // Optional: rebuild the search index through the command palette
    // (:search/re-index "Rebuild search index" -> search-handler/rebuild-indices!),
    // then wait for both threads to go quiet. Discriminates "the rebuild never
    // ran" from "the rebuild fails at this size" (count blocks_fts afterwards).
    if (process.env.LSBENCH_REINDEX) {
      // Use the key chord, not the palette: typing the command name runs a
      // file-search per keystroke, and Enter picks "Create page" first.
      await page.keyboard.press('Escape');
      await page.evaluate(() => document.activeElement && document.activeElement.blur && document.activeElement.blur());
      const box = true;
      if (box) {
        const start = wall();
        await page.keyboard.press('Control+c');
        await page.keyboard.press('Control+s');
        // Wait for the rebuild to end (LSSEARCH upsert-done or failed), not for
        // silence: a worker blocked in one long synchronous build reports
        // nothing until the stall ends, so silence can mean "busy".
        const maxMs = (Number(process.env.LSBENCH_REINDEX_MAX) || 1200) * 1000;
        const until = Date.now() + maxMs;
        const ended = () => records.find((r) => r.event === 'search' && r.wall >= start && (r.step === 'upsert-done' || r.step === 'failed'));
        while (!ended() && Date.now() < until) await sleep(1000);
        result.reindex_end = ended() || null;
        result.reindex_ms = result.reindex_end ? result.reindex_end.wall - start : null;
        result.reindex_quiet = await quiet(10000, 120000);
        const stop = wall();
        result.reindex = { start, stop,
          apis: records.filter((r) => r.api && overlaps(r, start, stop)).map((r) => `${r.api} ${r['sync-ms']}/${r['total-ms']}ms`),
          worker_max_ms: Math.max(0, ...records.filter((r) => r.phase && overlaps(r, start, stop)).map(lag)),
          ui_max_ms: Math.max(0, ...records.filter((r) => r.event === 'ui' && overlaps(r, start, stop)).map(lag)),
          notifications: await page.evaluate(() => [...document.querySelectorAll('.notification-area, .ui__notifications, [class*=notification]')]
            .map((e) => e.innerText.trim()).filter(Boolean).slice(0, 5)).catch(() => []) };
        await page.screenshot({ path: path.join(outDir, 'reindex.png') });
      } else {
        result.reindex = { error: 'no search input' };
      }
    }

    // LSBENCH_QUERIES=none skips the searches
    const queries = (process.env.LSBENCH_QUERIES || 'möte,ab').split(',').filter((q) => q && q !== 'none');
    result.searches = [];
    for (const [i, q] of queries.entries()) {
      await page.keyboard.press('Control+k');
      const input = await page.waitForSelector('input.cp__cmdk-search-input', { timeout: 10000 }).catch(() => null);
      if (!input) { result.searches.push({ q, error: 'no search input' }); break; }
      const start = wall();
      await input.fill(q);
      // user-facing latency: until the panel lists block/page hits ("Nodes")
      let nodes_ms = null;
      const s0 = Date.now();
      while (Date.now() - s0 < 30000) {
        if (await page.evaluate(() => /\bNodes\b/.test(document.body.innerText)).catch(() => false)) { nodes_ms = wall() - start; break; }
        await sleep(100);
      }
      // then settle: both threads quiet for 3 s, bounded
      const settleEnd = Date.now() + 60000;
      await sleep(500);
      while (Date.now() < settleEnd && Date.now() - lastActivity < 3000) await sleep(250);
      const stop = wall();
      const win = records.filter((r) => (r.phase || r.api || r.event === 'ui') && overlaps(r, start, stop));
      result.searches.push({ q, start, stop, nodes_ms,
        worker_max_ms: Math.max(0, ...win.filter((r) => r.event !== 'ui').map(lag)),
        ui_max_ms: Math.max(0, ...win.filter((r) => r.event === 'ui').map(lag)),
        restores: win.reduce((a, r) => a + (r.restores || 0), 0),
        apis: win.filter((r) => r.api).map((r) => `${r.api} ${r['sync-ms']}/${r['total-ms']}ms`) });
      await page.screenshot({ path: path.join(outDir, `search-${i}.png`) });
      await page.keyboard.press('Escape');
      await sleep(1000);
    }
    result.done_ms = wall();
  }

  if (CPUPROF) {
    try {
      await profStop('end of run');
      // The long LSUI tasks as windows for cpuprof-top.js (ms after profile
      // start); LSUI starts are relative to the same time origin as start_origin_ms.
      // covered = the profile spans the whole task.
      if (cpuprof.start_origin_ms != null) {
        cpuprof.longtasks = records.filter((r) => r.event === 'ui' && r.dur >= 1000).map((r) => ({
          start: r.start, dur: r.dur,
          prof_from_ms: r.start - cpuprof.start_origin_ms, prof_to_ms: r.start + r.dur - cpuprof.start_origin_ms,
          covered: r.start >= cpuprof.start_origin_ms && r.start + r.dur <= cpuprof.end_origin_ms }));
      }
      if (cpuprof.error) result.cpuprof_error = cpuprof.error;
    } catch (e) { result.cpuprof_error = errText(e); }
  }
  result.records = records;
  fs.writeFileSync(path.join(outDir, `${mode}.json`), JSON.stringify(result, null, 1));
  const worker = records.filter((r) => r.event === 'phase' || r.api);
  const ui = records.filter((r) => r.event === 'ui');
  console.log(JSON.stringify({ mode, launched_ms: result.launched_ms, done_ms: result.done_ms,
    clicked: result.clicked, quiet: result.quiet, records: records.length,
    worker_max_ms: Math.max(0, ...worker.map(lag)),
    ui_max_ms: Math.max(0, ...ui.map(lag)), ui_longtasks: ui.length,
    restores: records.filter((r) => r.event === 'phase').reduce((a, p) => a + (p.restores || 0), 0),
    ...(CPUPROF ? { cpuprof_file: cpuprof.file, cpuprof_start_origin_ms: cpuprof.start_origin_ms,
      cpuprof_error: result.cpuprof_error } : {}) }));
  await app.close();
})().catch((e) => { console.error('FAILED:', e && e.stack || e); process.exit(1); });
