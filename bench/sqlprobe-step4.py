#!/usr/bin/env python3
"""ADR-003 step 4: offline check of the search db's new write path.

In-memory SQLite (Python's sqlite3), with the schema-2 tables, triggers and
FTS5 table copied from src/main/frontend/worker/search.cljs, and the new
statements (upsert-sql, delete-sql, the FTS5 config and merge commands)
copied verbatim, run with JSON parameters the way sqlite-wasm binds them.

Checks:
  1. JSON round-trip of titles (quotes, backslash, emoji, control chars,
     U+FFFD from toWellFormed, U+0000) against a direct bind.
  2. Level-0 segments added per commit: the old per-row statements, the new
     statement with and without its ORDER BY, the json_each delete, a
     sync-shaped transaction (delete + upsert + meta).
  3. A no-op re-sync changes 0 rows.
  4. blocks and blocks_fts counts equal, rowids aligned, integrity-check ok.
  5. FTS5 config set idempotently; automerge=0 + crisismerge=16 bounds
     level 0; 'merge' steps report work via total_changes (delta >= 2) and
     stop (delta < 2).

Not covered (needs the app): WAL and checkpoint behaviour on OPFS, and the
exact wasm engine (3.50.3; this runs whatever Python links, printed below).
usage: python3 bench/sqlprobe-step4.py
"""
import json
import random
import sqlite3
import uuid

random.seed(4)

# --- copied from search.cljs -------------------------------------------------
CREATE_BLOCKS = """CREATE TABLE IF NOT EXISTS blocks (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        page TEXT)"""
CREATE_FTS = 'CREATE VIRTUAL TABLE IF NOT EXISTS blocks_fts USING fts5(id, title, page, tokenize="trigram")'
CREATE_META = "CREATE TABLE IF NOT EXISTS search_meta (k TEXT PRIMARY KEY, v TEXT)"
TRIGGERS = [
    """CREATE TRIGGER IF NOT EXISTS blocks_ad AFTER DELETE ON blocks
BEGIN
    DELETE FROM blocks_fts WHERE rowid = old.rowid;
END;""",
    """CREATE TRIGGER IF NOT EXISTS blocks_ai AFTER INSERT ON blocks
BEGIN
    INSERT INTO blocks_fts (rowid, id, title, page)
    VALUES (new.rowid, new.id, new.title, new.page);
END;""",
    """CREATE TRIGGER IF NOT EXISTS blocks_au AFTER UPDATE ON blocks
BEGIN
    DELETE FROM blocks_fts WHERE rowid = old.rowid;
    INSERT INTO blocks_fts (rowid, id, title, page)
    VALUES (new.rowid, new.id, new.title, new.page);
END;""",
]
UPSERT_SQL = ("INSERT INTO blocks (id, title, page)"
              " SELECT j.value ->> 0, j.value ->> 1, j.value ->> 2"
              " FROM json_each($rows) AS j LEFT JOIN blocks AS b ON b.id = j.value ->> 0"
              " WHERE true"
              " ORDER BY b.rowid IS NULL, b.rowid, j.key"
              " ON CONFLICT (id) DO UPDATE SET title = excluded.title, page = excluded.page"
              " WHERE blocks.title IS NOT excluded.title OR blocks.page IS NOT excluded.page")
DELETE_SQL = "DELETE FROM blocks WHERE id IN (SELECT value FROM json_each($ids))"
META_SQL = "INSERT INTO search_meta (k, v) VALUES ($k, $v) ON CONFLICT (k) DO UPDATE SET v = excluded.v"
FTS_CONFIG = {"automerge": 0, "crisismerge": 16, "usermerge": 4}
FTS_CONFIG_READ_SQL = "SELECT k, v FROM blocks_fts_config WHERE k IN ('automerge', 'crisismerge', 'usermerge')"
FTS_COMMAND_SQL = "INSERT INTO blocks_fts(blocks_fts, rank) VALUES ($k, $v)"
MERGE_SQL = "INSERT INTO blocks_fts(blocks_fts, rank) VALUES ('merge', $n)"
MERGE_PAGES = 64
# --- the statements replaced by this change ---------------------------------
OLD_UPSERT_SQL = ("INSERT INTO blocks (id, title, page) VALUES ($id, $title, $page)"
                  " ON CONFLICT (id) DO UPDATE SET title = excluded.title, page = excluded.page"
                  " WHERE blocks.title IS NOT excluded.title OR blocks.page IS NOT excluded.page")
UPSERT_NO_ORDER_SQL = UPSERT_SQL.replace(" ORDER BY b.rowid IS NULL, b.rowid, j.key", "")
assert UPSERT_NO_ORDER_SQL != UPSERT_SQL


def js_json(rows):
    """JSON.stringify: non-ASCII kept as is, control chars escaped."""
    return json.dumps(rows, ensure_ascii=False, separators=(",", ":"))


def connect():
    # isolation_level=None: no implicit BEGIN, like oo1 (explicit BEGIN/COMMIT)
    con = sqlite3.connect(":memory:", isolation_level=None)
    for sql in [CREATE_BLOCKS, CREATE_FTS, *TRIGGERS, CREATE_META]:
        con.execute(sql)
    return con


def ensure_fts_config(con):
    """search/ensure-fts-config!: write only the keys that differ."""
    have = dict(con.execute(FTS_CONFIG_READ_SQL).fetchall())
    for k, v in FTS_CONFIG.items():
        if have.get(k) != v:
            con.execute(FTS_COMMAND_SQL, {"k": k, "v": v})


def tx(con, f):
    con.execute("BEGIN")
    try:
        r = f()
        con.execute("COMMIT")
        return r
    except Exception:
        con.execute("ROLLBACK")
        raise


def varint(buf, i):
    """SQLite / FTS5 varint: 7 bits per byte, big-endian, 9th byte all 8."""
    v = 0
    for n in range(8):
        b = buf[i + n]
        v = (v << 7) | (b & 0x7F)
        if b < 0x80:
            return v, i + n + 1
    return (v << 8) | buf[i + 8], i + 9


def fts_structure(con):
    """Decoded structure record (fts5StructureDecode): segments per level."""
    row = con.execute("SELECT block FROM blocks_fts_data WHERE id = 10").fetchone()
    if row is None:
        return {"lvl": [], "merge": [], "wc": 0, "pages": []}
    buf = row[0]
    i = 4
    v2 = buf[4:8] == b"\xff\x00\x00\x01"
    if v2:
        i += 4
    n_level, i = varint(buf, i)
    _n_seg, i = varint(buf, i)
    wc, i = varint(buf, i)
    lvl, merge, pages = [], [], []
    for _ in range(n_level):
        n_merge, i = varint(buf, i)
        n_total, i = varint(buf, i)
        merge.append(n_merge)
        lvl.append(n_total)
        lv_pages = 0
        for _ in range(n_total):
            _segid, i = varint(buf, i)
            first, i = varint(buf, i)
            last, i = varint(buf, i)
            lv_pages += last - first + 1
            if v2:
                for _ in range(5):
                    _x, i = varint(buf, i)
        pages.append(lv_pages)
    return {"lvl": lvl, "merge": merge, "wc": wc, "pages": pages}


def nseg(con):
    return sum(fts_structure(con)["lvl"])


def total_changes(con):
    return con.total_changes


def rid():
    return str(uuid.uuid4())


def title(n=6):
    words = ["möte", "projekt", "kund", "logseq", "search", "index", "WAL", "fts5",
             "journal", "block", "page", "trigram", "segment", "merge"]
    return " ".join(random.choice(words) for _ in range(n)) + f" {random.randint(0, 10**6)}"


def upsert(con, rows, sql=UPSERT_SQL):
    con.execute(sql, {"rows": js_json(rows)})


def check_consistent(con, label):
    nb = con.execute("SELECT count(*) FROM blocks").fetchone()[0]
    nf = con.execute("SELECT count(*) FROM blocks_fts").fetchone()[0]
    misaligned = con.execute(
        "SELECT count(*) FROM blocks AS b LEFT JOIN blocks_fts AS f ON f.rowid = b.rowid"
        " WHERE f.id IS NOT b.id OR f.title IS NOT b.title OR f.page IS NOT b.page").fetchone()[0]
    con.execute("INSERT INTO blocks_fts(blocks_fts) VALUES('integrity-check')")
    ok = nb == nf and misaligned == 0
    print(f"  [{'ok' if ok else 'FAIL'}] {label}: blocks {nb} = blocks_fts {nf},"
          f" misaligned rows {misaligned}, integrity-check passed")
    assert ok
    return nb


def section(s):
    print(f"\n== {s}")


def main():
    print(f"SQLite {sqlite3.sqlite_version} (bundled sqlite-wasm is 3.50.3)")

    # ---------------------------------------------------------------- 1. JSON
    section("1. JSON round-trip of titles vs a direct bind")
    con = connect()
    ensure_fts_config(con)
    samples = ['plain', 'quote " and \' apostrophe', 'back\\slash \\u0041', 'emoji 😀 👍🏽',
               'tab\tnew\nline', 'ctrl \x01\x1f', 'fffd � (toWellFormed of a lone surrogate)',
               'nul \x00 inside', 'unicode åäö ÅÄÖ 中文']
    page = rid()
    all_ok = True
    for s in samples:
        a, b = rid(), rid()
        upsert(con, [[a, s, page]])
        con.execute(OLD_UPSERT_SQL, {"id": b, "title": s, "page": page})
        ha = con.execute("SELECT hex(title), typeof(title) FROM blocks WHERE id = ?", (a,)).fetchone()
        hb = con.execute("SELECT hex(title), typeof(title) FROM blocks WHERE id = ?", (b,)).fetchone()
        ok = ha == hb
        all_ok &= ok
        print(f"  [{'ok' if ok else 'DIFF'}] {s!r}: json {ha[1]} {ha[0][:40]} / bind {hb[1]} {hb[0][:40]}")
    print(f"  => every title round-trips: {all_ok}")

    # ---------------------------------------------------- 2. segments per commit
    section("2. level-0 segments added per commit (automerge=0: nothing merges them away)")
    con = connect()
    ensure_fts_config(con)
    # Measurement only: crisismerge at its maximum (FTS5_MAX_SEGMENT), so no
    # crisis merge folds level 0 in the middle of a count. Section 5 runs the
    # real config.
    con.execute(FTS_COMMAND_SQL, {"k": "crisismerge", "v": 2000})
    page = rid()
    seed = [[rid(), title(), page] for _ in range(300)]
    s0 = nseg(con)
    tx(con, lambda: upsert(con, seed))
    print(f"  seed 300 new rows, one statement: +{nseg(con) - s0} segment(s)")
    ids = [r[0] for r in seed]

    def mixed_batch(n_upd=25, n_new=25):
        upd = [[i, title(), page] for i in random.sample(ids, n_upd)]
        new = [[rid(), title(), page] for _ in range(n_new)]
        rows = upd + new
        random.shuffle(rows)
        return rows

    results = {}
    for label, sql in [("new statement with ORDER BY", UPSERT_SQL),
                       ("new statement without ORDER BY", UPSERT_NO_ORDER_SQL)]:
        adds = []
        for _ in range(5):
            rows = mixed_batch()
            s0 = nseg(con)
            tx(con, lambda: upsert(con, rows, sql))
            ids.extend(r[0] for r in rows if r[0] not in ids)
            adds.append(nseg(con) - s0)
        results[label] = adds
        print(f"  25 updates + 25 inserts, shuffled, {label}: +{adds} segments per commit")

    def old_per_row(rows):
        for i, t, p in rows:
            con.execute(OLD_UPSERT_SQL, {"id": i, "title": t, "page": p})

    rows = mixed_batch()
    s0 = nseg(con)
    tx(con, lambda: old_per_row(rows))
    ids.extend(r[0] for r in rows if r[0] not in ids)
    print(f"  the same shape, old per-row statements (50 rows): +{nseg(con) - s0} segments")

    # a slice-sized commit (~14 rows, as in now/reindex-10k) + meta, like commit-batch!
    rows = [[rid(), title(), page] for _ in range(14)]
    s0 = nseg(con)
    def commit_batch():
        upsert(con, rows)
        con.execute(META_SQL, {"k": "blocks_cursor", "v": "123"})
        con.execute(META_SQL, {"k": "blocks_state", "v": "building"})
    tx(con, commit_batch)
    ids.extend(r[0] for r in rows)
    print(f"  commit-batch! shape (14 new rows + 2 meta rows): +{nseg(con) - s0} segment(s)")

    # deletes
    del_ids = random.sample(ids, 30)
    random.shuffle(del_ids)
    s0 = nseg(con)
    tc0 = total_changes(con)
    tx(con, lambda: con.execute(DELETE_SQL, {"ids": js_json(del_ids)}))
    delete_adds = nseg(con) - s0
    print(f"  delete 30 ids (shuffled) via json_each: +{delete_adds} segment(s),"
          f" changes {total_changes(con) - tc0} (30 blocks rows + FTS5 shadow writes)")
    ids = [i for i in ids if i not in del_ids]
    del_ids = random.sample(ids, 30)
    s0 = nseg(con)
    old_delete = "DELETE from blocks WHERE id IN (" + ", ".join(f"'{i}'" for i in del_ids) + ")"
    tx(con, lambda: con.execute(old_delete))
    print(f"  delete 30 ids via the old string-built IN list: +{nseg(con) - s0} segment(s)"
          f" (already one: a DELETE with triggers visits rowids in order)")
    ids = [i for i in ids if i not in del_ids]

    # sync-rows! shape: delete + upsert + meta in one transaction
    del_ids = random.sample(ids, 3)
    rows = mixed_batch(4, 2)
    s0 = nseg(con)
    def sync_rows():
        con.execute(DELETE_SQL, {"ids": js_json(del_ids)})
        upsert(con, rows)
        con.execute(META_SQL, {"k": "blocks_indexed_tx", "v": "536871000"})
    tx(con, sync_rows)
    ids = [i for i in ids if i not in del_ids]
    ids.extend(r[0] for r in rows if r[0] not in ids)
    print(f"  sync-rows! shape (3 deletes, 4 updates + 2 inserts, meta): +{nseg(con) - s0} segment(s)"
          f" (the delete statement's pending data flushes when the upsert statement starts)")
    ok = all(a == 1 for a in results["new statement with ORDER BY"])
    print(f"  [{'ok' if ok else 'FAIL'}] with the ORDER BY every mixed commit adds exactly 1 segment")
    assert ok
    ok = delete_adds == 1
    print(f"  [{'ok' if ok else 'FAIL'}] the json_each delete adds exactly 1 segment")
    assert ok

    # rowid order: the statement never writes a lower rowid
    before = dict(con.execute("SELECT id, rowid FROM blocks").fetchall())
    rows = mixed_batch(10, 5)
    tx(con, lambda: upsert(con, rows))
    after = dict(con.execute("SELECT id, rowid FROM blocks").fetchall())
    kept = all(after[i] == r for i, r in before.items() if i in after)
    new_rowids = [after[r[0]] for r in rows if r[0] not in before]
    ok = kept and new_rowids == sorted(new_rowids) and min(new_rowids) > max(before.values())
    print(f"  [{'ok' if ok else 'FAIL'}] updates keep their rowid; new rows get max+1.. in input order")
    assert ok
    ids.extend(r[0] for r in rows if r[0] not in ids)
    check_consistent(con, "after mixed upserts and deletes")

    # ---------------------------------------------------------------- 3. no-op
    section("3. no-op re-sync")
    current = con.execute("SELECT id, title, page FROM blocks ORDER BY random() LIMIT 50").fetchall()
    s0, tc0 = nseg(con), total_changes(con)
    tx(con, lambda: upsert(con, [list(r) for r in current]))
    d_changes = con.execute("SELECT changes()").fetchone()[0]
    d_total = total_changes(con) - tc0
    ok = d_changes == 0 and d_total == 0 and nseg(con) == s0
    print(f"  [{'ok' if ok else 'FAIL'}] 50 unchanged rows: changes() {d_changes},"
          f" total_changes delta {d_total}, segments +{nseg(con) - s0}")
    assert ok
    dup = [[current[0][0], "first", page], [current[0][0], "last", page]]
    tx(con, lambda: upsert(con, dup))
    got = con.execute("SELECT title FROM blocks WHERE id = ?", (current[0][0],)).fetchone()[0]
    print(f"  (a duplicate id inside one statement: the later element wins by DO UPDATE -> {got!r};"
          f" JS dedupes first anyway)")
    check_consistent(con, "after the no-op re-sync")

    # ------------------------------------------------------ 5. config and merge
    section("5. FTS5 config and merge steps")
    con = connect()
    tc0 = total_changes(con)
    ensure_fts_config(con)
    first = total_changes(con) - tc0
    have = dict(con.execute(FTS_CONFIG_READ_SQL).fetchall())
    tc0 = total_changes(con)
    ensure_fts_config(con)
    ok = have == FTS_CONFIG and total_changes(con) == tc0
    print(f"  [{'ok' if ok else 'FAIL'}] blocks_fts_config {have} (first ensure: {first} changes);"
          f" a second ensure writes {total_changes(con) - tc0}")
    assert ok
    con.execute(FTS_COMMAND_SQL, {"k": "crisismerge", "v": 2000})
    tc0 = total_changes(con)
    ensure_fts_config(con)
    ok = dict(con.execute(FTS_CONFIG_READ_SQL).fetchall()) == FTS_CONFIG
    print(f"  [{'ok' if ok else 'FAIL'}] one differing key is rewritten ({total_changes(con) - tc0} changes)")
    assert ok

    page = rid()
    ids = []
    max_l0 = 0
    for n in range(200):  # 200 small commits, no merge step: only crisismerge
        rows = [[rid(), title(), page] for _ in range(14)]
        tx(con, lambda: upsert(con, rows))
        ids.extend(r[0] for r in rows)
        max_l0 = max(max_l0, fts_structure(con)["lvl"][0])
    st = fts_structure(con)
    print(f"  200 commits of 14 rows, no merge step: level-0 max {max_l0} (crisismerge 16 caps it),"
          f" structure {st['lvl']}, merging {st['merge']}, writeCounter {st['wc']}")
    assert max_l0 < 16
    steps = []
    for _ in range(500):
        tc0 = total_changes(con)
        con.execute(MERGE_SQL, {"n": MERGE_PAGES})
        d = total_changes(con) - tc0
        steps.append(d)
        if d < 2:
            break
    st2 = fts_structure(con)
    print(f"  'merge', {MERGE_PAGES} until delta < 2: {len(steps)} steps, deltas {steps[:12]}"
          f"{' ...' if len(steps) > 12 else ''} last {steps[-1]}")
    print(f"  after merging: segments per level {st2['lvl']}, pages per level {st2['pages']}")
    ok = steps[0] >= 2 and steps[-1] < 2 and all(n < FTS_CONFIG['usermerge'] for n in st2['lvl'])
    print(f"  [{'ok' if ok else 'FAIL'}] merge reports work (delta >= 2), then none (delta < 2);"
          f" every level below usermerge")
    assert ok
    tc0 = total_changes(con)
    con.execute(MERGE_SQL, {"n": MERGE_PAGES})
    print(f"  'merge' with nothing to do: total_changes delta {total_changes(con) - tc0}")
    s0 = nseg(con)
    rows = [[rid(), title(), page] for _ in range(14)]
    tx(con, lambda: upsert(con, rows))
    tc0 = total_changes(con)
    con.execute(MERGE_SQL, {"n": MERGE_PAGES})
    print(f"  one new commit then 'merge': +{nseg(con) - s0} segment after the commit,"
          f" merge delta {total_changes(con) - tc0} (below usermerge: nothing to merge)")
    check_consistent(con, "after 200 commits and merging")
    hits = con.execute("SELECT count(*) FROM blocks_fts WHERE title MATCH 'möte'").fetchone()[0]
    direct = con.execute("SELECT count(*) FROM blocks WHERE title LIKE '%möte%'").fetchone()[0]
    ok = hits == direct
    print(f"  [{'ok' if ok else 'FAIL'}] MATCH 'möte' finds {hits} rows, LIKE finds {direct}")
    assert ok
    print("\nall checks passed")


if __name__ == "__main__":
    main()
