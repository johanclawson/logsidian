# Build minimal JRE for sidecar testing
# Usage: pwsh -NoProfile -File scripts/build-jre-test.ps1

$ErrorActionPreference = "Stop"

$modules = "java.base,java.logging,java.sql,java.naming,java.management"

# Use JAVA_HOME if set, otherwise try to find JDK 21+
$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    # Try common locations
    $candidates = @(
        "C:\Users\johan\scoop\apps\openjdk\current",
        "C:\Program Files\Microsoft\jdk-21*",
        "C:\Program Files\Eclipse Adoptium\jdk-21*"
    )
    foreach ($pattern in $candidates) {
        $found = Get-Item $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) {
            $javaHome = $found.FullName
            break
        }
    }
}

if (-not $javaHome) {
    throw "Could not find JAVA_HOME. Please set JAVA_HOME to a JDK 21+ installation."
}

$jlink = Join-Path $javaHome "bin\jlink.exe"
$jmods = Join-Path $javaHome "jmods"

Write-Host "Using JDK from: $javaHome"
Write-Host "jlink: $jlink"
Write-Host "jmods: $jmods"
Write-Host "Building minimal JRE with modules: $modules"

# Verify jlink exists
if (-not (Test-Path $jlink)) {
    throw "jlink not found at: $jlink"
}

# Remove existing jre-test if present
if (Test-Path "jre-test") {
    Write-Host "Removing existing jre-test directory..."
    Remove-Item -Recurse -Force "jre-test"
}

# Build the minimal JRE using jlink from the same JDK
& $jlink --module-path $jmods `
         --add-modules $modules `
         --output jre-test `
         --strip-debug `
         --compress=2 `
         --no-header-files `
         --no-man-pages

if ($LASTEXITCODE -ne 0) {
    throw "jlink failed with exit code $LASTEXITCODE"
}

# Calculate and display size
$size = (Get-ChildItem -Recurse jre-test | Measure-Object -Sum Length).Sum
$sizeMB = [math]::Round($size/1MB, 2)
Write-Host "`nJRE built successfully!"
Write-Host "Size: $sizeMB MB"

# Verify java.exe exists
if (Test-Path "jre-test/bin/java.exe") {
    Write-Host "java.exe: Found"
    & ./jre-test/bin/java.exe -version
} else {
    throw "java.exe not found in JRE"
}
