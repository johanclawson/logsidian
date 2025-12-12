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

# Note: We need full install here (no --ignore-scripts) because electron-forge
# needs its postinstall scripts to set up properly
& yarn install
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Find electron-forge CLI - it may be in different locations depending on yarn version
$forgePath = Join-Path $PWD "node_modules/@electron-forge/cli/dist/electron-forge.js"
if (-not (Test-Path $forgePath)) {
    # Try alternate path (older versions)
    $forgePath = Join-Path $PWD "node_modules/.bin/electron-forge"
}
if (-not (Test-Path $forgePath)) {
    Write-Host "ERROR: Could not find electron-forge CLI" -ForegroundColor Red
    Write-Host "Searched: node_modules/@electron-forge/cli/dist/electron-forge.js"
    Write-Host "Searched: node_modules/.bin/electron-forge"
    exit 1
}

Write-Host "Running electron-forge make from: $forgePath"
& node $forgePath make
if ($LASTEXITCODE -ne 0) {
    Write-Host "electron:make failed" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "`n=== Build complete! ===" -ForegroundColor Green
Write-Host "Output: static\out\Logseq-win32-x64\Logseq.exe"
