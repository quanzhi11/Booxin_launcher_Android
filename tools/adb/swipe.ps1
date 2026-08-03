param(
    [Parameter(Mandatory = $true)][int]$X1,
    [Parameter(Mandatory = $true)][int]$Y1,
    [Parameter(Mandatory = $true)][int]$X2,
    [Parameter(Mandatory = $true)][int]$Y2,
    [int]$DurationMs = 250
)

$ErrorActionPreference = "Stop"

& adb shell input swipe $X1 $Y1 $X2 $Y2 $DurationMs
if ($LASTEXITCODE -ne 0) {
    throw "adb input swipe failed with exit code $LASTEXITCODE"
}

Write-Host "Swiped device from ($X1, $Y1) to ($X2, $Y2) in ${DurationMs}ms"
