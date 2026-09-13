param(
  [Parameter(Mandatory = $true)][string]$Url,
  [string]$Title = 'Booxin Studio'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms | Out-Null
Add-Type -AssemblyName System.Drawing | Out-Null

function Find-WebView2Dll {
  $candidates = @(
    (Join-Path $PSScriptRoot 'Microsoft.Web.WebView2.WinForms.dll'),
    (Join-Path $PSScriptRoot '..\lib\Microsoft.Web.WebView2.WinForms.dll')
  )
  foreach ($p in $candidates) {
    if (Test-Path -LiteralPath $p) { return (Resolve-Path $p).Path }
  }
  return $null
}

function Start-EdgeApp([string]$TargetUrl) {
  $edge = @(
    "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
    "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
    "$env:LOCALAPPDATA\Microsoft\Edge\Application\msedge.exe"
  ) | Where-Object { Test-Path $_ } | Select-Object -First 1
  if (-not $edge) { Start-Process $TargetUrl; return }
  $profile = Join-Path $env:LOCALAPPDATA 'BooxinStudio\edge-profile'
  New-Item -ItemType Directory -Force -Path $profile | Out-Null
  Start-Process -FilePath $edge -ArgumentList @(
    "--app=$TargetUrl",
    "--user-data-dir=$profile",
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--disable-component-update',
    '--disable-sync',
    '--disable-features=TranslateUI,EdgeSidebar,msEdgeCollections,msSmartScreenProtection',
    '--window-size=1440,900'
  ) | Out-Null
}

$dll = Find-WebView2Dll
if ($dll) {
  try {
    $core = Join-Path (Split-Path $dll -Parent) 'Microsoft.Web.WebView2.Core.dll'
    Add-Type -Path $core
    Add-Type -Path $dll
    $form = New-Object System.Windows.Forms.Form
    $form.Text = $Title
    $form.Width = 1400
    $form.Height = 860
    $form.StartPosition = 'CenterScreen'
    $form.BackColor = [System.Drawing.Color]::FromArgb(15, 20, 25)
    $web = New-Object Microsoft.Web.WebView2.WinForms.WebView2
    $web.Dock = 'Fill'
    $form.Controls.Add($web)
    $form.Add_Shown({
      $web.EnsureCoreWebView2Async($null).GetAwaiter().GetResult() | Out-Null
      $web.CoreWebView2.Settings.IsStatusBarEnabled = $false
      $web.Source = [Uri]$Url
    })
    [void]$form.ShowDialog()
    exit 0
  } catch {
    # fall through to Edge app
  }
}

Start-EdgeApp $Url
exit 0
