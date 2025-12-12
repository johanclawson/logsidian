# Package Electron app - run from static/ directory
$ErrorActionPreference = "Stop"

Write-Host "=== Installing static dependencies ===" -ForegroundColor Cyan

# Set Node version
$nvmDir = "C:\Users\johan\AppData\Local\nvm\v22.21.0"
$env:PATH = "$nvmDir;$env:PATH"
Write-Host "Using Node: $(node --version)"

# Install dependencies (full install, electron-forge needs its scripts)
yarn install
if ($LASTEXITCODE -ne 0) {
    Write-Host "yarn install failed" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit $LASTEXITCODE
}

Write-Host "`n=== Running electron-forge make ===" -ForegroundColor Cyan
yarn electron:make
if ($LASTEXITCODE -ne 0) {
    Write-Host "electron:make failed" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit $LASTEXITCODE
}

Write-Host "`n=== Build complete! ===" -ForegroundColor Green
Write-Host "Output: static\out\Logseq-win32-x64\Logseq.exe"
Read-Host "Press Enter to exit"
