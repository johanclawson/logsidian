# Logsidian Dev Server Script
# Starts the web dev server with CSS watch and ClojureScript watch
# Avoids yarn path corruption by running tools directly

param(
    [switch]$NoCss,
    [switch]$NoCljs,
    [switch]$CssOnly,
    [switch]$Help
)

if ($Help) {
    Write-Host @"
Logsidian Dev Server Script

Usage: .\dev-server.ps1 [options]

Options:
    -NoCss      Skip CSS watch (useful if CSS is already built)
    -NoCljs     Skip ClojureScript watch
    -CssOnly    Only build/watch CSS, don't start ClojureScript
    -Help       Show this help message

The script starts:
    - PostCSS watch for CSS at static/css/style.css
    - Shadow-cljs watch for :app, :electron, :publishing builds

Dev servers:
    - http://localhost:3001 - Main application
    - http://localhost:3002 - Secondary server
    - http://localhost:9630 - Shadow-cljs dashboard
    - nREPL on port 8701
"@
    exit 0
}

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot

Write-Host "=== Logsidian Dev Server ===" -ForegroundColor Cyan
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
$env:SHELL = "cmd.exe"
$env:ComSpec = "C:\Windows\System32\cmd.exe"

# Verify node version
$actualVersion = & node --version
Write-Host "Active Node version: $actualVersion"

# Change to project root
Set-Location $ProjectRoot

# Build CSS first (one-time build to ensure it exists)
Write-Host "`n=== Building CSS ===" -ForegroundColor Cyan
$postcssPath = Join-Path $ProjectRoot "node_modules/postcss-cli/index.js"
if (-not (Test-Path $postcssPath)) {
    Write-Host "ERROR: postcss-cli not found. Run 'yarn install' first." -ForegroundColor Red
    exit 1
}

# One-time CSS build
Write-Host "Building CSS with PostCSS..."
& node $postcssPath tailwind.all.css -o static/css/style.css --verbose
if ($LASTEXITCODE -ne 0) {
    Write-Host "CSS build failed" -ForegroundColor Red
    exit $LASTEXITCODE
}
Write-Host "CSS built: static/css/style.css" -ForegroundColor Green

if ($CssOnly) {
    Write-Host "`n=== Starting CSS watch only ===" -ForegroundColor Cyan
    & node $postcssPath tailwind.all.css -o static/css/style.css --watch --verbose
    exit 0
}

if ($NoCljs) {
    Write-Host "`nCSS built successfully. Exiting (--NoCljs specified)." -ForegroundColor Green
    exit 0
}

# Start ClojureScript watch
# Note: We use clojure directly since it doesn't have the path corruption issue
Write-Host "`n=== Starting ClojureScript watch ===" -ForegroundColor Cyan
Write-Host "Starting shadow-cljs watch for :app :electron :publishing builds..."
Write-Host ""
Write-Host "Dev servers will be available at:" -ForegroundColor Yellow
Write-Host "  http://localhost:3001 - Main application"
Write-Host "  http://localhost:3002 - Secondary server"
Write-Host "  http://localhost:9630 - Shadow-cljs dashboard"
Write-Host "  nREPL on port 8701"
Write-Host ""
Write-Host "Press Ctrl+C to stop" -ForegroundColor Yellow
Write-Host ""

# Start shadow-cljs watch (this is a blocking call)
& clojure -M:cljs watch app electron publishing
