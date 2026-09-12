#!/usr/bin/env bash
# ADR-003: measure the rowid-keyed search index (schema 2) on the perf
# worktree's build, one app at a time; graph copies only.
#
# 1. reopen g-real: the old index migrates by itself (LSSEARCH open reason
#    "schema" -> a sliced walk); the harness waits the walk out (thread CPU),
#    then types two bursts into an existing journal block;
# 2. the same on g-10k (a ~70k-row walk);
# 3. reopen g-10k again: the index must now be trusted, search must hit.
#
# usage: bench/run-fts.sh [scratchpadWithNewScripts] [outDir]
set -u
H=$(cd "$(dirname "$0")" && pwd)
B=$HOME/.cache/lsbench
SRC=${1:-}
OUT=${2:-$B/fts}
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

run migrate-real  reopen "$B/g-real" "$B/prof-real" 1200 LSBENCH_TYPE=40 LSBENCH_QUERIES=möte
run migrate-10k   reopen "$B/g-10k"  "$B/prof-10k"  2400 LSBENCH_TYPE=40 LSBENCH_QUERIES=möte
run trust-10k     reopen "$B/g-10k"  "$B/prof-10k"  1200 LSBENCH_TYPE=40

for n in migrate-real migrate-10k trust-10k; do
  j="$OUT/$n/reopen.json"
  [ -f "$j" ] || continue
  echo; echo "######## $n"
  node "$H/lssummary.js" "$j" 300 | sed -n '1,2p;/thread-api calls/,/^$/p;/thread CPU/,/^$/p;/errors/,/^$/p;/search rebuild/,/^$/p;/searches/,$p'
  node -e 'const r=require(process.argv[1]); const t=r.typing||{}; if (t.error) console.log("typing error:", t.error); (t.bursts||[]).forEach((b,i)=>console.log(`typing burst ${i+1}:`, JSON.stringify(b)))' "$j"
done
