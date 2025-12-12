<#
.SYNOPSIS
    TDD Loop for Logsidian sidecar development.

.DESCRIPTION
    Runs E2E tests, collects errors, and formats reports for Claude Code.
    Supports watch mode for continuous testing during development.

.PARAMETER TestFilter
    Filter tests by metadata tag (default: sidecar)

.PARAMETER WatchMode
    Enable watch mode - continuously runs tests on file changes

.PARAMETER SidecarOnly
    Only run sidecar-related tests

.PARAMETER SkipSidecarStart
    Don't attempt to start the sidecar (assume already running)

.PARAMETER Help
    Show this help message

.EXAMPLE
    .\tdd-loop.ps1
    # Runs all sidecar tests once

.EXAMPLE
    .\tdd-loop.ps1 -WatchMode
    # Runs tests in watch mode

.EXAMPLE
    .\tdd-loop.ps1 -TestFilter smoke
    # Runs only smoke tests
#>

param(
    [string]$TestFilter = "sidecar",
    [switch]$WatchMode,
    [switch]$SidecarOnly,
    [switch]$SkipSidecarStart,
    [switch]$Help
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# Resolve to repo root if running from scripts folder
if (-not (Test-Path "$RepoRoot/clj-e2e")) {
    $RepoRoot = Split-Path -Parent $PSScriptRoot
}
if (-not (Test-Path "$RepoRoot/clj-e2e")) {
    $RepoRoot = $PSScriptRoot
}

if ($Help) {
    Get-Help $MyInvocation.MyCommand.Path -Full
    exit 0
}

Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  Logsidian TDD Loop" -ForegroundColor Cyan
Write-Host "  Test Filter: $TestFilter" -ForegroundColor Cyan
Write-Host "  Watch Mode: $WatchMode" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

# =============================================================================
# Sidecar Management
# =============================================================================

function Get-SidecarProcess {
    Get-Process -Name "java" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*logsidian-sidecar*" }
}

function Start-Sidecar {
    $sidecarJar = "$RepoRoot/sidecar/target/logsidian-sidecar.jar"

    if (-not (Test-Path $sidecarJar)) {
        Write-Host "Sidecar JAR not found at: $sidecarJar" -ForegroundColor Yellow
        Write-Host "Building sidecar..." -ForegroundColor Yellow
        Push-Location "$RepoRoot/sidecar"
        & clj -T:build uberjar
        Pop-Location
    }

    if (-not (Test-Path $sidecarJar)) {
        Write-Host "ERROR: Failed to build sidecar JAR" -ForegroundColor Red
        return $false
    }

    $existingProcess = Get-SidecarProcess
    if ($existingProcess) {
        Write-Host "Sidecar already running (PID: $($existingProcess.Id))" -ForegroundColor Green
        return $true
    }

    Write-Host "Starting sidecar server..." -ForegroundColor Yellow

    # Create logs directory
    $logDir = "$RepoRoot/sidecar/logs"
    if (-not (Test-Path $logDir)) {
        New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    }

    $logFile = "$logDir/sidecar.log"

    Start-Process -FilePath "java" `
        -ArgumentList "-jar", $sidecarJar `
        -WorkingDirectory "$RepoRoot/sidecar" `
        -RedirectStandardOutput $logFile `
        -RedirectStandardError "$logDir/sidecar-error.log" `
        -NoNewWindow

    # Wait for sidecar to start
    Start-Sleep -Seconds 3

    $process = Get-SidecarProcess
    if ($process) {
        Write-Host "Sidecar started (PID: $($process.Id))" -ForegroundColor Green
        return $true
    } else {
        Write-Host "WARNING: Sidecar may not have started correctly" -ForegroundColor Yellow
        return $false
    }
}

function Stop-Sidecar {
    $process = Get-SidecarProcess
    if ($process) {
        Write-Host "Stopping sidecar (PID: $($process.Id))..." -ForegroundColor Yellow
        Stop-Process -Id $process.Id -Force
        Write-Host "Sidecar stopped" -ForegroundColor Green
    }
}

# =============================================================================
# Test Execution
# =============================================================================

function Run-E2ETests {
    param([string]$Filter)

    Write-Host ""
    Write-Host "Running E2E tests with filter: $Filter" -ForegroundColor Cyan
    Write-Host "--------------------------------------------" -ForegroundColor Gray

    Push-Location "$RepoRoot/clj-e2e"

    try {
        # Run tests with filter
        if ($Filter -eq "all") {
            $result = & clj -M:test 2>&1
        } else {
            $result = & clj -M:test -i $Filter 2>&1
        }

        $exitCode = $LASTEXITCODE

        # Output test results
        $result | ForEach-Object { Write-Host $_ }

        return @{
            Output = $result -join "`n"
            ExitCode = $exitCode
        }
    }
    finally {
        Pop-Location
    }
}

# =============================================================================
# Report Parsing and Formatting
# =============================================================================

function Parse-TestFailures {
    param([string]$Output)

    $failures = @()
    $lines = $Output -split "`n"
    $currentFailure = $null

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]

        if ($line -match "FAIL in \(([^)]+)\)") {
            if ($currentFailure) {
                $failures += $currentFailure
            }
            $currentFailure = @{
                TestName = $matches[1]
                Lines = @()
            }
        }
        elseif ($line -match "ERROR in \(([^)]+)\)") {
            if ($currentFailure) {
                $failures += $currentFailure
            }
            $currentFailure = @{
                TestName = $matches[1]
                Type = "error"
                Lines = @()
            }
        }
        elseif ($currentFailure -and $line.Trim()) {
            $currentFailure.Lines += $line
        }
        elseif ($currentFailure -and -not $line.Trim() -and $currentFailure.Lines.Count -gt 0) {
            $failures += $currentFailure
            $currentFailure = $null
        }
    }

    if ($currentFailure) {
        $failures += $currentFailure
    }

    return $failures
}

function Format-ReportForClaude {
    param([array]$Failures, [string]$RawOutput)

    $report = @"
# TDD Test Failure Report

**Generated:** $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
**Test Filter:** $TestFilter
**Total Failures:** $($Failures.Count)

## Quick Fix Guide

1. Read the failure details below
2. Fix the code based on error messages
3. Re-run: ``.\scripts\tdd-loop.ps1``

---

"@

    foreach ($failure in $Failures) {
        $type = if ($failure.Type -eq "error") { "ERROR" } else { "FAIL" }
        $report += @"

## $type`: $($failure.TestName)

``````
$($failure.Lines -join "`n")
``````

"@
    }

    # Add console errors if found in output
    if ($RawOutput -match "Console errors detected") {
        $report += @"

## Console Errors Detected

Check the latest error report for console error details:
- ``clj-e2e/error-reports/latest.md``
- ``clj-e2e/error-reports/latest.edn``

"@
    }

    $report += @"

---

## Files to Check

Based on sidecar tests, common files to modify:
- ``sidecar/src/logseq/sidecar/server.clj`` - Add/fix operation handlers
- ``sidecar/src/logseq/sidecar/protocol.clj`` - Fix Transit serialization
- ``src/electron/electron/sidecar.cljs`` - Fix Electron integration
- ``src/main/frontend/sidecar/*.cljs`` - Fix frontend client code

## Run Tests Again

``````powershell
.\scripts\tdd-loop.ps1
``````

"@

    return $report
}

# =============================================================================
# Main Loop
# =============================================================================

function Run-TDDLoop {
    # Start sidecar if needed
    if (-not $SkipSidecarStart) {
        $sidecarStarted = Start-Sidecar
        if (-not $sidecarStarted) {
            Write-Host "WARNING: Proceeding without sidecar - tests may fail" -ForegroundColor Yellow
        }
    }

    do {
        $result = Run-E2ETests -Filter $TestFilter

        if ($result.ExitCode -ne 0) {
            Write-Host ""
            Write-Host "================================================" -ForegroundColor Red
            Write-Host "  TESTS FAILED" -ForegroundColor Red
            Write-Host "================================================" -ForegroundColor Red

            $failures = Parse-TestFailures -Output $result.Output

            if ($failures.Count -gt 0) {
                $report = Format-ReportForClaude -Failures $failures -RawOutput $result.Output

                # Write report
                $reportDir = "$RepoRoot/clj-e2e/error-reports"
                if (-not (Test-Path $reportDir)) {
                    New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
                }

                $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
                $reportPath = "$reportDir/tdd-report-$timestamp.md"
                $latestPath = "$reportDir/latest-tdd.md"

                $report | Out-File -FilePath $reportPath -Encoding utf8
                $report | Out-File -FilePath $latestPath -Encoding utf8

                Write-Host ""
                Write-Host "Error report written to:" -ForegroundColor Yellow
                Write-Host "  $reportPath" -ForegroundColor White
                Write-Host "  $latestPath" -ForegroundColor White
                Write-Host ""
                Write-Host "View report:" -ForegroundColor Cyan
                Write-Host "  cat clj-e2e/error-reports/latest-tdd.md" -ForegroundColor White
            }
        }
        else {
            Write-Host ""
            Write-Host "================================================" -ForegroundColor Green
            Write-Host "  ALL TESTS PASSED!" -ForegroundColor Green
            Write-Host "================================================" -ForegroundColor Green
        }

        if ($WatchMode) {
            Write-Host ""
            Write-Host "Waiting for file changes... (Ctrl+C to exit)" -ForegroundColor Cyan
            Start-Sleep -Seconds 5
        }

    } while ($WatchMode)

    return $result.ExitCode
}

# =============================================================================
# Entry Point
# =============================================================================

try {
    $exitCode = Run-TDDLoop
    exit $exitCode
}
catch {
    Write-Host "ERROR: $_" -ForegroundColor Red
    Write-Host $_.ScriptStackTrace -ForegroundColor Red
    exit 1
}
