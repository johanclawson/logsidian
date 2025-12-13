# Run-Build: Isolated build script runner
# Spawns build.ps1 in a completely isolated PowerShell process
# Output is written to a log file and tailed in real-time
#
# Usage from Claude Code bash:
#   pwsh -NoProfile -File scripts/run-build.ps1 [-SkipInstall] [-ElectronOnly]

param(
    [switch]$SkipInstall,
    [switch]$ElectronOnly,
    [string]$LogFile = "build.log"
)

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LogPath = Join-Path $ProjectRoot $LogFile
$BuildScript = Join-Path $PSScriptRoot "build.ps1"

# Build arguments string for the inner script
$innerArgs = ""
if ($SkipInstall) { $innerArgs += " -SkipInstall" }
if ($ElectronOnly) { $innerArgs += " -ElectronOnly" }

Write-Host "=== Isolated Build Runner ===" -ForegroundColor Cyan
Write-Host "Project: $ProjectRoot"
Write-Host "Log file: $LogPath"
Write-Host "Args: $innerArgs"
Write-Host ""

# Clear previous log
"" | Out-File $LogPath -Encoding utf8

# Create a wrapper script that will run in the clean process
# This script clears bash-polluted environment and runs the build
$wrapperScript = @"
`$ErrorActionPreference = 'Stop'

# Clear bash/cygwin pollution from environment
`$env:SHELL = 'cmd.exe'
`$env:ComSpec = 'C:\Windows\System32\cmd.exe'
Remove-Item Env:BASH -ErrorAction SilentlyContinue
Remove-Item Env:BASH_ENV -ErrorAction SilentlyContinue
Remove-Item Env:MSYSTEM -ErrorAction SilentlyContinue
Remove-Item Env:CYGPATH -ErrorAction SilentlyContinue
Remove-Item Env:TERM -ErrorAction SilentlyContinue

# Run the actual build script
& '$BuildScript'$innerArgs
exit `$LASTEXITCODE
"@

$wrapperPath = Join-Path $env:TEMP "logsidian-build-wrapper.ps1"
$wrapperScript | Out-File $wrapperPath -Encoding utf8

Write-Host "Starting isolated build..." -ForegroundColor Yellow
Write-Host "Wrapper: $wrapperPath"
Write-Host ""

# Start the process - use cmd.exe as intermediate to fully break from bash environment
$proc = Start-Process -FilePath "cmd.exe" `
    -ArgumentList "/c", "pwsh -NoProfile -ExecutionPolicy Bypass -File `"$wrapperPath`" > `"$LogPath`" 2>&1" `
    -WorkingDirectory $ProjectRoot `
    -PassThru `
    -NoNewWindow

# Tail the log file while the process runs
$lastSize = 0
$lastLines = 0

Write-Host "=== Build Output ===" -ForegroundColor Cyan

while (-not $proc.HasExited) {
    Start-Sleep -Milliseconds 500

    if (Test-Path $LogPath) {
        $content = Get-Content $LogPath -Raw -ErrorAction SilentlyContinue
        if ($content -and $content.Length -gt $lastSize) {
            # Print new content
            $newContent = $content.Substring($lastSize)
            Write-Host $newContent -NoNewline
            $lastSize = $content.Length
        }
    }
}

# Final read to catch any remaining output
Start-Sleep -Milliseconds 500
if (Test-Path $LogPath) {
    $content = Get-Content $LogPath -Raw -ErrorAction SilentlyContinue
    if ($content -and $content.Length -gt $lastSize) {
        $newContent = $content.Substring($lastSize)
        Write-Host $newContent -NoNewline
    }
}

$exitCode = $proc.ExitCode

# Cleanup
Remove-Item $wrapperPath -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "=== Build Finished ===" -ForegroundColor $(if ($exitCode -eq 0) { "Green" } else { "Red" })
Write-Host "Exit code: $exitCode"
Write-Host "Full log: $LogPath"

exit $exitCode
