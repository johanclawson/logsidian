# Native Binaries

Pre-built native Node.js modules for local development. These are checked in temporarily to avoid requiring a full native build toolchain for local development.

## Contents

### win32-x64/

| File | Size | Description |
|------|------|-------------|
| `rsapi.win32-x64-msvc.node` | ~6 MB | Rust API with libgit2 for git sync |
| `keytar.node` | ~700 KB | Credential storage (Windows Credential Manager) |
| `electron-deeplink.node` | ~100 KB | Deep link handling |

## Usage

These binaries are **automatically copied** by the build script (`scripts/build.ps1`):

1. **During build** - Copied to `static/node_modules/` after yarn install
2. **After build** - Verified/copied to final app in `static/out/Logseq-win32-x64/`

### Manual Copy (if needed)

```powershell
# Copy to static/node_modules after yarn install
Copy-Item native-binaries/win32-x64/rsapi.win32-x64-msvc.node `
    static/node_modules/@logseq/rsapi/

Copy-Item native-binaries/win32-x64/keytar.node `
    static/node_modules/keytar/build/Release/

Copy-Item native-binaries/win32-x64/electron-deeplink.node `
    static/node_modules/electron-deeplink/build/Release/
```

## Source

Extracted from CI build artifacts:
- Workflow: `build-windows.yml`
- Run: #20208334094
- Date: 2025-12-14

## Rebuilding

To rebuild these from source:

```bash
# rsapi (requires Rust toolchain)
cd packages/rsapi
yarn build

# keytar (requires node-gyp + Visual Studio)
cd node_modules/keytar
npm run build

# electron-deeplink
cd node_modules/electron-deeplink
npm run build
```

## Note

These binaries should be removed once we have a proper local build script or when the CI properly caches native modules.
