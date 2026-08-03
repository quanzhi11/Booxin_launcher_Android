# Booxin Launcher - APK packaging script (PowerShell)
#
# Usage:
#   .\build-apk.ps1              # debug
#   .\build-apk.ps1 -Type debug
#   .\build-apk.ps1 -Type release
#   .\build-apk.ps1 -Clean

param(
    [ValidateSet("debug", "release")]
    [string]$Type = "debug",
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

Write-Host ""
Write-Host "========================================"
Write-Host "  Booxin Launcher APK Builder"
Write-Host "  Type: $Type"
Write-Host "========================================"
Write-Host ""

if (-not (Test-Path ".\gradlew.bat")) {
    throw "gradlew.bat not found. Run from project root."
}

if (-not (Test-Path ".\local.properties")) {
    if (Test-Path "D:\Android\Sdk") {
        "sdk.dir=D:/Android/Sdk" | Set-Content -Path ".\local.properties" -Encoding ASCII
        Write-Host "[INFO] Created local.properties -> D:/Android/Sdk"
    } else {
        throw "local.properties missing and Android SDK not found."
    }
}

if ($Clean) {
    Write-Host "[1/3] Cleaning..."
    & .\gradlew.bat clean --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Clean failed." }
} else {
    Write-Host "[1/3] Skip clean (pass -Clean to force)"
}

Write-Host "[2/3] Building $Type APK..."
if ($Type -eq "release") {
    & .\gradlew.bat :app:assembleRelease --no-daemon
} else {
    & .\gradlew.bat :app:assembleDebug --no-daemon
}
if ($LASTEXITCODE -ne 0) { throw "Build failed." }

$src = if ($Type -eq "release") {
    $candidates = @(
        "app\build\outputs\apk\release\app-release-unsigned.apk",
        "app\build\outputs\apk\release\app-release.apk"
    )
    $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
} else {
    "app\build\outputs\apk\debug\app-debug.apk"
}

if (-not $src -or -not (Test-Path $src)) {
    throw "APK not found for type=$Type"
}

$versionMatch = Select-String -Path "app\build.gradle.kts" -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
$version = if ($versionMatch) { $versionMatch.Matches[0].Groups[1].Value } else { "0.0.0" }
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"

New-Item -ItemType Directory -Force -Path "dist" | Out-Null
$outName = "BooxinLauncher-$version-$Type-$stamp.apk"
$outApk = Join-Path "dist" $outName
$outLatest = Join-Path "dist" "BooxinLauncher-$Type-latest.apk"

Copy-Item -Path $src -Destination $outApk -Force
Copy-Item -Path $src -Destination $outLatest -Force

$sizeKb = [math]::Round((Get-Item $outApk).Length / 1KB)

Write-Host ""
Write-Host "[3/3] Done."
Write-Host "----------------------------------------"
Write-Host "  Version : $version"
Write-Host "  Source  : $src"
Write-Host "  Output  : $outApk"
Write-Host "  Latest  : $outLatest"
Write-Host "  Size    : $sizeKb KB"
Write-Host "----------------------------------------"
Write-Host ""

Invoke-Item "dist"
