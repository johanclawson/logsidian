# Logsidian Parallel Development Tasks

This folder contains task documentation for parallel development across git worktrees.

## Worktree Overview

| Worktree | Branch | Task Document | Status |
|----------|--------|---------------|--------|
| Main (`X:\source\repos\logsidian`) | `feature-sidecar` | [hybrid-architecture.md](./hybrid-architecture.md) | In Progress |
| `logsidian-bundling` | `feature-sidecar-bundling` | [java-bundling.md](./java-bundling.md) | Not Started |
| `logsidian-cicd` | `feature-sidecar-cicd` | [github-actions.md](./github-actions.md) | **In Progress** (Research Complete) |
| `logsidian-git-sync` | `feature-sidecar-git-sync` | [git-sync.md](./git-sync.md) | Not Started |

### Research Documents

| Task | Research Document | Description |
|------|-------------------|-------------|
| GitHub Actions | [github-actions-sidecar-cicd.md](../research/github-actions-sidecar-cicd.md) | Comprehensive CI/CD analysis with TDD plan |

## Dependency Graph

```
Phase 2.5 (Hybrid Architecture) ←── CRITICAL PATH
    │
    ├──► Phase 3 (Enable skipped E2E tests)
    │
    ▼
Phase 5 (Performance Benchmarks)
    │
    ▼
Phase 5.5 (JRE Bundling) + CI/CD
    │
    ▼
Phase 6 (Release)
```

## Quick Start

1. **Fork into a worktree:**
   ```
   /fork C:\Users\johan\repos\_worktrees\logsidian-bundling
   /fork C:\Users\johan\repos\_worktrees\logsidian-cicd
   /fork C:\Users\johan\repos\_worktrees\logsidian-git-sync
   ```

2. **Read the task document** for that worktree

3. **Work independently** - each worktree has its own branch

4. **Merge back** to `feature-sidecar` when complete

## Task Priority

| Priority | Task | Worktree | Estimate | Blocks |
|----------|------|----------|----------|--------|
| 1 | Hybrid Architecture | Main | 2-3 days | Everything |
| 2 | GitHub Actions | cicd | 2-3 hours | Bundling |
| 3 | Java Bundling | bundling | 3-4 hours | Release |
| 4 | Git Sync | git-sync | 3-5 days | Nothing |

## Master Plan

See [master-plan.md](../plans/master-plan.md) for the complete project plan.

## See Also

- [Research Documents](../research/) - Detailed research supporting each task
- [Architecture Overview](../architecture/sidecar-overview.md) - System design
- [CLAUDE.md](../../CLAUDE.md) - Build commands and project context
