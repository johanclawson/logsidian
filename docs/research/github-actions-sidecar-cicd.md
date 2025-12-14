# Research: GitHub Actions CI/CD for Sidecar Builds

**Related Task:** [github-actions.md](../tasks/github-actions.md)
**Date:** 2025-12-13
**Status:** In Progress
**Branch:** `feature-sidecar-cicd`
**Worktree:** `C:\Users\johan\repos\_worktrees\logsidian-cicd`

## Executive Summary

This document provides comprehensive research and a TDD-based implementation plan for extending Logsidian's CI/CD pipeline to support the JVM sidecar. The goal is to automate building, testing, and releasing the sidecar alongside the Electron app.

**Key Deliverables:**
1. Build sidecar uberjar in CI
2. Create minimal JREs for x64 and ARM64 using jlink
3. Run E2E tests with sidecar in CI
4. Bundle sidecar with Electron releases
5. Maintain TDD workflow with proper test-first approach

---

## Table of Contents

1. [Current State Analysis](#1-current-state-analysis)
2. [Target Architecture](#2-target-architecture)
3. [Job Dependency Graph](#3-job-dependency-graph)
4. [JRE Bundling Strategy](#4-jre-bundling-strategy)
5. [E2E Test Strategy](#5-e2e-test-strategy)
6. [TDD Implementation Plan](#6-tdd-implementation-plan)
7. [GitHub Actions Best Practices](#7-github-actions-best-practices)
8. [Security Considerations](#8-security-considerations)
9. [Cost and Performance Analysis](#9-cost-and-performance-analysis)
10. [Risk Assessment](#10-risk-assessment)
11. [Implementation Checklist](#11-implementation-checklist)

---

## 1. Current State Analysis

### 1.1 Existing Workflows

| Workflow | File | Purpose | Triggers |
|----------|------|---------|----------|
| Build Windows | `build-windows.yml` | Build x64 + ARM64 Electron apps | `version.cljs` changes, manual |
| Sync Upstream | `sync-upstream.yml` | Merge 0.10.x Logseq releases | Weekly (Mon 6:00 UTC), manual |
| Clojure E2E | `clj-e2e.yml` | Run browser E2E tests | Push/PR to master |

### 1.2 Current Build Windows Workflow Structure

```yaml
jobs:
  build-rsapi-arm64:      # Windows 11 ARM runner - builds native Rust module
    ↓
  compile-cljs:           # Ubuntu - builds ClojureScript
    ↓
  build-windows-x64:      # Windows - packages Electron x64
    ↓
  build-windows-arm64:    # Windows - packages Electron ARM64 (needs rsapi)
    ↓
  test-release:           # Creates test release (manual, test_release=true)
  release:                # Creates production release (on master push)
```

### 1.3 Current Build Times (Estimated)

| Job | Runner | Estimated Time |
|-----|--------|----------------|
| build-rsapi-arm64 | windows-11-arm | 8-12 min |
| compile-cljs | ubuntu-22.04 | 5-8 min |
| build-windows-x64 | windows-latest | 10-15 min |
| build-windows-arm64 | windows-latest | 12-18 min |
| release | ubuntu-22.04 | 2-3 min |
| **Total (parallel)** | - | **~20-25 min** |

### 1.4 What's Missing for Sidecar

| Component | Status | Required Action |
|-----------|--------|-----------------|
| Sidecar JAR build | ❌ Missing | Add `build-sidecar-jar` job |
| Minimal JRE (x64) | ❌ Missing | Add `build-jre-x64` job with jlink |
| Minimal JRE (ARM64) | ❌ Missing | Add `build-jre-arm64` job with jlink |
| E2E tests with sidecar | ❌ Missing | Add `e2e-tests` job |
| Sidecar bundling | ❌ Missing | Update packaging jobs to include sidecar |
| AppCDS generation | ❌ Missing | Add startup optimization |

---

## 2. Target Architecture

### 2.1 Extended Job Graph

```
                    ┌───────────────────────┐
                    │   build-sidecar-jar   │ (Ubuntu)
                    │   ~2-3 min            │
                    └───────────┬───────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        │                       │                       │
        ▼                       ▼                       ▼
┌───────────────┐     ┌───────────────┐       ┌───────────────┐
│ build-jre-x64 │     │build-jre-arm64│       │ test-sidecar  │
│ (Windows)     │     │(Win ARM64)    │       │ (Ubuntu)      │
│ ~3-5 min      │     │ ~3-5 min      │       │ ~2-3 min      │
└───────┬───────┘     └───────┬───────┘       └───────────────┘
        │                     │
        ▼                     │
┌───────────────────────┐     │      ┌───────────────────┐
│   compile-cljs        │     │      │ build-rsapi-arm64 │
│   (Ubuntu) ~5-8 min   │     │      │ (Win ARM64)       │
└───────────┬───────────┘     │      └─────────┬─────────┘
            │                 │                │
    ┌───────┴───────┐         │        ┌───────┘
    ▼               ▼         ▼        ▼
┌────────────┐  ┌────────────────────────┐
│build-win-  │  │  build-windows-arm64   │
│x64         │  │                        │
│+ sidecar   │  │  + sidecar + JRE-arm64 │
└─────┬──────┘  └──────────┬─────────────┘
      │                    │
      └────────┬───────────┘
               ▼
        ┌────────────┐
        │  e2e-tests │ (Windows)
        │  ~5-10 min │
        └─────┬──────┘
              ▼
        ┌────────────┐
        │  release   │
        └────────────┘
```

### 2.2 Artifact Flow

```
build-sidecar-jar ──► logsidian-sidecar.jar (25-30 MB)
                              │
                              ├──► build-jre-x64 ──► jre-x64/ (50-80 MB)
                              │
                              ├──► build-jre-arm64 ──► jre-arm64/ (50-80 MB)
                              │
                              └──► test-sidecar (unit tests)

compile-cljs ──► cljs-static/ (static assets)

build-windows-x64
  ├── Downloads: cljs-static/, logsidian-sidecar.jar, jre-x64/
  └── Produces: logsidian-win-x64/ (installer + zip)

build-windows-arm64
  ├── Downloads: cljs-static/, logsidian-sidecar.jar, jre-arm64/, rsapi-arm64
  └── Produces: logsidian-win-arm64/ (installer + zip)
```

---

## 3. Job Dependency Graph

### 3.1 Dependency Matrix

| Job | Depends On | Produces | Runner |
|-----|------------|----------|--------|
| build-sidecar-jar | - | `sidecar-jar` artifact | ubuntu-22.04 |
| test-sidecar | build-sidecar-jar | test results | ubuntu-22.04 |
| build-jre-x64 | - | `jre-x64` artifact | windows-latest |
| build-jre-arm64 | - | `jre-arm64` artifact | windows-11-arm |
| build-rsapi-arm64 | - | `rsapi-win32-arm64` artifact | windows-11-arm |
| compile-cljs | - | `cljs-static` artifact | ubuntu-22.04 |
| build-windows-x64 | compile-cljs, build-sidecar-jar, build-jre-x64 | `logsidian-win-x64` | windows-latest |
| build-windows-arm64 | compile-cljs, build-sidecar-jar, build-jre-arm64, build-rsapi-arm64 | `logsidian-win-arm64` | windows-latest |
| e2e-tests | build-windows-x64 | test report | windows-latest |
| release | e2e-tests, build-windows-arm64 | GitHub release | ubuntu-22.04 |

### 3.2 Parallelization Opportunities

Jobs that can run in parallel (no dependencies between them):
- `build-sidecar-jar` ║ `build-jre-x64` ║ `build-jre-arm64` ║ `build-rsapi-arm64` ║ `compile-cljs`

This means the critical path is:
```
build-sidecar-jar (3 min) → build-jre-x64 (5 min) → build-windows-x64 (15 min) → e2e-tests (10 min) → release (3 min)
= ~36 min total (vs 25 min currently)
```

**Optimization:** JRE builds can start immediately (no dependency on sidecar JAR).

### 3.3 Conditional Execution

The workflow should support:
- `skip_x64`: Skip x64 build
- `skip_arm64`: Skip ARM64 build
- `skip_sidecar`: Skip sidecar build (for debugging Electron-only issues)
- `test_release`: Create test release instead of production

---

## 4. JRE Bundling Strategy

### 4.1 Why Minimal JRE?

| Approach | Size | Pros | Cons |
|----------|------|------|------|
| Full JDK 21 | ~300 MB | Easy, all features | Huge download |
| Full JRE (N/A) | - | Oracle discontinued JRE-only | - |
| jlink minimal JRE | 50-80 MB | Small, exactly what we need | Requires analysis |
| GraalVM native | 30-50 MB | Smallest, fast startup | Complex, may not work |

**Decision:** Use **jlink** to create minimal custom JRE.

### 4.2 Required Java Modules

Analysis of sidecar dependencies:

```
sidecar/deps.edn dependencies:
├── datascript (pure Clojure, no native deps)
├── transit-clj (pure Clojure)
├── datascript-transit (pure Clojure)
├── core.async (pure Clojure)
├── next.jdbc (requires java.sql)
├── sqlite-jdbc (requires java.sql, java.naming)
├── tools.logging (requires java.logging)
├── logback-classic (requires java.management, java.naming)
└── http-kit (requires java.base)
```

**Minimal modules required:**
```bash
java.base          # Always required
java.logging       # For tools.logging
java.sql           # For next.jdbc, sqlite-jdbc
java.naming        # For sqlite-jdbc, logback
java.management    # For logback JMX support
```

### 4.3 jlink Command

```bash
jlink \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.base,java.logging,java.sql,java.naming,java.management \
  --output jre-minimal \
  --strip-debug \
  --compress=2 \
  --no-header-files \
  --no-man-pages
```

**Expected output size:** 50-80 MB (depending on platform)

### 4.4 Testing the Minimal JRE

```bash
# Test that sidecar runs with minimal JRE
./jre-minimal/bin/java -jar logsidian-sidecar.jar &
sleep 5
curl http://localhost:47633/health  # Should return 200
```

### 4.5 Cross-Platform JRE Builds

| Platform | Runner | JDK Distribution | Notes |
|----------|--------|------------------|-------|
| Windows x64 | windows-latest | Microsoft OpenJDK 21 | Native jlink |
| Windows ARM64 | windows-11-arm | Microsoft OpenJDK 21 | Native ARM64 jlink |
| macOS x64 | macos-latest | Temurin 21 | Future |
| macOS ARM64 | macos-latest | Temurin 21 | Future |
| Linux x64 | ubuntu-22.04 | Temurin 21 | Future |

**Note:** Microsoft OpenJDK has excellent ARM64 support on Windows.

### 4.6 AppCDS (Application Class Data Sharing)

For faster startup (reduces JVM startup from ~2s to ~200ms):

```bash
# Step 1: Generate class list during build
java -XX:DumpLoadedClassList=classes.lst -jar sidecar.jar --dry-run

# Step 2: Create shared archive
java -Xshare:dump -XX:SharedClassListFile=classes.lst -XX:SharedArchiveFile=app-cds.jsa -jar sidecar.jar

# Step 3: Use at runtime
java -Xshare:on -XX:SharedArchiveFile=app-cds.jsa -jar sidecar.jar
```

**Implementation:** This should be part of the packaging step, not a separate job.

---

## 5. E2E Test Strategy

### 5.1 Current E2E Infrastructure

| Component | Technology | Location |
|-----------|------------|----------|
| Browser E2E | Wally (Clojure Playwright) | `clj-e2e/` |
| Electron E2E | Playwright Node.js | `e2e-electron/` |
| Test reports | HTML + screenshots | `clj-e2e/error-reports/` |

### 5.2 Why Playwright Node.js for Electron?

**Critical fact:** Playwright Java does NOT support Electron ([issue #830](https://github.com/microsoft/playwright-java/issues/830)).

Therefore, Electron E2E tests MUST use Playwright's Node.js/TypeScript API:
- `e2e-electron/tests/sidecar-smoke.spec.ts`
- Uses `@playwright/test` package
- Launches actual Electron app via `_electron.launch()`

### 5.3 Test Matrix

| Test Suite | Requires | Runner | Est. Time |
|------------|----------|--------|-----------|
| Sidecar unit tests | Java 21 | ubuntu-22.04 | 2-3 min |
| CLJS sidecar tests | Node, Clojure | ubuntu-22.04 | 3-5 min |
| Electron E2E smoke | Built app, sidecar | windows-latest | 5-10 min |
| Browser E2E (Wally) | Built app | ubuntu-22.04 | 15-30 min |

### 5.4 CI E2E Test Flow

```
┌──────────────────────────────────────────────────────────────────┐
│  E2E Test Job (Windows runner)                                    │
│                                                                   │
│  1. Download built Electron app artifact                          │
│  2. Download sidecar JAR artifact                                 │
│  3. Download JRE x64 artifact                                     │
│  4. Install Playwright browsers                                   │
│  5. Start sidecar server in background                            │
│  6. Run Playwright tests against Electron app                     │
│  7. Upload screenshots and reports on failure                     │
└──────────────────────────────────────────────────────────────────┘
```

### 5.5 Test Stability Considerations

| Issue | Mitigation |
|-------|------------|
| Flaky startup timing | Add proper wait conditions, retry logic |
| Single-instance lock | Kill existing Logseq processes before test |
| Sidecar port conflicts | Use unique ports or check before start |
| Screenshot timing | Wait for UI elements before capturing |

### 5.6 Failure Reporting

On test failure:
1. Capture screenshot
2. Capture console logs
3. Capture sidecar logs
4. Upload all as artifacts
5. Include in test report HTML

---

## 6. TDD Implementation Plan

### 6.1 TDD Principles for CI/CD

1. **Write the test first** - Define expected workflow behavior before implementation
2. **Minimal implementation** - Add only what's needed to pass
3. **Refactor** - Improve workflow structure while keeping tests green
4. **Red-Green-Refactor** - Apply the cycle to each new feature

### 6.2 Phase 1: Sidecar JAR Build (RED-GREEN-REFACTOR)

#### RED: Define the expected behavior

**Test file:** `.github/workflows/test-sidecar-build.yml` (temporary test workflow)

```yaml
name: Test Sidecar Build

on: workflow_dispatch

jobs:
  test-sidecar-jar:
    runs-on: ubuntu-22.04
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      - uses: DeLaGuardo/setup-clojure@12.5
        with:
          cli: 1.11.1.1435

      - name: Build sidecar uberjar
        run: cd sidecar && clojure -T:build uberjar

      - name: Verify JAR exists and is valid
        run: |
          test -f sidecar/target/logsidian-sidecar.jar
          java -jar sidecar/target/logsidian-sidecar.jar --version || true

      - name: Verify JAR size (should be 25-35 MB)
        run: |
          SIZE=$(stat -c %s sidecar/target/logsidian-sidecar.jar)
          echo "JAR size: $((SIZE / 1024 / 1024)) MB"
          test $SIZE -gt 20000000  # At least 20 MB
          test $SIZE -lt 50000000  # Less than 50 MB
```

**Expected outcome:** This test should PASS when run manually.

#### GREEN: Implement the job

Add to `build-windows.yml`:

```yaml
build-sidecar-jar:
  runs-on: ubuntu-22.04
  steps:
    - uses: actions/checkout@v4

    - uses: actions/setup-java@v4
      with:
        distribution: 'temurin'
        java-version: '21'

    - uses: DeLaGuardo/setup-clojure@12.5
      with:
        cli: 1.11.1.1435

    - name: Cache Clojure dependencies
      uses: actions/cache@v4
      with:
        path: |
          ~/.m2/repository
          ~/.gitlibs
        key: ${{ runner.os }}-clojure-sidecar-${{ hashFiles('sidecar/deps.edn') }}

    - name: Build sidecar uberjar
      run: cd sidecar && clojure -T:build uberjar

    - uses: actions/upload-artifact@v4
      with:
        name: sidecar-jar
        path: sidecar/target/logsidian-sidecar.jar
        if-no-files-found: error
```

#### REFACTOR: Add caching, optimize

After green, consider:
- Clojure dependency caching
- Parallel compilation flags
- Build info embedding (version, git SHA)

---

### 6.3 Phase 2: JRE Build Jobs (RED-GREEN-REFACTOR)

#### RED: Define expected behavior

**Test criteria:**
1. JRE directory exists after jlink
2. Contains `bin/java` or `bin/java.exe`
3. Size is between 50-100 MB
4. Can execute `java -version`
5. Can run sidecar JAR

#### GREEN: Implement jlink job

```yaml
build-jre-x64:
  runs-on: windows-latest
  steps:
    - uses: actions/setup-java@v4
      with:
        distribution: 'microsoft'
        java-version: '21'

    - name: Create minimal JRE with jlink
      shell: pwsh
      run: |
        $modules = "java.base,java.logging,java.sql,java.naming,java.management"
        jlink --module-path "$env:JAVA_HOME/jmods" `
              --add-modules $modules `
              --output jre-x64 `
              --strip-debug `
              --compress=2 `
              --no-header-files `
              --no-man-pages

    - name: Verify JRE
      shell: pwsh
      run: |
        ./jre-x64/bin/java -version
        $size = (Get-ChildItem -Recurse jre-x64 | Measure-Object -Sum Length).Sum
        Write-Host "JRE size: $([math]::Round($size/1MB, 2)) MB"
        if ($size -lt 40MB -or $size -gt 120MB) {
          throw "JRE size outside expected range"
        }

    - uses: actions/upload-artifact@v4
      with:
        name: jre-x64
        path: jre-x64/
```

---

### 6.4 Phase 3: Sidecar Unit Tests (RED-GREEN-REFACTOR)

#### RED: Define test job

```yaml
test-sidecar:
  needs: build-sidecar-jar
  runs-on: ubuntu-22.04
  steps:
    - uses: actions/checkout@v4

    - uses: actions/setup-java@v4
      with:
        distribution: 'temurin'
        java-version: '21'

    - uses: DeLaGuardo/setup-clojure@12.5
      with:
        cli: 1.11.1.1435

    - name: Run sidecar tests
      run: cd sidecar && clj -M:test

    - name: Upload test results
      if: failure()
      uses: actions/upload-artifact@v4
      with:
        name: sidecar-test-results
        path: sidecar/target/test-results/
```

**Current test status:**
- 66 JVM tests, 230 assertions
- 12 CLJS tests, 320 assertions
- All passing as of 2025-12-13

---

### 6.5 Phase 4: E2E Tests (RED-GREEN-REFACTOR)

#### RED: Define E2E test job

```yaml
e2e-tests:
  needs: [build-windows-x64, build-sidecar-jar, build-jre-x64]
  runs-on: windows-latest
  steps:
    - uses: actions/checkout@v4

    - name: Download Electron app
      uses: actions/download-artifact@v4
      with:
        name: logsidian-win-x64
        path: electron-app/

    - name: Download sidecar JAR
      uses: actions/download-artifact@v4
      with:
        name: sidecar-jar
        path: sidecar/target/

    - name: Download JRE
      uses: actions/download-artifact@v4
      with:
        name: jre-x64
        path: jre-x64/

    - uses: actions/setup-node@v4
      with:
        node-version: '22'

    - name: Install Playwright
      run: npx playwright install --with-deps chromium

    - name: Start sidecar
      shell: pwsh
      run: |
        Start-Process -FilePath "jre-x64/bin/java" `
                      -ArgumentList "-jar", "sidecar/target/logsidian-sidecar.jar" `
                      -NoNewWindow
        Start-Sleep -Seconds 5

    - name: Run E2E tests
      run: npx playwright test --config e2e-electron/playwright.config.ts

    - name: Upload screenshots on failure
      if: failure()
      uses: actions/upload-artifact@v4
      with:
        name: e2e-screenshots
        path: |
          e2e-electron/screenshots/
          clj-e2e/error-reports/
```

---

### 6.6 Phase 5: Integration (RED-GREEN-REFACTOR)

#### Update packaging jobs to include sidecar

```yaml
build-windows-x64:
  needs: [compile-cljs, build-sidecar-jar, build-jre-x64]
  # ... existing steps ...

  - name: Download sidecar JAR
    uses: actions/download-artifact@v4
    with:
      name: sidecar-jar
      path: sidecar/target/

  - name: Download JRE
    uses: actions/download-artifact@v4
    with:
      name: jre-x64
      path: resources/jre/

  - name: Copy sidecar to resources
    run: |
      mkdir -p resources/sidecar
      cp sidecar/target/logsidian-sidecar.jar resources/sidecar/

  # ... electron:make will include resources/ in the app ...
```

---

## 7. GitHub Actions Best Practices

### 7.1 Workflow Organization

| Practice | Reason |
|----------|--------|
| Use reusable workflows | DRY - avoid duplication |
| Use composite actions | Encapsulate common steps |
| Use workflow_call | Allow triggering from other workflows |
| Use matrix builds | Test multiple configurations |

### 7.2 Artifact Management

```yaml
# Good: Specific retention and error handling
- uses: actions/upload-artifact@v4
  with:
    name: my-artifact
    path: output/
    retention-days: 7              # Don't keep forever
    if-no-files-found: error       # Fail if missing

# Good: Download with version pinning
- uses: actions/download-artifact@v4
  with:
    name: my-artifact
    path: ./local-path/
```

### 7.3 Caching Strategy

| Cache Target | Key Strategy | Path |
|--------------|--------------|------|
| Clojure deps | `hashFiles('deps.edn')` | `~/.m2/repository`, `~/.gitlibs` |
| Node modules | `hashFiles('yarn.lock')` | `node_modules/` |
| Shadow-cljs | `github.sha` | `.shadow-cljs/` |
| Rust deps | `hashFiles('Cargo.lock')` | `~/.cargo/` |

### 7.4 Conditional Execution

```yaml
# Skip job based on input
if: ${{ !inputs.skip_sidecar }}

# Run only on specific branch
if: github.ref == 'refs/heads/master'

# Run only if previous job succeeded
if: needs.build-sidecar-jar.result == 'success'

# Always run cleanup even if previous failed
if: always()
```

### 7.5 Secrets Management

```yaml
# Never log secrets
- run: echo "${{ secrets.MY_SECRET }}" | base64  # BAD!

# Use environment variables
env:
  GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

---

## 8. Security Considerations

### 8.1 Supply Chain Security

| Risk | Mitigation |
|------|------------|
| Compromised action | Pin actions to SHA, not tag |
| Malicious PR | Require approval for external PRs |
| Secret leakage | Use `secrets.GITHUB_TOKEN` only |
| Artifact tampering | Sign artifacts (future) |

### 8.2 Action Pinning

```yaml
# Good: Pinned to SHA
- uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11  # v4.1.1

# Acceptable: Pinned to major version
- uses: actions/checkout@v4

# Bad: Floating tag
- uses: actions/checkout@master
```

### 8.3 Permissions

```yaml
permissions:
  contents: write      # For creating releases
  pull-requests: write # For PR comments
  issues: write        # For creating issues

# Principle of least privilege
# Only request what you need
```

---

## 9. Cost and Performance Analysis

### 9.1 Runner Costs (GitHub-hosted)

| Runner | Cost | Minutes/month (free) |
|--------|------|----------------------|
| ubuntu-latest | 1x | 2,000 |
| windows-latest | 2x | 1,000 equivalent |
| windows-11-arm | 2x | 1,000 equivalent |
| macos-latest | 10x | 200 equivalent |

### 9.2 Estimated Build Cost

| Job | Runner | Est. Minutes | Cost Multiple |
|-----|--------|--------------|---------------|
| build-sidecar-jar | ubuntu | 3 | 3 |
| build-jre-x64 | windows | 5 | 10 |
| build-jre-arm64 | windows-arm | 5 | 10 |
| compile-cljs | ubuntu | 7 | 7 |
| build-windows-x64 | windows | 15 | 30 |
| build-windows-arm64 | windows | 18 | 36 |
| e2e-tests | windows | 10 | 20 |
| release | ubuntu | 3 | 3 |
| **Total** | - | - | **~119 min equiv** |

**Monthly estimate:** ~10 builds/month × 119 = 1,190 minutes (within free tier)

### 9.3 Optimization Opportunities

| Optimization | Savings | Implementation |
|--------------|---------|----------------|
| Better caching | 30-50% | Cache all dependencies |
| Parallel jobs | 40% | Max parallelization |
| Skip unchanged | Variable | Path filters |
| Self-hosted runners | 90% cost | Requires maintenance |

---

## 10. Risk Assessment

### 10.1 Technical Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| jlink missing modules | Medium | High | Test with real workload |
| E2E test flakiness | High | Medium | Retry logic, wait conditions |
| ARM64 runner availability | Low | High | Fallback to skip ARM64 |
| Artifact size limits | Low | Medium | Compress, split artifacts |
| Build timeout | Medium | Medium | Increase timeout, optimize |

### 10.2 Process Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Breaking main build | Medium | High | Test in separate workflow first |
| Merge conflicts | Medium | Low | Work in feature branch |
| Missing test coverage | Medium | Medium | TDD approach |

---

## 11. Implementation Checklist

### Phase 1: Foundation (Day 1)

- [ ] Create test workflow `.github/workflows/test-sidecar-ci.yml`
- [ ] Implement `build-sidecar-jar` job
- [ ] Verify JAR builds correctly
- [ ] Add caching for Clojure dependencies

### Phase 2: JRE Builds (Day 1-2)

- [ ] Implement `build-jre-x64` job
- [ ] Implement `build-jre-arm64` job
- [ ] Verify JRE size is within expected range
- [ ] Test sidecar runs with minimal JRE

### Phase 3: Testing (Day 2)

- [ ] Add `test-sidecar` job (unit tests)
- [ ] Run existing 66 JVM tests in CI
- [ ] Set up test result reporting

### Phase 4: Integration (Day 2-3)

- [ ] Update `build-windows-x64` to download sidecar artifacts
- [ ] Update `build-windows-arm64` to download sidecar artifacts
- [ ] Verify sidecar is included in packaged app

### Phase 5: E2E Tests (Day 3)

- [ ] Add `e2e-tests` job
- [ ] Configure Playwright for CI
- [ ] Set up screenshot capture on failure
- [ ] Verify smoke tests pass in CI

### Phase 6: Release Integration (Day 3-4)

- [ ] Update release notes to mention sidecar
- [ ] Add sidecar version to release body
- [ ] Test full workflow end-to-end

### Phase 7: Cleanup (Day 4)

- [ ] Remove temporary test workflow
- [ ] Document new workflow structure in CLAUDE.md
- [ ] Update task document with completion status

---

## References

1. [GitHub Actions Documentation](https://docs.github.com/en/actions)
2. [Playwright Electron Testing](https://playwright.dev/docs/api/class-electron)
3. [jlink Documentation](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jlink.html)
4. [Microsoft OpenJDK](https://learn.microsoft.com/en-us/java/openjdk/download)
5. [AppCDS Documentation](https://docs.oracle.com/en/java/javase/21/vm/class-data-sharing.html)
6. [DataScript IStorage](https://github.com/tonsky/datascript/blob/master/docs/storage.md)

---

## Appendix A: Complete Workflow YAML (Target State)

See separate file: `github-actions-complete-workflow.yml`

---

## Appendix B: jlink Module Analysis

Command to analyze required modules:

```bash
# Run sidecar with module tracing
java --module-path mods -m logseq.sidecar/logseq.sidecar.server \
     -XX:+TraceClassLoading 2>&1 | grep "java\." | sort -u
```

---

## Change Log

| Date | Change |
|------|--------|
| 2025-12-13 | Initial research document created |
