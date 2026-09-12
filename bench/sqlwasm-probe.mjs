// The search db's statements on the bundled sqlite-wasm 3.50.3 in plain Node:
// what the cljs test fakes cannot check. The SQL is read out of
// src/main/frontend/worker/search.cljs (its string literals), so this runs the
// exact statements: tables, triggers, fts-config, upsert-sql, delete-sql, the
// meta upsert, the merge step, and the lone-surrogate regex. It also checks the
// capi calls the maintenance tick uses (sqlite3_db_status CACHE_WRITE through
// wasm.pstack, sqlite3_txn_state).
// usage (from the repo root): node bench/sqlwasm-probe.mjs
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { randomUUID } from 'node:crypto';
import sqlite3InitModule from '/home/johan/dev/logsidian-perf/node_modules/@sqlite.org/sqlite-wasm/node.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const src = readFileSync(join(here, '../src/main/frontend/worker/search.cljs'), 'utf8');
// every string literal of search.cljs in order, cljs escapes undone
const lits = [...src.matchAll(/"((?:[^"\\]|\\.)*)"/g)]
  .map((m) => m[1].replace(/\\(.)/g, (_, c) => ({ n: '\n', t: '\t' })[c] ?? c));
const pick = (pred, what) => {
  const s = lits.find(pred);
  if (!s) throw new Error('not found in search.cljs: ' + what);
  return s;
};
const createBlocks = pick((s) => s.startsWith('CREATE TABLE IF NOT EXISTS blocks ('), 'blocks table');
const createFts = pick((s) => s.startsWith('CREATE VIRTUAL TABLE IF NOT EXISTS blocks_fts'), 'blocks_fts');
const createMeta = pick((s) => s.startsWith('CREATE TABLE IF NOT EXISTS search_meta'), 'search_meta');
const triggers = lits.filter((s) => s.startsWith('CREATE TRIGGER IF NOT EXISTS blocks_'));
const ftsConfigSql = pick((s) => s === 'INSERT INTO blocks_fts(blocks_fts, rank) VALUES ($k, $v)', 'fts-config write');
const metaSql = pick((s) => s.startsWith('INSERT INTO search_meta (k, v) VALUES ($k, $v)'), 'meta upsert');
const deleteSql = pick((s) => s.startsWith('DELETE FROM blocks WHERE id IN'), 'delete-sql');
const mergeSql = pick((s) => s.startsWith("INSERT INTO blocks_fts(blocks_fts, rank) VALUES ('merge'"), 'merge step');
const ui = lits.findIndex((s) => s.startsWith('INSERT INTO blocks (id, title, page)'));
let upsertSql = lits[ui];
for (let k = ui + 1; lits[k].startsWith(' '); k++) upsertSql += lits[k];
const ftsConfig = Object.fromEntries(
  [...src.match(/\(def fts-config[\s\S]*?\{([^}]*)\}\)/)[1].matchAll(/"(\w+)" (\d+)/g)].map((m) => [m[1], +m[2]]));
const loneRe = new RegExp(pick((s) => s.startsWith('[\\uD800'), 'lone-surrogate regex'), 'g');
const replaceLone = (s) => s.replace(loneRe, '\uFFFD');
if (triggers.length !== 3) throw new Error('expected 3 triggers, found ' + triggers.length);

let bad = 0;
const check = (cond, msg) => {
  if (!cond) bad++;
  console.log((cond ? 'ok   ' : 'FAIL ') + msg);
};

const sqlite3 = await sqlite3InitModule({ print: () => {}, printErr: () => {} });
const { capi, wasm } = sqlite3;
console.log('sqlite-wasm', capi.sqlite3_libversion(), '| upsert-sql:', upsertSql.length, 'chars | fts-config', JSON.stringify(ftsConfig));

function openSearchDb(name) {
  const db = new sqlite3.oo1.DB(name, 'c');
  // create-tables-and-triggers!* order
  db.exec(createBlocks);
  db.exec(createFts);
  for (const [k, v] of Object.entries(ftsConfig)) db.exec({ sql: ftsConfigSql, bind: { $k: k, $v: v } });
  for (const t of triggers) db.exec(t);
  db.exec(createMeta);
  db.exec({ sql: metaSql, bind: { $k: 'schema', $v: '2' } });
  return db;
}
const db = openSearchDb(':memory:');
const q = (sql, bind) => db.exec({ sql, bind, rowMode: 'array', returnValue: 'resultRows' });
const one = (sql, bind) => q(sql, bind)[0]?.[0];

function varint(b, i) {
  let v = 0;
  for (let n = 0; n < 9; n++) {
    const x = b[i + n];
    if (n === 8) return [v * 256 + x, i + 9];
    if (x < 128) return [v * 128 + x, i + n + 1];
    v = v * 128 + (x & 127);
  }
}
// structure-level0-segments
function level0() {
  const b = one('SELECT block FROM blocks_fts_data WHERE id = 10');
  if (!b) return 0;
  const v2 = b.length >= 8 && b[4] === 0xff && b[5] === 0 && b[6] === 0 && b[7] === 1;
  let [nLevel, i] = varint(b, v2 ? 8 : 4);
  [, i] = varint(b, i);
  [, i] = varint(b, i);
  if (!nLevel) return 0;
  [, i] = varint(b, i);
  return varint(b, i)[0];
}
// search/sync-rows!: delete, upsert, meta, each its own exec, one transaction
function syncRows(removeIds, rows, meta) {
  let upserted = null;
  db.transaction((tx) => {
    if (removeIds.length) tx.exec({ sql: deleteSql, bind: { $ids: JSON.stringify(removeIds) } });
    if (rows.length) {
      tx.exec({ sql: upsertSql, bind: { $rows: JSON.stringify(rows) } });
      upserted = tx.changes();
    }
    for (const [k, v] of Object.entries(meta)) tx.exec({ sql: metaSql, bind: { $k: k, $v: String(v) } });
  });
  return upserted;
}
const aligned = () => {
  const nb = one('SELECT count(*) FROM blocks');
  const nf = one('SELECT count(*) FROM blocks_fts');
  const nj = one('SELECT count(*) FROM blocks b JOIN blocks_fts f ON f.rowid = b.rowid WHERE f.id = b.id AND f.title = b.title AND f.page IS b.page');
  return [nb === nf && nf === nj, `blocks ${nb}, blocks_fts ${nf}, same rowid+id+title+page ${nj}`];
};
const integrity = () => {
  try {
    db.exec("INSERT INTO blocks_fts(blocks_fts, rank) VALUES ('integrity-check', 1)");
    return one('PRAGMA integrity_check') === 'ok';
  } catch (e) {
    console.log('     integrity-check:', e.message);
    return false;
  }
};
const page = randomUUID();
const words = 'alpha beta gamma delta epsilon zeta eta theta iota kappa lambda'.split(' ');
const title = (i) => `${words[i % 11]} block ${i} ${words[(i * 7) % 11]}`;

// 1. a first commit of new rows
const ids = Array.from({ length: 50 }, () => randomUUID());
let l0 = level0();
check(syncRows([], ids.map((id, i) => [id, title(i), page]), { blocks_indexed_tx: 1 }) === 50, 'new rows: 50 inserted');
check(level0() - l0 === 1, `new rows: one level-0 segment for the commit (${l0} -> ${level0()})`);
check(...aligned());

// 2. mixed: updates, unchanged rows and new rows, interleaved in input order
const rowidOf = (id) => one('SELECT rowid FROM blocks WHERE id = ?', [id]);
const maxRowid = one('SELECT max(rowid) FROM blocks');
const upd = ids.slice(0, 10), same = ids.slice(10, 30), fresh = Array.from({ length: 15 }, () => randomUUID());
const before = Object.fromEntries(upd.map((id) => [id, rowidOf(id)]));
const mixed = [];
for (let i = 0; i < 20; i++) {
  if (i < 10) mixed.push([upd[9 - i], 'updated ' + title(i), page]); // reverse rowid order on purpose
  mixed.push([same[19 - i], title(29 - i), page]);                     // unchanged
  if (i < 15) mixed.push([fresh[i], 'fresh ' + title(i), page]);
}
l0 = level0();
const n = syncRows([], mixed, { blocks_indexed_tx: 2 });
check(n === 25, `mixed: the statement wrote ${n} rows (10 updates + 15 inserts; 20 unchanged wrote nothing)`);
check(level0() - l0 === 1, `mixed: one level-0 segment for the commit (${l0} -> ${level0()})`);
check(upd.every((id) => rowidOf(id) === before[id]), 'mixed: updated rows keep their rowid');
check(fresh.every((id) => rowidOf(id) > maxRowid), 'mixed: new rows get rowids above every existing one');
check(upd.every((id) => one('SELECT title FROM blocks_fts WHERE rowid = ?', [rowidOf(id)]).startsWith('updated ')),
  'mixed: blocks_fts has the new titles at the same rowids');
check(...aligned());

// 3. a no-op re-sync of the same rows
l0 = level0();
const changes0 = db.changes(true);
const n2 = syncRows([], mixed, { blocks_indexed_tx: 3 });
check(n2 === 0, `re-sync: the statement wrote ${n2} rows`);
check(level0() === l0, `re-sync: no segment (${l0} -> ${level0()})`);
check(db.changes(true) - changes0 === 1, `re-sync: total_changes +${db.changes(true) - changes0} (the meta row only)`);

// 4. deletes (one id not in the index), alone and with an upsert in one transaction
const gone = [ids[40], ids[41], ids[42], fresh[3], randomUUID()];
l0 = level0();
syncRows(gone, [], { blocks_indexed_tx: 4 });
check(gone.every((id) => rowidOf(id) === undefined), 'delete: the rows are gone');
check(level0() - l0 <= 1, `delete: ${level0() - l0} level-0 segment(s) for the commit`);
check(...aligned());
l0 = level0();
syncRows([ids[43]], [[ids[44], 'both ' + title(44), page], [randomUUID(), 'both new', page]], { blocks_indexed_tx: 5 });
console.log(`     delete + upsert in one commit (two statements): ${level0() - l0} level-0 segment(s)`);
check(...aligned());
check(integrity(), 'integrity-check (FTS5 against content, and PRAGMA integrity_check)');

// 5. rollback: the meta write fails, so neither rows nor meta commit
db.exec("CREATE TEMP TRIGGER fail_meta BEFORE INSERT ON search_meta BEGIN SELECT RAISE(ABORT, 'meta write failed'); END");
const nBlocks = one('SELECT count(*) FROM blocks');
const tx5 = one("SELECT v FROM search_meta WHERE k = 'blocks_indexed_tx'");
let threw = false;
try {
  syncRows([ids[45]], [[ids[46], 'rolled back', page], [randomUUID(), 'rolled back new', page]], { blocks_indexed_tx: 6 });
} catch (e) {
  threw = /meta write failed/.test(e.message);
}
db.exec('DROP TRIGGER temp.fail_meta');
check(threw, 'rollback: the failed meta write throws out of the transaction');
check(one('SELECT count(*) FROM blocks') === nBlocks && rowidOf(ids[45]) !== undefined, 'rollback: the delete is undone');
check(one('SELECT title FROM blocks WHERE id = ?', [ids[46]]) === title(46), 'rollback: the update is undone');
check(one("SELECT v FROM search_meta WHERE k = 'blocks_indexed_tx'") === tx5, 'rollback: the watermark is unchanged');
check(...aligned());

// 6. lone surrogates through the JSON parameter
const hexOf = (id) => one('SELECT hex(title) FROM blocks WHERE id = ?', [id]);
const [s1, s2, s3] = [randomUUID(), randomUUID(), randomUUID()];
syncRows([], [[s1, 'a\uD800b', page], [s2, replaceLone('a\uD800b'), page], [s3, 'a\u0000b "q" \\ möte 😀', page]], {});
check(hexOf(s1) === '61EDA08062', `lone surrogate as JSON.stringify sends it: ${hexOf(s1)} (invalid UTF-8)`);
check(hexOf(s2) === '61EFBFBD62', `after the regex fallback: ${hexOf(s2)} (U+FFFD)`);
check(hexOf(s3) === Buffer.from('a\u0000b "q" \\ möte 😀', 'utf8').toString('hex').toUpperCase(),
  'U+0000, quotes, backslash, non-ASCII: the stored bytes are the UTF-8 of the title');
const units = ['a', 'ö', '\uD83D', '\uDE00', '\uD800', '\uDBFF', '\uDC00', '\uDFFF', 'x'];
let same2 = 0, tried = 0;
for (let i = 0; i < 20000; i++) {
  let s = '';
  for (let k = 0, len = 1 + (i % 7); k < len; k++) s += units[(i * 31 + k * 17 + ((i * k) % 5)) % units.length];
  tried++;
  if (replaceLone(s) === s.toWellFormed()) same2++;
}
check(same2 === tried, `regex fallback = String.prototype.toWellFormed on ${same2}/${tried} strings`);

// 7. merge steps with the exact statement
l0 = level0();
let steps = 0, last = 0;
for (; steps < 100; steps++) {
  const c0 = db.changes(true);
  db.exec({ sql: mergeSql, bind: { $n: 8 } });
  last = db.changes(true) - c0;
  if (last < 2) break;
}
check(last === 1, `merge: ${steps} step(s) with work, then one with nothing to merge changes ${last} row`);
console.log(`     level 0: ${l0} -> ${level0()} segments (usermerge ${ftsConfig.usermerge})`);
check(integrity(), 'integrity-check after the merges');
check(...aligned());

// 8. the capi calls the maintenance tick makes (search/cache-writes, search/txn-open?)
function cacheWrites(d) {
  const pos = wasm.pstack.pointer;
  try {
    const out = wasm.pstack.alloc(8);
    const rc = capi.sqlite3_db_status(d.pointer, capi.SQLITE_DBSTATUS_CACHE_WRITE, out, out + 4, 0);
    return rc === 0 ? wasm.peek32(out) : null;
  } finally {
    wasm.pstack.restore(pos);
  }
}
const txnState = (d) => capi.sqlite3_txn_state(d.pointer, 'main');
check(typeof capi.SQLITE_DBSTATUS_CACHE_WRITE === 'number' && typeof capi.SQLITE_TXN_NONE === 'number',
  `capi constants: SQLITE_DBSTATUS_CACHE_WRITE ${capi.SQLITE_DBSTATUS_CACHE_WRITE}, SQLITE_TXN_NONE ${capi.SQLITE_TXN_NONE}`);
const p0 = wasm.pstack.pointer;
check(typeof cacheWrites(db) === 'number' && wasm.pstack.pointer === p0, `sqlite3_db_status: a number (${cacheWrites(db)} on :memory:), pstack restored`);
let states = [txnState(db)];
db.transaction(() => {
  states.push(txnState(db));
  one('SELECT count(*) FROM blocks');
  states.push(txnState(db));
  db.exec({ sql: metaSql, bind: { $k: 'probe', $v: '1' } });
  states.push(txnState(db));
});
states.push(txnState(db));
check(JSON.stringify(states) === JSON.stringify([0, 0, 1, 2, 0]), `sqlite3_txn_state: outside, BEGIN, read, write, after COMMIT = ${JSON.stringify(states)}`);

// the same on a file db in WAL mode, if this Node build has a file VFS
let dir;
try {
  dir = mkdtempSync(join(tmpdir(), 'sqlwasm-'));
  const f = openSearchDb(join(dir, 'search.db'));
  const mode = f.exec({ sql: 'PRAGMA journal_mode=WAL', rowMode: 'array', returnValue: 'resultRows' })[0][0];
  f.exec('PRAGMA synchronous=NORMAL');
  const w0 = cacheWrites(f);
  f.transaction((tx) => tx.exec({ sql: upsertSql, bind: { $rows: JSON.stringify(Array.from({ length: 50 }, (_, i) => [randomUUID(), title(i), page])) } }));
  const w1 = cacheWrites(f);
  const [busy, log, ckpt] = f.exec({ sql: 'PRAGMA wal_checkpoint(PASSIVE)', rowMode: 'array', returnValue: 'resultRows' })[0];
  const w2 = cacheWrites(f);
  console.log(`     file db (${mode}): 50-row commit CACHE_WRITE +${w1 - w0}, WAL log ${log}; checkpoint busy ${busy} log ${log} checkpointed ${ckpt}, CACHE_WRITE +${w2 - w1}`);
  if (mode === 'wal') check(w1 - w0 === log && w2 === w1, 'file db: CACHE_WRITE delta = WAL frames of the commit; the checkpoint adds none');
  f.close();
} catch (e) {
  console.log('     no file db in this Node build:', e.message);
} finally {
  if (dir) rmSync(dir, { recursive: true, force: true });
}

db.close();
console.log(bad ? `${bad} FAILED` : 'ALL OK');
process.exit(bad ? 1 : 0);
