# Test that sidecar runs with the minimal JRE
# Usage: pwsh -NoProfile -File scripts/test-sidecar-jre.ps1

$ErrorActionPreference = "Stop"

$jreJava = "jre-test\bin\java.exe"
$sidecarJar = "sidecar\target\logsidian-sidecar.jar"
$port = 47632

Write-Host "Testing sidecar with minimal JRE..."
Write-Host "JRE: $jreJava"
Write-Host "JAR: $sidecarJar"

# Verify files exist
if (-not (Test-Path $jreJava)) {
    throw "JRE java.exe not found: $jreJava"
}
if (-not (Test-Path $sidecarJar)) {
    throw "Sidecar JAR not found: $sidecarJar"
}

# Kill any existing sidecar on the port
$existing = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Killing existing process on port $port..."
    Stop-Process -Id $existing.OwningProcess -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

# Start sidecar in background
Write-Host "Starting sidecar..."
$proc = Start-Process -FilePath $jreJava `
                      -ArgumentList "-jar", $sidecarJar `
                      -NoNewWindow `
                      -PassThru `
                      -RedirectStandardOutput "sidecar-stdout.log" `
                      -RedirectStandardError "sidecar-stderr.log"

Write-Host "Sidecar PID: $($proc.Id)"

# Wait for startup
Write-Host "Waiting for sidecar to start..."
$maxWait = 15
$waited = 0
$connected = $false

while ($waited -lt $maxWait -and -not $connected) {
    Start-Sleep -Seconds 1
    $waited++

    try {
        $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
        if ($conn) {
            $connected = $true
            Write-Host "Sidecar is listening on port $port!"
        }
    } catch {
        # Port not yet listening
    }

    if (-not $connected) {
        Write-Host "Waiting... ($waited/$maxWait)"
    }
}

# Show logs
Write-Host "`n=== Sidecar stdout ==="
if (Test-Path "sidecar-stdout.log") {
    Get-Content "sidecar-stdout.log" -Tail 20
}

Write-Host "`n=== Sidecar stderr ==="
if (Test-Path "sidecar-stderr.log") {
    Get-Content "sidecar-stderr.log" -Tail 20
}

# Kill sidecar
Write-Host "`nStopping sidecar..."
Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue

# Report result
if ($connected) {
    Write-Host "`n[PASS] Sidecar runs successfully with minimal JRE!"
    exit 0
} else {
    Write-Host "`n[FAIL] Sidecar failed to start"
    exit 1
}
