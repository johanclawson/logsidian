# Build Skill

Knowledge base for building Logsidian on Windows (x64 and ARM64).
Used by the build-agent subagent.

## Arguments

Parse arguments from user input:

| Argument | Description |
|----------|-------------|
| `background`, `bg` | Run build in background, return immediately |
| `skip-install` | Skip yarn install step (faster rebuilds) |
| `electron-only` | Only package electron (skip CSS/webpack) |
| `quick` | Alias for `skip-install electron-only` |

**Examples:**
- `/build-agent` - Full foreground build
- `/build-agent bg` - Full build in background
- `/build-agent skip-install` - Skip dependencies
- `/build-agent quick bg` - Fast rebuild in background

## Background Mode

When `background` argument is present:

1. Use Bash tool with `run_in_background: true`
2. Log output to `build.log` in project root
3. Return immediately with task ID
4. User can check status with: `tail -f build.log` or `/tasks`

**Background build command:**
```bash
cmd.exe /c "cd /d X:\source\repos\logsidian && pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\build.ps1 2>&1 | Tee-Object -FilePath build.log"
```

**Immediate response for background builds:**
```
Build started in background.

Monitor: tail -f build.log
Check tasks: /tasks

Will notify when complete.
```

## Pre-Build Checklist

Before starting, verify:
- [ ] Kill any running Logseq process (prevents EBUSY errors)
- [ ] Node.js version matches `.nvmrc` (22.x)
- [ ] Working directory is `X:\source\repos\logsidian`
- [ ] No uncommitted changes that might affect build (optional warning)

### Kill Logseq Process

**Always run this before building** to prevent directory lock errors:
```bash
taskkill //F //IM Logseq.exe 2>/dev/null || echo "Logseq not running"
```

Or via PowerShell (more reliable):
```bash
cmd.exe /c "taskkill /F /IM Logseq.exe 2>nul || echo Logseq not running"
```

## Build Command

**Primary build command** (handles path corruption):
```bash
cmd.exe /c "cd /d X:\source\repos\logsidian && pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\build.ps1"
```

**Options:**
- `-SkipInstall` - Skip yarn install (faster rebuilds)
- `-ElectronOnly` - Only package electron (skip CSS/webpack)

## Post-Build Checklist

Before reporting success, verify ALL of these:

1. **Executable exists:**
   ```bash
   ls -la "static/out/Logseq-win32-{arch}/Logseq.exe"
   ```

2. **rsapi native module installed:**
   ```bash
   ls -la "static/out/Logseq-win32-{arch}/resources/app/node_modules/@logseq/rsapi-win32-{arch}-msvc/"
   ```
   Must contain:
   - `rsapi.win32-{arch}-msvc.node`
   - `package.json`

3. **If rsapi missing, install it:**
   ```bash
   mkdir -p "static/out/Logseq-win32-{arch}/resources/app/node_modules/@logseq/rsapi-win32-{arch}-msvc"
   cp "native-binaries/win32-{arch}/rsapi.win32-{arch}-msvc.node" "static/out/Logseq-win32-{arch}/resources/app/node_modules/@logseq/rsapi-win32-{arch}-msvc/"
   ```

   Create package.json:
   ```json
   {
     "name": "@logseq/rsapi-win32-{arch}-msvc",
     "version": "0.0.1",
     "main": "rsapi.win32-{arch}-msvc.node"
   }
   ```

4. **Key bundle files exist:**
   ```bash
   ls static/js/main.js static/js/publishing/main.js
   ls static/js/db-worker-bundle.js static/js/inference-worker-bundle.js
   ls static/css/style.css
   ```

## Architecture Detection

Detect architecture from build output or system:
- ARM64: `Logseq-win32-arm64`
- x64: `Logseq-win32-x64`

## Success Report Format

```
Build complete for {arch}.

Output: static/out/Logseq-win32-{arch}/Logseq.exe ({size} MB)

Verified:
- [x] Executable exists
- [x] rsapi native module installed
- [x] Bundle files present
```

## Error Handling

If build fails, check the Known Errors section below and attempt fixes automatically.

---

## Known Errors & Solutions

### Error: electron-forge not found

**Symptom:**
```
ERROR: Could not find electron-forge CLI
```

**Solution:**
```bash
cmd.exe /c "cd /d X:\source\repos\logsidian\static && yarn install"
```
Then retry packaging:
```bash
cmd.exe /c "cd /d X:\source\repos\logsidian\static && node node_modules/@electron-forge/cli/dist/electron-forge.js package"
```

---

### Error: Cannot find module '@logseq/rsapi-win32-{arch}-msvc'

**Symptom:**
```
Error: Cannot find module '@logseq/rsapi-win32-arm64-msvc'
```

**Cause:** Native module not copied or missing package.json

**Solution:**
1. Copy the .node file from `native-binaries/win32-{arch}/`
2. Create package.json in the module directory (see Post-Build Checklist)

---

### Error: Path corruption (X:\x\source\...)

**Symptom:**
```
Cannot find module 'X:\x\source\repos\...'
```

**Cause:** Claude Code's cygpath corrupts paths for yarn/npm

**Solution:** Always use `cmd.exe /c` wrapper:
```bash
cmd.exe /c "cd /d X:\source\repos\logsidian && pwsh -NoProfile -Command 'your-command'"
```

---

### Error: EBUSY - Directory locked

**Date:** 2025-01-09
**Symptom:**
```
EBUSY: resource busy or locked, rmdir 'X:\source\repos\logsidian\static\out\Logseq-win32-arm64'
```

**Root Cause:** Previous build output directory is locked (app running, folder open in Explorer, or antivirus scanning)

**Solution:**
1. Close the running Logseq app if open
2. Close any Explorer windows in the output directory
3. Remove the locked directory:
   ```bash
   rm -rf "static/out/Logseq-win32-arm64"
   ```
4. Retry packaging

---

### Error: exe-icon-extractor build failed

**Symptom:**
```
error C2664: 'void throwIfNotSuccess...'
```

**Impact:** Optional dependency, does not affect build. Safe to ignore.

---

### Error: ClojureScript compilation failed

**Symptom:**
```
[:app] Build failure
```

**Solution:**
1. Check for syntax errors in recent changes
2. Try cleaning shadow-cljs cache:
   ```bash
   rm -rf .shadow-cljs
   ```
3. Re-run build

---

### Error: Webpack bundle missing

**Symptom:** App loads but shows blank screen or worker errors

**Solution:**
```bash
cmd.exe /c "cd /d X:\source\repos\logsidian && pwsh -NoProfile -Command 'npm run webpack-app-build'"
```
Then copy bundles to output if needed.

---

## Build Log Template

When errors occur, update this section with new findings:

```
### Error: [Title]

**Date:** YYYY-MM-DD
**Symptom:**
[What error message appeared]

**Root Cause:**
[Why it happened]

**Solution:**
[How to fix it]
```
