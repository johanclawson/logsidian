# Mobile Git Sync - Feature Specification

> **Status**: Planning
> **Last Updated**: 2025-12-18
> **Target Platforms**: Android, iOS, Windows, macOS, Linux

## Executive Summary

This document outlines the design for adding Git-based synchronization to Logsidian, with a focus on making it work on mobile platforms (Android/iOS) where native Git is unavailable. The solution uses **isomorphic-git** for cross-platform Git operations with user-provided credentials.

**Key Principles:**
- Works with any Git provider (GitHub, GitLab, Gitea, self-hosted)
- User owns and controls their credentials
- No backend infrastructure required
- Same library (isomorphic-git) across all platforms
- Simple setup: enter remote URL + token

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Current Architecture Analysis](#2-current-architecture-analysis)
3. [Proposed Solution](#3-proposed-solution)
4. [User Flows](#4-user-flows)
5. [Technical Architecture](#5-technical-architecture)
6. [Conflict Handling](#6-conflict-handling)
7. [Security Considerations](#7-security-considerations)
8. [Implementation Phases](#8-implementation-phases)
9. [Tech Stack](#9-tech-stack)

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
│              Logsidian App (All Platforms)                   │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                    Settings UI                        │    │
│  │  Remote URL: [https://github.com/user/vault.git  ]   │    │
│  │  Username:   [x-access-token_____________________ ]   │    │
│  │  Token:      [ghp_xxxxxxxxxxxxxxxxxxxx___________ ]   │    │
│  │              [Test Connection]  [Save]               │    │
│  └─────────────────────────────────────────────────────┘    │
│                            │                                 │
│                            ▼                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              isomorphic-git (pure JS)                │    │
│  │  ├── clone, fetch, pull, push, commit               │    │
│  │  ├── Works identically on all platforms              │    │
│  │  └── No native binaries required                     │    │
│  └─────────────────────────────────────────────────────┘    │
│                            │                                 │
│                            ▼                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │           Platform Filesystem Adapter                │    │
│  │  ├── Electron: Node.js fs                           │    │
│  │  ├── Android:  @capacitor/filesystem                │    │
│  │  └── iOS:      @capacitor/filesystem                │    │
│  └─────────────────────────────────────────────────────┘    │
│                            │                                 │
│                            ▼                                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              Secure Credential Storage               │    │
│  │  ├── Electron: safeStorage API                      │    │
│  │  ├── Android:  Android Keystore                     │    │
│  │  └── iOS:      iOS Keychain                         │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              Any Git Provider (User's Choice)                │
│  ├── GitHub (github.com)                                    │
│  ├── GitLab (gitlab.com or self-hosted)                     │
│  ├── Gitea (self-hosted)                                    │
│  ├── Bitbucket                                              │
│  └── Any Git server with HTTPS support                      │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Why User-Provided Credentials

| Aspect | User-Provided Token | Backend Service |
|--------|---------------------|-----------------|
| **Complexity** | Simple - no backend | Complex - servers, DB, auth |
| **Cost** | Free | Hosting + maintenance costs |
| **Privacy** | Credentials stay on device | Token passes through our servers |
| **Provider Support** | Any Git provider | Only supported providers |
| **Offline Setup** | Works offline after setup | Requires internet for auth |
| **User Control** | Full control over access | Delegated to our service |

### 3.3 Why Isomorphic-Git

- **Already in dependencies**: `@isomorphic-git/lightning-fs`
- **Pure JavaScript**: Works in browser, mobile, Electron
- **Capacitor compatible**: Can use any filesystem backend
- **Active maintenance**: Well-supported library
- **Full Git support**: Clone, fetch, push, merge, etc.

```javascript
// Example: Clone with user-provided credentials
import git from 'isomorphic-git';
import http from 'isomorphic-git/http/web';

await git.clone({
  fs,
  http,
  dir: '/vault',
  url: 'https://github.com/user/my-notes.git',
  onAuth: () => ({
    username: settings.git.username,  // e.g., 'x-access-token' for GitHub
    password: settings.git.token      // User's PAT
  })
});
```

### 3.4 Supported Git Providers

| Provider | Username | Token Type | Token Creation |
|----------|----------|------------|----------------|
| **GitHub** | `x-access-token` | Personal Access Token (classic or fine-grained) | Settings → Developer settings → Personal access tokens |
| **GitLab** | Your username or `oauth2` | Personal Access Token | Settings → Access Tokens |
| **Gitea** | Your username | Application Token | Settings → Applications |
| **Bitbucket** | Your username | App Password | Settings → App passwords |
| **Self-hosted** | Varies | Varies | Check your server docs |

**Required token permissions:**
- Read/write repository contents
- (Optional) Create repositories

---

## 4. User Flows

### 4.1 First-Time Setup Flow

```
┌─────────────────────────────────────────────────────────────┐
│                 Logsidian App - First Launch                 │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Welcome to Logsidian!                                       │
│                                                              │
│  Choose how to get started:                                  │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  📁 Open Local Folder                                   │ │
│  │     Start with a folder on this device (no sync)       │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  🔄 Clone from Git                                      │ │
│  │     Sync notes across devices with your Git account    │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  📥 Import Existing Logseq Graph                        │ │
│  │     Found 2 Logseq vaults on this device               │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 Git Setup Flow (Clone from Git)

```
User clicks "Clone from Git"
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│                    Git Repository Setup                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Repository URL:                                             │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ https://github.com/username/my-notes.git               │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  Authentication:                                             │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Provider: [GitHub ▼]                                   │ │
│  │                                                         │ │
│  │ Username: [x-access-token____________________________] │ │
│  │           (For GitHub, use "x-access-token")           │ │
│  │                                                         │ │
│  │ Token:    [ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxx__________] │ │
│  │           🔗 How to create a token                     │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  Local folder:                                               │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ ~/Documents/Logsidian/my-notes          [Browse...]    │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│         [Test Connection]              [Clone & Open]       │
│                                                              │
└─────────────────────────────────────────────────────────────┘
        │
        ▼ (User clicks "Clone & Open")
┌─────────────────────────────────────────────────────────────┐
│                      Cloning...                              │
│                                                              │
│  ████████████████████░░░░░░░░░░  65%                        │
│                                                              │
│  Receiving objects: 1,234 / 1,899                           │
│                                                              │
└─────────────────────────────────────────────────────────────┘
        │
        ▼
   Vault opens, sync enabled!
```

### 4.3 Settings UI for Git Sync

Users can configure/modify git settings from the app settings:

```
┌─────────────────────────────────────────────────────────────┐
│  Settings > Git Sync                                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─ Current Repository ─────────────────────────────────┐   │
│  │                                                       │   │
│  │  Remote: https://github.com/user/my-notes.git        │   │
│  │  Branch: main                                         │   │
│  │  Status: ✓ Connected                                  │   │
│  │                                                       │   │
│  │  Last sync: 2 minutes ago                            │   │
│  │  Local changes: 3 files modified                     │   │
│  │                                                       │   │
│  └───────────────────────────────────────────────────────┘   │
│                                                              │
│  Sync Settings:                                              │
│  ├─ Auto-sync interval: [30 seconds ▼]                      │
│  ├─ Sync on app open:   [✓]                                 │
│  ├─ Sync on file save:  [✓]                                 │
│  └─ Commit message:     [Auto-sync {timestamp}]             │
│                                                              │
│  Actions:                                                    │
│  [Sync Now]  [View History]  [Change Repository]            │
│                                                              │
│  ─────────────────────────────────────────────────────────  │
│                                                              │
│  Credentials:                                                │
│  ├─ Username: x-access-token                                │
│  └─ Token:    ghp_xxxx...xxxx (hidden)                      │
│                                                              │
│  [Update Credentials]  [Test Connection]                    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
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
                     Git Repository
               (Any provider - GitHub, GitLab, etc.)
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
- Configurable interval (default: 30 seconds) while active
- Manual sync button
- Before editing a file (fetch latest)
- After saving changes (commit + push)

### 4.5 Adding Git to Existing Local Vault

Users with a local-only vault can enable Git sync later:

```
┌─────────────────────────────────────────────────────────────┐
│  Settings > Git Sync                                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Git sync is not configured for this vault.                 │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  🔗 Connect to Existing Repository                      │ │
│  │     Link this vault to a Git repo you already have     │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  ✨ Create New Repository                               │ │
│  │     Initialize Git and push to a new remote repo       │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 5. Technical Architecture

### 5.1 System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      LOGSIDIAN APP (All Platforms)                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                         Git Settings UI                              │    │
│  │  ┌─────────────────────────────────────────────────────────────┐    │    │
│  │  │  Remote URL: https://github.com/user/notes.git              │    │    │
│  │  │  Username:   x-access-token                                  │    │    │
│  │  │  Token:      ********** (stored securely)                   │    │    │
│  │  └─────────────────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                      │                                       │
│                                      ▼                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                    Cross-Platform Git Layer                          │    │
│  │                                                                      │    │
│  │  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐        │    │
│  │  │ isomorphic-git │  │  FS Adapter    │  │ Secure Storage │        │    │
│  │  │  - clone       │  │  - Electron:   │  │  - Electron:   │        │    │
│  │  │  - fetch       │  │    Node fs     │  │    safeStorage │        │    │
│  │  │  - push        │  │  - Mobile:     │  │  - Mobile:     │        │    │
│  │  │  - commit      │  │    Capacitor   │  │    Keychain/   │        │    │
│  │  │  - merge       │  │    Filesystem  │  │    Keystore    │        │    │
│  │  └────────────────┘  └────────────────┘  └────────────────┘        │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                      │                                       │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐            │
│  │  Windows   │  │   macOS    │  │  Android   │  │    iOS     │            │
│  │  Electron  │  │  Electron  │  │  Capacitor │  │  Capacitor │            │
│  └────────────┘  └────────────┘  └────────────┘  └────────────┘            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       │ HTTPS (git clone/fetch/push)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                       User's Git Provider (Any)                              │
│                                                                              │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐            │
│  │   GitHub   │  │   GitLab   │  │   Gitea    │  │ Self-hosted│            │
│  └────────────┘  └────────────┘  └────────────┘  └────────────┘            │
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
   [frontend.config :as config]
   [frontend.state :as state]
   [frontend.handler.notification :as notification]
   [promesa.core :as p]))

(defn get-fs
  "Returns appropriate filesystem for current platform."
  []
  (if (mobile-util/native-platform?)
    (capacitor-fs/create-fs)
    (node-fs/create-fs)))

(defn get-git-config
  "Get git configuration for current graph."
  []
  (let [repo (state/get-current-repo)]
    (config/get-git-config repo)))

(defn get-auth
  "Returns auth callback using user-provided credentials."
  []
  (fn []
    (let [{:keys [username token]} (get-git-config)]
      (when (and username token)
        #js {:username username
             :password token}))))

(defn clone!
  "Clone a repository."
  [url dir {:keys [username token]}]
  (p/let [fs (get-fs)]
    (git/clone
     #js {:fs fs
          :http http
          :dir dir
          :url url
          :onAuth (fn [] #js {:username username :password token})
          :singleBranch true
          :depth 1})))

(defn pull!
  "Fetch and merge remote changes."
  [dir]
  (p/let [fs (get-fs)
          {:keys [author-name author-email]} (get-git-config)]
    (git/pull
     #js {:fs fs
          :http http
          :dir dir
          :onAuth (get-auth)
          :author #js {:name (or author-name "Logsidian")
                       :email (or author-email "user@logsidian.app")}})))

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
  (p/let [fs (get-fs)
          {:keys [author-name author-email]} (get-git-config)]
    (git/commit
     #js {:fs fs
          :dir dir
          :message message
          :author #js {:name (or author-name "Logsidian")
                       :email (or author-email "user@logsidian.app")}})))

(defn sync!
  "Full sync: pull, add, commit, push."
  [dir]
  (p/let [_ (pull! dir)
          status (git/statusMatrix #js {:fs (get-fs) :dir dir})
          has-changes? (some #(not= (aget % 1) (aget % 2)) status)]
    (when has-changes?
      (p/do!
       (add-all! dir)
       (commit! dir (str "Auto-sync " (.toISOString (js/Date.))))
       (push! dir)))))

(defn test-connection!
  "Test git connection with provided credentials. Returns promise."
  [url {:keys [username token]}]
  (p/let [result (git/getRemoteInfo
                  #js {:http http
                       :url url
                       :onAuth (fn [] #js {:username username :password token})})]
    {:success true
     :default-branch (.-HEAD result)}))
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

## 6. Conflict Handling

Since users manage their own Git credentials, conflict handling is simpler - we rely on Git's standard merge capabilities with user-friendly UI.

### 6.1 Conflict Detection

When `git pull` encounters conflicts, isomorphic-git will throw an error. We catch this and present options to the user.

### 6.2 Conflict Resolution UI

```
┌─────────────────────────────────────────────────────────────┐
│  Sync Conflict Detected                                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  The file "Meeting Notes.md" was changed on both devices.   │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  Keep Local Version                                    │ │
│  │  Keep the version from this device                     │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  Keep Remote Version                                   │ │
│  │  Keep the version from the server                      │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  Keep Both (Recommended)                               │ │
│  │  Create "Meeting Notes (conflict).md" with local copy  │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  View Differences                                      │ │
│  │  See what changed and manually resolve                 │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 6.3 Conflict Prevention

| Strategy | Implementation |
|----------|----------------|
| **Frequent sync** | Sync every 30s when online |
| **Sync on app open** | Always pull when app opens/foregrounds |
| **Offline indicator** | Warn: "Offline - changes sync later" |
| **Pre-edit fetch** | Pull before opening file for edit |

---

## 7. Security Considerations

### 7.1 Credential Storage

| Platform | Storage Method | Security |
|----------|----------------|----------|
| **Electron (Desktop)** | `safeStorage` API | OS-level encryption (DPAPI on Windows, Keychain on macOS) |
| **Android** | Android Keystore via `@aparajita/capacitor-secure-storage` | Hardware-backed encryption |
| **iOS** | iOS Keychain via `@aparajita/capacitor-secure-storage` | Secure Enclave |

### 7.2 Token Best Practices

**Recommend users create tokens with:**
- Minimum required permissions (repo contents only)
- Expiration dates (e.g., 90 days)
- Single-repository scope (if using fine-grained PATs on GitHub)

### 7.3 Data Privacy

- **Credentials never leave device** - stored locally in secure storage
- **No backend** - direct communication between app and Git provider
- **No analytics** - we don't track sync activity
- **User owns everything** - repository, credentials, data

---

## 8. Implementation Phases

### Phase 1: Core Git Layer

**Goal:** Cross-platform git operations working

**Tasks:**
- [ ] Create `src/main/frontend/fs/git.cljs` using isomorphic-git
- [ ] Create `src/main/frontend/fs/capacitor_fs.cljs` for mobile
- [ ] Create `src/main/frontend/fs/node_fs.cljs` wrapper for desktop
- [ ] Implement secure credential storage abstraction
- [ ] Add `test-connection!` function for credential validation

**Deliverables:**
- Working clone/pull/push/commit on all platforms

---

### Phase 2: Settings UI

**Goal:** Users can configure Git sync in settings

**Tasks:**
- [ ] Create Git settings component
- [ ] Add provider presets (GitHub, GitLab, etc.) with username hints
- [ ] Implement "Test Connection" button
- [ ] Add "How to create a token" help links per provider
- [ ] Store credentials in secure storage

**Deliverables:**
- Settings > Git Sync page with full configuration

---

### Phase 3: Clone Flow

**Goal:** Users can clone a repo on first launch

**Tasks:**
- [ ] Create first-launch setup wizard
- [ ] Implement clone with progress indicator
- [ ] Handle clone errors gracefully (bad URL, bad credentials, etc.)
- [ ] Remove mobile platform guards from sync code

**Deliverables:**
- "Clone from Git" flow working on all platforms

---

### Phase 4: Auto-Sync

**Goal:** Automatic background synchronization

**Tasks:**
- [ ] Implement configurable sync interval (default 30s)
- [ ] Add sync-on-app-open trigger
- [ ] Add sync-on-file-save trigger (optional)
- [ ] Show sync status indicator in UI
- [ ] Handle offline gracefully

**Deliverables:**
- Seamless background sync with status feedback

---

### Phase 5: Conflict Resolution

**Goal:** User-friendly conflict handling

**Tasks:**
- [ ] Detect merge conflicts during pull
- [ ] Create conflict resolution UI
- [ ] Implement "keep local/remote/both" options
- [ ] Add diff viewer for manual resolution

**Deliverables:**
- Complete conflict handling without data loss

---

## 9. Tech Stack

### 9.1 Cross-Platform Libraries

| Component | Library | Purpose |
|-----------|---------|---------|
| **Git operations** | isomorphic-git | Pure JS git implementation |
| **HTTP for Git** | isomorphic-git/http/web | HTTPS transport layer |
| **Desktop filesystem** | Node.js `fs` | File operations on Electron |
| **Mobile filesystem** | @capacitor/filesystem | File operations on iOS/Android |
| **Secure storage (mobile)** | @aparajita/capacitor-secure-storage | Credential storage |
| **Secure storage (desktop)** | Electron safeStorage | Credential storage |

### 9.2 Existing Infrastructure

| Component | Technology | Notes |
|-----------|------------|-------|
| **Desktop app** | Electron | No changes needed |
| **Mobile app** | Capacitor | Already configured |
| **UI framework** | ClojureScript + Rum | Existing codebase |
| **Build system** | Shadow-cljs | Existing setup |

---

## Appendix A: File Structure

```
logsidian/
├── src/
│   ├── main/frontend/
│   │   ├── fs/
│   │   │   ├── git.cljs           # NEW: Cross-platform git operations
│   │   │   ├── capacitor_fs.cljs  # NEW: Capacitor filesystem adapter
│   │   │   ├── node_fs.cljs       # NEW: Node.js filesystem adapter
│   │   │   ├── sync.cljs          # MODIFIED: Remove mobile guards
│   │   │   └── ...
│   │   ├── handler/
│   │   │   ├── git_sync.cljs      # NEW: Git sync handlers
│   │   │   ├── file_sync.cljs     # MODIFIED: Enable mobile
│   │   │   └── ...
│   │   ├── components/
│   │   │   ├── git_settings.cljs  # NEW: Git configuration UI
│   │   │   ├── git_setup.cljs     # NEW: First-launch clone wizard
│   │   │   ├── conflict_resolver.cljs # NEW: Conflict resolution UI
│   │   │   └── ...
│   │   └── ...
│   └── ...
├── docs/
│   └── feature/
│       └── mobile-git-sync.md     # This document
└── ...
```

---

## Appendix B: Glossary

| Term | Definition |
|------|------------|
| **PAT** | Personal Access Token - user-generated credential for Git authentication |
| **isomorphic-git** | Pure JavaScript Git implementation that works in any JS environment |
| **Capacitor** | Cross-platform native runtime for web apps (iOS/Android) |
| **safeStorage** | Electron API for OS-level encrypted storage |
| **Keychain/Keystore** | Platform-specific secure credential storage (iOS/Android) |

---

## Appendix C: References

- [isomorphic-git Documentation](https://isomorphic-git.org/)
- [isomorphic-git API Reference](https://isomorphic-git.org/docs/en/alphabetic)
- [Capacitor Filesystem](https://capacitorjs.com/docs/apis/filesystem)
- [Capacitor Secure Storage Plugin](https://github.com/nicfoster/capacitor-secure-storage)
- [Electron safeStorage](https://www.electronjs.org/docs/latest/api/safe-storage)
- [GitHub Personal Access Tokens](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token)
- [GitLab Personal Access Tokens](https://docs.gitlab.com/ee/user/profile/personal_access_tokens.html)

---

*Document maintained by the Logsidian team. Last updated: 2025-12-18*
