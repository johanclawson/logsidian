#!/usr/bin/env bash
# ADR-003: the graph-independent UI boot freeze. Starts the perf worktree's
# build N times with an empty profile (explore mode) and reports the renderer's
# boot long tasks (LSUI entries that started in the first 15 s after the
# renderer's time origin) plus the bundle sizes.
#
# usage: bench/run-boot.sh <label> [runs]   (default 3 runs)
set -u
H=$(cd "$(dirname "$0")" && pwd)
B=$HOME/.cache/lsbench
LABEL=${1:?label}
N=${2:-3}
OUT=$B/boot/$LABEL
APPDIR=$HOME/dev/logsidian-perf/static/out/Logseq-linux-x64/resources/app/js
mkdir -p "$OUT"

for p in /proc/[0-9]*; do
  e=$(readlink -f "$p/exe" 2>/dev/null)
  case "$e" in */static/out/Logseq-linux-x64/Logseq) echo "Logseq running (pid ${p#/proc/}, $e) — abort"; exit 4;; esac
done

echo "== $LABEL: main.js $(du -m "$APPDIR/main.js" | cut -f1) MB, db-worker.js $(du -m "$APPDIR/db-worker.js" | cut -f1) MB"
for i in $(seq 1 "$N"); do
  o="$OUT/run$i"; rm -rf "$o" "$B/prof-boot"; mkdir -p "$o"
  ( cd "$H" && timeout 300 node lsbench.js explore "" "$B/prof-boot" "$o" 60 > "$o/run.log" 2>&1 )
  node -e '
const r = require(process.argv[1]);
const boot = r.records.filter((x) => x.event === "ui" && x.start < 15000).map((x) => x.dur).sort((a, b) => b - a);
const sum = boot.reduce((a, b) => a + b, 0);
console.log(`  run ${process.argv[2]}: launched ${r.launched_ms} ms, longest boot task ${boot[0] || 0} ms, boot tasks ${boot.length} summing ${sum} ms (top ${boot.slice(0, 4).join(", ")})`);' "$o/explore.json" "$i" 2>&1 | head -2
done
rm -rf "$B/prof-boot"
