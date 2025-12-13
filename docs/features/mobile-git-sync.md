# Mobile Git Sync - Feature Specification

> **Status**: Planning
> **Last Updated**: 2025-12-09
> **Target Platforms**: Android, iOS, Windows, macOS, Linux

## Executive Summary

This document outlines the design for adding seamless Git-based synchronization to Logsidian, with a focus on making it work on mobile platforms (Android/iOS) where native Git is unavailable. The solution uses a GitHub App for authentication, isomorphic-git for cross-platform Git operations, and AI-powered conflict resolution to hide Git complexity from non-technical users.

**Key Principles:**
- Git is invisible to users - they just see "sync"
- Zero configuration for new users (sign up → download → works)
- Seamless migration from existing Logseq installations
- AI handles conflicts so users never see merge errors

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Current Architecture Analysis](#2-current-architecture-analysis)
3. [Proposed Solution](#3-proposed-solution)
4. [User Flows](#4-user-flows)
5. [Technical Architecture](#5-technical-architecture)
6. [Conflict Resolution System](#6-conflict-resolution-system)
7. [Data Models](#7-data-models)
8. [API Specifications](#8-api-specifications)
9. [Security Considerations](#9-security-considerations)
10. [Pricing & Limits](#10-pricing--limits)
11. [Implementation Phases](#11-implementation-phases)
12. [Tech Stack](#12-tech-stack)

---

## 1. Problem Statement

### 1.1 Current Limitation

Logseq's Git sync is **exclusively Electron-based** due to:

1. **Hard dependency on Dugite** - bundles native Git binaries (desktop only)
2. **Explicit platform checks** disabling sync on mobile:
   ```clojure
   ;; src/main/frontend/fs/sync.cljs:3216
   (when-not (or @*sync-starting (util/mobile?) util/web-platform?)
     ;; sync completely disabled on mobile and web
   ```
3. **IPC architecture** assumes Node.js/Electron
4. **No Git-capable Capacitor plugins** exist

### 1.2 Dependency Chain (Current)

```
Git Sync (Desktop Only)
├── Electron IPC (electron.ipc)
│   └── Only works on Electron
├── Dugite (git binary wrapper)
│   └── Only bundled for Electron/desktop
├── Shell execution (child_process)
│   └── Node.js only
└── Node.js fs backend
    └── Not available on mobile
```

### 1.3 User Pain Points

- Android users cannot sync vaults
- iOS users limited to iCloud (no cross-platform)
- Non-technical users struggle with Git setup
- Merge conflicts are confusing and scary

---

## 2. Current Architecture Analysis

### 2.1 Key Files in Current Implementation

| Component | File | Purpose | Platform |
|-----------|------|---------|----------|
| **Git Sync Core** | `src/electron/electron/git.cljs` | Low-level Git operations via Dugite | Electron only |
| **Sync Engine** | `src/main/frontend/fs/sync.cljs` | State machine + sync logic | All (disabled on mobile) |
| **Sync Handler** | `src/main/frontend/handler/file_sync.cljs` | Remote API integration | All |
| **Git UI** | `src/main/frontend/components/file_based/git.cljs` | Version selector + username input | File-based only |
| **Shell Handler** | `src/main/frontend/handler/shell.cljs` | IPC wrapper for git commands | Electron only |
| **Mobile Utils** | `src/main/frontend/mobile/util.cljs` | Capacitor integration | Mobile |
| **Platform Detection** | `src/main/frontend/util.cljc` | Platform checks | All |
| **FS Protocol** | `src/main/frontend/fs/protocol.cljs` | Filesystem abstraction | All |

### 2.2 Platform Detection

```clojure
;; src/main/frontend/util.cljc
(def mobile? (memoize mobile*?))      ;; checks for "Mobi" in user agent
(def web-platform? nfs?)              ;; !electron && !native-platform
(def electron? (memoize electron*?))  ;; true only on Electron

;; src/main/frontend/mobile/util.cljs
(defn native-platform? [])            ;; true for Capacitor apps
(defn native-ios? [])                 ;; true for iOS specifically
(defn native-android? [])             ;; true for Android specifically
```

### 2.3 Available Capacitor Plugins

**File Operations:**
- `@capacitor/filesystem` - Read/write files (sandboxed paths)

**Security:**
- `@aparajita/capacitor-secure-storage` - Secure credential storage

**System Integration:**
- `@capacitor/network` - Network status detection
- `@capacitor/device` - Device info

**Missing for Git Sync:**
- No native Git execution
- No shell access
- No binary wrapping

---

## 3. Proposed Solution

### 3.1 Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    logsidian.com                             │
│                   (Next.js + Auth.js)                        │
├─────────────────────────────────────────────────────────────┤
│  Backend (Cloudflare Workers or Vercel Edge)                │
│  ├── GitHub App token management                            │
│  ├── User database (free/paid status)                       │
│  ├── Repo creation & size monitoring                        │
│  ├── Conflict resolution queue                              │
│  └── Download link generator with embedded config           │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                 GitHub App: "Logsidian"                      │
│  Permissions:                                                │
│  ├── Repository: Read & Write (contents, metadata)          │
│  ├── Administration: Read & Write (create repos)            │
│  └── Scoped to user-selected repos only                     │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              Logsidian App (All Platforms)                   │
│  ├── isomorphic-git (pure JS Git implementation)            │
│  ├── Cross-platform filesystem abstraction                  │
│  ├── Token refresh via backend API                          │
│  └── Conflict detection & reporting                         │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Why GitHub App (Not OAuth + PAT)

| Aspect | GitHub App | OAuth + PAT |
|--------|------------|-------------|
| **Token Lifecycle** | Auto-refresh (1 hour) | User manages (long-lived) |
| **Scope** | Per-repo granular access | All repos or none |
| **Security** | More secure, revocable | Less secure, manual revoke |
| **User Experience** | One-click install | Must create/paste PAT |
| **Audit** | GitHub tracks all access | Limited visibility |

### 3.3 Why Isomorphic-Git

- **Already in dependencies**: `@isomorphic-git/lightning-fs`
- **Pure JavaScript**: Works in browser, mobile, Electron
- **Capacitor compatible**: Can use any filesystem backend
- **Active maintenance**: Well-supported library
- **Full Git support**: Clone, fetch, push, merge, etc.

```javascript
// Example: Clone with pre-configured token
import git from 'isomorphic-git';
import http from 'isomorphic-git/http/web';

await git.clone({
  fs,
  http,
  dir: '/vault',
  url: 'https://github.com/user/logsidian-vault',
  onAuth: () => ({
    username: 'x-access-token',
    password: installationToken
  })
});
```

---

## 4. User Flows

### 4.1 New User Signup Flow

```
┌─────────────────────────────────────────────────────────────┐
│                   logsidian.com                              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│              Welcome to Logsidian                            │
│     "Obsidian's speed with Logseq's blocks,                 │
│              files stay yours"                               │
│                                                              │
│         [ Sign in with GitHub ]                             │
│                                                              │
│  ✓ Your notes sync via your own GitHub repos                │
│  ✓ You own your data - always                               │
│  ✓ Works on all devices                                     │
│                                                              │
└─────────────────────────────────────────────────────────────┘
                            │
                     User clicks button
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                   GitHub OAuth                               │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Logsidian by @johanclawson wants to access your account    │
│                                                              │
│  ○ Create repositories                                      │
│  ○ Read and write repository contents                       │
│                                                              │
│         [ Authorize Logsidian ]                             │
│                                                              │
└─────────────────────────────────────────────────────────────┘
                            │
                     User authorizes
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                   logsidian.com/setup                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Welcome, @johanclawson!                                    │
│                                                              │
│  Let's create your first vault:                             │
│                                                              │
│  Vault name: [ my-notes_____________ ]                      │
│                                                              │
│  This will create: github.com/johanclawson/logsidian-my-notes│
│                                                              │
│              [ Create Vault ]                               │
│                                                              │
│  ─────────────────────────────────────────────────────────  │
│                                                              │
│  Or import existing Logseq vault:                           │
│              [ I have an existing vault ]                   │
│                                                              │
└─────────────────────────────────────────────────────────────┘
                            │
                     User creates vault
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                   logsidian.com/download                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Your vault is ready!                                       │
│                                                              │
│  Download Logsidian for your device:                        │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ Windows  │  │  macOS   │  │ Android  │  │   iOS    │   │
│  │    ⊞     │  │    🍎     │  │    🤖    │  │    📱    │   │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘   │
│                                                              │
│  Your download includes your account setup.                 │
│  Just install and your vault will sync automatically!       │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 Download with Embedded Config

**Download URL Structure:**
```
https://logsidian.com/download/windows?token=eyJhbGc...
https://logsidian.com/download/android?token=eyJhbGc...
```

**Token Contents (JWT):**
```json
{
  "user_id": "123",
  "github_username": "johanclawson",
  "vaults": [
    {
      "repo": "johanclawson/logsidian-my-notes",
      "installation_id": 456
    }
  ],
  "exp": 1234567890
}
```

**App First Launch Flow:**
```
App launches with token
        │
        ▼
┌─ Token Exchange ───────────────────────────────────────────┐
│  POST /api/auth/exchange                                   │
│  { "setup_token": "eyJhbGc..." }                          │
│                                                            │
│  Response:                                                 │
│  {                                                         │
│    "access_token": "ghs_xxxx",  // GitHub installation token│
│    "user": { "id": 123, "plan": "free" },                 │
│    "vaults": [...]                                         │
│  }                                                         │
└────────────────────────────────────────────────────────────┘
        │
        ▼
┌─ Clone Vaults ─────────────────────────────────────────────┐
│  For each vault:                                           │
│  1. git.clone({ url, token })                             │
│  2. Register local path                                    │
│  3. Start sync watcher                                     │
└────────────────────────────────────────────────────────────┘
        │
        ▼
   Ready to use!
```

### 4.3 Existing Logseq User Migration Flow

```
┌─────────────────────────────────────────────────────────────┐
│  User has existing Logseq installation                       │
│  └── C:\Users\{user}\Documents\Logseq\                      │
│      ├── journals/                                           │
│      ├── pages/                                              │
│      ├── logseq/                                             │
│      │   ├── config.edn        ← User settings              │
│      │   ├── custom.css        ← Custom styling             │
│      │   └── plugins/          ← Installed plugins          │
│      └── .git/ (maybe)         ← Existing git setup         │
└─────────────────────────────────────────────────────────────┘
                            │
                    Logsidian Install
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  1. Detect existing Logseq graphs                           │
│  2. Show: "Found 2 vaults - import to Logsidian?"           │
│  3. User selects which to sync                              │
│  4. We push existing content to their GitHub repo           │
│  5. Settings preserved, sync enabled, done!                 │
└─────────────────────────────────────────────────────────────┘
```

**Detection Logic:**
```javascript
// Common Logseq locations by platform
const LOGSEQ_PATHS = {
  win32: [
    '%USERPROFILE%\\Documents\\Logseq',
    '%USERPROFILE%\\Logseq',
    '%APPDATA%\\Logseq\\graphs.edn'  // Logseq's graph registry
  ],
  darwin: [
    '~/Documents/Logseq',
    '~/Logseq',
    '~/Library/Application Support/Logseq/graphs.edn'
  ],
  android: [
    '/storage/emulated/0/Documents/Logseq',
    '/storage/emulated/0/Logseq'
  ],
  ios: [
    // Check via Capacitor filesystem API
  ]
};

// Logseq stores known graphs in graphs.edn
// Format: [{:name "vault1" :path "/path/to/vault1"} ...]
```

**First Launch UI (With Existing Vaults):**
```
┌─────────────────────────────────────────────────────────────┐
│           Welcome to Logsidian!                              │
│                                                              │
│  ┌─ Found existing vaults ─────────────────────────────┐    │
│  │                                                      │    │
│  │  ☑ My Notes (2.3 GB)        ~/Documents/Logseq      │    │
│  │    ⚠️ Exceeds 1GB free limit - upgrade for full sync │    │
│  │                                                      │    │
│  │  ☑ Work Vault (340 MB)      ~/Work/notes            │    │
│  │    ✓ Within free tier                               │    │
│  │                                                      │    │
│  │  ☐ Old Archive (50 MB)      ~/Archive/logseq        │    │
│  │                                                      │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                              │
│  These will sync to your GitHub: @johanclawson              │
│                                                              │
│  [ ] Keep existing git remote (advanced)                    │
│                                                              │
│         [ Import Selected Vaults ]                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

**What Gets Migrated:**

| Item | Location | Action |
|------|----------|--------|
| **Pages & Journals** | `pages/`, `journals/` | Push to GitHub repo |
| **Config** | `logseq/config.edn` | Preserve exactly |
| **Custom CSS** | `logseq/custom.css` | Preserve exactly |
| **Plugins** | `logseq/plugins/` | Store list, reinstall on new devices |
| **Assets** | `assets/` | Push to repo (counts against storage) |
| **Existing .git** | `.git/` | Option: keep remote or replace |

**Import Flow Backend:**
```
User selects vaults to import
        │
        ▼
┌─ For Each Selected Vault ──────────────────────────────────┐
│  1. API: Create repo (e.g., johanclawson/logsidian-notes)  │
│  2. Initialize with .gitignore, .logsidian-config          │
│  3. git init locally (isomorphic-git)                      │
│  4. Add remote, push all content                           │
│  5. Register vault in our database                         │
└────────────────────────────────────────────────────────────┘
        │
        ▼
   Vault ready, sync enabled!
```

### 4.4 Multi-Device Sync Flow

```
┌─────────────────────────────────────────────────────────────┐
│                     Device A (Desktop)                       │
│  1. User edits "Project Ideas.md"                           │
│  2. Auto-save triggers                                       │
│  3. Sync: commit + push (every 30s or on change)            │
└─────────────────────────────────────────────────────────────┘
                            │
                     GitHub Repository
                    (johanclawson/vault)
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                     Device B (Phone)                         │
│  1. App opens / comes to foreground                         │
│  2. Sync: fetch + merge                                      │
│  3. User sees updated "Project Ideas.md"                    │
└─────────────────────────────────────────────────────────────┘
```

**Sync Triggers:**
- App launch / foreground
- Every 30 seconds while active
- Manual pull-to-refresh
- Before editing a file (fetch latest)
- After saving changes (commit + push)

---

## 5. Technical Architecture

### 5.1 System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              LOGSIDIAN ECOSYSTEM                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                         WEB (logsidian.com)                          │    │
│  │  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐            │    │
│  │  │   Next.js     │  │   Auth.js     │  │   Stripe      │            │    │
│  │  │   Frontend    │  │   (GitHub)    │  │   Payments    │            │    │
│  │  └───────────────┘  └───────────────┘  └───────────────┘            │    │
│  │           │                  │                  │                    │    │
│  │           └──────────────────┼──────────────────┘                    │    │
│  │                              │                                       │    │
│  │  ┌───────────────────────────┴───────────────────────────────────┐  │    │
│  │  │                    API Routes (Edge Functions)                 │  │    │
│  │  │  ├── /api/auth/callback     - GitHub OAuth callback           │  │    │
│  │  │  ├── /api/auth/exchange     - Token exchange for app          │  │    │
│  │  │  ├── /api/vaults/create     - Create new vault repo           │  │    │
│  │  │  ├── /api/vaults/list       - List user's vaults              │  │    │
│  │  │  ├── /api/sync/token        - Get fresh GitHub token          │  │    │
│  │  │  ├── /api/conflicts/report  - Report sync conflict            │  │    │
│  │  │  └── /api/conflicts/resolve - Submit resolution               │  │    │
│  │  └───────────────────────────────────────────────────────────────┘  │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                      │                                       │
│                                      │ HTTPS                                 │
│                                      │                                       │
│  ┌───────────────────────────────────┼───────────────────────────────────┐  │
│  │                            DATABASE                                    │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                   │  │
│  │  │   Users     │  │   Vaults    │  │  Conflicts  │                   │  │
│  │  │  - github_id│  │  - repo     │  │  - file     │                   │  │
│  │  │  - plan     │  │  - user_id  │  │  - versions │                   │  │
│  │  │  - stripe_id│  │  - size     │  │  - status   │                   │  │
│  │  └─────────────┘  └─────────────┘  └─────────────┘                   │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                      │                                       │
├──────────────────────────────────────┼───────────────────────────────────────┤
│                                      │                                       │
│  ┌───────────────────────────────────┼───────────────────────────────────┐  │
│  │                         GITHUB APP                                     │  │
│  │                                   │                                    │  │
│  │  Permissions:                     │                                    │  │
│  │  ├── contents: write              │                                    │  │
│  │  ├── metadata: read          Installation Tokens                      │  │
│  │  └── administration: write        │                                    │  │
│  │                                   │                                    │  │
│  └───────────────────────────────────┼───────────────────────────────────┘  │
│                                      │                                       │
├──────────────────────────────────────┼───────────────────────────────────────┤
│                                      │                                       │
│  ┌───────────────────────────────────┴───────────────────────────────────┐  │
│  │                      LOGSIDIAN APP (All Platforms)                     │  │
│  │                                                                        │  │
│  │  ┌──────────────────────────────────────────────────────────────────┐ │  │
│  │  │                    Cross-Platform Git Layer                       │ │  │
│  │  │  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐     │ │  │
│  │  │  │ isomorphic-git │  │  FS Adapter    │  │  Token Manager │     │ │  │
│  │  │  │  - clone       │  │  - Electron:   │  │  - Refresh     │     │ │  │
│  │  │  │  - fetch       │  │    Node fs     │  │  - Cache       │     │ │  │
│  │  │  │  - push        │  │  - Mobile:     │  │  - Retry       │     │ │  │
│  │  │  │  - merge       │  │    Capacitor   │  │                │     │ │  │
│  │  │  └────────────────┘  └────────────────┘  └────────────────┘     │ │  │
│  │  └──────────────────────────────────────────────────────────────────┘ │  │
│  │                                                                        │  │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐      │  │
│  │  │  Windows   │  │   macOS    │  │  Android   │  │    iOS     │      │  │
│  │  │  Electron  │  │  Electron  │  │  Capacitor │  │  Capacitor │      │  │
│  │  └────────────┘  └────────────┘  └────────────┘  └────────────┘      │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Cross-Platform Git Implementation

**New File: `src/main/frontend/fs/git.cljs`**

```clojure
(ns frontend.fs.git
  "Cross-platform Git operations using isomorphic-git.
   Works on Electron, Android, and iOS."
  (:require
   ["isomorphic-git" :as git]
   ["isomorphic-git/http/web" :as http]
   [frontend.fs.capacitor-fs :as capacitor-fs]
   [frontend.fs.node-fs :as node-fs]
   [frontend.mobile.util :as mobile-util]
   [frontend.util :as util]
   [frontend.state :as state]
   [promesa.core :as p]))

(defn get-fs
  "Returns appropriate filesystem for current platform."
  []
  (if (mobile-util/native-platform?)
    (capacitor-fs/create-fs)
    (node-fs/create-fs)))

(defn get-auth
  "Returns auth callback for GitHub."
  []
  (fn []
    (let [token (state/get-github-token)]
      #js {:username "x-access-token"
           :password token})))

(defn clone!
  "Clone a repository."
  [url dir]
  (p/let [fs (get-fs)]
    (git/clone
     #js {:fs fs
          :http http
          :dir dir
          :url url
          :onAuth (get-auth)
          :singleBranch true
          :depth 1})))

(defn pull!
  "Fetch and merge remote changes."
  [dir]
  (p/let [fs (get-fs)]
    (git/pull
     #js {:fs fs
          :http http
          :dir dir
          :onAuth (get-auth)
          :author #js {:name "Logsidian"
                       :email "sync@logsidian.com"}})))

(defn push!
  "Push local commits to remote."
  [dir]
  (p/let [fs (get-fs)]
    (git/push
     #js {:fs fs
          :http http
          :dir dir
          :onAuth (get-auth)})))

(defn add-all!
  "Stage all changes."
  [dir]
  (p/let [fs (get-fs)]
    (git/add
     #js {:fs fs
          :dir dir
          :filepath "."})))

(defn commit!
  "Create a commit with message."
  [dir message]
  (p/let [fs (get-fs)]
    (git/commit
     #js {:fs fs
          :dir dir
          :message message
          :author #js {:name "Logsidian"
                       :email "sync@logsidian.com"}})))

(defn sync!
  "Full sync: pull, add, commit, push."
  [dir]
  (p/let [_ (pull! dir)
          status (git/statusMatrix #js {:fs (get-fs) :dir dir})
          has-changes? (some #(not= (aget % 1) (aget % 2)) status)]
    (when has-changes?
      (p/do!
       (add-all! dir)
       (commit! dir (str "Sync " (js/Date.)))
       (push! dir)))))
```

### 5.3 Filesystem Abstraction for Capacitor

**New File: `src/main/frontend/fs/capacitor_fs.cljs`**

```clojure
(ns frontend.fs.capacitor-fs
  "Filesystem adapter for Capacitor (mobile).
   Implements the interface expected by isomorphic-git."
  (:require
   ["@capacitor/filesystem" :refer [Filesystem Directory Encoding]]))

(defn create-fs
  "Create isomorphic-git compatible filesystem using Capacitor."
  []
  #js {:promises
       #js {:readFile
            (fn [path options]
              (-> (Filesystem.readFile
                   #js {:path path
                        :directory Directory.Documents
                        :encoding (.-UTF8 Encoding)})
                  (.then #(.-data %))))

            :writeFile
            (fn [path data options]
              (Filesystem.writeFile
               #js {:path path
                    :data data
                    :directory Directory.Documents
                    :encoding (.-UTF8 Encoding)}))

            :unlink
            (fn [path]
              (Filesystem.deleteFile
               #js {:path path
                    :directory Directory.Documents}))

            :readdir
            (fn [path]
              (-> (Filesystem.readdir
                   #js {:path path
                        :directory Directory.Documents})
                  (.then #(clj->js (map :name (.-files %))))))

            :mkdir
            (fn [path options]
              (Filesystem.mkdir
               #js {:path path
                    :directory Directory.Documents
                    :recursive (.-recursive options)}))

            :rmdir
            (fn [path options]
              (Filesystem.rmdir
               #js {:path path
                    :directory Directory.Documents
                    :recursive (.-recursive options)}))

            :stat
            (fn [path]
              (-> (Filesystem.stat
                   #js {:path path
                        :directory Directory.Documents})
                  (.then (fn [result]
                           #js {:type (if (= (.-type result) "directory")
                                        "dir" "file")
                                :size (.-size result)
                                :mtimeMs (.-mtime result)}))))

            :lstat
            (fn [path]
              ;; Same as stat for our purposes
              (.. (create-fs) -promises (stat path)))}})
```

### 5.4 Removing Mobile Guards

**File: `src/main/frontend/fs/sync.cljs`**

```clojure
;; BEFORE (line ~3216):
(when-not (or @*sync-starting (util/mobile?) util/web-platform?)
  (reset! *sync-starting true)
  ;; ... sync setup code
  )

;; AFTER:
(when-not @*sync-starting
  (reset! *sync-starting true)
  ;; ... sync setup code - now works on all platforms
  )
```

**File: `src/main/frontend/handler/file_sync.cljs`**

```clojure
;; BEFORE (line ~104):
(when-not (or util/web-platform? (util/mobile?))
  ;; load file sync list
  )

;; AFTER:
(when-not util/web-platform?
  ;; load file sync list - now includes mobile
  )
```

---

## 6. Conflict Resolution System

### 6.1 Conflict Types & Auto-Resolution

| Conflict Type | Auto-Resolvable? | Strategy |
|---------------|------------------|----------|
| **Same block edited differently** | Often | AI merges both edits |
| **Block deleted vs edited** | Maybe | Keep the edit, flag for review |
| **New blocks in same location** | Yes | Keep both, reorder by timestamp |
| **Config file conflict** | Yes | Merge settings, prefer newer |
| **Binary file (image/pdf)** | No | Ask user which to keep |
| **Rename + edit** | Usually | Follow rename, apply edit |

### 6.2 Conflict Resolution Flow

```
┌─────────────────────────────────────────────────────────────┐
│                   User's Device                              │
│  Sync attempt → Conflict detected → Can't auto-resolve      │
│                         │                                    │
│                         ▼                                    │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  "Sync paused - we're fixing it!"                     │  │
│  │  You can keep working, we'll notify you.              │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                 Logsidian Backend                            │
│                                                              │
│  1. Receive conflict report (both versions of files)        │
│  2. AI attempts auto-resolution                             │
│  3. If confident (>0.9) → resolve & push                    │
│  4. If uncertain → email user with simple choices           │
└─────────────────────────────────────────────────────────────┘
```

### 6.3 AI Auto-Resolution Prompt

```javascript
const prompt = `
You are resolving a sync conflict in a note-taking app.
The user edited this file on two devices before syncing.

ORIGINAL VERSION (common ancestor):
${base}

VERSION A (${deviceA} - edited ${timeA}):
${ours}

VERSION B (${deviceB} - edited ${timeB}):
${theirs}

Rules:
1. NEVER delete user content - preserve everything
2. If both added content, include both (newer first)
3. If both edited same line differently, include both marked:
   <<<< Phone version
   [content]
   ====
   [content]
   >>>> Desktop version
4. For config files (.edn), merge keys, prefer newer values
5. Output confidence score (0.0-1.0) on first line

Format:
CONFIDENCE: 0.95
---
[merged content here]
`;
```

### 6.4 Email Flow (When AI Can't Resolve)

**Initial Email Template:**

```
┌─────────────────────────────────────────────────────────────┐
│  Subject: Action needed: Your vault needs attention          │
│  From: Logsidian Sync <sync@logsidian.com>                  │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Hi Johan,                                                   │
│                                                              │
│  Your vault "Work Notes" has a sync conflict we couldn't    │
│  automatically resolve. Don't worry - nothing is lost!      │
│                                                              │
│  📄 File: Meeting Notes.md                                  │
│                                                              │
│  What happened:                                              │
│  You edited this file on your phone AND your computer       │
│  before they could sync.                                     │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ PHONE VERSION (Tuesday 3pm):                        │    │
│  │ "- Call with Sarah at 2pm                          │    │
│  │  - Discuss Q4 budget"                              │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ COMPUTER VERSION (Tuesday 4pm):                     │    │
│  │ "- Call with Sarah at 2pm                          │    │
│  │  - She approved the proposal!"                     │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  What would you like to do?                                  │
│                                                              │
│  [ Keep Phone Version ]  [ Keep Computer Version ]          │
│                                                              │
│  [ Keep Both ] ← We'll combine them with clear labels       │
│                                                              │
│  [ Let me fix it myself ] ← Opens in Logsidian              │
│                                                              │
│  ─────────────────────────────────────────────────────────  │
│  💬 Need help? Just reply to this email and describe        │
│     what you'd like - our AI assistant will help!           │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 6.5 Reply-to-Email AI Resolution

```
┌─────────────────────────────────────────────────────────────┐
│  User replies:                                               │
│  "Keep both but the computer one is more recent so put      │
│   that first"                                                │
├─────────────────────────────────────────────────────────────┤
│                          │                                   │
│                          ▼                                   │
│  AI parses intent:                                          │
│  - Action: merge_both                                       │
│  - Order: theirs (computer) first, ours (phone) second      │
│                          │                                   │
│                          ▼                                   │
│  Generates merged file, pushes to repo                      │
│                          │                                   │
│                          ▼                                   │
│  Sends confirmation email:                                  │
│  "Done! I merged both versions with your computer edits     │
│   first. Your vault is syncing now."                        │
└─────────────────────────────────────────────────────────────┘
```

### 6.6 In-App Notification (Non-Blocking)

Users can keep working while conflicts are pending:

```
┌─────────────────────────────────────────────────────────────┐
│  Logsidian                                        [─][□][×] │
├─────────────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────────────────┐ │
│  │ ⚠️ 1 file needs attention • Check email or [Fix Now]  │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  Your normal vault content here...                          │
│  - Daily notes                                               │
│  - etc.                                                      │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 6.7 Conflict Prevention Strategies

| Strategy | Implementation |
|----------|----------------|
| **Frequent sync** | Sync every 30s when online |
| **Block-level sync** | Track changes per block, not whole file |
| **Edit locking** | Soft lock files being edited, short timeout |
| **Offline indicator** | Warn: "Offline - changes sync later" |
| **Pre-edit fetch** | Always pull before opening file for edit |
| **Last-write-wins** | For config files, use newest version |

---

## 7. Data Models

### 7.1 Database Schema

```sql
-- Users table
CREATE TABLE users (
  id            TEXT PRIMARY KEY,           -- UUID
  github_id     INTEGER UNIQUE NOT NULL,    -- GitHub user ID
  github_username TEXT NOT NULL,
  email         TEXT,
  plan          TEXT DEFAULT 'free',        -- 'free' | 'paid'
  stripe_customer_id TEXT,
  created_at    TIMESTAMP DEFAULT NOW(),
  updated_at    TIMESTAMP DEFAULT NOW()
);

-- Vaults table
CREATE TABLE vaults (
  id              TEXT PRIMARY KEY,         -- UUID
  user_id         TEXT REFERENCES users(id),
  github_repo     TEXT NOT NULL,            -- 'username/repo-name'
  installation_id INTEGER NOT NULL,         -- GitHub App installation
  name            TEXT NOT NULL,            -- Display name
  size_bytes      BIGINT DEFAULT 0,
  last_sync_at    TIMESTAMP,
  created_at      TIMESTAMP DEFAULT NOW(),
  UNIQUE(user_id, github_repo)
);

-- Conflicts table
CREATE TABLE conflicts (
  id                TEXT PRIMARY KEY,       -- UUID
  vault_id          TEXT REFERENCES vaults(id),
  user_id           TEXT REFERENCES users(id),
  file_path         TEXT NOT NULL,
  base_content      TEXT,                   -- Common ancestor
  local_content     TEXT NOT NULL,          -- "ours"
  remote_content    TEXT NOT NULL,          -- "theirs"
  local_device      TEXT,                   -- Device identifier
  remote_device     TEXT,
  status            TEXT DEFAULT 'pending', -- pending|ai_resolved|user_resolved|manual
  ai_confidence     REAL,                   -- 0.0 - 1.0
  resolution_content TEXT,
  resolved_by       TEXT,                   -- 'ai'|'user_email'|'user_app'
  created_at        TIMESTAMP DEFAULT NOW(),
  resolved_at       TIMESTAMP
);

-- Conflict emails table
CREATE TABLE conflict_emails (
  id              TEXT PRIMARY KEY,
  conflict_id     TEXT REFERENCES conflicts(id),
  email_type      TEXT NOT NULL,            -- 'initial'|'reminder'|'resolved'
  sent_at         TIMESTAMP DEFAULT NOW(),
  user_reply      TEXT,
  ai_interpretation TEXT
);

-- Subscriptions table (for Stripe)
CREATE TABLE subscriptions (
  id                    TEXT PRIMARY KEY,
  user_id               TEXT REFERENCES users(id),
  stripe_subscription_id TEXT UNIQUE,
  status                TEXT,               -- 'active'|'canceled'|'past_due'
  current_period_end    TIMESTAMP,
  created_at            TIMESTAMP DEFAULT NOW()
);
```

### 7.2 GitHub App Manifest

```yaml
name: Logsidian
description: Sync your Logsidian vaults to GitHub
url: https://logsidian.com
callback_url: https://logsidian.com/api/auth/callback
setup_url: https://logsidian.com/setup
webhook_url: https://logsidian.com/api/webhooks/github

# Permissions
default_permissions:
  contents: write          # Read/write repo files
  metadata: read           # Repo info (name, size, etc.)
  administration: write    # Create repos on user's behalf

# Events to receive
default_events:
  - push                   # Notified of pushes (for multi-device sync)
  - repository             # Repo created/deleted

# Installation settings
public: true
single_file_name: null     # Not single-file app
```

### 7.3 Vault Configuration File

**`.logsidian/config.json`** (stored in each vault repo):

```json
{
  "version": 1,
  "vault_id": "abc123",
  "created_at": "2025-12-09T10:00:00Z",
  "sync": {
    "interval_seconds": 30,
    "auto_commit": true,
    "commit_message_template": "Logsidian sync: {timestamp}"
  },
  "plugins": [
    { "id": "logseq-todo-plugin", "version": "1.2.3" },
    { "id": "logseq-markmap", "version": "2.0.0" }
  ],
  "ignore": [
    "logseq/.recycle/",
    "logseq/bak/",
    ".logseq/"
  ]
}
```

### 7.4 Default .gitignore

```gitignore
# Logsidian vault .gitignore

# OS files
.DS_Store
Thumbs.db

# Logseq internals (regenerated locally)
logseq/.recycle/
logseq/bak/
.logseq/

# Plugins (reinstalled from config, not synced)
logseq/plugins/

# Logsidian local state
.logsidian/local/

# Large binary files (optional - user configurable)
# Uncomment to exclude:
# *.pdf
# *.mp4
# *.zip
```

---

## 8. API Specifications

### 8.1 Authentication Endpoints

#### POST /api/auth/callback
GitHub OAuth callback handler.

**Query Parameters:**
- `code` - OAuth authorization code
- `state` - CSRF token

**Response:** Redirects to `/setup` or `/dashboard`

---

#### POST /api/auth/exchange
Exchange setup token for access credentials.

**Request:**
```json
{
  "setup_token": "eyJhbGc..."
}
```

**Response:**
```json
{
  "access_token": "ghs_xxxx",
  "expires_at": "2025-12-09T11:00:00Z",
  "user": {
    "id": "user_123",
    "github_username": "johanclawson",
    "plan": "free"
  },
  "vaults": [
    {
      "id": "vault_abc",
      "name": "my-notes",
      "repo": "johanclawson/logsidian-my-notes",
      "size_bytes": 52428800
    }
  ]
}
```

---

### 8.2 Vault Endpoints

#### POST /api/vaults/create
Create a new vault repository.

**Request:**
```json
{
  "name": "work-notes",
  "description": "My work notes vault",
  "private": true
}
```

**Response:**
```json
{
  "id": "vault_xyz",
  "name": "work-notes",
  "repo": "johanclawson/logsidian-work-notes",
  "clone_url": "https://github.com/johanclawson/logsidian-work-notes.git",
  "created_at": "2025-12-09T10:00:00Z"
}
```

---

#### GET /api/vaults
List user's vaults.

**Response:**
```json
{
  "vaults": [
    {
      "id": "vault_abc",
      "name": "my-notes",
      "repo": "johanclawson/logsidian-my-notes",
      "size_bytes": 52428800,
      "last_sync_at": "2025-12-09T09:30:00Z"
    }
  ],
  "storage": {
    "used_bytes": 52428800,
    "limit_bytes": 1073741824,
    "plan": "free"
  }
}
```

---

#### DELETE /api/vaults/:id
Delete a vault (removes from Logsidian, optionally deletes GitHub repo).

**Query Parameters:**
- `delete_repo` - boolean, whether to delete GitHub repo

**Response:**
```json
{
  "success": true
}
```

---

### 8.3 Sync Endpoints

#### POST /api/sync/token
Get fresh GitHub installation token for syncing.

**Request:**
```json
{
  "vault_id": "vault_abc"
}
```

**Response:**
```json
{
  "token": "ghs_xxxx",
  "expires_at": "2025-12-09T11:00:00Z"
}
```

---

#### POST /api/sync/pre-check
Check if push is allowed (storage limits).

**Request:**
```json
{
  "vault_id": "vault_abc",
  "new_size_bytes": 1200000000
}
```

**Response (OK):**
```json
{
  "allowed": true
}
```

**Response (Blocked):**
```json
{
  "allowed": false,
  "reason": "STORAGE_LIMIT_EXCEEDED",
  "current_bytes": 900000000,
  "limit_bytes": 1073741824,
  "upgrade_url": "https://logsidian.com/upgrade"
}
```

---

### 8.4 Conflict Endpoints

#### POST /api/conflicts/report
Report a sync conflict.

**Request:**
```json
{
  "vault_id": "vault_abc",
  "file_path": "pages/Meeting Notes.md",
  "base_content": "...",
  "local_content": "...",
  "remote_content": "...",
  "local_device": "Windows Desktop",
  "remote_device": "Android Phone"
}
```

**Response:**
```json
{
  "conflict_id": "conflict_123",
  "status": "processing",
  "estimated_resolution": "auto"
}
```

---

#### GET /api/conflicts/:id
Get conflict status.

**Response:**
```json
{
  "id": "conflict_123",
  "status": "pending",
  "file_path": "pages/Meeting Notes.md",
  "created_at": "2025-12-09T10:00:00Z",
  "options": {
    "keep_local_url": "https://logsidian.com/conflicts/123/resolve?choice=local",
    "keep_remote_url": "https://logsidian.com/conflicts/123/resolve?choice=remote",
    "keep_both_url": "https://logsidian.com/conflicts/123/resolve?choice=both"
  }
}
```

---

#### POST /api/conflicts/:id/resolve
Resolve a conflict.

**Request:**
```json
{
  "choice": "both",
  "custom_content": null
}
```

**Response:**
```json
{
  "success": true,
  "resolved_content": "...",
  "committed": true
}
```

---

### 8.5 Webhook Endpoints

#### POST /api/webhooks/github
Handle GitHub App webhooks.

**Events handled:**
- `installation` - App installed/uninstalled
- `push` - Repository pushed to
- `repository` - Repo created/deleted

---

#### POST /api/webhooks/email
Handle inbound email replies (from Postmark/Resend).

**Request:** Postmark inbound webhook format

**Processing:**
1. Extract conflict ID from email subject/headers
2. Parse user reply with AI
3. Apply resolution
4. Send confirmation email

---

## 9. Security Considerations

### 9.1 Token Security

| Token Type | Lifetime | Storage | Refresh |
|------------|----------|---------|---------|
| **Setup Token** | 1 hour | URL parameter | One-time use |
| **GitHub Installation Token** | 1 hour | Memory only | Via backend API |
| **User Session** | 7 days | HTTP-only cookie | Sliding window |
| **Stripe Customer ID** | Permanent | Database | N/A |

### 9.2 Data Privacy

- **Vault contents**: Stored only in user's GitHub repo
- **We never store**: File contents, note text, personal data
- **We store**: Metadata only (vault names, sizes, sync times)
- **Conflict resolution**: Content held temporarily, deleted after resolution
- **Email replies**: Processed by AI, not stored long-term

### 9.3 GitHub App Permissions

**Minimal required permissions:**
- `contents: write` - Required to read/write vault files
- `metadata: read` - Required to check repo size
- `administration: write` - Required to create repos

**NOT requested:**
- `actions` - No CI/CD access
- `issues/pull_requests` - No issue tracker access
- `workflows` - No workflow access

### 9.4 Encryption

- **In transit**: All API calls over HTTPS/TLS 1.3
- **At rest**: GitHub encrypts repos at rest
- **Tokens**: AES-256 encrypted in database
- **Email**: TLS required for email delivery

---

## 10. Pricing & Limits

### 10.1 Tier Comparison

| Feature | Free | Paid ($X/month) |
|---------|------|-----------------|
| **Vaults** | Unlimited | Unlimited |
| **Storage per vault** | 1 GB | Unlimited |
| **Devices** | Unlimited | Unlimited |
| **Sync frequency** | 30 seconds | 30 seconds |
| **Conflict resolution** | AI auto + email buttons | + Reply-to-email AI |
| **Support** | Community | Priority email |

### 10.2 Storage Limit Enforcement

**Enforcement Points:**

1. **Pre-push check** (client-side):
   ```javascript
   // Before syncing
   const size = await calculateVaultSize(dir);
   const allowed = await api.checkStorageLimit(vaultId, size);
   if (!allowed) {
     showUpgradePrompt();
     return;
   }
   ```

2. **Backend validation** (API):
   ```javascript
   // POST /api/sync/pre-check
   if (newSize > user.storageLimit && user.plan === 'free') {
     return { allowed: false, reason: 'STORAGE_LIMIT_EXCEEDED' };
   }
   ```

3. **Periodic audit** (cron job):
   ```javascript
   // Daily job
   for (const vault of await getOversizedVaults()) {
     await sendStorageWarningEmail(vault.user);
     await flagVaultReadOnly(vault.id);
   }
   ```

### 10.3 What Counts Toward Storage

| Included | Excluded |
|----------|----------|
| Markdown files | `.git/` directory |
| Assets (images, PDFs) | `logseq/plugins/` |
| Config files | `.logsidian/local/` |
| Custom CSS | |

---

## 11. Implementation Phases

### Phase 1: Foundation (Web + Backend)

**Goal:** User can sign up, create vault, get download link

**Tasks:**
- [ ] Register GitHub App (dev mode)
- [ ] Scaffold Next.js web app
- [ ] Implement Auth.js with GitHub provider
- [ ] Create vault management API
- [ ] Build download link generator
- [ ] Set up database (Turso/Supabase)

**Deliverables:**
- logsidian.com with GitHub login
- Vault creation flow
- Download page with platform options

---

### Phase 2: Desktop Integration

**Goal:** Desktop app syncs via isomorphic-git

**Tasks:**
- [ ] Create `src/main/frontend/fs/git.cljs`
- [ ] Implement token refresh flow
- [ ] Add first-launch setup UI
- [ ] Implement existing vault detection
- [ ] Build migration wizard
- [ ] Test sync loop

**Deliverables:**
- Windows/macOS builds with sync
- Seamless migration from Logseq

---

### Phase 3: Mobile Support

**Goal:** Android app syncs via isomorphic-git

**Tasks:**
- [ ] Create Capacitor filesystem adapter
- [ ] Remove mobile platform guards
- [ ] Build mobile-optimized first-launch UI
- [ ] Test on real Android devices
- [ ] Handle background sync
- [ ] Implement offline mode

**Deliverables:**
- Android APK with working sync
- iOS build (if applicable)

---

### Phase 4: Conflict Resolution

**Goal:** Conflicts resolved automatically or via email

**Tasks:**
- [ ] Build conflict detection logic
- [ ] Implement AI auto-resolution
- [ ] Create email templates (React Email)
- [ ] Set up email sending (Resend/Postmark)
- [ ] Implement inbound email handling
- [ ] Build reply parsing with AI

**Deliverables:**
- Automatic conflict resolution
- Email-based resolution flow
- In-app conflict indicator

---

### Phase 5: Payments & Polish

**Goal:** Paid tier with unlimited storage

**Tasks:**
- [ ] Integrate Stripe
- [ ] Implement storage limit enforcement
- [ ] Build upgrade flow
- [ ] Add usage dashboard
- [ ] Performance optimization
- [ ] Documentation

**Deliverables:**
- Working payments
- Storage limits enforced
- Production-ready system

---

## 12. Tech Stack

### 12.1 Web Application

| Component | Technology | Rationale |
|-----------|------------|-----------|
| **Framework** | Next.js 14 (App Router) | Fast, Auth.js integration, Edge support |
| **Authentication** | Auth.js v5 | Built-in GitHub provider |
| **Database** | Turso (SQLite edge) | Low latency, simple, cheap |
| **ORM** | Drizzle | Type-safe, lightweight |
| **Hosting** | Vercel | Easy deploys, edge functions |
| **Email (outbound)** | Resend | Great DX, React Email support |
| **Email (inbound)** | Postmark Inbound | Reliable webhook delivery |
| **Payments** | Stripe | Industry standard |

### 12.2 Desktop Application

| Component | Technology | Rationale |
|-----------|------------|-----------|
| **Framework** | Electron | Existing Logseq infrastructure |
| **Git** | isomorphic-git | Cross-platform, pure JS |
| **UI** | ClojureScript + Rum | Existing codebase |

### 12.3 Mobile Application

| Component | Technology | Rationale |
|-----------|------------|-----------|
| **Framework** | Capacitor | Existing Logseq infrastructure |
| **Git** | isomorphic-git | Works in mobile WebView |
| **Filesystem** | @capacitor/filesystem | Native file access |
| **Secure Storage** | @aparajita/capacitor-secure-storage | Token storage |

### 12.4 AI Integration

| Component | Technology | Rationale |
|-----------|------------|-----------|
| **Conflict Resolution** | Claude API (claude-3-haiku) | Fast, cheap, good at merging |
| **Email Parsing** | Claude API (claude-3-haiku) | Natural language understanding |
| **SDK** | @anthropic-ai/sdk | Official TypeScript SDK |

---

## Appendix A: File Structure

```
logsidian/
├── src/
│   ├── main/frontend/
│   │   ├── fs/
│   │   │   ├── git.cljs           # NEW: Cross-platform git
│   │   │   ├── capacitor_fs.cljs  # NEW: Capacitor adapter
│   │   │   ├── sync.cljs          # MODIFIED: Remove mobile guards
│   │   │   └── ...
│   │   ├── handler/
│   │   │   ├── file_sync.cljs     # MODIFIED: Enable mobile
│   │   │   └── ...
│   │   ├── components/
│   │   │   ├── sync_setup.cljs    # NEW: First-launch wizard
│   │   │   ├── migration.cljs     # NEW: Logseq migration
│   │   │   └── ...
│   │   └── ...
│   └── ...
├── docs/
│   └── feature/
│       └── mobile-git-sync.md     # This document
└── ...

logsidian-web/                      # Separate repo
├── app/
│   ├── page.tsx                   # Landing page
│   ├── login/page.tsx             # GitHub OAuth
│   ├── setup/page.tsx             # Vault creation
│   ├── download/page.tsx          # Download links
│   ├── dashboard/page.tsx         # User dashboard
│   └── api/
│       ├── auth/
│       │   ├── callback/route.ts
│       │   └── exchange/route.ts
│       ├── vaults/
│       │   └── route.ts
│       ├── sync/
│       │   ├── token/route.ts
│       │   └── pre-check/route.ts
│       ├── conflicts/
│       │   ├── report/route.ts
│       │   └── [id]/resolve/route.ts
│       └── webhooks/
│           ├── github/route.ts
│           └── email/route.ts
├── components/
├── lib/
│   ├── db.ts
│   ├── github.ts
│   ├── ai.ts
│   └── email.ts
└── ...
```

---

## Appendix B: Glossary

| Term | Definition |
|------|------------|
| **Vault** | A Logsidian knowledge base, synced to a GitHub repo |
| **Installation Token** | Short-lived GitHub token for a specific app installation |
| **isomorphic-git** | Pure JavaScript Git implementation |
| **Capacitor** | Cross-platform native runtime for web apps |
| **Conflict** | When same file edited on multiple devices before sync |
| **Setup Token** | One-time token embedded in download link |

---

## Appendix C: References

- [isomorphic-git Documentation](https://isomorphic-git.org/)
- [GitHub Apps Documentation](https://docs.github.com/en/apps)
- [Capacitor Filesystem](https://capacitorjs.com/docs/apis/filesystem)
- [Auth.js GitHub Provider](https://authjs.dev/reference/core/providers/github)
- [Logseq Architecture](https://github.com/logseq/logseq)

---

*Document maintained by the Logsidian team. Last updated: 2025-12-09*
