# Fetches EasyTier 2.6.0 Magisk Android binaries into jniLibs (executable on Android 10+).
param(
    [string]$Version = "2.6.0",
    [string]$Abi = "arm64-v8a"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$jni = Join-Path $root "app\src\main\jniLibs\$Abi"
$assets = Join-Path $root "app\src\main\assets\app_runtime\easytier\$Abi"
$cache = Join-Path $env:TEMP "easytier-magisk"
$zip = Join-Path $cache "Easytier-Magisk-v$Version.zip"
$url = "https://github.com/EasyTier/EasyTier/releases/download/v$Version/Easytier-Magisk-v$Version.zip"

New-Item -ItemType Directory -Force -Path $cache, $jni, $assets | Out-Null
if (-not (Test-Path $zip)) {
    Write-Host "Downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
}
Expand-Archive -Force -Path $zip -DestinationPath (Join-Path $cache "out")
$src = Join-Path $cache "out"
Copy-Item -Force (Join-Path $src "easytier-core") (Join-Path $jni "libeasytier_core.so")
Copy-Item -Force (Join-Path $src "easytier-cli") (Join-Path $jni "libeasytier_cli.so")
# Keep assets copy for reference / older tooling; runtime uses jniLibs only.
Copy-Item -Force (Join-Path $src "easytier-core") (Join-Path $assets "easytier-core")
Copy-Item -Force (Join-Path $src "easytier-cli") (Join-Path $assets "easytier-cli")
Write-Host "jniLibs:"
Get-ChildItem $jni -Filter "libeasytier*" | Format-Table Name, Length
Write-Host "assets:"
Get-ChildItem $assets | Format-Table Name, Length
