# Logsidian Build Script
# Uses nvm to set the correct Node.js version for this session only

param(
    [switch]$SkipInstall,
    [switch]$ElectronOnly,
    [switch]$Help
)

if ($Help) {
    Write-Host @"
Logsidian Build Script

Usage: .\build.ps1 [options]

Options:
    -SkipInstall    Skip yarn install step
    -ElectronOnly   Only build electron (skip CSS and webpack)
    -Help           Show this help message

Examples:
    .\build.ps1                    # Full build
    .\build.ps1 -SkipInstall       # Build without reinstalling deps
    .\build.ps1 -ElectronOnly      # Only build electron app
"@
    exit 0
}

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot

Write-Host "=== Logsidian Build Script ===" -ForegroundColor Cyan
Write-Host "Project root: $ProjectRoot"

# Read required Node version from .nvmrc
$nvmrcPath = Join-Path $ProjectRoot ".nvmrc"
if (Test-Path $nvmrcPath) {
    $RequiredNodeVersion = (Get-Content $nvmrcPath -Raw).Trim()
    Write-Host "Required Node version (from .nvmrc): $RequiredNodeVersion"
} else {
    $RequiredNodeVersion = "22"
    Write-Host "No .nvmrc found, using default: $RequiredNodeVersion" -ForegroundColor Yellow
}

# Set up nvm for this session
$nvmDir = "$env:USERPROFILE\AppData\Local\nvm"
$nvmNodePath = "$nvmDir\v$RequiredNodeVersion"

# Find matching version directory
$matchingVersion = Get-ChildItem $nvmDir -Directory | Where-Object { $_.Name -like "v$RequiredNodeVersion*" } | Select-Object -First 1

if (-not $matchingVersion) {
    Write-Host "ERROR: Node version $RequiredNodeVersion not found in nvm" -ForegroundColor Red
    Write-Host "Available versions:"
    Get-ChildItem $nvmDir -Directory | ForEach-Object { Write-Host "  $($_.Name)" }
    exit 1
}

$nodeDir = $matchingVersion.FullName
Write-Host "Using Node from: $nodeDir" -ForegroundColor Green

# Prepend to PATH for this session
$env:PATH = "$nodeDir;$env:PATH"

# Force yarn to use cmd.exe instead of bash for script execution
# This avoids path corruption issues when running from X: drive
$env:SHELL = "cmd.exe"
$env:ComSpec = "C:\Windows\System32\cmd.exe"

# Verify node version
$actualVersion = & node --version
Write-Host "Active Node version: $actualVersion"

# Change to project root
Set-Location $ProjectRoot

# Step 1: Install dependencies
if (-not $SkipInstall) {
    Write-Host "`n=== Step 1: Installing root dependencies ===" -ForegroundColor Cyan
    # Skip postinstall to avoid nested yarn calls through bash
    & yarn install --ignore-scripts
    if ($LASTEXITCODE -ne 0) {
        Write-Host "yarn install failed" -ForegroundColor Red
        exit $LASTEXITCODE
    }

    # Manually run the nested installs that postinstall would do
    Write-Host "`n=== Step 1b: Installing packages/tldraw ===" -ForegroundColor Cyan
    Push-Location (Join-Path $ProjectRoot "packages/tldraw")
    & yarn install --ignore-scripts
    if ($LASTEXITCODE -ne 0) {
        Write-Host "packages/tldraw install failed" -ForegroundColor Red
        Pop-Location
        exit $LASTEXITCODE
    }

    # Build tldraw - run zx directly to avoid bash path corruption
    # zx is hoisted to packages/tldraw/node_modules, not apps/tldraw-logseq/node_modules
    $tldrawLogseqDir = Join-Path $ProjectRoot "packages/tldraw/apps/tldraw-logseq"
    $zxPath = Join-Path $ProjectRoot "packages/tldraw/node_modules/zx/build/cli.js"
    Push-Location $tldrawLogseqDir
    Write-Host "Running zx build.mjs directly from $tldrawLogseqDir..."
    & node $zxPath build.mjs
    if ($LASTEXITCODE -ne 0) {
        Write-Host "tldraw build failed" -ForegroundColor Red
        Pop-Location
        Pop-Location
        exit $LASTEXITCODE
    }
    Pop-Location
    Pop-Location

    Write-Host "`n=== Step 1c: Installing packages/ui ===" -ForegroundColor Cyan
    Push-Location (Join-Path $ProjectRoot "packages/ui")
    & yarn install --ignore-scripts
    if ($LASTEXITCODE -ne 0) {
        Write-Host "packages/ui install failed" -ForegroundColor Red
        Pop-Location
        exit $LASTEXITCODE
    }

    # Build ui - run parcel directly to avoid bash path corruption
    Write-Host "Removing .parcel-cache..."
    $parcelCache = Join-Path $PWD ".parcel-cache"
    if (Test-Path $parcelCache) { Remove-Item -Recurse -Force $parcelCache }

    # Find parcel - could be in packages/ui/node_modules or hoisted to root
    $parcelPath = Join-Path $PWD "node_modules/parcel/lib/bin.js"
    if (-not (Test-Path $parcelPath)) {
        $parcelPath = Join-Path $ProjectRoot "node_modules/parcel/lib/bin.js"
    }

    Write-Host "Building ui target with parcel at: $parcelPath"
    & node $parcelPath build --target ui
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ui build --target ui failed" -ForegroundColor Red
        Pop-Location
        exit $LASTEXITCODE
    }

    Write-Host "Building silkhq target..."
    & node $parcelPath build --target silkhq
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ui build --target silkhq failed" -ForegroundColor Red
        Pop-Location
        exit $LASTEXITCODE
    }
    Pop-Location
}

if ($ElectronOnly) {
    Write-Host "`n=== Building Electron only ===" -ForegroundColor Cyan
    & yarn cljs:release-electron
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "`n=== Build complete! ===" -ForegroundColor Green
    exit 0
}

# Helper function to find and run a node module binary
function Invoke-NodeBin {
    param([string]$BinName, [string[]]$Args)

    # Look in local node_modules/.bin first, then root
    $binPath = Join-Path $PWD "node_modules/.bin/$BinName.CMD"
    if (-not (Test-Path $binPath)) {
        $binPath = Join-Path $ProjectRoot "node_modules/.bin/$BinName.CMD"
    }
    if (-not (Test-Path $binPath)) {
        # Try finding the js file directly
        $binPath = Join-Path $PWD "node_modules/$BinName/bin/$BinName.js"
        if (-not (Test-Path $binPath)) {
            $binPath = Join-Path $ProjectRoot "node_modules/$BinName/bin/$BinName.js"
        }
        if (Test-Path $binPath) {
            & node $binPath @Args
            return $LASTEXITCODE
        }
    }

    if (Test-Path $binPath) {
        # Use cmd.exe to run .CMD files to avoid bash path corruption
        & cmd.exe /c "$binPath" @Args
        return $LASTEXITCODE
    }

    Write-Host "ERROR: Could not find $BinName" -ForegroundColor Red
    return 1
}

# Step 2: Gulp build (without CSS) + CSS build
# Run directly using node.exe to avoid yarn path corruption
Write-Host "`n=== Step 2: Gulp build ===" -ForegroundColor Cyan

$gulpPath = Join-Path $ProjectRoot "node_modules/gulp/bin/gulp.js"
$nodePath = Join-Path $nodeDir "node.exe"

# Set NODE_ENV for production
$env:NODE_ENV = "production"

# Run gulp buildNoCSS (avoids yarn css:build which has path corruption)
Write-Host "Running gulp buildNoCSS..."
& $nodePath $gulpPath buildNoCSS
if ($LASTEXITCODE -ne 0) {
    Write-Host "gulp buildNoCSS failed" -ForegroundColor Red
    exit $LASTEXITCODE
}

# Build CSS directly with postcss
Write-Host "`n=== Step 2b: Building CSS ===" -ForegroundColor Cyan
$postcssPath = Join-Path $ProjectRoot "node_modules/postcss-cli/index.js"
& $nodePath $postcssPath tailwind.all.css -o static/css/style.css --verbose --env production
if ($LASTEXITCODE -ne 0) {
    Write-Host "postcss build failed" -ForegroundColor Red
    exit $LASTEXITCODE
}
Write-Host "CSS build completed successfully" -ForegroundColor Green

# Step 3: Build ClojureScript for Electron
# This uses shadow-cljs which is a Clojure tool, not Node.js - should work
Write-Host "`n=== Step 3: Building ClojureScript ===" -ForegroundColor Cyan
& yarn cljs:release-electron
if ($LASTEXITCODE -ne 0) {
    Write-Host "cljs:release-electron failed" -ForegroundColor Red
    exit $LASTEXITCODE
}

# Step 4: Build webpack bundles - run webpack directly
Write-Host "`n=== Step 4: Building webpack bundles ===" -ForegroundColor Cyan
$webpackPath = Join-Path $ProjectRoot "node_modules/webpack/bin/webpack.js"
& node $webpackPath --config webpack.config.js
if ($LASTEXITCODE -ne 0) {
    Write-Host "webpack-app-build failed" -ForegroundColor Red
    exit $LASTEXITCODE
}

# Step 5: Install static dependencies and package
Write-Host "`n=== Step 5: Packaging Electron app ===" -ForegroundColor Cyan
Set-Location (Join-Path $ProjectRoot "static")

# Install with --ignore-scripts to avoid PATH issues with postinstall
& yarn install --ignore-scripts
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Step 5a: Copy pre-built native binaries if they exist
# This handles cases where npm install doesn't provide working binaries
$nativeBinDir = Join-Path $ProjectRoot "native-binaries/win32-x64"
if (Test-Path $nativeBinDir) {
    Write-Host "`n=== Step 5a: Installing pre-built native binaries ===" -ForegroundColor Cyan

    # rsapi - copy to @logseq/rsapi package
    $rsapiSrc = Join-Path $nativeBinDir "rsapi.win32-x64-msvc.node"
    $rsapiDest = Join-Path $PWD "node_modules/@logseq/rsapi"
    if ((Test-Path $rsapiSrc) -and (Test-Path $rsapiDest)) {
        Write-Host "Copying rsapi native binary..."
        Copy-Item $rsapiSrc -Destination $rsapiDest -Force
    }

    # keytar - copy to keytar package
    $keytarSrc = Join-Path $nativeBinDir "keytar.node"
    $keytarDest = Join-Path $PWD "node_modules/keytar/build/Release"
    if ((Test-Path $keytarSrc) -and (Test-Path $keytarDest)) {
        Write-Host "Copying keytar native binary..."
        Copy-Item $keytarSrc -Destination $keytarDest -Force
    } elseif (Test-Path $keytarSrc) {
        # Create the directory if it doesn't exist
        $keytarBuildDir = Join-Path $PWD "node_modules/keytar/build/Release"
        New-Item -ItemType Directory -Force -Path $keytarBuildDir | Out-Null
        Copy-Item $keytarSrc -Destination $keytarBuildDir -Force
    }

    # electron-deeplink - copy to electron-deeplink package
    $deeplinkSrc = Join-Path $nativeBinDir "electron-deeplink.node"
    $deeplinkDest = Join-Path $PWD "node_modules/electron-deeplink/build/Release"
    if ((Test-Path $deeplinkSrc) -and (Test-Path $deeplinkDest)) {
        Write-Host "Copying electron-deeplink native binary..."
        Copy-Item $deeplinkSrc -Destination $deeplinkDest -Force
    } elseif (Test-Path $deeplinkSrc) {
        # Create the directory if it doesn't exist
        $deeplinkBuildDir = Join-Path $PWD "node_modules/electron-deeplink/build/Release"
        New-Item -ItemType Directory -Force -Path $deeplinkBuildDir | Out-Null
        Copy-Item $deeplinkSrc -Destination $deeplinkBuildDir -Force
    }

    Write-Host "Native binaries installed" -ForegroundColor Green
} else {
    Write-Host "Note: native-binaries/win32-x64 not found, relying on npm packages" -ForegroundColor Yellow
}

# Verify electron-forge is installed (yarn linking can sometimes fail in isolated processes)
$forgePath = Join-Path $PWD "node_modules/@electron-forge/cli/dist/electron-forge.js"
if (-not (Test-Path $forgePath)) {
    Write-Host "electron-forge not found after yarn install, retrying..." -ForegroundColor Yellow
    # Delete node_modules and reinstall to force fresh linking
    $nodeModulesPath = Join-Path $PWD "node_modules"
    if (Test-Path $nodeModulesPath) {
        Write-Host "Removing node_modules for clean install..."
        Remove-Item -Recurse -Force $nodeModulesPath
    }
    # Also remove yarn.lock to force fresh resolution
    $yarnLockPath = Join-Path $PWD "yarn.lock"
    if (Test-Path $yarnLockPath) {
        Remove-Item -Force $yarnLockPath
    }
    & yarn install --ignore-scripts
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

# Manually run install-app-deps (the postinstall script)
# This is from electron-builder and rebuilds native modules
$installAppDepsPath = Join-Path $PWD "node_modules/electron-builder/out/cli/install-app-deps.js"
if (-not (Test-Path $installAppDepsPath)) {
    # Try alternate location (app-builder-lib)
    $installAppDepsPath = Join-Path $PWD "node_modules/app-builder-lib/out/cli/install-app-deps.js"
}
if (Test-Path $installAppDepsPath) {
    Write-Host "Running install-app-deps manually..."
    & node $installAppDepsPath
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Warning: install-app-deps failed, continuing anyway..." -ForegroundColor Yellow
    }
} else {
    Write-Host "Note: install-app-deps not found, skipping native module rebuild" -ForegroundColor Yellow
}

# Find electron-forge CLI
$forgePath = Join-Path $PWD "node_modules/@electron-forge/cli/dist/electron-forge.js"
if (-not (Test-Path $forgePath)) {
    # Try alternate path (older versions)
    $forgePath = Join-Path $PWD "node_modules/.bin/electron-forge"
}
if (-not (Test-Path $forgePath)) {
    Write-Host "ERROR: Could not find electron-forge CLI" -ForegroundColor Red
    Write-Host "Searched: node_modules/@electron-forge/cli/dist/electron-forge.js"
    Write-Host "Searched: node_modules/.bin/electron-forge"
    Write-Host "Try running 'yarn install' manually in the static directory" -ForegroundColor Yellow
    exit 1
}

Write-Host "Running electron-forge make from: $forgePath"
& node $forgePath make
$forgeExitCode = $LASTEXITCODE

# Check for WiX-specific errors and treat them as warnings (WiX toolkit often not installed)
if ($forgeExitCode -ne 0) {
    # Check if zip was created despite error (WiX failure is common and non-fatal)
    $zipFiles = Get-ChildItem -Path (Join-Path $PWD "out/make/zip") -Filter "*.zip" -Recurse -ErrorAction SilentlyContinue
    if ($zipFiles) {
        Write-Host "Warning: electron-forge make had errors (likely WiX), but zip was created" -ForegroundColor Yellow
    } else {
        Write-Host "electron:make failed" -ForegroundColor Red
        exit $forgeExitCode
    }
}

Write-Host "`n=== Build complete! ===" -ForegroundColor Green

# Find and display the output
$outDir = Join-Path $PWD "out"
$appDir = Get-ChildItem -Path $outDir -Directory -Filter "Logseq-win32-*" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($appDir) {
    Write-Host "App: $($appDir.FullName)\Logseq.exe"

    # Verify/copy native binaries to the final app
    $appResourcesDir = Join-Path $appDir.FullName "resources/app/node_modules"
    $nativeBinDir = Join-Path $ProjectRoot "native-binaries/win32-x64"

    if ((Test-Path $nativeBinDir) -and (Test-Path $appResourcesDir)) {
        Write-Host "`n=== Verifying native binaries in final app ===" -ForegroundColor Cyan

        # rsapi
        $rsapiDest = Join-Path $appResourcesDir "@logseq/rsapi/rsapi.win32-x64-msvc.node"
        if (-not (Test-Path $rsapiDest)) {
            $rsapiSrc = Join-Path $nativeBinDir "rsapi.win32-x64-msvc.node"
            if (Test-Path $rsapiSrc) {
                Write-Host "Copying rsapi to final app..."
                $rsapiDestDir = Join-Path $appResourcesDir "@logseq/rsapi"
                if (-not (Test-Path $rsapiDestDir)) { New-Item -ItemType Directory -Force -Path $rsapiDestDir | Out-Null }
                Copy-Item $rsapiSrc -Destination $rsapiDestDir -Force
            }
        } else {
            Write-Host "rsapi binary present in final app" -ForegroundColor Green
        }

        # keytar
        $keytarDest = Join-Path $appResourcesDir "keytar/build/Release/keytar.node"
        if (-not (Test-Path $keytarDest)) {
            $keytarSrc = Join-Path $nativeBinDir "keytar.node"
            if (Test-Path $keytarSrc) {
                Write-Host "Copying keytar to final app..."
                $keytarDestDir = Join-Path $appResourcesDir "keytar/build/Release"
                if (-not (Test-Path $keytarDestDir)) { New-Item -ItemType Directory -Force -Path $keytarDestDir | Out-Null }
                Copy-Item $keytarSrc -Destination $keytarDestDir -Force
            }
        } else {
            Write-Host "keytar binary present in final app" -ForegroundColor Green
        }

        # electron-deeplink
        $deeplinkDest = Join-Path $appResourcesDir "electron-deeplink/build/Release/electron-deeplink.node"
        if (-not (Test-Path $deeplinkDest)) {
            $deeplinkSrc = Join-Path $nativeBinDir "electron-deeplink.node"
            if (Test-Path $deeplinkSrc) {
                Write-Host "Copying electron-deeplink to final app..."
                $deeplinkDestDir = Join-Path $appResourcesDir "electron-deeplink/build/Release"
                if (-not (Test-Path $deeplinkDestDir)) { New-Item -ItemType Directory -Force -Path $deeplinkDestDir | Out-Null }
                Copy-Item $deeplinkSrc -Destination $deeplinkDestDir -Force
            }
        } else {
            Write-Host "electron-deeplink binary present in final app" -ForegroundColor Green
        }
    }
}
$zipFile = Get-ChildItem -Path $outDir -Filter "*.zip" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if ($zipFile) {
    Write-Host "Zip: $($zipFile.FullName)"
}
