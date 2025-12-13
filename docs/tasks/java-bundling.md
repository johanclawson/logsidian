# Phase 5.5: Java Runtime Bundling for MSIX

**Worktree:** `C:\Users\johan\repos\_worktrees\logsidian-bundling`
**Branch:** `feature-sidecar-bundling`
**Status:** Not Started
**Priority:** 3
**Estimate:** 3-4 hours
**Depends on:** Phase 5 (Performance Benchmarks)

## Goal

Bundle a minimal JRE with the MSIX installer so users don't need to install Java separately.

## Strategy: jlink + AppCDS

### 1. Create Minimal JRE with jlink

```bash
# Creates ~50-80MB runtime instead of ~200MB full JRE
jlink --module-path $JAVA_HOME/jmods \
      --add-modules java.base,java.logging,java.sql,java.naming,java.management \
      --output jre-minimal \
      --strip-debug \
      --compress=2 \
      --no-header-files \
      --no-man-pages
```

### 2. Create AppCDS Archive for Fast Startup

```bash
# One-time: Generate class list
java -Xshare:off -XX:DumpLoadedClassList=classes.lst -jar logsidian-sidecar.jar --dry-run

# Build: Create AppCDS archive
java -Xshare:dump -XX:SharedClassListFile=classes.lst \
     -XX:SharedArchiveFile=logsidian-sidecar.jsa -jar logsidian-sidecar.jar

# Result: ~100-200ms startup instead of ~500ms
```

### 3. Bundle Structure in MSIX

```
Logsidian.msix (total ~250-300MB)
├── Logseq.exe (Electron app ~150MB)
├── resources/
│   ├── logsidian-sidecar.jar (~30MB)
│   └── logsidian-sidecar.jsa (AppCDS archive ~5MB)
└── jre-minimal/ (~50-80MB)
    ├── bin/java.exe
    └── lib/modules
```

## Implementation Steps

### Step 1: Create jlink Script

**File:** `scripts/build-jre.ps1`

```powershell
param(
    [ValidateSet("x64", "arm64")]
    [string]$Arch = "x64"
)

$javaHome = $env:JAVA_HOME
$modules = "java.base,java.logging,java.sql,java.naming,java.management"
$output = "jre-$Arch"

jlink --module-path "$javaHome/jmods" `
      --add-modules $modules `
      --output $output `
      --strip-debug `
      --compress=2 `
      --no-header-files `
      --no-man-pages

Write-Host "Created minimal JRE at: $output"
Write-Host "Size: $((Get-ChildItem $output -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB) MB"
```

### Step 2: Update Electron Config

**File:** `static/forge.config.js`

```javascript
module.exports = {
  packagerConfig: {
    extraResource: [
      // Sidecar JAR
      '../sidecar/target/logsidian-sidecar.jar',
      // Platform-specific JRE (set by CI)
      process.env.JRE_PATH || '../jre-minimal'
    ]
  }
};
```

### Step 3: Modify Sidecar to Find Bundled Java

**File:** `src/electron/electron/sidecar.cljs`

```clojure
(defn- find-bundled-java
  "Find the bundled Java executable in app resources"
  []
  (let [resources-path (.-resourcesPath js/process)
        platform (.-platform js/process)
        java-exe (if (= platform "win32") "java.exe" "java")
        bundled-path (.join node-path resources-path "jre-minimal" "bin" java-exe)]
    (if (.existsSync fs bundled-path)
      (do (logger/info "Using bundled JRE" {:path bundled-path})
          bundled-path)
      ;; Fall back to system Java
      (do (logger/warn "Bundled JRE not found, falling back to system Java")
          "java"))))
```

### Step 4: Create AppCDS Generation Script

**File:** `scripts/build-appcds.ps1`

```powershell
param(
    [string]$JarPath = "sidecar/target/logsidian-sidecar.jar"
)

# Generate class list
java -Xshare:off -XX:DumpLoadedClassList=classes.lst -jar $JarPath --dry-run

# Create AppCDS archive
java -Xshare:dump -XX:SharedClassListFile=classes.lst `
     -XX:SharedArchiveFile=logsidian-sidecar.jsa -jar $JarPath

Write-Host "Created AppCDS archive: logsidian-sidecar.jsa"
```

## Files to Create/Modify

| File | Action |
|------|--------|
| `scripts/build-jre.ps1` | Create - jlink script |
| `scripts/build-appcds.ps1` | Create - AppCDS script |
| `static/forge.config.js` | Modify - add extraResource |
| `src/electron/electron/sidecar.cljs` | Modify - find bundled Java |

## Platform Support

| Platform | JDK Distribution | Status |
|----------|------------------|--------|
| Windows x64 | Microsoft OpenJDK 21 | Ready |
| Windows ARM64 | Microsoft OpenJDK 21 | Ready |
| macOS (future) | Temurin/Zulu 21 | Planned |

## Size Impact

| Component | Size |
|-----------|------|
| Electron app | ~150MB |
| Sidecar JAR | ~30MB |
| AppCDS archive | ~5MB |
| Minimal JRE | ~50-80MB |
| **Total added** | **~85-115MB** |

## Startup Time Targets

| Configuration | Cold Start | Warm Start |
|---------------|------------|------------|
| Full JRE, no cache | ~800ms | ~500ms |
| jlink JRE + AppCDS | **~200ms** | **~100ms** |

## Success Criteria

- [ ] jlink script creates minimal JRE (~50-80MB)
- [ ] AppCDS archive reduces startup to <200ms
- [ ] Electron config bundles JAR and JRE
- [ ] Sidecar finds and uses bundled Java
- [ ] Works on clean Windows (no Java installed)
- [ ] Works on Windows ARM64

## Testing

```bash
# Test on clean Windows VM
1. Install MSIX (no Java installed)
2. Launch app
3. Verify sidecar starts with bundled JRE
4. Check startup time < 200ms
```
