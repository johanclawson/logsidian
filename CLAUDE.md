# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Logsidian** - "Obsidian's speed with Logseq's blocks, files stay yours."

Logsidian is a high-performance fork of Logseq focused on file-based graphs with dramatically improved performance for large knowledge bases. The project aims to combine:
- **Logseq's power**: Block-level editing, bidirectional linking, outliner operations
- **Obsidian's speed**: Fast startup, responsive UI, efficient memory usage
- **File-first philosophy**: Markdown files are the source of truth, no vendor lock-in

### Project Identity

| Item | Value |
|------|-------|
| **Name** | Logsidian |
| **Tagline** | "Obsidian's speed with Logseq's blocks, files stay yours" |
| **Base** | Logseq 0.10.15 (stable, file-based only) |
| **License** | AGPL-3.0 (app), MIT (sidecar) |
| **Status** | Early Development |

### Why 0.10.15?

We chose Logseq 0.10.15 as the base because:
- **Stable**: Last major stable release before the database version
- **File-based only**: No SQLite/DB-based graph code to maintain
- **Clean codebase**: Simpler architecture without dual file/DB code paths
- **Our focus**: The JVM sidecar is specifically for file-based graphs

---

## Build Commands

### Requirements

- Node.js 22.x (use `nvm use 22.21.0`)
- Java 21 (Temurin recommended)
- Clojure CLI 1.11+
- Yarn (via corepack: `corepack enable`)

### Browser Development

```bash
yarn install              # Install dependencies (includes postinstall builds)
yarn watch                # Dev server at http://localhost:3001
```

Dev servers started by `yarn watch`:
- http://localhost:3001 - Main application
- http://localhost:3002 - Secondary server
- http://localhost:9630 - Shadow-cljs dashboard
- nREPL on port 8701

### Desktop App Development

```bash
yarn install && cd static && yarn install && cd ..
yarn watch                # Wait for "Build Completed" for :electron and :app
yarn dev-electron-app     # In a separate terminal
# Or use: bb dev:electron-start
```

### Production Builds

```bash
yarn release              # Browser - outputs to static/
yarn release-electron     # Desktop - outputs to static/out/
```

---

## Testing Commands

```bash
bb dev:lint-and-test      # Run all linters and unit tests
bb lint:dev               # Run all linters only
yarn test                 # Run unit tests only
```

**Focus Testing:**
1. Add `^:focus` metadata to test: `(deftest ^:focus test-name ...)`
2. Run: `bb dev:test -i focus`

**Individual Linters:**
```bash
bb lint:kondo-git-changes   # Fast lint for changed files only
bb lint:carve               # Detect unused vars
bb lint:large-vars          # Check for overly complex functions
```

---

## Architecture

### Tech Stack

- **ClojureScript** compiled via Shadow-cljs
- **React** wrapped with Rum for UI components
- **DataScript** for in-memory database with Datalog queries
- **Electron** for desktop app

### Source Organization

```
logsidian/
├── src/
│   ├── main/frontend/          # Main frontend code
│   │   ├── components/         # UI components (Rum/React)
│   │   ├── handler/            # System handlers and business logic
│   │   ├── worker/             # Web worker code
│   │   └── common/             # Shared code between worker and frontend
│   ├── electron/               # Electron desktop app specific code
│   └── test/                   # Unit tests
├── deps/                       # Internal ClojureScript libraries
│   ├── graph-parser/           # Parses Logseq graphs
│   ├── db/                     # Database operations
│   ├── outliner/               # Outliner operations
│   └── common/                 # Shared utilities
├── packages/                   # JavaScript dependencies
│   ├── ui/                     # shadcn-based component system
│   └── tldraw/                 # Custom fork for whiteboards
└── clj-e2e/                    # End-to-end Clojure tests
```

### State Management

- **Document state** (pages, blocks): stored in DataScript
- **UI state**: stored in Clojure atoms
- **Components**: subscribe via Rum reactive components

### Code Conventions

- Keywords defined using `logseq.common.defkeywords/defkeyword`
- File-specific code in `file_based/` directories
- Worker and frontend namespaces must stay separate

---

## REPL Setup

### VSCode + Calva

1. Run `yarn watch` (starts nREPL on port 8701)
2. `Cmd+Shift+P` → "Calva: Connect to a Running REPL Server in the Project"
3. Select: logseq → shadow-cljs → :app → localhost:8701

### Web Worker REPL

Use `(shadow.user/worker-repl)` or check http://localhost:9630/runtimes for runtime IDs.

---

## JVM Sidecar Performance Project

The core differentiator of Logsidian is a JVM sidecar approach to dramatically improve performance for large file-based graphs.

### The Problem

Large Logseq graphs (10k+ blocks) suffer from:
- Slow startup (minutes to load)
- High memory usage (GBs for large graphs)
- UI lag during operations

### The Solution: JVM Sidecar with Lazy Loading

**Architecture:**
```
┌─────────────────────────────────────────────────────────────┐
│                     Electron (Renderer)                      │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                 ClojureScript UI                     │    │
│  │         (existing Logseq frontend code)              │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │                                  │
│                    Transit over IPC                          │
│                           │                                  │
└───────────────────────────┼─────────────────────────────────┘
                            │
┌───────────────────────────┼─────────────────────────────────┐
│                     JVM Sidecar                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              DataScript + IStorage                   │    │
│  │         (lazy loading with soft references)          │    │
│  └─────────────────────────────────────────────────────┘    │
│                           │                                  │
│                      File System                             │
│                           │                                  │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              Markdown/Org-mode Files                 │    │
│  │           (source of truth, unchanged)               │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Key Components:**
1. **DataScript IStorage**: JVM-only protocol enabling lazy loading with soft references
2. **Transit Serialization**: Already used in Logseq worker communication
3. **Named Pipes (Windows)**: Fast IPC with sub-millisecond latency
4. **Soft References**: JVM garbage collector automatically evicts unused data

### Why JVM?

- **DataScript IStorage is JVM-only**: The lazy loading protocol only exists in JVM DataScript
- **Soft References**: JVM GC can automatically manage memory pressure
- **Clojure compatibility**: Same language as ClojureScript, easy code sharing
- **Proven technology**: DataScript on JVM is battle-tested

### Project Structure

```
X:\source\repos\
├── logsidian/                    # Main app (AGPL-3.0)
│   └── (this repo)
│
├── logsidian-sidecar/            # JVM sidecar (MIT)
│   └── (separate repo - original code)
│
└── logseqWinArm64/               # ARM64 fork reference
    └── docs/hybrid-architecture-revised.md
```

### Implementation Phases

1. **Phase 0: Validation** - Test JVM startup time, IPC latency
2. **Phase 1: Read Path** - Lazy loading for queries
3. **Phase 2: Write Path** - Transaction handling
4. **Phase 3: Integration** - Full file-based graph support

---

## Upstream Sync Process

This project is based on Logseq 0.10.15. To merge upstream fixes:

```bash
git remote add upstream https://github.com/logseq/logseq.git
git fetch upstream --tags
git merge 0.10.15  # or newer 0.10.x tag
# Resolve conflicts
git push origin master
```

**Note:** We intentionally stay on 0.10.x branch (file-based only). Do not merge 0.11.x+ which includes database graphs.

---

## Windows Development Notes

### Path Issues with Claude Code

When running commands through Claude Code's bash environment, there can be path translation issues (`X:\x\source\repos\` instead of `X:\source\repos\`).

**Workaround:** Run build commands from a native Windows terminal (cmd.exe or PowerShell):

```cmd
cd X:\source\repos\logsidian
nvm use 22.21.0
corepack enable
yarn install
yarn watch
```

### Install Dependencies

Via scoop:
```
scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
scoop bucket add extras
scoop bucket add java
scoop install java/openjdk clj-deps babashka leiningen nodejs-lts
```

Or via winget:
```
winget install --id CoreyButler.NVMforWindows
nvm install 22
nvm use 22
npm install -g yarn
winget install --id Microsoft.OpenJDK.17
```

---

## Related Resources

- **Architecture Document**: `X:\source\repos\logseqWinArm64\docs\hybrid-architecture-revised.md`
- **ARM64 Fork**: https://github.com/johanclawson/logseq-win-arm64
- **Upstream Logseq**: https://github.com/logseq/logseq
- **Logseq 0.10.15**: https://github.com/logseq/logseq/releases/tag/0.10.15

---

## Links

- **Main Repo**: https://github.com/johanclawson/logsidian (private)
- **Sidecar Repo**: https://github.com/johanclawson/logsidian-sidecar (private)
- **Domain**: https://logsidian.com (reserved)
- **GitHub Org**: https://github.com/logsidian (reserved)

---

## Disclaimer

Logsidian is an independent open-source project. It is not affiliated with, endorsed by, or connected to Logseq Inc. or Dynalist Inc. (makers of Obsidian).

- Logseq is a trademark of Logseq Inc.
- Obsidian is a trademark of Dynalist Inc.
