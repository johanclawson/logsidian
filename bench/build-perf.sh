#!/bin/bash
# usage: build-perf.sh [repoDir]   (default ~/dev/logsidian)
#   CLJS_DEBUG=0  release ClojureScript without --debug (package.json's
#                 cljs:release-electron passes --debug: pseudo-names and pretty
#                 printing, main.js ~36 MB instead of ~11 MB)
#   LOWMEM=1      keep the memory peak low on a VM shared with other sessions:
#                 one shadow target per JVM (-Xmx2500m, 3 compiler threads)
#                 and a 4 GB webpack heap. The one-JVM build was killed three
#                 times by the harness's low-memory guard on 2026-09-12; this
#                 shape built in ~17 min without any swap growth.
set -euo pipefail
export PATH="$HOME/.local/bin:$PATH"
if [ "${LOWMEM:-0}" = 1 ]; then
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Xmx2500m -XX:ActiveProcessorCount=3}"
  NODE_OPTIONS_BUILD="${NODE_OPTIONS_BUILD:---max-old-space-size=4096}"
fi
# NODE_OPTIONS_BUILD overrides the webpack/node heap (default 6 GB)
export NODE_OPTIONS="${NODE_OPTIONS_BUILD:---max-old-space-size=6144}"
cd "${1:-$HOME/dev/logsidian}"
echo "[$(date '+%H:%M:%S')] repo: $(pwd)"
ts() { date '+%H:%M:%S'; }
echo "[$(ts)] branch: $(git branch --show-current)  head: $(git log --oneline -1)"
echo "[$(ts)] ===== 1 gulp:build (static/ from resources/, CSS) ====="
yarn gulp:build
echo "[$(ts)] ===== 2 cljs:release-electron (produces target/*.js) ====="
if [ "${LOWMEM:-0}" = 1 ]; then
  # one JVM per target keeps the peak heap to the largest single target
  dbg=$([ "${CLJS_DEBUG:-1}" = 0 ] || echo --debug)
  echo "[$(ts)] (LOWMEM=1: one target per JVM, JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS${dbg:+, $dbg})"
  clojure -M:cljs release app $dbg
  clojure -M:cljs release db-worker $dbg
  clojure -M:cljs release inference-worker electron $dbg
  clojure -M:cljs release publishing
elif [ "${CLJS_DEBUG:-1}" = 0 ]; then
  echo "[$(ts)] (CLJS_DEBUG=0: release without --debug)"
  clojure -M:cljs release app db-worker inference-worker electron && clojure -M:cljs release publishing
else
  yarn cljs:release-electron
fi
echo "[$(ts)] ===== 3 webpack-app-build (consumes target/*.js) ====="
yarn webpack-app-build
echo "[$(ts)] ===== 4 static deps ====="
[ -d static/node_modules/electron ] || (cd static && yarn install --network-timeout 600000)
echo "[$(ts)] ===== 5 electron-forge package (no makers) ====="
(cd static && npx electron-forge package)
echo "[$(ts)] ===== DONE ====="
# grep -c exits 1 on zero matches; a clean (uninstrumented) build is not a failure
{ grep -c 'LSPERF' static/out/Logseq-linux-x64/resources/app/js/db-worker.js || true; } | sed 's/^/  LSPERF i paketerad worker: /'
ls -la static/out/Logseq-linux-x64/Logseq | sed 's/^/  /'
