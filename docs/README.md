# Logsidian Documentation

> "Obsidian's speed with Logseq's blocks, files stay yours."

This folder contains all project documentation for Logsidian, a high-performance fork of Logseq.

## Quick Links

| I want to... | Go to... |
|--------------|----------|
| Build the project | [CLAUDE.md](../CLAUDE.md#build-commands) |
| Understand the architecture | [architecture/sidecar-overview.md](architecture/sidecar-overview.md) |
| See the project roadmap | [plans/master-plan.md](plans/master-plan.md) |
| Work on a task | [tasks/README.md](tasks/README.md) |
| See test results | [tests/performance-baseline.md](tests/performance-baseline.md) |
| Understand a decision | [decisions/](decisions/) |
| Read detailed research | [research/](research/) |

## Folder Structure

```
docs/
├── README.md              ← You are here
├── upstream/              # Original Logseq docs (do not modify)
├── architecture/          # System design documents
├── plans/                 # Project plans and roadmaps
├── tasks/                 # Active task documents (per worktree)
├── tests/                 # Test documentation and results
├── features/              # Feature specifications
├── research/              # Detailed research and findings
└── decisions/             # Architecture Decision Records (ADRs)
```

## Documentation by Folder

### `/upstream`
Original documentation from Logseq 0.10.15. These files are kept for reference and should NOT be modified to allow clean merges with upstream.

- [develop-logseq.md](upstream/develop-logseq.md) - General Logseq development guide
- [dev-practices.md](upstream/dev-practices.md) - Logseq coding practices

### `/architecture`
System design and architecture documentation for Logsidian-specific features.

- [sidecar-overview.md](architecture/sidecar-overview.md) - JVM sidecar architecture

### `/plans`
Project plans, roadmaps, and implementation strategies.

- [master-plan.md](plans/master-plan.md) - The comprehensive TDD master plan for sidecar development

### `/tasks`
Active task documents for parallel development across git worktrees.

- [README.md](tasks/README.md) - Task overview and dependency graph
- [hybrid-architecture.md](tasks/hybrid-architecture.md) - Phase 2.5: Worker + Sidecar
- [java-bundling.md](tasks/java-bundling.md) - Phase 5.5: JRE bundling
- [github-actions.md](tasks/github-actions.md) - CI/CD workflows
- [git-sync.md](tasks/git-sync.md) - Git synchronization feature

### `/tests`
Test documentation, strategies, and benchmark results.

- [performance-baseline.md](tests/performance-baseline.md) - Performance metrics before sidecar

### `/features`
Feature specifications and design documents for planned features.

- [mobile-git-sync.md](features/mobile-git-sync.md) - Mobile Git sync design

### `/research`
Detailed research findings, library comparisons, and technical investigations. Research docs support task implementation and inform decisions.

- [hybrid-architecture-search.md](research/hybrid-architecture-search.md) - Vector search compatibility analysis
- [edge-architecture-pivot.md](research/edge-architecture-pivot.md) - Edge computing pivot feasibility (~10-15 days, not massive!)
- [hosting-comparison.md](research/hosting-comparison.md) - Fly.io vs Cloudflare Workers comparison and recommendation
- [github-actions-sidecar-cicd.md](research/github-actions-sidecar-cicd.md) - **Comprehensive CI/CD research** for sidecar builds including job dependencies, JRE bundling with jlink, E2E test strategy, TDD implementation plan, cost analysis, and security considerations
- [github-actions-complete-workflow.yml](research/github-actions-complete-workflow.yml) - Target workflow YAML showing final integrated state

### `/decisions`
Architecture Decision Records (ADRs) documenting key technical decisions.

- [001-jvm-sidecar.md](decisions/001-jvm-sidecar.md) - Why we chose the JVM sidecar approach

## Contributing to Documentation

### Creating New Documents

1. Choose the right folder based on document type
2. Use lowercase with hyphens: `my-document.md`
3. Add a link to this README
4. For ADRs, use sequential numbering: `002-next-decision.md`

### Document Templates

**Task Document:**
```markdown
# Task Name

**Worktree:** `path/to/worktree`
**Branch:** `branch-name`
**Status:** Not Started | In Progress | Done
**Priority:** 1-4
**Estimate:** X hours/days
**Depends on:** Other tasks

## Goal
Brief description of what this task accomplishes.

## Implementation Steps
1. Step one
2. Step two

## Files to Modify
| File | Change |
|------|--------|
| `path/to/file` | Description |

## Success Criteria
- [ ] Criterion 1
- [ ] Criterion 2
```

**Research Document:**
```markdown
# Research: Topic Name

**Related Task:** [task-name.md](../tasks/task-name.md)
**Date:** YYYY-MM-DD
**Status:** In Progress | Complete

## Summary
Brief overview of findings (2-3 sentences).

## Questions to Answer
- Question 1?
- Question 2?

## Findings

### Option A: Name
- **Pros:** ...
- **Cons:** ...
- **Links:** ...

### Option B: Name
- **Pros:** ...
- **Cons:** ...

## Recommendation
Which option and why.

## References
- [Link 1](url)
- [Link 2](url)
```

**ADR (Architecture Decision Record):**
```markdown
# ADR-XXX: Title

**Status:** Proposed | Accepted | Deprecated | Superseded
**Date:** YYYY-MM-DD
**Deciders:** Names

## Context
What is the issue we're addressing?

## Decision
What did we decide to do?

## Alternatives Considered
What other options did we consider?

## Consequences
What are the positive and negative outcomes?
```

## See Also

- [CLAUDE.md](../CLAUDE.md) - Main project documentation (build commands, architecture, etc.)
- [GitHub Repository](https://github.com/johanclawson/logsidian)
