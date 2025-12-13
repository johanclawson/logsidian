# CI/CD: GitHub Actions for Sidecar Builds

**Worktree:** `C:\Users\johan\repos\_worktrees\logsidian-cicd`
**Branch:** `feature-sidecar-cicd`
**Status:** Not Started
**Priority:** 2
**Estimate:** 2-3 hours
**Depends on:** Nothing (can start now)

## Goal

Set up GitHub Actions workflows to:
1. Build the sidecar JAR
2. Build minimal JREs for x64 and ARM64
3. Run E2E tests
4. Package releases with bundled Java

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

- [ ] `build-sidecar-jar` job creates uberjar
- [ ] `build-jre-x64` job creates minimal JRE (~50-80MB)
- [ ] `build-jre-arm64` job creates ARM64 JRE
- [ ] E2E tests run in CI and pass
- [ ] Release artifacts include sidecar JAR and JRE
- [ ] ARM64 build uses ARM64 JRE

## Testing the Workflow

1. Push changes to a test branch
2. Check Actions tab for job status
3. Download artifacts and verify sizes
4. Test E2E workflow manually first

## Notes

- GitHub has ARM64 Windows runners (`windows-11-arm`)
- Microsoft OpenJDK 21 supports both x64 and ARM64
- E2E tests need the sidecar running before tests start
- Consider caching JRE builds (they rarely change)
