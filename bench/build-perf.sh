#!/bin/bash
# usage: build-perf.sh [repoDir]   (default ~/dev/logsidian)
set -euo pipefail
export PATH="$HOME/.local/bin:$PATH"
export NODE_OPTIONS="--max-old-space-size=6144"
cd "${1:-$HOME/dev/logsidian}"
echo "[$(date '+%H:%M:%S')] repo: $(pwd)"
ts() { date '+%H:%M:%S'; }
echo "[$(ts)] branch: $(git branch --show-current)  head: $(git log --oneline -1)"
echo "[$(ts)] ===== 1 gulp:build (static/ from resources/, CSS) ====="
yarn gulp:build
echo "[$(ts)] ===== 2 cljs:release-electron (produces target/*.js) ====="
yarn cljs:release-electron
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
