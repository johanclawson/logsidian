#!/usr/bin/env bash
# ADR-003 step 3: measurements on the perf worktree's build, one app at a time.
# Graph copies and profiles under ~/.cache/lsbench; never the real graph.
#
# usage: bench/run-step3.sh [outDir]   (default ~/.cache/lsbench/step3)
#
# 1. reopen + search on the existing profiles (prof-10k now has a full index
#    from the follow-up rebuild, so search at 10k should show hits);
# 2. the manual "Rebuild search index", now a worker-owned batched walk;
#    waits for LSSEARCH upsert-done/failed;
# 3. a fresh first open of g-10k: per-file indexing should fill blocks_fts
#    without the two minute-long rebuild stalls; then reopen + search on it.
set -u
H=$(cd "$(dirname "$0")" && pwd)
B=$HOME/.cache/lsbench
OUT=${1:-$B/step3}
mkdir -p "$OUT"

for p in /proc/[0-9]*; do
  e=$(readlink -f "$p/exe" 2>/dev/null)
  case "$e" in */static/out/Logseq-linux-x64/Logseq) echo "Logseq running (pid ${p#/proc/}, $e) — abort"; exit 4;; esac
done

run() { # name mode graph profile timeoutSec [VAR=value ...]
  local name=$1 mode=$2 graph=$3 prof=$4 to=$5; shift 5
  local o="$OUT/$name"; rm -rf "$o"; mkdir -p "$o"
  python3 "$H/memwatch.py" "$o/mem.log" 4096 & local w=$!
  ( cd "$H" && env "$@" timeout $((to + 300)) node lsbench.js "$mode" "$graph" "$prof" "$o" "$to" > "$o/run.log" 2>&1 )
  local rc=$?
  kill "$w" 2>/dev/null
  echo "== $name exit $rc: $(tail -n 1 "$o/run.log" | cut -c1-240)"
  grep -q FLOOR "$o/mem.log" && echo "   MEMORY FLOOR HIT"
  return 0
}

fts() { # profileDir -> rows in the search index (blocks_fts)
  local d="$1/chromium/File System/000/t/00" x f n
  x=$(mktemp -d)
  for f in "$d"/*; do
    n=$(head -c 4096 "$f" | strings -n 6 | grep -m1 '^/')
    case "$n" in
      /search/db.sqlite) tail -c +4097 "$f" > "$x/s.sqlite" ;;
      /search/db.sqlite-wal) tail -c +4097 "$f" > "$x/s.sqlite-wal" ;;
    esac
  done
  python3 -c "import sqlite3; print(sqlite3.connect('file:$x/s.sqlite?mode=ro', uri=True).execute('select count(*) from blocks_fts').fetchone()[0])" 2>&1
  rm -rf "$x"
}

run reopen-real  reopen "$B/g-real" "$B/prof-real" 600
run reopen-10k   reopen "$B/g-10k"  "$B/prof-10k"  1200
run reindex-10k  reopen "$B/g-10k"  "$B/prof-10k"  3000 LSBENCH_REINDEX=1 LSBENCH_REINDEX_MAX=1800 LSBENCH_QUERIES=möte
echo "   blocks_fts prof-10k after reindex: $(fts "$B/prof-10k")"
rm -rf "$B/prof-10k-s3"
run open-10k     open   "$B/g-10k"  "$B/prof-10k-s3" 3600
echo "   blocks_fts prof-10k-s3 after first open: $(fts "$B/prof-10k-s3")"
run reopen-10k-s3 reopen "$B/g-10k" "$B/prof-10k-s3" 1200
echo "   blocks_fts prof-10k-s3 after reopen: $(fts "$B/prof-10k-s3")"

for d in "$OUT"/*/; do
  for j in "$d"open.json "$d"reopen.json; do
    [ -f "$j" ] && { echo; echo "######## $j"; node "$H/lssummary.js" "$j" 300; node -e 'const r=require(process.argv[1]); if (r.reindex_ms!==undefined) console.log("reindex_ms:", r.reindex_ms, "end:", JSON.stringify(r.reindex_end))' "$j"; }
  done
done
