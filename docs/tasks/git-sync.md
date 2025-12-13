# Git Sync Module (System Git)

**Worktree:** `C:\Users\johan\repos\_worktrees\logsidian-git-sync`
**Branch:** `feature-sidecar-git-sync`
**Status:** Not Started
**Priority:** 4
**Estimate:** 3-5 days
**Depends on:** Nothing (can start now, independent feature)

## Goal

Allow users to sync their Logseq graphs via Git, using the system-installed Git (not JGit). This leverages the user's existing SSH keys and credentials.

## Why System Git (Not JGit)?

| Aspect | System Git | JGit |
|--------|-----------|------|
| SSH keys | Uses existing `~/.ssh/` | Needs separate config |
| Credentials | Uses Git credential manager | Needs separate config |
| Performance | Native, fast | JVM overhead |
| Features | Full Git features | Subset of Git |
| Maintenance | User updates Git | We bundle JGit |

**Decision:** Use system Git via child process calls.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Electron Main Process                     │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                   git-sync.cljs                        │  │
│  │  - spawn git commands                                  │  │
│  │  - watch for conflicts                                 │  │
│  │  - handle merge/rebase                                 │  │
│  └───────────────────────────────────────────────────────┘  │
│                            │                                 │
│                   child_process.spawn                        │
│                            │                                 │
│                            ▼                                 │
│  ┌───────────────────────────────────────────────────────┐  │
│  │              System Git (user installed)               │  │
│  │  - Uses ~/.ssh/ keys                                   │  │
│  │  - Uses Git credential manager                         │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Implementation Steps

### Step 1: Create Git Sync Module

**File:** `src/electron/electron/git_sync.cljs`

```clojure
(ns electron.git-sync
  (:require [clojure.string :as str]
            ["child_process" :as cp]
            ["path" :as path]
            [promesa.core :as p]))

(defn- run-git
  "Run a git command in the graph directory"
  [graph-path & args]
  (p/create
    (fn [resolve reject]
      (let [proc (cp/spawn "git" (clj->js args)
                          #js {:cwd graph-path
                               :encoding "utf8"})]
        (let [stdout (atom "")
              stderr (atom "")]
          (.on (.-stdout proc) "data" #(swap! stdout str %))
          (.on (.-stderr proc) "data" #(swap! stderr str %))
          (.on proc "close"
               (fn [code]
                 (if (zero? code)
                   (resolve @stdout)
                   (reject (ex-info "Git command failed"
                                   {:code code
                                    :stderr @stderr
                                    :args args}))))))))))

(defn git-status
  "Get git status for a graph"
  [graph-path]
  (run-git graph-path "status" "--porcelain"))

(defn git-pull
  "Pull changes from remote"
  [graph-path]
  (run-git graph-path "pull" "--rebase"))

(defn git-push
  "Push changes to remote"
  [graph-path]
  (run-git graph-path "push"))

(defn git-add-all
  "Stage all changes"
  [graph-path]
  (run-git graph-path "add" "-A"))

(defn git-commit
  "Create a commit"
  [graph-path message]
  (run-git graph-path "commit" "-m" message))

(defn sync-graph!
  "Full sync: pull, commit local changes, push"
  [graph-path]
  (p/let [;; Pull remote changes first
          _ (git-pull graph-path)
          ;; Check for local changes
          status (git-status graph-path)]
    (when (seq (str/trim status))
      ;; Stage and commit local changes
      (p/let [_ (git-add-all graph-path)
              _ (git-commit graph-path (str "Sync: " (js/Date.)))]
        ;; Push to remote
        (git-push graph-path)))))
```

### Step 2: Add Conflict Detection

```clojure
(defn has-conflicts?
  "Check if there are merge conflicts"
  [graph-path]
  (p/let [status (git-status graph-path)]
    (boolean (re-find #"^(?:U.|.U|DD|AA)" status))))

(defn get-conflicted-files
  "Get list of files with conflicts"
  [graph-path]
  (p/let [status (git-status graph-path)]
    (->> (str/split-lines status)
         (filter #(re-find #"^(?:U.|.U|DD|AA)" %))
         (map #(subs % 3)))))
```

### Step 3: Add Auto-Sync on File Change

```clojure
(defn setup-auto-sync!
  "Set up automatic sync when files change"
  [graph-path interval-ms]
  (let [sync-pending (atom false)
        timer-id (atom nil)]
    ;; Debounced sync
    (fn trigger-sync []
      (when-not @sync-pending
        (reset! sync-pending true)
        (when @timer-id
          (js/clearTimeout @timer-id))
        (reset! timer-id
          (js/setTimeout
            (fn []
              (-> (sync-graph! graph-path)
                  (p/catch #(logger/error "Auto-sync failed:" %))
                  (p/finally #(reset! sync-pending false))))
            interval-ms))))))
```

### Step 4: Add IPC Handlers

**File:** `src/electron/electron/core.cljs`

```clojure
(defn setup-git-sync-handlers! []
  (ipc/handle "git-sync:status"
    (fn [_ graph-path]
      (git-sync/git-status graph-path)))

  (ipc/handle "git-sync:sync"
    (fn [_ graph-path]
      (git-sync/sync-graph! graph-path)))

  (ipc/handle "git-sync:has-conflicts"
    (fn [_ graph-path]
      (git-sync/has-conflicts? graph-path))))
```

### Step 5: Add UI Components

**File:** `src/main/frontend/components/git_sync.cljs`

```clojure
(ns frontend.components.git-sync
  (:require [rum.core :as rum]
            [frontend.ui :as ui]))

(rum/defc sync-button < rum/reactive
  [graph-path]
  (let [syncing? (rum/react *syncing?*)
        has-changes? (rum/react *has-changes?*)]
    [:button.git-sync-btn
     {:on-click #(trigger-sync! graph-path)
      :disabled syncing?
      :class (when has-changes? "has-changes")}
     (if syncing?
       [:span.syncing "Syncing..."]
       [:span.sync-icon "⟳"])]))
```

## Files to Create

| File | Purpose |
|------|---------|
| `src/electron/electron/git_sync.cljs` | Core Git operations |
| `src/main/frontend/components/git_sync.cljs` | UI components |
| `src/main/frontend/handler/git_sync.cljs` | Frontend handlers |
| `src/test/electron/git_sync_test.cljs` | Unit tests |

## Configuration

Allow users to configure Git sync in settings:

```clojure
{:git-sync/enabled true
 :git-sync/auto-sync true
 :git-sync/interval-ms 30000  ; 30 seconds
 :git-sync/commit-message "Auto-sync from Logsidian"}
```

## Error Handling

| Error | User Action |
|-------|-------------|
| Git not installed | Show "Install Git" prompt |
| No remote configured | Show "Configure remote" dialog |
| Merge conflict | Show conflict resolution UI |
| Auth failure | Prompt to check SSH keys |
| Network error | Retry with backoff |

## Success Criteria

- [ ] Can detect if graph is a git repo
- [ ] Can pull/push changes
- [ ] Detects and shows conflicts
- [ ] Auto-sync on file changes (optional)
- [ ] Works with SSH keys (no extra config)
- [ ] Works with Git credential manager
- [ ] UI shows sync status

## Testing

```bash
# Create test repo
mkdir test-graph && cd test-graph
git init
git remote add origin git@github.com:user/test-graph.git

# Test sync operations
1. Make changes in Logsidian
2. Click sync button
3. Verify changes pushed to remote
4. Make changes on another device
5. Click sync, verify changes pulled
```

## Future Enhancements

- [ ] Branch switching UI
- [ ] Commit history viewer
- [ ] Selective file sync
- [ ] Conflict resolution UI (3-way merge)
- [ ] Multiple remotes support
