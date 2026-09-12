#!/usr/bin/env bash
# ADR-003 step 2 follow-up, after run-after.sh:
#  1. search rebuild on the g-10k profile, waiting for it to finish (not for
#     silence) — how long does the full rebuild block the worker at 10k?
#  2. scroll the journals home on g-real and g-10k — does paging load older
#     journals?
# One app at a time; graph copies only.
#
# usage: bench/run-followup.sh [scratchpadWithNewScripts]
set -u
H=$(cd "$(dirname "$0")" && pwd)
B=$HOME/.cache/lsbench
OUT=$B/followup
SRC=${1:-}
if [ -n "$SRC" ]; then
  cp "$SRC/lsbench.js" "$SRC/memwatch.py" "$SRC/lssummary.js" "$H/" && echo "scripts updated from $SRC"
fi
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

echo "   blocks_fts prof-10k before: $(fts "$B/prof-10k")"
run reindex-10k-full reopen "$B/g-10k"  "$B/prof-10k"  3000 LSBENCH_REINDEX=1 LSBENCH_REINDEX_MAX=1800 LSBENCH_QUERIES=möte
echo "   blocks_fts prof-10k after: $(fts "$B/prof-10k")"
run scroll-real      reopen "$B/g-real" "$B/prof-real" 600  LSBENCH_SCROLL=6 LSBENCH_QUERIES=none
run scroll-10k       reopen "$B/g-10k"  "$B/prof-10k"  1200 LSBENCH_SCROLL=6 LSBENCH_QUERIES=none

for n in reindex-10k-full scroll-real scroll-10k; do
  j="$OUT/$n/reopen.json"
  [ -f "$j" ] || continue
  echo; echo "######## $n"
  node "$H/lssummary.js" "$j" 300
  node -e 'const r=require(process.argv[1]); if (r.reindex_ms!==undefined) console.log("reindex_ms:", r.reindex_ms, "end:", JSON.stringify(r.reindex_end)); if (r.scroll) for (const s of r.scroll) console.log("scroll", JSON.stringify(s));' "$j"
done
