param(
    [string]$Output = "diag-logs/device-screen.png",
    [switch]$Open
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$projectRoot = Split-Path -Parent $root
$outputPath = if ([System.IO.Path]::IsPathRooted($Output)) {
    $Output
} else {
    Join-Path $projectRoot $Output
}

$outputDir = Split-Path -Parent $outputPath
if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
}

cmd /c "adb exec-out screencap -p > ""$outputPath"""
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $outputPath) -or (Get-Item $outputPath).Length -le 0) {
    throw "Failed to capture screen via adb."
}

Write-Host "Saved screenshot to $outputPath"

if ($Open) {
    Start-Process $outputPath
}
