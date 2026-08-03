param(
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y
)

$ErrorActionPreference = "Stop"

& adb shell input tap $X $Y
if ($LASTEXITCODE -ne 0) {
    throw "adb input tap failed with exit code $LASTEXITCODE"
}

Write-Host "Tapped device at ($X, $Y)"
