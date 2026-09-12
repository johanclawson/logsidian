#!/bin/bash
# usage: build-perf.sh [repoDir]   (default ~/dev/logsidian)
#   CLJS_DEBUG=0  release ClojureScript without --debug (package.json's
#                 cljs:release-electron passes --debug: pseudo-names and pretty
#                 printing, main.js ~36 MB instead of ~11 MB)
set -euo pipefail
export PATH="$HOME/.local/bin:$PATH"
# NODE_OPTIONS_BUILD overrides the webpack/node heap (default 6 GB); with
# JAVA_TOOL_OPTIONS=-Xmx3g it keeps the build under the harness's memory
# threshold when other sessions share the VM
export NODE_OPTIONS="${NODE_OPTIONS_BUILD:---max-old-space-size=6144}"
cd "${1:-$HOME/dev/logsidian}"
echo "[$(date '+%H:%M:%S')] repo: $(pwd)"
ts() { date '+%H:%M:%S'; }
echo "[$(ts)] branch: $(git branch --show-current)  head: $(git log --oneline -1)"
echo "[$(ts)] ===== 1 gulp:build (static/ from resources/, CSS) ====="
yarn gulp:build
echo "[$(ts)] ===== 2 cljs:release-electron (produces target/*.js) ====="
if [ "${CLJS_DEBUG:-1}" = 0 ]; then
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
