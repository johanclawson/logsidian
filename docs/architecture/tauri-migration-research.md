# Tauri Migration Research

**Status**: Deferred
**Researched**: 2026-01-09
**Decision**: Document for future consideration
**Primary Blocker**: Plugin system incompatibility

---

## Executive Summary

Tauri is a viable alternative to Electron for Logsidian, offering significant performance and size improvements. However, the **loss of the current Logseq plugin ecosystem** is a major blocker that makes immediate migration impractical.

| Aspect | Electron (Current) | Tauri |
|--------|-------------------|-------|
| Bundle size | ~150-180MB | ~10-20MB |
| Memory usage | 150-300MB | 30-50MB |
| Startup time | 1-2s | <0.5s |
| Plugin compatibility | Full | **None** |
| WebView consistency | Chromium everywhere | Varies by platform |

---

## Why Consider Tauri?

### Benefits

1. **Dramatically smaller app size** - 93% reduction (150MB → 10-15MB)
2. **Lower memory footprint** - 80% reduction
3. **Faster startup** - Sub-500ms cold start
4. **rsapi simplification** - rsapi is already Rust; could integrate directly, eliminating native module build pain
5. **Modern architecture** - Rust backend, better security model

### Blockers

1. **Plugin system incompatibility** (PRIMARY BLOCKER)
   - Electron's isolation model differs fundamentally from Tauri
   - Current plugins rely on Node.js APIs not available in Tauri
   - Would need complete plugin architecture redesign

2. **WebKit rendering differences** on macOS/Linux
   - CSS `position: sticky` edge cases
   - Flexbox aspect ratio handling
   - Scroll behavior variations

3. **90 IPC handlers** need Rust rewrites
   - Significant development effort (6-8 weeks minimum)

---

## Platform Analysis

### Windows - Best Candidate

| Aspect | Status |
|--------|--------|
| WebView | WebView2 (Chromium-based) |
| Rendering | Consistent with Electron |
| Updates | Evergreen (auto-updated) |
| Recommendation | **Primary Tauri target** |

Windows uses WebView2 which is Chromium-based, meaning virtually identical rendering to Electron. This makes Windows the ideal first platform for any Tauri migration.

### macOS - Caution Required

| Aspect | Status |
|--------|--------|
| WebView | WKWebView (Safari/WebKit) |
| Rendering | Differs from Chromium |
| Updates | Tied to macOS version |
| Recommendation | Keep Electron initially |

WebKit on macOS has known differences:
- Safari is always behind other browsers in feature support
- CSS rendering inconsistencies (flexbox, sticky positioning)
- Users on older macOS versions get older WebKit

### Linux - High Variance

| Aspect | Status |
|--------|--------|
| WebView | WebKitGTK |
| Rendering | Varies by distro |
| Updates | Distribution-dependent |
| Recommendation | Keep Electron or skip platform |

Linux has the highest variance due to different WebKitGTK versions across distributions.

---

## Technical Migration Scope

### IPC Handlers to Rewrite (90 total)

Based on analysis of `src/electron/electron/handler.cljs`:

| Category | Count | Complexity | Notes |
|----------|-------|------------|-------|
| File operations | 15 | Low | mkdir, read, write, rename, stat |
| Graph management | 10 | Medium | setCurrentGraph, getGraphs, etc. |
| Git operations | 8 | Medium | runGit, gitCommitAll, gitStatus |
| Plugin management | 6 | **High** | Primary blocker |
| File sync (rsapi) | 12 | Medium | Could simplify with direct Rust |
| HTTP/Network | 5 | Medium | httpRequest, proxy handling |
| System dialogs | 4 | Low | openDir, openDialog |
| Window management | 8 | Low | toggle, minimize, etc. |
| Configuration | 6 | Low | userAppCfgs |
| File watching | 3 | Medium | addDirWatcher, unwatchDir |
| Other | 13 | Varies | Various utilities |

### Native Modules

| Module | Current Use | Tauri Equivalent |
|--------|-------------|------------------|
| @logseq/rsapi | File sync, encryption | **Direct Rust integration** (opportunity!) |
| keytar | OS keychain | tauri-plugin-keychain or keyring-rs |
| electron-deeplink | URL schemes | tauri-plugin-deep-link |
| electron-window-state | Window persistence | Custom implementation |
| chokidar | File watching | tauri-plugin-fs-watch (notify-rs) |

### Electron Features Used

| Feature | Logsidian Usage | Tauri Support |
|---------|-----------------|---------------|
| Custom protocols | `logseq://`, `lsp://`, `assets://` | Supported |
| Context menus | Right-click menus | Limited - may need custom |
| Multiple windows | One per graph | Supported |
| Frameless windows | Custom titlebar | Supported |
| Auto-updater | GitHub releases | Supported |
| Preload scripts | Context isolation | Different model |

---

## Plugin System: The Primary Blocker

### Current Architecture (Electron)

```
┌─────────────────────────────────────────────────────────┐
│                    Main Process (Node.js)                │
│  - Full Node.js API access                              │
│  - Can spawn processes, access filesystem               │
│  - IPC bridge to renderer                               │
└─────────────────────────────────────────────────────────┘
                         │
                    IPC Bridge
                         │
┌─────────────────────────────────────────────────────────┐
│                  Renderer Process                        │
│  ┌─────────────────────────────────────────────────┐   │
│  │              Plugin Sandbox (iframe)              │   │
│  │  - Limited API access via postMessage            │   │
│  │  - Can inject CSS/JS into main UI                │   │
│  │  - Access to Logseq Plugin API                   │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### Tauri Architecture (Different Model)

```
┌─────────────────────────────────────────────────────────┐
│                    Rust Core                             │
│  - No Node.js - pure Rust                               │
│  - Tauri commands (not IPC)                             │
│  - Strict capability-based permissions                  │
└─────────────────────────────────────────────────────────┘
                         │
                   Tauri Commands
                         │
┌─────────────────────────────────────────────────────────┐
│                    WebView                               │
│  - No Node.js integration                               │
│  - Invoke Tauri commands only                           │
│  - Plugin model would need complete redesign            │
└─────────────────────────────────────────────────────────┘
```

### Migration Options for Plugins

1. **WASM-based plugins** - Plugins compiled to WebAssembly
   - Pro: Sandboxed, portable
   - Con: Complete ecosystem rebuild required

2. **Lua/JavaScript sandbox** - Custom scripting runtime
   - Pro: Familiar to plugin developers
   - Con: Limited capability, significant work

3. **Rust plugin system** - Native Rust plugins
   - Pro: Full performance
   - Con: Higher barrier for plugin developers

4. **Drop plugin support** - Focus on core features
   - Pro: Simplifies migration
   - Con: Loses major Logseq differentiator

**Current recommendation**: Any Tauri migration would likely need to defer plugin support or build a new, incompatible system.

---

## rsapi: An Opportunity

The `@logseq/rsapi` module is **already written in Rust**. Currently it's compiled as a Node.js native addon, requiring:
- Platform-specific compilation (x64, ARM64)
- Distribution as npm packages
- Complex CI/CD for native binaries

In Tauri, rsapi could be **integrated directly** into the Rust backend:

```rust
// Instead of Node.js native addon:
// const rsapi = require('@logseq/rsapi');
// rsapi.keygen();

// Direct Rust integration:
use logseq_rsapi::{keygen, set_env, ...};

#[tauri::command]
fn generate_key() -> Result<String, String> {
    keygen().map_err(|e| e.to_string())
}
```

Benefits:
- No more native module compilation issues
- No ARM64 binary distribution problems
- Direct Rust-to-Rust calls (faster)
- Simplified build process

---

## Estimated Migration Effort

### Phase 1: Windows Only (Minimum Viable)

| Task | Effort |
|------|--------|
| Tauri project setup | 1-2 days |
| File system commands (15) | 1 week |
| Window management (8) | 3-4 days |
| Git integration (8) | 1 week |
| rsapi integration (12) | 1-2 weeks |
| Deep linking (4) | 2-3 days |
| File watching | 1 week |
| Testing & polish | 2 weeks |
| **Total** | **6-8 weeks** |

**Note**: This does NOT include plugin support.

### Phase 2: macOS/Linux (If Phase 1 Succeeds)

| Task | Effort |
|------|--------|
| WebKit CSS compatibility fixes | 1-2 weeks |
| Platform-specific code paths | 1 week |
| macOS version testing | 1 week |
| Linux distro testing | 1 week |
| **Total** | **4-5 weeks** |

### Phase 3: Plugin System (Major Undertaking)

| Task | Effort |
|------|--------|
| Architecture design | 2-4 weeks |
| Implementation | 2-3 months |
| Plugin API migration | 1-2 months |
| Ecosystem rebuild | Ongoing |
| **Total** | **4-6+ months** |

---

## Recommended Strategy (Future)

If/when Tauri migration becomes viable:

### Stage 1: Windows Tauri (Without Plugins)
- Target users who don't rely on plugins
- Offer as "Logsidian Lite" or separate download
- Validate Tauri approach with real users

### Stage 2: Expand Platforms
- Add macOS if WebKit issues are manageable
- Consider Linux or leave on Electron

### Stage 3: Plugin System
- Design new plugin architecture
- Provide migration path for existing plugins
- Gradual ecosystem transition

### Alternative: Hybrid Long-term

```
Windows:  Tauri (for users who want performance)
          Electron (for users who need plugins)

macOS:    Electron (WebKit issues)

Linux:    Electron (distro variance)
```

---

## Prior Art

### logseq-ng (Experimental Tauri Port)
- **Repository**: https://github.com/andelf/logseq-ng
- **Status**: Abandoned/stalled (6 commits, 12 stars)
- **Approach**: Git submodule wrapping main Logseq
- **Outcome**: Proof of concept only, not functional

### Community Discussion
- **GitHub Discussion #5875**: https://github.com/logseq/logseq/discussions/5875
- Key quote: "A lot has to be done to port it to Tauri"
- Identified blockers: Plugin support, browser compatibility

### ClojureScript + Tauri
- **Working template**: https://github.com/rome-user/tauri-clojurescript-template
- Shadow-cljs + React + Helix
- Confirms ClojureScript/Tauri compatibility

---

## References

### Official Documentation
- [Tauri 2.0 Documentation](https://v2.tauri.app/)
- [Tauri IPC Concepts](https://v2.tauri.app/concept/inter-process-communication/)
- [Tauri Deep Linking](https://v2.tauri.app/plugin/deep-linking/)
- [Tauri Sidecar (External Binaries)](https://v2.tauri.app/develop/sidecar/)
- [Tauri WebView Versions](https://v2.tauri.app/reference/webview-versions/)

### Comparisons & Analysis
- [Tauri vs Electron 2025 - RaftLabs](https://www.raftlabs.com/blog/tauri-vs-electron-pros-cons/)
- [LogRocket Migration Guide](https://blog.logrocket.com/tauri-electron-comparison-migration-guide/)
- [Real-world Comparison - Levminer](https://www.levminer.com/blog/tauri-vs-electron)
- [DoltHub Analysis](https://www.dolthub.com/blog/2025-11-13-electron-vs-tauri/)
- [Hopp Blog - Trade-offs](https://www.gethopp.app/blog/tauri-vs-electron)

### Browser Compatibility
- [CSS Browser Issues - LambdaTest](https://www.lambdatest.com/blog/css-browser-compatibility-issues/)
- [WebKit Safari 18.4 Features](https://webkit.org/blog/16574/webkit-features-in-safari-18-4/)
- [Interop 2024](https://webkit.org/blog/14955/the-web-just-gets-better-with-interop/)

### Related Projects
- [notify-rs (File Watching)](https://github.com/notify-rs/notify)
- [tauri-plugin-fs-watch](https://github.com/tauri-apps/tauri-plugin-fs-watch)
- [WRY (WebView Library)](https://github.com/tauri-apps/wry)

---

## Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-01-09 | Defer Tauri migration | Plugin incompatibility is primary blocker; sidecar experiment concluded |

---

## Revision History

- **2026-01-09**: Initial research and documentation
