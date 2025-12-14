# CI/CD: GitHub Actions for Sidecar Builds

**Worktree:** `C:\Users\johan\repos\_worktrees\logsidian-cicd`
**Branch:** `feature-sidecar-cicd`
**Status:** ✅ Complete
**Priority:** 2

## Goal

Set up GitHub Actions workflows to:
1. ✅ Build the sidecar JAR
2. ✅ Build minimal JREs for x64 using jlink
3. ✅ Run sidecar unit tests in CI
4. ✅ Package releases with bundled Java
5. ⏳ ARM64 JRE (blocked - private repo runners)
6. ⏳ E2E tests with sidecar (future iteration)

## What's Implemented

### build-windows.yml

The main build workflow now includes:

| Job | Description | Duration |
|-----|-------------|----------|
| `build-sidecar-jar` | Builds uberjar (~27 MB) | ~40s |
| `build-jre-x64` | Creates minimal JRE (~35 MB) | ~1-2m |
| `build-windows-x64` | Packages Electron + JRE + JAR | ~12-15m |
| `test-release` | Creates GitHub prerelease | ~1m |

### test-sidecar-ci.yml

TDD test workflow for validating CI components:

```bash
# Run all tests
gh workflow run test-sidecar-ci.yml -f test_component=all

# Test individual components
gh workflow run test-sidecar-ci.yml -f test_component=sidecar-jar
gh workflow run test-sidecar-ci.yml -f test_component=jre-x64
```

### Electron Integration

**File:** `src/electron/electron/sidecar.cljs`

The `find-java` function now checks for bundled JRE first:
1. `{resources}/jre/bin/java.exe` (bundled)
2. `{resources}/jre-{platform}-{arch}/bin/java.exe` (platform-specific)
3. `JAVA_HOME` environment variable
4. System `PATH`

## Implementation Phases

### Phase 1: Foundation ✅ Complete

- [x] Research current workflow structure
- [x] Analyze sidecar build requirements
- [x] Design job dependency graph
- [x] Create test workflow (`test-sidecar-ci.yml`)
- [x] Write comprehensive research document

### Phase 2: Validate Test Workflow ✅ Complete

- [x] Run `test-sidecar-ci.yml` with `test_component=sidecar-jar`
- [x] Run `test-sidecar-ci.yml` with `test_component=jre-x64`
- [x] Run `test-sidecar-ci.yml` with `test_component=sidecar-tests`
- [x] Run `test-sidecar-ci.yml` with `test_component=all`
- [x] Verify all assertions pass
- [x] Fix CI failures (GREEN phase of TDD)

### Phase 3: Integrate into build-windows.yml ✅ Complete

- [x] Add `build-sidecar-jar` job
- [x] Add `build-jre-x64` job
- [x] Update `build-windows-x64` to download and bundle JRE + JAR
- [x] Update Electron code to find bundled JRE

### Phase 4: E2E Test Integration ⏳ Future

- [ ] Add `e2e-tests` job
- [ ] Configure Playwright for CI
- [ ] Gate releases on E2E pass

### Phase 5: ARM64 Support ⏳ Blocked

- [ ] `build-jre-arm64` job (requires `windows-11-arm` runner)
- [ ] Update `build-windows-arm64` dependencies

**Note:** `windows-11-arm` runners are not available for private repositories. This will be enabled when the repo goes public or GitHub adds support.

## Job Dependencies

```
┌─────────────────────┐     ┌─────────────────────┐
│   compile-cljs      │     │  build-sidecar-jar  │
└──────────┬──────────┘     └──────────┬──────────┘
           │                           │
           │     ┌─────────────────────┤
           │     │                     │
           ▼     ▼                     ▼
┌─────────────────────┐     ┌─────────────────────┐
│  build-windows-x64  │◄────│   build-jre-x64     │
└──────────┬──────────┘     └─────────────────────┘
           │
           ▼
┌─────────────────────┐
│    test-release     │
└─────────────────────┘
```

## Artifacts

| Artifact | Contents | Size |
|----------|----------|------|
| `sidecar-jar` | `logsidian-sidecar.jar` | ~27 MB |
| `jre-win32-x64` | Minimal JRE (jlink) | ~35 MB |
| `cljs-static` | Compiled frontend | ~80 MB |
| `logsidian-win-x64` | MSI + ZIP + nupkg | ~300 MB |

## Success Criteria ✅

- [x] `build-sidecar-jar` job creates uberjar (~27 MB)
- [x] `build-jre-x64` job creates minimal JRE (~35 MB)
- [x] Sidecar runs successfully with minimal JRE (integration test)
- [x] Release artifacts include sidecar JAR and JRE
- [x] MSI installer works without system Java installed
- [ ] ARM64 build uses ARM64 JRE (blocked)
- [ ] E2E smoke tests pass with sidecar (future)

## jlink Modules

```
java.base          # Always required
java.logging       # For tools.logging
java.sql           # For next.jdbc, sqlite-jdbc
java.naming        # For sqlite-jdbc, logback
java.management    # For logback JMX
```

## Future Improvements

1. **AppCDS** - Pre-generate class data sharing archive for ~500ms startup
2. **E2E Tests** - Playwright tests with sidecar running
3. **ARM64** - When runners become available for private repos
4. **Caching** - Cache JRE builds (they change rarely)

## References

- [Research Document](../research/github-actions-sidecar-cicd.md)
- [jlink Documentation](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jlink.html)
