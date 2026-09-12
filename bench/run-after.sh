#!/usr/bin/env bash
# ADR-003 step 2: measurements on the perf worktree's build, one app at a time.
# Graph copies and profiles under ~/.cache/lsbench; never the real graph.
#
# usage: bench/run-after.sh [outDir]   (default ~/.cache/lsbench/after)
#
# Order matters: reopen + search on the existing profiles first (same state as
# the baseline), then the search-rebuild experiment (it may fill the index),
# then a fresh first open of g-10k with per-file reset-file timers.
set -u
H=$(cd "$(dirname "$0")" && pwd)
B=$HOME/.cache/lsbench
OUT=${1:-$B/after}
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

run reopen-real      reopen "$B/g-real" "$B/prof-real"      600
run reopen-10k       reopen "$B/g-10k"  "$B/prof-10k"       1200
run reindex-10k      reopen "$B/g-10k"  "$B/prof-10k"       2000 LSBENCH_REINDEX=1 LSBENCH_QUERIES=möte
echo "   blocks_fts prof-10k after reindex: $(fts "$B/prof-10k")"
rm -rf "$B/prof-10k-after"
run open-10k         open   "$B/g-10k"  "$B/prof-10k-after" 3600
echo "   blocks_fts prof-10k-after after first open: $(fts "$B/prof-10k-after")"
run reopen-10k-after reopen "$B/g-10k"  "$B/prof-10k-after" 1200

for d in "$OUT"/*/; do
  for j in "$d"open.json "$d"reopen.json; do
    [ -f "$j" ] && { echo; echo "######## $j"; node "$H/lssummary.js" "$j" 300; }
  done
done
