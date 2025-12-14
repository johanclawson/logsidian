# Java Runtime Bundling

**Status:** ✅ Basic Bundling Complete | ⏳ AppCDS Optimization Pending
**Branch:** `feature-sidecar-cicd`

## Goal

Bundle a minimal JRE with the installer so users don't need to install Java separately.

## What's Implemented ✅

### 1. Minimal JRE Creation (jlink)

**CI Job:** `build-jre-x64` in `build-windows.yml`

```powershell
jlink --module-path "$env:JAVA_HOME/jmods" `
      --add-modules java.base,java.logging,java.sql,java.naming,java.management `
      --output jre-win32-x64 `
      --strip-debug --compress=2 --no-header-files --no-man-pages
```

**Result:** ~35 MB JRE (vs ~300 MB full JDK)

### 2. Bundled JRE Discovery

**File:** `src/electron/electron/sidecar.cljs`

The `find-java` function checks for bundled JRE first:
1. `{resources}/jre/bin/java.exe`
2. `{resources}/jre-{platform}-{arch}/bin/java.exe`
3. Falls back to `JAVA_HOME` or system `PATH`

### 3. CI/CD Integration

The build workflow:
1. Builds sidecar JAR (~27 MB)
2. Creates minimal JRE (~35 MB)
3. Downloads both to `static/` folder
4. Electron Forge packages them into the MSI

### 4. Bundle Structure

```
Logsidian-win-x64.msi (~310 MB total)
├── Logseq.exe (Electron app)
├── resources/
│   ├── logsidian-sidecar.jar (~27 MB)
│   └── jre/
│       ├── bin/java.exe
│       └── lib/modules (~35 MB)
```

## What's Pending ⏳

### AppCDS (Class Data Sharing)

Would reduce JVM startup from ~2s to ~500ms.

**Not yet implemented because:**
- Requires generating class list at build time
- Adds complexity to CI pipeline
- Basic startup is acceptable for now

**Implementation when needed:**

```powershell
# Generate class list (one-time)
java -Xshare:off -XX:DumpLoadedClassList=classes.lst -jar logsidian-sidecar.jar --dry-run

# Create AppCDS archive
java -Xshare:dump -XX:SharedClassListFile=classes.lst `
     -XX:SharedArchiveFile=sidecar.jsa -jar logsidian-sidecar.jar

# Use at runtime
java -Xshare:on -XX:SharedArchiveFile=sidecar.jsa -jar logsidian-sidecar.jar
```

### ARM64 JRE

**Blocked:** `windows-11-arm` runners not available for private repos.

When available, add `build-jre-arm64` job to CI.

## Size Impact

| Component | Size |
|-----------|------|
| Electron app (existing) | ~150 MB |
| Sidecar JAR | ~27 MB |
| Minimal JRE | ~35 MB |
| **Total added** | **~62 MB** |
| **MSI total** | **~310 MB** |

## Startup Time

| Configuration | Time |
|---------------|------|
| Current (no AppCDS) | ~2s |
| With AppCDS (future) | ~500ms |

## jlink Modules

```
java.base          # Core (always required)
java.logging       # For tools.logging
java.sql           # For next.jdbc, sqlite-jdbc
java.naming        # For sqlite-jdbc JNDI
java.management    # For logback JMX
```

## Testing

```bash
# Verify MSI works without system Java
1. Uninstall Java from system
2. Install Logsidian-win-x64.msi
3. Launch app
4. Verify sidecar connects (check logs)
```

## Success Criteria

- [x] jlink creates minimal JRE (~35 MB)
- [x] Electron finds and uses bundled Java
- [x] MSI works on clean Windows (no Java)
- [ ] AppCDS reduces startup to <500ms (future)
- [ ] ARM64 JRE bundled (blocked)

## References

- [GitHub Actions CI/CD](./github-actions.md) - Full CI implementation
- [jlink Documentation](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jlink.html)
- [AppCDS Guide](https://docs.oracle.com/en/java/javase/21/vm/class-data-sharing.html)
