param(
  [Parameter(Mandatory = $true)][int]$Port,
  [Parameter(Mandatory = $true)][string]$Token
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms | Out-Null
Add-Type -AssemblyName System.Drawing | Out-Null
[System.Windows.Forms.Application]::EnableVisualStyles()

function Invoke-StudioApi {
  # 不能用 $Args：PowerShell 保留自动变量，会吞掉真实参数
  param([string]$Cmd, [string[]]$CmdArgs = @())
  # PS 5.1 的 ConvertTo-Json 会把单元素数组压成标量，这里手写 args 数组
  $escapedArgs = foreach ($a in @($CmdArgs)) {
    '"' + ([string]$a).Replace('\', '\\').Replace('"', '\"') + '"'
  }
  $body = "{`"cmd`":$((ConvertTo-Json -InputObject $Cmd -Compress)),`"args`":[$($escapedArgs -join ',')],`"token`":$((ConvertTo-Json -InputObject $Token -Compress))}"
  try {
    $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/run" -Method Post -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -ContentType 'application/json; charset=utf-8' -TimeoutSec 120
    if ($resp.ok) { return [string]$resp.output }
    throw [string]$resp.error
  } catch {
    $msg = $_.Exception.Message
    if ($_.ErrorDetails.Message) { $msg = $_.ErrorDetails.Message }
    throw $msg
  }
}

function Append-Log {
  param([string]$Text)
  if ([string]::IsNullOrEmpty($Text)) { return }
  $boxLog.AppendText($Text.TrimEnd() + [Environment]::NewLine)
  $boxLog.SelectionStart = $boxLog.Text.Length
  $boxLog.ScrollToCaret()
}

function Pick-Folder([string]$Description) {
  $d = New-Object System.Windows.Forms.FolderBrowserDialog
  $d.Description = $Description
  $d.ShowNewFolderButton = $true
  if ($d.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { return $null }
  return $d.SelectedPath
}

function Pick-SaveZip([string]$DefaultName) {
  $d = New-Object System.Windows.Forms.SaveFileDialog
  $d.Filter = 'Zip (*.zip)|*.zip'
  $d.FileName = $DefaultName
  $d.AddExtension = $true
  $d.DefaultExt = 'zip'
  if ($d.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { return $null }
  return $d.FileName
}

function Ask-Text([string]$Title, [string]$Prompt, [string]$Default) {
  $f = New-Object System.Windows.Forms.Form
  $f.Text = $Title
  $f.Size = New-Object System.Drawing.Size(420, 160)
  $f.StartPosition = 'CenterParent'
  $f.FormBorderStyle = 'FixedDialog'
  $f.MaximizeBox = $false
  $f.MinimizeBox = $false
  $lbl = New-Object System.Windows.Forms.Label
  $lbl.Text = $Prompt
  $lbl.Location = New-Object System.Drawing.Point(12, 12)
  $lbl.Size = New-Object System.Drawing.Size(380, 24)
  $tb = New-Object System.Windows.Forms.TextBox
  $tb.Text = $Default
  $tb.Location = New-Object System.Drawing.Point(12, 42)
  $tb.Size = New-Object System.Drawing.Size(380, 24)
  $ok = New-Object System.Windows.Forms.Button
  $ok.Text = '确定'
  $ok.Location = New-Object System.Drawing.Point(220, 80)
  $ok.DialogResult = [System.Windows.Forms.DialogResult]::OK
  $cancel = New-Object System.Windows.Forms.Button
  $cancel.Text = '取消'
  $cancel.Location = New-Object System.Drawing.Point(312, 80)
  $cancel.DialogResult = [System.Windows.Forms.DialogResult]::Cancel
  $f.Controls.AddRange(@($lbl, $tb, $ok, $cancel))
  $f.AcceptButton = $ok
  $f.CancelButton = $cancel
  if ($f.ShowDialog($form) -ne [System.Windows.Forms.DialogResult]::OK) { return $null }
  return $tb.Text.Trim()
}

function New-Plugin([string]$Kind, [string]$DefaultFolder) {
  $parent = Pick-Folder "选择保存位置（将在其中创建 $DefaultFolder）"
  if (-not $parent) { return }
  $id = Ask-Text '插件 ID' '插件 id（英文/数字/横线）' $DefaultFolder
  if (-not $id) { return }
  $out = Join-Path $parent $id
  try {
    $r = Invoke-StudioApi -Cmd 'new' -CmdArgs @($Kind, $out, $id)
    Append-Log $r
    if (Test-Path $out) { Start-Process explorer.exe $out }
  } catch {
    Append-Log ("错误: " + $_)
    [System.Windows.Forms.MessageBox]::Show("$_", 'Booxin Studio', 'OK', 'Error') | Out-Null
  }
}

$form = New-Object System.Windows.Forms.Form
$form.Text = 'Booxin Studio'
$form.Size = New-Object System.Drawing.Size(640, 520)
$form.StartPosition = 'CenterScreen'
$form.MinimumSize = New-Object System.Drawing.Size(560, 440)
$form.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 9)

$lbl = New-Object System.Windows.Forms.Label
$lbl.Text = '官方插件工具 · 新建模板 → 打包 zip → 启动器安装'
$lbl.Location = New-Object System.Drawing.Point(16, 14)
$lbl.Size = New-Object System.Drawing.Size(600, 22)

$panel = New-Object System.Windows.Forms.FlowLayoutPanel
$panel.Location = New-Object System.Drawing.Point(16, 44)
$panel.Size = New-Object System.Drawing.Size(590, 120)
$panel.FlowDirection = 'LeftToRight'
$panel.WrapContents = $true

function Add-Btn([string]$Text, [scriptblock]$Action) {
  $b = New-Object System.Windows.Forms.Button
  $b.Text = $Text
  $b.AutoSize = $true
  $b.Margin = New-Object System.Windows.Forms.Padding(0, 0, 8, 8)
  $b.Padding = New-Object System.Windows.Forms.Padding(10, 6, 10, 6)
  $b.Tag = $Action
  $b.Add_Click({
    param($sender, $e)
    $sb = [scriptblock]$sender.Tag
    & $sb
  })
  [void]$panel.Controls.Add($b)
}

Add-Btn '新建问候语' { New-Plugin 'greeting' 'my-greeting' }
Add-Btn '新建字体插件' { New-Plugin 'font' 'my-ui-font' }
Add-Btn '新建主页图标' { New-Plugin 'icon' 'my-brand-icon' }
Add-Btn '新建完整主题包' { New-Plugin 'theme' 'my-full-theme' }

Add-Btn '打包 zip' {
  $dir = Pick-Folder '选择插件文件夹（内含 booxin-plugin.json）'
  if (-not $dir) { return }
  $zip = Pick-SaveZip ((Split-Path $dir -Leaf) + '.zip')
  try {
    if ($zip) { $r = Invoke-StudioApi -Cmd 'pack' -CmdArgs @($dir, $zip) }
    else { $r = Invoke-StudioApi -Cmd 'pack' -CmdArgs @($dir) }
    Append-Log $r
    [System.Windows.Forms.MessageBox]::Show('打包完成', 'Booxin Studio', 'OK', 'Information') | Out-Null
  } catch {
    Append-Log ("错误: " + $_)
    [System.Windows.Forms.MessageBox]::Show("$_", 'Booxin Studio', 'OK', 'Error') | Out-Null
  }
}

Add-Btn '校验插件' {
  $dir = Pick-Folder '选择要校验的插件文件夹'
  if (-not $dir) { return }
  try {
    Append-Log (Invoke-StudioApi -Cmd 'validate' -CmdArgs @($dir))
  } catch {
    Append-Log ("错误: " + $_)
  }
}

Add-Btn '环境检查' {
  try { Append-Log (Invoke-StudioApi -Cmd 'doctor' -CmdArgs @()) }
  catch { Append-Log ("错误: " + $_) }
}

$boxLog = New-Object System.Windows.Forms.TextBox
$boxLog.Multiline = $true
$boxLog.ScrollBars = 'Vertical'
$boxLog.ReadOnly = $true
$boxLog.Location = New-Object System.Drawing.Point(16, 180)
$boxLog.Size = New-Object System.Drawing.Size(590, 260)
$boxLog.Anchor = 'Top,Bottom,Left,Right'
$boxLog.Font = New-Object System.Drawing.Font('Consolas', 9)
$boxLog.BackColor = [System.Drawing.Color]::FromArgb(245, 246, 248)

$tip = New-Object System.Windows.Forms.Label
$tip.Text = '装到手机请用启动器「插件」安装 zip，不要依赖 adb push'
$tip.Location = New-Object System.Drawing.Point(16, 450)
$tip.Size = New-Object System.Drawing.Size(590, 22)
$tip.Anchor = 'Bottom,Left,Right'
$tip.ForeColor = [System.Drawing.Color]::FromArgb(90, 96, 110)

$form.Controls.AddRange(@($lbl, $panel, $boxLog, $tip))
Append-Log '就绪。选上面的按钮开始。'
[void]$form.ShowDialog()

try {
  Invoke-RestMethod -Uri "http://127.0.0.1:$Port/shutdown" -Method Post -Body (@{ token = $Token } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 3 | Out-Null
} catch { }
