#!/usr/bin/env bash
# Data-safety regression gate: bench/lssafety.js against the perf worktree's
# build (graph copies under ~/.cache/lsbench/safety only; never ~/OneDrive).
#
# usage: bench/run-safety.sh [--only=a,b] [--timeout=SEC] [--same-page-delay=MS]
#   LSSAFETY_APP (or LSBENCH_APP)  app binary, default: the perf worktree build
#   LSSAFETY_ALLOW_CONCURRENT=1    run even while another Logseq is running
#   LSSAFETY_TOTAL_TIMEOUT         whole run, seconds (default 5400)
# exit: 0 all pass, 1 a scenario failed or errored, 2 usage/refused, 3 no app,
#       4 another Logseq is running
set -u
H=$(cd "$(dirname "$0")" && pwd)
APP=${LSSAFETY_APP:-${LSBENCH_APP:-$HOME/dev/logsidian-perf/static/out/Logseq-linux-x64/Logseq}}
[ -x "$APP" ] || { echo "no app binary at $APP"; exit 3; }

if [ "${LSSAFETY_ALLOW_CONCURRENT:-}" != 1 ]; then
  for p in /proc/[0-9]*; do
    e=$(readlink -f "$p/exe" 2>/dev/null)
    case "$e" in */static/out/Logseq-linux-x64/Logseq) echo "Logseq running (pid ${p#/proc/}, $e) — abort (LSSAFETY_ALLOW_CONCURRENT=1 overrides)"; exit 4;; esac
  done
fi

OUT=$HOME/.cache/lsbench/safety/$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"
( cd "$H" && LSSAFETY_APP="$APP" timeout "${LSSAFETY_TOTAL_TIMEOUT:-5400}" node lssafety.js --out="$OUT" "$@" ) 2>&1 | tee "$OUT/run.log"
rc=${PIPESTATUS[0]}

# safety net: kill app processes of THIS run only (their HOME is under $OUT)
for p in /proc/[0-9]*; do
  [ "$(readlink -f "$p/exe" 2>/dev/null)" = "$APP" ] || continue
  if tr '\0' '\n' < "$p/environ" 2>/dev/null | grep -q "^HOME=$OUT/"; then
    echo "killing leftover app pid ${p#/proc/}"; kill -9 "${p#/proc/}" 2>/dev/null
  fi
done

echo "results: $OUT (summary.txt, summary.json, <scenario>/result.json, <scenario>/out/*)"
exit "$rc"
