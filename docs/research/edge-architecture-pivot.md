# Research: Edge Architecture Pivot Analysis

**Date:** 2025-12-13
**Status:** Complete
**Related:** [Dream Architecture Plan](../../.claude/plans/drifting-soaring-token.md)

## Executive Summary

Pivoting from JVM sidecar to edge computing (Cloudflare Workers) for a free hosted service is **NOT a massive undertaking**. Most core logic already exists in ClojureScript and can run on edge with ~10-15 days of work.

## The Vision: Free Hosted Service

**Goal:** Host Logsidian for everyone for free

**Challenge:** JVM-per-user doesn't scale economically

| Architecture | Cost for 100K Users |
|--------------|---------------------|
| JVM per user | $500,000/month |
| Shared JVM | $20,000/month |
| Serverless JVM | $5,000/month |
| **Edge + User Storage** | **$200/month** |

---

## Why Edge is Feasible

### Key Insight: The Code Already Exists in ClojureScript

The Logsidian/Logseq codebase is primarily ClojureScript, which compiles to JavaScript and runs on edge:

```
Codebase Structure:
├── src/main/frontend/          ← ClojureScript (edge-compatible!)
│   ├── worker/search.cljs      ← FTS logic
│   ├── worker/db_worker.cljs   ← DataScript operations
│   ├── worker/embedding.cljs   ← Vector search orchestration
│   └── ...
├── deps/                        ← ClojureScript libraries
│   ├── db/                     ← DataScript schemas
│   ├── outliner/               ← Block operations
│   └── graph-parser/           ← File parsing (uses mldoc)
└── sidecar/                    ← Clojure JVM (only ~2000 lines)
    └── src/logseq/sidecar/
```

### Component Compatibility Analysis

| Component | Current Location | Edge-Ready? | Notes |
|-----------|------------------|-------------|-------|
| mldoc parser | JS (OCaml→JS) | ✅ Yes | Already JavaScript |
| DataScript | CLJS | ✅ Yes | Same library, different runtime |
| Graph parser | `deps/graph-parser` | ✅ Yes | Pure CLJS |
| Outliner ops | `deps/outliner` | ✅ Yes | Pure CLJS |
| FTS5 logic | `worker/search.cljs` | ✅ Yes | Query building is CLJS |
| UI components | `src/main/frontend/` | ✅ Yes | React/Rum, already CLJS |
| IStorage | `sidecar/storage.clj` | ❌ JVM-only | Replace with D1 |
| JGit | `sidecar/` (planned) | ❌ JVM-only | Replace with Git HTTP API |

### JVM Sidecar Size Analysis

The JVM sidecar is surprisingly small:

| Component | Lines of Code | Edge Equivalent |
|-----------|---------------|-----------------|
| `storage.clj` (IStorage) | ~180 | Cloudflare D1 |
| `server.clj` (TCP/WS) | ~900 | Cloudflare handles routing |
| `outliner.clj` | ~400 | Use existing CLJS `deps/outliner` |
| `file_export.clj` | ~150 | Use existing CLJS |
| `protocol.clj` | ~50 | Not needed (HTTP) |
| **Total JVM-specific** | **~1680** | Mostly replaceable |

**Conclusion:** Only ~1700 lines are JVM-specific, and most have direct edge equivalents.

---

## Architecture Comparison

### Current: Desktop with JVM Sidecar

```
┌─────────────────────────────────────────────────────────────────┐
│                         Browser                                  │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  UI (ClojureScript)                                         ││
│  └─────────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  Web Worker (ClojureScript)                                 ││
│  │  - mldoc parser                                             ││
│  │  - DataScript                                               ││
│  │  - SQLite FTS5 (WASM)                                       ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
                              │ IPC
┌─────────────────────────────▼───────────────────────────────────┐
│                      JVM Sidecar                                 │
│  - DataScript + IStorage (lazy loading)                         │
│  - SQLite-JDBC                                                  │
│  - JGit (planned)                                               │
└─────────────────────────────────────────────────────────────────┘
                              │
                       Local Git Repo
```

### Target: Edge with User Storage

```
┌─────────────────────────────────────────────────────────────────┐
│                         Browser                                  │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  UI (ClojureScript) - Same code!                            ││
│  └─────────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  Service Worker (optional)                                  ││
│  │  - Offline read-only cache                                  ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
                              │ HTTPS
┌─────────────────────────────▼───────────────────────────────────┐
│                   Cloudflare Worker                              │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  Core Logic (ClojureScript) - Same code!                    ││
│  │  - mldoc parser                                             ││
│  │  - DataScript                                               ││
│  │  - Graph parser                                             ││
│  │  - Outliner operations                                      ││
│  └─────────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  Cloudflare D1 (SQLite at edge)                             ││
│  │  - FTS5 search index                                        ││
│  │  - User session cache                                       ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
                              │ Git HTTP API
┌─────────────────────────────▼───────────────────────────────────┐
│              User's Git Provider (FREE)                          │
│              GitHub / GitLab / Gitea                             │
│              Storage is user's responsibility                    │
└─────────────────────────────────────────────────────────────────┘
```

---

## Implementation Plan

### Phase 1: Project Setup (1 day)

```bash
# Initialize Cloudflare Worker project
npm create cloudflare@latest logsidian-edge
cd logsidian-edge

# Add ClojureScript build
npm install shadow-cljs
```

**shadow-cljs.edn for Worker target:**
```clojure
{:builds
 {:worker
  {:target :esm
   :output-dir "dist"
   :modules {:worker {:init-fn logsidian.edge.worker/init}}
   :js-options {:js-provider :import}}}}
```

### Phase 2: Compile CLJS to Worker (2-3 days)

**Tasks:**
1. Create Worker entry point that loads existing CLJS modules
2. Configure shadow-cljs for ESM output (Workers requirement)
3. Handle DataScript initialization per-request
4. Test mldoc parsing works in Worker environment

**Key file: `src/logsidian/edge/worker.cljs`**
```clojure
(ns logsidian.edge.worker
  (:require [datascript.core :as d]
            [logseq.graph-parser.core :as parser]
            [logseq.db.schema :as schema]))

(defn handle-request [request env ctx]
  ;; Reuse existing CLJS logic
  (let [conn (d/create-conn schema/schema)
        content (fetch-from-git request env)
        parsed (parser/parse content)]
    (d/transact! conn parsed)
    ;; Return response...
    ))

(defn init []
  (js/addEventListener "fetch"
    (fn [event]
      (.respondWith event (handle-request ...)))))
```

### Phase 3: Git HTTP Integration (3-5 days)

**Replace local file system with Git HTTP API:**

```clojure
(ns logsidian.edge.git
  (:require [promesa.core :as p]))

(defn fetch-file [env owner repo path]
  (p/let [response (js/fetch
                     (str "https://api.github.com/repos/"
                          owner "/" repo "/contents/" path)
                     #js {:headers #js {"Authorization"
                                        (str "Bearer " (.-GITHUB_TOKEN env))}})]
    (p/let [json (.json response)
            content (js/atob (.-content json))]
      content)))

(defn list-files [env owner repo path]
  ;; Similar, returns directory listing
  )

(defn write-file [env owner repo path content message]
  ;; PUT to GitHub API for commits
  )
```

**Considerations:**
- GitHub API rate limits: 5000 req/hour with auth
- Use conditional requests (If-None-Match) for caching
- Shallow clone for initial load, then incremental

### Phase 4: D1 Database Integration (2-3 days)

**Replace SQLite WASM with Cloudflare D1:**

```clojure
(ns logsidian.edge.db
  (:require [promesa.core :as p]))

(defn init-schema [db]
  (.exec db "CREATE TABLE IF NOT EXISTS blocks (
               id TEXT PRIMARY KEY,
               title TEXT,
               page TEXT)")
  (.exec db "CREATE VIRTUAL TABLE IF NOT EXISTS blocks_fts
             USING fts5(id, title, page, tokenize='trigram')"))

(defn search-blocks [db query]
  (p/let [stmt (.prepare db
                 "SELECT * FROM blocks_fts WHERE title MATCH ? LIMIT 100")
          results (.bind stmt query)
          rows (.all results)]
    (js->clj (.-results rows) :keywordize-keys true)))

(defn upsert-block [db block]
  (p/let [stmt (.prepare db
                 "INSERT OR REPLACE INTO blocks (id, title, page)
                  VALUES (?, ?, ?)")
          _ (.bind stmt (:id block) (:title block) (:page block))]
    (.run stmt)))
```

**D1 bindings in wrangler.toml:**
```toml
[[d1_databases]]
binding = "DB"
database_name = "logsidian"
database_id = "xxx"
```

### Phase 5: Authentication (1-2 days)

**GitHub OAuth flow:**

```clojure
(defn handle-auth-callback [request env]
  (let [code (-> request .-url js/URL. .-searchParams (.get "code"))]
    (p/let [token-response (js/fetch "https://github.com/login/oauth/access_token"
                             #js {:method "POST"
                                  :headers #js {"Accept" "application/json"}
                                  :body (js/JSON.stringify
                                          #js {:client_id (.-GITHUB_CLIENT_ID env)
                                               :client_secret (.-GITHUB_CLIENT_SECRET env)
                                               :code code})})
            token-json (.json token-response)
            access-token (.-access_token token-json)]
      ;; Store in KV or return as cookie
      )))
```

### Phase 6: Deployment (1 day)

**wrangler.toml:**
```toml
name = "logsidian-edge"
main = "dist/worker.js"
compatibility_date = "2024-01-01"

[vars]
GITHUB_CLIENT_ID = "xxx"

[[kv_namespaces]]
binding = "SESSIONS"
id = "xxx"

[[d1_databases]]
binding = "DB"
database_name = "logsidian"
database_id = "xxx"
```

**Deploy:**
```bash
npx wrangler deploy
```

---

## Effort Summary

| Task | Days | Dependencies |
|------|------|--------------|
| Project setup | 1 | None |
| CLJS to Worker compilation | 2-3 | Setup |
| Git HTTP integration | 3-5 | CLJS working |
| D1 database integration | 2-3 | CLJS working |
| Authentication | 1-2 | Git integration |
| Deployment pipeline | 1 | All above |
| **Total** | **10-15 days** | |

---

## Cost Analysis

### Cloudflare Pricing (as of 2024)

| Service | Free Tier | Paid Tier |
|---------|-----------|-----------|
| Workers | 100K req/day | $5/mo for 10M req |
| D1 | 5M rows read/day | $0.001/M rows |
| KV | 100K reads/day | $0.50/M reads |
| R2 | 10GB storage | $0.015/GB |

### Projected Costs

| Users | Requests/Month | D1 Reads | **Total Cost** |
|-------|----------------|----------|----------------|
| 1,000 | 3M | 30M | ~$5/month |
| 10,000 | 30M | 300M | ~$20/month |
| 100,000 | 300M | 3B | ~$200/month |

**Conclusion:** Free tier covers small deployments; $200/month supports 100K users.

---

## Feature Comparison

| Feature | Desktop + JVM | Edge |
|---------|---------------|------|
| Offline editing | ✅ Full | ⚠️ Read-only cache |
| Graph size | ✅ Unlimited (lazy load) | ⚠️ Limited by Worker memory |
| Startup time | ⚠️ ~500ms JVM | ✅ ~50ms cold start |
| Vector search | ✅ ONNX + JVector | ⚠️ External API or skip |
| Git integration | ✅ Full JGit | ⚠️ HTTP API only |
| Cost per user | ✅ $0 (local) | ✅ ~$0.002/month |
| Multi-device sync | ⚠️ Manual | ✅ Automatic (Git) |

---

## Dual-Target Strategy

Support BOTH architectures with shared core:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Shared Core (ClojureScript)                   │
│                                                                  │
│  deps/db           - DataScript schemas                         │
│  deps/outliner     - Block operations                           │
│  deps/graph-parser - File parsing                               │
│  worker/*.cljs     - Search, sync logic                         │
│                                                                  │
│  ~50,000 lines of shared code                                   │
└─────────────────────────────────────────────────────────────────┘
              │                                │
    ┌─────────┴─────────┐          ┌──────────┴──────────┐
    ▼                   ▼          ▼                     ▼
┌───────────┐   ┌───────────┐   ┌───────────┐   ┌───────────┐
│  Electron │   │    JVM    │   │   Edge    │   │  Mobile   │
│   App     │   │  Sidecar  │   │  Worker   │   │  (future) │
│           │   │           │   │           │   │           │
│  Desktop  │   │  Power    │   │   Free    │   │   Native  │
│  users    │   │  users    │   │   tier    │   │   apps    │
└───────────┘   └───────────┘   └───────────┘   └───────────┘
```

**Build targets:**
- `shadow-cljs release app` → Electron renderer
- `shadow-cljs release worker` → Web Worker (desktop)
- `shadow-cljs release edge` → Cloudflare Worker
- `clj -T:build uberjar` → JVM Sidecar

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Worker memory limits (128MB) | Large graphs fail | Pagination, streaming |
| GitHub API rate limits | Heavy users blocked | Caching, conditional requests |
| D1 query limits | Search degraded | Index optimization |
| Cold start latency | First request slow | Keep-alive pings |
| CLJS bundle size | Slow load | Code splitting, tree shaking |

---

## Conclusion

### Is it a massive pivot?

**No.** The pivot is ~10-15 days of work because:

1. **90%+ code reuse** - Core logic is already ClojureScript
2. **mldoc already JavaScript** - No parser rewrite needed
3. **DataScript works in CLJS** - Same library, different runtime
4. **JVM sidecar is small** - Only ~1700 lines of JVM-specific code
5. **Edge equivalents exist** - D1 for SQLite, Git HTTP for JGit

### Recommendation

1. **Keep JVM sidecar** for desktop power users (large graphs, offline)
2. **Add edge target** for free hosted service
3. **Share 90%+ of code** between both targets
4. **Start with Phase 1-2** to validate CLJS-on-Worker works

---

## References

- [Cloudflare Workers Documentation](https://developers.cloudflare.com/workers/)
- [Cloudflare D1 Documentation](https://developers.cloudflare.com/d1/)
- [shadow-cljs ESM Target](https://shadow-cljs.github.io/docs/UsersGuide.html#target-esm)
- [GitHub REST API](https://docs.github.com/en/rest)
- [DataScript on ClojureScript](https://github.com/tonsky/datascript)
