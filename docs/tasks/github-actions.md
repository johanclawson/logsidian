# CI/CD: GitHub Actions for Sidecar Builds

**Worktree:** `C:\Users\johan\repos\_worktrees\logsidian-cicd`
**Branch:** `feature-sidecar-cicd`
**Status:** In Progress (Research Complete)
**Priority:** 2
**Depends on:** Nothing (can start now)

## Goal

Set up GitHub Actions workflows to:
1. Build the sidecar JAR
2. Build minimal JREs for x64 and ARM64 using jlink
3. Run sidecar unit tests in CI
4. Run E2E tests with sidecar
5. Package releases with bundled Java

## Research Documents

Extensive research has been completed. See:

- **[github-actions-sidecar-cicd.md](../research/github-actions-sidecar-cicd.md)** - Comprehensive analysis
- **[github-actions-complete-workflow.yml](../research/github-actions-complete-workflow.yml)** - Target workflow state

## TDD Test Workflow

A dedicated test workflow exists for TDD validation:

**File:** `.github/workflows/test-sidecar-ci.yml`

Run it to validate individual components before integration:
```bash
gh workflow run test-sidecar-ci.yml -f test_component=all
```

## Implementation Phases

### Phase 1: Foundation ✅ Complete

- [x] Research current workflow structure
- [x] Analyze sidecar build requirements
- [x] Design job dependency graph
- [x] Create test workflow (`test-sidecar-ci.yml`)
- [x] Create target workflow documentation
- [x] Write comprehensive research document

### Phase 2: Validate Test Workflow (Next)

- [ ] Run `test-sidecar-ci.yml` with `test_component=sidecar-jar`
- [ ] Run `test-sidecar-ci.yml` with `test_component=jre-x64`
- [ ] Run `test-sidecar-ci.yml` with `test_component=jre-arm64`
- [ ] Run `test-sidecar-ci.yml` with `test_component=sidecar-tests`
- [ ] Run `test-sidecar-ci.yml` with `test_component=all`
- [ ] Verify all assertions pass

### Phase 3: Integrate into build-windows.yml

- [ ] Add `build-sidecar-jar` job
- [ ] Add `test-sidecar` job
- [ ] Add `build-jre-x64` job
- [ ] Add `build-jre-arm64` job
- [ ] Update `build-windows-x64` dependencies
- [ ] Update `build-windows-arm64` dependencies

### Phase 4: E2E Test Integration

- [ ] Add `e2e-tests` job
- [ ] Configure Playwright for CI
- [ ] Gate releases on E2E pass

### Phase 5: Cleanup

- [ ] Remove test workflow (or keep for debugging)
- [ ] Update CLAUDE.md documentation
- [ ] Test full workflow end-to-end

## Implementation Steps

### Step 1: Add Sidecar JAR Build Job

**File:** `.github/workflows/build-windows.yml`

Add after existing jobs:

```yaml
build-sidecar-jar:
  runs-on: ubuntu-22.04
  steps:
    - uses: actions/checkout@v4

    - uses: actions/setup-java@v4
      with:
        distribution: 'microsoft'
        java-version: '21'

    - uses: DeLaGuardo/setup-clojure@12.5
      with:
        cli: 1.11.1.1435

    - name: Build sidecar uberjar
      run: cd sidecar && clojure -T:build uberjar

    - uses: actions/upload-artifact@v4
      with:
        name: sidecar-jar
        path: sidecar/target/logsidian-sidecar.jar
```

### Step 2: Add JRE Build Jobs

```yaml
build-jre-x64:
  runs-on: windows-latest
  steps:
    - uses: actions/setup-java@v4
      with:
        distribution: 'microsoft'
        java-version: '21'

    - name: Create minimal JRE (x64)
      run: |
        jlink --module-path "$env:JAVA_HOME/jmods" `
              --add-modules java.base,java.logging,java.sql,java.naming,java.management `
              --output jre-x64 --strip-debug --compress=2 --no-header-files --no-man-pages

    - uses: actions/upload-artifact@v4
      with:
        name: jre-x64
        path: jre-x64/

build-jre-arm64:
  runs-on: windows-11-arm  # GitHub ARM64 runner
  steps:
    - uses: actions/setup-java@v4
      with:
        distribution: 'microsoft'
        java-version: '21'

    - name: Create minimal JRE (ARM64)
      run: |
        jlink --module-path "$env:JAVA_HOME/jmods" `
              --add-modules java.base,java.logging,java.sql,java.naming,java.management `
              --output jre-arm64 --strip-debug --compress=2 --no-header-files --no-man-pages

    - uses: actions/upload-artifact@v4
      with:
        name: jre-arm64
        path: jre-arm64/
```

### Step 3: Create E2E Test Workflow

**File:** `.github/workflows/e2e-tests.yml`

```yaml
name: E2E Tests

on:
  push:
    branches: [feature-sidecar, main]
  pull_request:
    branches: [feature-sidecar, main]

jobs:
  e2e:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: 'microsoft'
          java-version: '21'

      - uses: DeLaGuardo/setup-clojure@12.5
        with:
          cli: 1.11.1.1435

      - uses: actions/setup-node@v4
        with:
          node-version: '22'

      - name: Build Sidecar
        run: cd sidecar && clj -T:build uberjar

      - name: Install Dependencies
        run: yarn install

      - name: Build App
        run: yarn release-electron

      - name: Install Playwright
        run: npx playwright install --with-deps

      - name: Start Sidecar
        run: |
          Start-Process -FilePath "java" -ArgumentList "-jar", "sidecar/target/logsidian-sidecar.jar" -NoNewWindow
          Start-Sleep -Seconds 5
        shell: pwsh

      - name: Run E2E Tests
        run: npx playwright test --config e2e-electron/playwright.config.ts

      - name: Upload Screenshots
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: e2e-screenshots
          path: e2e-electron/screenshots/
```

### Step 4: Modify Release Job to Include Sidecar

Update the existing release job to download and include sidecar artifacts:

```yaml
build-windows-x64:
  needs: [compile-cljs, build-sidecar-jar, build-jre-x64]
  runs-on: windows-latest
  steps:
    # ... existing steps ...

    - name: Download sidecar JAR
      uses: actions/download-artifact@v4
      with:
        name: sidecar-jar
        path: sidecar/target/

    - name: Download JRE x64
      uses: actions/download-artifact@v4
      with:
        name: jre-x64
        path: jre-x64/

    - name: Set JRE path for packaging
      run: echo "JRE_PATH=jre-x64" >> $env:GITHUB_ENV

    # ... continue with electron:make ...
```

## Files to Create/Modify

| File | Action |
|------|--------|
| `.github/workflows/build-windows.yml` | Modify - add sidecar and JRE jobs |
| `.github/workflows/e2e-tests.yml` | Create - E2E test workflow |

## Job Dependencies

```
┌─────────────────────┐
│   compile-cljs      │
└──────────┬──────────┘
           │
┌──────────┼──────────┐     ┌─────────────────────┐
│          │          │     │  build-sidecar-jar  │
│          ▼          │     └──────────┬──────────┘
│  build-windows-x64  │◄───────────────┤
│                     │     ┌──────────┴──────────┐
│                     │◄────│     build-jre-x64   │
└─────────────────────┘     └─────────────────────┘

┌─────────────────────┐     ┌─────────────────────┐
│ build-windows-arm64 │◄────│    build-jre-arm64  │
└─────────────────────┘     └─────────────────────┘
```

## Existing Workflow Reference

The current `.github/workflows/build-windows.yml` has:
- `build-rsapi-arm64` - Compiles native rsapi module
- `compile-cljs` - Builds ClojureScript
- `build-windows-x64` - Packages Electron x64
- `build-windows-arm64` - Packages Electron ARM64
- `release` - Creates GitHub releases

## Success Criteria

- [ ] `build-sidecar-jar` job creates uberjar (~30 MB)
- [ ] `test-sidecar` job runs 66 unit tests successfully
- [ ] `build-jre-x64` job creates minimal JRE (~60 MB)
- [ ] `build-jre-arm64` job creates ARM64 JRE (~60 MB)
- [ ] Sidecar runs successfully with minimal JRE (integration test)
- [ ] E2E smoke tests pass with sidecar
- [ ] Release artifacts include sidecar JAR and JRE
- [ ] ARM64 build uses ARM64 JRE
- [ ] Workflow inputs allow skipping sidecar for debugging

## Testing the Workflow

### TDD Test Workflow (Recommended)

```bash
# Run the TDD test workflow to validate each component
gh workflow run test-sidecar-ci.yml -f test_component=all

# Watch the run
gh run watch

# View summary
gh run view <run-id>
```

### Manual Validation

```bash
# Build sidecar JAR locally
cd sidecar && clj -T:build uberjar
ls -lh target/logsidian-sidecar.jar  # Should be ~30 MB

# Test minimal JRE locally (Windows PowerShell)
jlink --module-path "$env:JAVA_HOME/jmods" `
      --add-modules java.base,java.logging,java.sql,java.naming,java.management `
      --output jre-test --strip-debug --compress=2

# Run sidecar with minimal JRE
./jre-test/bin/java -jar target/logsidian-sidecar.jar
```

## New Workflow Inputs

| Input | Type | Default | Description |
|-------|------|---------|-------------|
| `skip_sidecar` | boolean | false | Skip sidecar build |
| `skip_e2e` | boolean | false | Skip E2E tests |

## jlink Modules Required

```
java.base          # Always required
java.logging       # For tools.logging
java.sql           # For next.jdbc, sqlite-jdbc
java.naming        # For sqlite-jdbc, logback
java.management    # For logback JMX
```

## Estimated Build Times

| Job | Runner | Time |
|-----|--------|------|
| build-sidecar-jar | ubuntu-22.04 | ~3 min |
| test-sidecar | ubuntu-22.04 | ~3 min |
| build-jre-x64 | windows-latest | ~5 min |
| build-jre-arm64 | windows-11-arm | ~5 min |
| e2e-tests | windows-latest | ~10 min |

## Notes

- GitHub has ARM64 Windows runners (`windows-11-arm`)
- Microsoft OpenJDK 21 supports both x64 and ARM64
- E2E tests need the sidecar running before tests start
- JRE builds can run in parallel (no dependency on sidecar JAR)
- Consider caching JRE builds (they change rarely)
- Clojure dependency caching reduces JAR build time

## References

- [Research Document](../research/github-actions-sidecar-cicd.md) - Full analysis
- [Target Workflow](../research/github-actions-complete-workflow.yml) - What we're building towards
- [jlink Documentation](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jlink.html)
- [GitHub Windows ARM64 Runners](https://github.blog/changelog/2024-06-03-github-hosted-runners-public-beta-of-windows-arm64-runners/)
