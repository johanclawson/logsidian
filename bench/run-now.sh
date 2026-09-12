#!/usr/bin/env bash
# ADR-003: measure the NOW/NEXT query fast path and the search-rebuild slice
# split on the perf worktree's build, one app at a time; graph copies only.
#
# usage: bench/run-now.sh [scratchpadWithNewScripts] [outDir]
set -u
H=$(cd "$(dirname "$0")" && pwd)
B=$HOME/.cache/lsbench
SRC=${1:-}
OUT=${2:-$B/now}
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

run reopen-real reopen "$B/g-real" "$B/prof-real" 600
run reopen-10k  reopen "$B/g-10k"  "$B/prof-10k"  1200
run reindex-10k reopen "$B/g-10k"  "$B/prof-10k"  3000 LSBENCH_REINDEX=1 LSBENCH_REINDEX_MAX=1800 LSBENCH_QUERIES=möte

for n in reopen-real reopen-10k reindex-10k; do
  j="$OUT/$n/reopen.json"
  [ -f "$j" ] || continue
  echo; echo "######## $n"
  node "$H/lssummary.js" "$j" 300
  node -e 'const r=require(process.argv[1]); if (r.reindex_ms!==undefined) console.log("reindex_ms:", r.reindex_ms, "end:", JSON.stringify(r.reindex_end))' "$j"
  echo "--- q-shape calls (NOW/NEXT) still on d/q:"
  node -e 'const r=require(process.argv[1]); const c=r.records.filter(x=>x.api==="thread-api/q"&&/journal-day/.test(x.args||"")); console.log(c.length? c.map(x=>`  sync ${x["sync-ms"]} total ${x["total-ms"]} ${(x.args||"").slice(0,120)}`).join("\n"):"  none above the log threshold")' "$j"
done
