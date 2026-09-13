param(
  [Parameter(Mandatory = $true)][int]$Port,
  [Parameter(Mandatory = $true)][string]$Token
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms | Out-Null
Add-Type -AssemblyName System.Drawing | Out-Null
[System.Windows.Forms.Application]::EnableVisualStyles()

$script:ProjectRoot = $null
$script:CurrentFile = $null
$script:Dirty = $false
$script:LoadingEditor = $false
$script:AiChatTurns = New-Object System.Collections.Generic.List[object]
$script:AiBusy = $false
$script:AiPanelWidth = 400
$script:AiPanelReady = $false
$script:AiLoggedIn = $false
$script:RecentPaths = @()

# ---------- API ----------
function Invoke-Ide {
  param(
    [Parameter(Mandatory = $true)][string]$Action,
    [hashtable]$Fields = @{}
  )
  $parts = New-Object System.Collections.Generic.List[string]
  $parts.Add('"action":' + (ConvertTo-Json -InputObject $Action -Compress))
  $parts.Add('"token":' + (ConvertTo-Json -InputObject $Token -Compress))
  foreach ($key in $Fields.Keys) {
    $val = $Fields[$key]
    if ($null -eq $val) { continue }
    if ($val -is [System.Collections.IEnumerable] -and -not ($val -is [string])) {
      $escaped = foreach ($a in @($val)) {
        '"' + ([string]$a).Replace('\', '\\').Replace('"', '\"') + '"'
      }
      $parts.Add(('"{0}":[{1}]' -f $key, ($escaped -join ',')))
    } else {
      $parts.Add(('"{0}":{1}' -f $key, (ConvertTo-Json -InputObject ([string]$val) -Compress)))
    }
  }
  $body = '{' + ($parts -join ',') + '}'
  try {
    $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/ide" -Method Post `
      -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
      -ContentType 'application/json; charset=utf-8' -TimeoutSec 180
    if ($resp.ok) { return $resp }
    throw [string]$resp.error
  } catch {
    $msg = $_.Exception.Message
    if ($_.ErrorDetails.Message) { $msg = $_.ErrorDetails.Message }
    throw $msg
  }
}

function Append-Log([string]$Text) {
  if ([string]::IsNullOrWhiteSpace($Text)) { return }
  $boxLog.AppendText($Text.TrimEnd() + [Environment]::NewLine)
  $boxLog.SelectionStart = $boxLog.Text.Length
  $boxLog.ScrollToCaret()
}

function Set-Status([string]$Text) {
  $statusLabel.Text = $Text
}

function Confirm-SaveIfDirty {
  if (-not $script:Dirty) { return $true }
  $r = [System.Windows.Forms.MessageBox]::Show(
    '当前文件尚未保存，是否保存？',
    'Booxin Studio',
    [System.Windows.Forms.MessageBoxButtons]::YesNoCancel,
    [System.Windows.Forms.MessageBoxIcon]::Question
  )
  if ($r -eq [System.Windows.Forms.DialogResult]::Cancel) { return $false }
  if ($r -eq [System.Windows.Forms.DialogResult]::Yes) {
    return (Save-CurrentFile)
  }
  return $true
}

function Mark-Clean {
  $script:Dirty = $false
  Update-Title
}

function Mark-Dirty {
  if ($script:LoadingEditor) { return }
  $script:Dirty = $true
  Update-Title
}

function Update-Title {
  $name = if ($script:ProjectRoot) { Split-Path $script:ProjectRoot -Leaf } else { '未打开项目' }
  $file = if ($script:CurrentFile) { ' — ' + (Split-Path $script:CurrentFile -Leaf) } else { '' }
  $star = if ($script:Dirty) { ' *' } else { '' }
  $form.Text = "Booxin Studio · $name$file$star"
}

function Update-EditorPathLabel {
  if ($script:CurrentFile) {
    $lblEditorPath.Text = $script:CurrentFile
  } elseif ($script:ProjectRoot) {
    $lblEditorPath.Text = '未打开文件 — 双击左侧文件开始编辑'
  } else {
    $lblEditorPath.Text = ''
  }
}

function Test-SafeName([string]$Name) {
  if ([string]::IsNullOrWhiteSpace($Name)) { return $false }
  if ($Name -eq '.' -or $Name -eq '..') { return $false }
  if ($Name -match '[\\/:*?"<>|]') { return $false }
  return $true
}

function Get-TreeTargetDir {
  $node = $tree.SelectedNode
  if ($null -eq $node -or $null -eq $node.Tag) {
    return $script:ProjectRoot
  }
  $meta = $node.Tag
  if ($meta.kind -eq 'dir') { return [string]$meta.path }
  return [string](Split-Path $meta.path -Parent)
}

function Get-TreeTargetNodeForRefresh {
  $node = $tree.SelectedNode
  if ($null -eq $node) { return $null }
  $meta = $node.Tag
  if ($null -eq $meta) { return $null }
  if ($meta.kind -eq 'dir') { return $node }
  return $node.Parent
}

function Refresh-TreeNode([System.Windows.Forms.TreeNode]$Node) {
  if ($null -eq $Node) {
    Load-Tree
    return
  }
  $meta = $Node.Tag
  if ($null -eq $meta -or $meta.kind -ne 'dir') {
    Load-Tree
    return
  }
  $wasExpanded = $Node.IsExpanded
  Fill-Node $Node
  if ($wasExpanded) { $Node.Expand() }
}

function New-ProjectFile {
  if (-not $script:ProjectRoot) { return }
  $dir = Get-TreeTargetDir
  if (-not $dir) { return }
  $name = Ask-Text '新建文件' '文件名（如 pages/home.html 请先建文件夹）' 'untitled.txt'
  if (-not $name) { return }
  if (-not (Test-SafeName $name)) {
    [System.Windows.Forms.MessageBox]::Show('文件名非法', 'Booxin Studio', 'OK', 'Warning') | Out-Null
    return
  }
  $path = Join-Path $dir $name
  try {
    $resp = Invoke-Ide -Action 'fs.create' -Fields @{ path = $path; content = '' }
    Append-Log ("已创建文件: " + [string]$resp.path)
    Refresh-TreeNode (Get-TreeTargetNodeForRefresh)
    Open-FileInEditor ([string]$resp.path) $true
  } catch {
    Append-Log ("新建文件失败: " + $_)
    [System.Windows.Forms.MessageBox]::Show("$_", 'Booxin Studio', 'OK', 'Error') | Out-Null
  }
}

function New-ProjectFolder {
  if (-not $script:ProjectRoot) { return }
  $dir = Get-TreeTargetDir
  if (-not $dir) { return }
  $name = Ask-Text '新建文件夹' '文件夹名称' 'pages'
  if (-not $name) { return }
  if (-not (Test-SafeName $name)) {
    [System.Windows.Forms.MessageBox]::Show('文件夹名非法', 'Booxin Studio', 'OK', 'Warning') | Out-Null
    return
  }
  $path = Join-Path $dir $name
  try {
    $resp = Invoke-Ide -Action 'fs.mkdir' -Fields @{ path = $path }
    Append-Log ("已创建文件夹: " + [string]$resp.path)
    Refresh-TreeNode (Get-TreeTargetNodeForRefresh)
  } catch {
    Append-Log ("新建文件夹失败: " + $_)
    [System.Windows.Forms.MessageBox]::Show("$_", 'Booxin Studio', 'OK', 'Error') | Out-Null
  }
}

function Rename-TreeItem {
  if (-not $script:ProjectRoot) { return }
  $node = $tree.SelectedNode
  if ($null -eq $node -or $null -eq $node.Tag) { return }
  $meta = $node.Tag
  $oldName = Split-Path ([string]$meta.path) -Leaf
  $name = Ask-Text '重命名' '新名称' $oldName
  if (-not $name -or $name -eq $oldName) { return }
  if (-not (Test-SafeName $name)) {
    [System.Windows.Forms.MessageBox]::Show('名称非法', 'Booxin Studio', 'OK', 'Warning') | Out-Null
    return
  }
  try {
    $resp = Invoke-Ide -Action 'fs.rename' -Fields @{ path = [string]$meta.path; name = $name }
    $newPath = [string]$resp.path
    if ($script:CurrentFile -and ([string]$meta.path -eq $script:CurrentFile)) {
      $script:CurrentFile = $newPath
      Update-EditorPathLabel
      Update-Title
    }
    Append-Log ("已重命名: $oldName → $name")
    $parent = $node.Parent
    if ($null -eq $parent) { Load-Tree } else { Refresh-TreeNode $parent }
  } catch {
    Append-Log ("重命名失败: " + $_)
    [System.Windows.Forms.MessageBox]::Show("$_", 'Booxin Studio', 'OK', 'Error') | Out-Null
  }
}

function Remove-TreeItem {
  if (-not $script:ProjectRoot) { return }
  $node = $tree.SelectedNode
  if ($null -eq $node -or $null -eq $node.Tag) { return }
  $meta = $node.Tag
  $path = [string]$meta.path
  if ($path -eq $script:ProjectRoot) {
    [System.Windows.Forms.MessageBox]::Show('不能删除项目根目录', 'Booxin Studio', 'OK', 'Warning') | Out-Null
    return
  }
  $kindLabel = if ($meta.kind -eq 'dir') { '文件夹' } else { '文件' }
  $r = [System.Windows.Forms.MessageBox]::Show(
    "确定删除${kindLabel}？`n$path",
    'Booxin Studio',
    [System.Windows.Forms.MessageBoxButtons]::YesNo,
    [System.Windows.Forms.MessageBoxIcon]::Warning
  )
  if ($r -ne [System.Windows.Forms.DialogResult]::Yes) { return }
  try {
    [void](Invoke-Ide -Action 'fs.delete' -Fields @{ path = $path })
    if ($script:CurrentFile -and ($script:CurrentFile -eq $path -or $script:CurrentFile.StartsWith($path + [IO.Path]::DirectorySeparatorChar))) {
      $script:CurrentFile = $null
      $script:LoadingEditor = $true
      $editor.Text = ''
      $script:LoadingEditor = $false
      Mark-Clean
      Update-EditorPathLabel
    }
    Append-Log ("已删除: $path")
    $parent = $node.Parent
    if ($null -eq $parent) { Load-Tree } else { Refresh-TreeNode $parent }
  } catch {
    Append-Log ("删除失败: " + $_)
    [System.Windows.Forms.MessageBox]::Show("$_", 'Booxin Studio', 'OK', 'Error') | Out-Null
  }
}

function Show-InExplorer {
  $node = $tree.SelectedNode
  $path = $null
  if ($node -and $node.Tag) { $path = [string]$node.Tag.path }
  elseif ($script:ProjectRoot) { $path = $script:ProjectRoot }
  if (-not $path) { return }
  if (Test-Path -LiteralPath $path -PathType Container) {
    Start-Process explorer.exe $path
  } else {
    Start-Process explorer.exe "/select,`"$path`""
  }
}

function Check-StudioUpdate([bool]$Silent = $false) {
  try {
    Set-Status '正在检查更新…'
    $r = Invoke-Ide -Action 'app.version.check'
    $cur = [string]$r.currentVersion
    $latest = [string]$r.latestVersion
    $notes = [string]$r.notes
    if ($r.hasUpdate) {
      $msg = "发现新版本 $latest（当前 $cur）`n`n需要手动下载安装包覆盖更新。"
      if (-not [string]::IsNullOrWhiteSpace($notes)) {
        $msg += "`n`n更新说明：`n" + $notes.Trim()
      }
      $msg += "`n`n是否打开下载页？"
      $pick = [System.Windows.Forms.MessageBox]::Show(
        $msg,
        '检查更新',
        [System.Windows.Forms.MessageBoxButtons]::YesNo,
        [System.Windows.Forms.MessageBoxIcon]::Information
      )
      if ($pick -eq [System.Windows.Forms.DialogResult]::Yes -and $r.downloadUrl) {
        Start-Process ([string]$r.downloadUrl)
      }
      Set-Status "有新版本 $latest"
    } else {
      if (-not $Silent) {
        [System.Windows.Forms.MessageBox]::Show(
          "已是最新版本 $cur",
          '检查更新',
          'OK',
          'Information'
        ) | Out-Null
      } else {
        Append-Log ("版本检查：已是最新 $cur")
      }
      Set-Status "就绪 · v$cur"
    }
  } catch {
    if ($Silent) {
      Append-Log ("版本检查跳过: " + $_)
      Set-Status '就绪'
    } else {
      [System.Windows.Forms.MessageBox]::Show([string]$_, '检查更新', 'OK', 'Warning') | Out-Null
      Set-Status '版本检查失败'
    }
  }
}

# ---------- AI Agent ----------
function Get-AiHistoryJson {
  if ($script:AiChatTurns.Count -eq 0) { return '[]' }
  $parts = foreach ($t in $script:AiChatTurns) {
    '{"role":' + (ConvertTo-Json -InputObject ([string]$t.role) -Compress) +
      ',"content":' + (ConvertTo-Json -InputObject ([string]$t.content) -Compress) + '}'
  }
  return '[' + ($parts -join ',') + ']'
}

function Append-AiFeed([string]$Role, [string]$Content) {
  if ([string]::IsNullOrWhiteSpace($Content)) { return }
  $label = if ($Role -eq 'user') { '你' } elseif ($Role -eq 'system') { '系统' } else { 'Agent' }
  $color = if ($Role -eq 'user') {
    [System.Drawing.Color]::FromArgb(50, 90, 160)
  } elseif ($Role -eq 'system') {
    [System.Drawing.Color]::FromArgb(120, 128, 140)
  } else {
    [System.Drawing.Color]::FromArgb(30, 120, 90)
  }
  if ($aiFeed.TextLength -gt 0) {
    $aiFeed.AppendText([Environment]::NewLine + [Environment]::NewLine)
  }
  $aiFeed.SelectionStart = $aiFeed.TextLength
  $aiFeed.SelectionLength = 0
  $aiFeed.SelectionFont = New-Object System.Drawing.Font('Microsoft YaHei UI', 9, [System.Drawing.FontStyle]::Bold)
  $aiFeed.SelectionColor = $color
  $aiFeed.AppendText($label)
  $aiFeed.AppendText([Environment]::NewLine)
  $aiFeed.SelectionFont = New-Object System.Drawing.Font('Microsoft YaHei UI', 9)
  $aiFeed.SelectionColor = [System.Drawing.Color]::FromArgb(32, 36, 44)
  $aiFeed.AppendText($Content.TrimEnd())
  $aiFeed.SelectionStart = $aiFeed.TextLength
  $aiFeed.ScrollToCaret()
}

function Clear-AiChat {
  $script:AiChatTurns.Clear()
  $aiFeed.Clear()
  if ($script:AiLoggedIn) {
    Append-AiFeed 'system' '已登录。在下方输入问题，可附带当前打开文件作为上下文。'
  } else {
    Append-AiFeed 'system' '请先用用户名和密码登录 Booxin 账号。'
  }
}

function Update-AiAuthUi {
  if ($script:AiLoggedIn) {
    $panelAiLogin.Visible = $false
    $panelAiChat.Visible = $true
    $aiBtnLogout.Visible = $true
    $aiBtnSub.Visible = $true
    $aiBtnNew.Visible = $true
  } else {
    $panelAiLogin.Visible = $true
    $panelAiChat.Visible = $false
    $aiBtnLogout.Visible = $false
    $aiBtnSub.Visible = $false
    $aiBtnNew.Visible = $false
    $aiQuota.Text = '未登录'
    $aiTitle.Text = 'Agent'
  }
}

function Refresh-AiQuotaLabel {
  if (-not $script:AiLoggedIn) {
    $aiQuota.Text = '未登录'
    return
  }
  try {
    $r = Invoke-Ide -Action 'ai.quota'
    if ($r.quotaLine) {
      $aiQuota.Text = [string]$r.quotaLine
    } else {
      $aiQuota.Text = '额度已刷新'
    }
  } catch {
    $aiQuota.Text = '额度：' + $_.Exception.Message
  }
}

function Refresh-AiSession([bool]$Quiet = $false) {
  try {
    $r = Invoke-Ide -Action 'ai.session.validate'
    $script:AiLoggedIn = [bool]$r.settings.loggedIn
    if ($script:AiLoggedIn) {
      $name = [string]$r.settings.username
      if ([string]::IsNullOrWhiteSpace($name)) { $name = [string]$r.settings.userId }
      $aiTitle.Text = 'Agent · ' + $name
      if ($r.quotaLine) { $aiQuota.Text = [string]$r.quotaLine }
      else { Refresh-AiQuotaLabel }
    } else {
      $aiTitle.Text = 'Agent'
      $aiQuota.Text = '未登录'
    }
    Update-AiAuthUi
  } catch {
    if (-not $Quiet) { Append-Log ("AI 会话: " + $_) }
    $script:AiLoggedIn = $false
    Update-AiAuthUi
  }
}

function Do-AiLogin {
  $user = $txtAiUser.Text.Trim()
  $pass = $txtAiPass.Text
  if ([string]::IsNullOrWhiteSpace($user) -or [string]::IsNullOrWhiteSpace($pass)) {
    [System.Windows.Forms.MessageBox]::Show('请输入用户名和密码', 'Agent', 'OK', 'Warning') | Out-Null
    return
  }
  try {
    Set-Status '正在登录…'
    $form.UseWaitCursor = $true
    $r = Invoke-Ide -Action 'ai.login' -Fields @{ username = $user; password = $pass }
    $script:AiLoggedIn = [bool]$r.settings.loggedIn
    if (-not $script:AiLoggedIn) {
      throw '登录未成功，请检查账号密码'
    }
    $name = [string]$r.settings.username
    if ([string]::IsNullOrWhiteSpace($name)) { $name = $user }
    $aiTitle.Text = 'Agent · ' + $name
    if ($r.quotaLine) { $aiQuota.Text = [string]$r.quotaLine }
    else { Refresh-AiQuotaLabel }
    $txtAiPass.Text = ''
    Update-AiAuthUi
    Clear-AiChat
    Set-Status 'Agent 已登录'
    Append-Log ("Agent 登录成功: $name")
  } catch {
    $script:AiLoggedIn = $false
    Update-AiAuthUi
    Append-Log ("Agent 登录失败: " + $_)
    [System.Windows.Forms.MessageBox]::Show([string]$_, '登录失败', 'OK', 'Error') | Out-Null
    Set-Status '登录失败'
  } finally {
    $form.UseWaitCursor = $false
  }
}

function Do-AiLogout {
  try {
    [void](Invoke-Ide -Action 'ai.logout')
  } catch {
    Append-Log ("退出登录: " + $_)
  }
  $script:AiLoggedIn = $false
  $script:AiChatTurns.Clear()
  $aiFeed.Clear()
  $aiTitle.Text = 'Agent'
  $aiQuota.Text = '未登录'
  Update-AiAuthUi
  Set-Status '已退出 Agent 登录'
}

function Start-AiSubscribe {
  if (-not $script:AiLoggedIn) {
    [System.Windows.Forms.MessageBox]::Show('请先登录', '订阅', 'OK', 'Information') | Out-Null
    return
  }
  $plansHint = "选择档位后将打开支付页。`nShelter / Hearth / Atelier`n`n「是」= Shelter，「否」= Hearth，「取消」= Atelier"
  $pick = [System.Windows.Forms.MessageBox]::Show(
    $plansHint,
    '升级订阅',
    [System.Windows.Forms.MessageBoxButtons]::YesNoCancel
  )
  $plan = if ($pick -eq [System.Windows.Forms.DialogResult]::Yes) { 'Pro' }
    elseif ($pick -eq [System.Windows.Forms.DialogResult]::No) { 'ProMax' }
    elseif ($pick -eq [System.Windows.Forms.DialogResult]::Cancel) { 'Ultra' }
    else { return }
  $payPick = [System.Windows.Forms.MessageBox]::Show(
    '支付方式：「是」= 支付宝，「否」= 微信',
    '支付',
    [System.Windows.Forms.MessageBoxButtons]::YesNo
  )
  $payType = if ($payPick -eq [System.Windows.Forms.DialogResult]::Yes) { 'alipay' } else { 'wxpay' }
  try {
    Set-Status '创建订阅订单…'
    $created = (Invoke-Ide -Action 'ai.subscribe' -Fields @{ plan = $plan; payType = $payType }).created
    if (-not $created.success) {
      [System.Windows.Forms.MessageBox]::Show([string]$created.message, '订阅失败', 'OK', 'Warning') | Out-Null
      return
    }
    if ($created.payUrl) { Start-Process ([string]$created.payUrl) }
    $outTradeNo = [string]$created.outTradeNo
    Append-Log ("已创建 Studio 订阅订单: $outTradeNo")
    [System.Windows.Forms.MessageBox]::Show('已打开支付页。付款后点「确定」检测到账。', '支付', 'OK', 'Information') | Out-Null
    for ($i = 0; $i -lt 40; $i++) {
      Start-Sleep -Seconds 3
      $st = Invoke-Ide -Action 'ai.status' -Fields @{ outTradeNo = $outTradeNo }
      if ($st.status -and $st.status.paid) {
        if ($st.quotaLine) { $aiQuota.Text = [string]$st.quotaLine }
        else { Refresh-AiQuotaLabel }
        [System.Windows.Forms.MessageBox]::Show(
          $(if ($st.status.upgraded) { '支付成功，会员已到账。' } else { [string]$st.status.message }),
          '到账',
          'OK',
          'Information'
        ) | Out-Null
        Set-Status '订阅已到账'
        return
      }
    }
    [System.Windows.Forms.MessageBox]::Show('仍在等待支付，可稍后刷新额度。', '提示', 'OK', 'Information') | Out-Null
    Set-Status '等待支付确认'
  } catch {
    Append-Log ("订阅失败: " + $_)
    [System.Windows.Forms.MessageBox]::Show([string]$_, '订阅失败', 'OK', 'Error') | Out-Null
    Set-Status '订阅失败'
  }
}

function Set-AiPanelVisible([bool]$Visible) {
  if (-not $script:AiPanelReady) { return }
  if ($Visible) {
    $splitHost.Panel2Collapsed = $false
    $w = [Math]::Max(1, $splitHost.ClientSize.Width)
    $desired = [Math]::Max(280, [Math]::Min($script:AiPanelWidth, [int]($w * 0.45)))
    $dist = $w - $desired - $splitHost.SplitterWidth
    $dist = [Math]::Max($splitHost.Panel1MinSize, [Math]::Min($dist, $w - $splitHost.Panel2MinSize - $splitHost.SplitterWidth))
    try { $splitHost.SplitterDistance = $dist } catch { }
    $btnAi.Checked = $true
    if (-not $script:AiLoggedIn) {
      Refresh-AiSession $true
    }
  } else {
    if (-not $splitHost.Panel2Collapsed -and $splitHost.Panel2.Width -gt 40) {
      $script:AiPanelWidth = $splitHost.Panel2.Width
    }
    $splitHost.Panel2Collapsed = $true
    $btnAi.Checked = $false
  }
}

function Toggle-AiPanel {
  if (-not $script:AiPanelReady) { return }
  Set-AiPanelVisible ($splitHost.Panel2Collapsed)
}

function Send-AiMessage {
  if ($script:AiBusy) { return }
  if (-not $script:AiLoggedIn) {
    [System.Windows.Forms.MessageBox]::Show('请先登录', 'Agent', 'OK', 'Information') | Out-Null
    return
  }
  $msg = $txtAiAsk.Text.Trim()
  if ([string]::IsNullOrWhiteSpace($msg)) { return }
  $script:AiBusy = $true
  $btnAiSend.Enabled = $false
  $txtAiAsk.Enabled = $false
  try {
    Append-AiFeed 'user' $msg
    $script:AiChatTurns.Add([pscustomobject]@{ role = 'user'; content = $msg })
    $txtAiAsk.Text = ''
    $fields = @{
      message = $msg
      historyJson = (Get-AiHistoryJson)
    }
    if ($chkAiContext.Checked -and $script:CurrentFile) {
      try {
        $read = Invoke-Ide -Action 'fs.read' -Fields @{ path = $script:CurrentFile }
        $fields['context'] = [string]$read.content
      } catch {
        Append-Log ("附带文件失败: " + $_)
      }
    }
    Set-Status 'Agent 思考中…'
    $form.UseWaitCursor = $true
    $r = Invoke-Ide -Action 'ai.chat' -Fields $fields
    $reply = [string]$r.reply
    Append-AiFeed 'assistant' $reply
    $script:AiChatTurns.Add([pscustomobject]@{ role = 'assistant'; content = $reply })
    if ($r.quotaLine) { $aiQuota.Text = [string]$r.quotaLine }
    Set-Status 'Agent 完成'
  } catch {
    Append-AiFeed 'system' ("错误: " + $_)
    Append-Log ("Agent 失败: " + $_)
    Set-Status 'Agent 失败'
  } finally {
    $form.UseWaitCursor = $false
    $script:AiBusy = $false
    $btnAiSend.Enabled = $true
    $txtAiAsk.Enabled = $true
    $txtAiAsk.Focus()
  }
}

function Pick-Folder([string]$Description) {
  $d = New-Object System.Windows.Forms.FolderBrowserDialog
  $d.Description = $Description
  $d.ShowNewFolderButton = $true
  if ($d.ShowDialog($form) -ne [System.Windows.Forms.DialogResult]::OK) { return $null }
  return $d.SelectedPath
}

function Pick-SaveZip([string]$DefaultName) {
  $d = New-Object System.Windows.Forms.SaveFileDialog
  $d.Filter = 'Zip (*.zip)|*.zip'
  $d.FileName = $DefaultName
  $d.AddExtension = $true
  $d.DefaultExt = 'zip'
  if ($d.ShowDialog($form) -ne [System.Windows.Forms.DialogResult]::OK) { return $null }
  return $d.FileName
}

function Ask-Text([string]$Title, [string]$Prompt, [string]$Default) {
  $f = New-Object System.Windows.Forms.Form
  $f.Text = $Title
  $f.Size = New-Object System.Drawing.Size(440, 170)
  $f.StartPosition = 'CenterParent'
  $f.FormBorderStyle = 'FixedDialog'
  $f.MaximizeBox = $false
  $f.MinimizeBox = $false
  $lbl = New-Object System.Windows.Forms.Label
  $lbl.Text = $Prompt
  $lbl.Location = New-Object System.Drawing.Point(12, 12)
  $lbl.Size = New-Object System.Drawing.Size(400, 24)
  $tb = New-Object System.Windows.Forms.TextBox
  $tb.Text = $Default
  $tb.Location = New-Object System.Drawing.Point(12, 42)
  $tb.Size = New-Object System.Drawing.Size(400, 24)
  $ok = New-Object System.Windows.Forms.Button
  $ok.Text = '确定'
  $ok.Location = New-Object System.Drawing.Point(236, 84)
  $ok.DialogResult = [System.Windows.Forms.DialogResult]::OK
  $cancel = New-Object System.Windows.Forms.Button
  $cancel.Text = '取消'
  $cancel.Location = New-Object System.Drawing.Point(328, 84)
  $cancel.DialogResult = [System.Windows.Forms.DialogResult]::Cancel
  $f.Controls.AddRange(@($lbl, $tb, $ok, $cancel))
  $f.AcceptButton = $ok
  $f.CancelButton = $cancel
  if ($f.ShowDialog($form) -ne [System.Windows.Forms.DialogResult]::OK) { return $null }
  return $tb.Text.Trim()
}

# ---------- Project / files ----------
function Show-Welcome {
  $panelWelcome.Visible = $true
  $panelIde.Visible = $false
  $btnPack.Enabled = $false
  $btnValidate.Enabled = $false
  $btnSave.Enabled = $false
  $btnCloseProject.Enabled = $false
  $btnNewFile.Enabled = $false
  $btnNewFolder.Enabled = $false
  $btnTreeRefresh.Enabled = $false
  Refresh-Recent
  Update-Title
  Update-EditorPathLabel
  Set-Status '打开一个插件项目开始编辑，或从历史记录进入'
}

function Show-Ide {
  $panelWelcome.Visible = $false
  $panelIde.Visible = $true
  $btnPack.Enabled = $true
  $btnValidate.Enabled = $true
  $btnSave.Enabled = $true
  $btnCloseProject.Enabled = $true
  $btnNewFile.Enabled = $true
  $btnNewFolder.Enabled = $true
  $btnTreeRefresh.Enabled = $true
  Update-Title
  Update-EditorPathLabel
}

function Refresh-Recent {
  $listRecent.Items.Clear()
  try {
    $resp = Invoke-Ide -Action 'recent.get'
    foreach ($item in @($resp.recent)) {
      $path = [string]$item.path
      if ([string]::IsNullOrWhiteSpace($path)) { continue }
      $display = "{0}    {1}" -f ([string]$item.name), $path
      [void]$listRecent.Items.Add($display)
    }
    $script:RecentPaths = @($resp.recent | ForEach-Object { [string]$_.path })
  } catch {
    Append-Log ("读取历史失败: " + $_)
    $script:RecentPaths = @()
  }
  $lblRecentEmpty.Visible = ($listRecent.Items.Count -eq 0)
}

function Open-Project([string]$Dir) {
  if (-not (Confirm-SaveIfDirty)) { return }
  if ([string]::IsNullOrWhiteSpace($Dir) -or -not (Test-Path -LiteralPath $Dir)) {
    [System.Windows.Forms.MessageBox]::Show('目录不存在', 'Booxin Studio', 'OK', 'Warning') | Out-Null
    return
  }
  try {
    $sum = Invoke-Ide -Action 'project.summary' -Fields @{ path = $Dir }
    [void](Invoke-Ide -Action 'recent.add' -Fields @{ path = $Dir })
    $script:ProjectRoot = [string]$sum.project.path
    $script:CurrentFile = $null
    $script:LoadingEditor = $true
    $editor.Text = ''
    $script:LoadingEditor = $false
    Mark-Clean
    Show-Ide
    Load-Tree
    $info = if ($sum.project.hasManifest) {
      "项目: $($sum.project.name)  id=$($sum.project.id)  v$($sum.project.version)"
    } else {
      "项目: $($sum.project.name)（未找到 booxin-plugin.json，仍可编辑文件）"
    }
    Append-Log $info
    Set-Status $script:ProjectRoot
  } catch {
    Append-Log ("打开失败: " + $_)
    [System.Windows.Forms.MessageBox]::Show("$_", 'Booxin Studio', 'OK', 'Error') | Out-Null
  }
}

function Close-Project {
  if (-not (Confirm-SaveIfDirty)) { return }
  $script:ProjectRoot = $null
  $script:CurrentFile = $null
  $tree.Nodes.Clear()
  $script:LoadingEditor = $true
  $editor.Text = ''
  $script:LoadingEditor = $false
  Mark-Clean
  Show-Welcome
  Append-Log '已关闭项目'
}

function Load-Tree {
  $tree.BeginUpdate()
  $tree.Nodes.Clear()
  if (-not $script:ProjectRoot) { $tree.EndUpdate(); return }
  $root = New-Object System.Windows.Forms.TreeNode
  $root.Text = Split-Path $script:ProjectRoot -Leaf
  $root.Tag = @{ path = $script:ProjectRoot; kind = 'dir' }
  [void]$root.Nodes.Add('…')
  [void]$tree.Nodes.Add($root)
  $root.Expand()
  $tree.EndUpdate()
}

function Fill-Node([System.Windows.Forms.TreeNode]$Node) {
  $meta = $Node.Tag
  if ($null -eq $meta -or $meta.kind -ne 'dir') { return }
  $Node.Nodes.Clear()
  try {
    $resp = Invoke-Ide -Action 'fs.list' -Fields @{ path = [string]$meta.path }
    foreach ($ent in @($resp.entries)) {
      $child = New-Object System.Windows.Forms.TreeNode
      $child.Text = [string]$ent.name
      $child.Tag = @{
        path = [string]$ent.path
        kind = [string]$ent.kind
        text = [bool]$ent.text
      }
      if ($ent.kind -eq 'dir') {
        [void]$child.Nodes.Add('…')
      }
      [void]$Node.Nodes.Add($child)
    }
  } catch {
    Append-Log ("列出目录失败: " + $_)
  }
}

function Open-FileInEditor([string]$FilePath, [bool]$IsText) {
  if (-not $IsText) {
    Append-Log "二进制/资源文件，请用资源管理器打开: $FilePath"
    Start-Process explorer.exe "/select,`"$FilePath`""
    return
  }
  if (-not (Confirm-SaveIfDirty)) { return }
  try {
    $resp = Invoke-Ide -Action 'fs.read' -Fields @{ path = $FilePath }
    $script:LoadingEditor = $true
    $editor.Text = [string]$resp.content
    $script:LoadingEditor = $false
    $script:CurrentFile = [string]$resp.path
    Mark-Clean
    Update-EditorPathLabel
    Set-Status $script:CurrentFile
  } catch {
    Append-Log ("打开文件失败: " + $_)
    [System.Windows.Forms.MessageBox]::Show("$_", 'Booxin Studio', 'OK', 'Error') | Out-Null
  }
}

function Save-CurrentFile {
  if (-not $script:CurrentFile) {
    Append-Log '没有打开的文件可保存'
    return $false
  }
  try {
    $resp = Invoke-Ide -Action 'fs.write' -Fields @{
      path = $script:CurrentFile
      content = $editor.Text
    }
    Mark-Clean
    Append-Log ("已保存 ($($resp.bytes) 字节): " + $script:CurrentFile)
    Set-Status ("已保存 · " + $script:CurrentFile)
    return $true
  } catch {
    Append-Log ("保存失败: " + $_)
    [System.Windows.Forms.MessageBox]::Show("$_", 'Booxin Studio', 'OK', 'Error') | Out-Null
    return $false
  }
}

function Pack-Current {
  if (-not $script:ProjectRoot) {
    [System.Windows.Forms.MessageBox]::Show('请先打开项目', 'Booxin Studio', 'OK', 'Information') | Out-Null
    return
  }
  if (-not (Confirm-SaveIfDirty)) { return }
  $defaultName = (Split-Path $script:ProjectRoot -Leaf) + '.zip'
  $zip = Pick-SaveZip $defaultName
  try {
    $fields = @{ path = $script:ProjectRoot }
    if ($zip) { $fields['out'] = $zip }
    $resp = Invoke-Ide -Action 'pack' -Fields $fields
    Append-Log ([string]$resp.output)
    [System.Windows.Forms.MessageBox]::Show(
      ("打包完成`n" + [string]$resp.dest),
      'Booxin Studio',
      'OK',
      'Information'
    ) | Out-Null
    if ($resp.dest -and (Test-Path -LiteralPath ([string]$resp.dest))) {
      Start-Process explorer.exe "/select,`"$(([string]$resp.dest))`""
    }
  } catch {
    Append-Log ("打包失败: " + $_)
    [System.Windows.Forms.MessageBox]::Show("$_", 'Booxin Studio', 'OK', 'Error') | Out-Null
  }
}

function Validate-Current {
  if (-not $script:ProjectRoot) { return }
  try {
    $resp = Invoke-Ide -Action 'validate' -Fields @{ path = $script:ProjectRoot }
    Append-Log ([string]$resp.output)
  } catch {
    Append-Log ("校验失败: " + $_)
  }
}

function New-Plugin([string]$Kind, [string]$DefaultFolder) {
  $parent = Pick-Folder "选择保存位置（将在其中创建 $DefaultFolder）"
  if (-not $parent) { return }
  $id = Ask-Text '插件 ID' '插件 id（英文/数字/横线）' $DefaultFolder
  if (-not $id) { return }
  $out = Join-Path $parent $id
  try {
    $resp = Invoke-Ide -Action 'run' -Fields @{ cmd = 'new'; args = @($Kind, $out, $id) }
    Append-Log ([string]$resp.output)
    if (Test-Path -LiteralPath $out) {
      Open-Project $out
    }
  } catch {
    Append-Log ("新建失败: " + $_)
    [System.Windows.Forms.MessageBox]::Show("$_", 'Booxin Studio', 'OK', 'Error') | Out-Null
  }
}

# ---------- UI ----------
$form = New-Object System.Windows.Forms.Form
$form.Text = 'Booxin Studio'
$form.Size = New-Object System.Drawing.Size(1280, 760)
$form.StartPosition = 'CenterScreen'
$form.MinimumSize = New-Object System.Drawing.Size(980, 600)
$form.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 9)
$form.BackColor = [System.Drawing.Color]::FromArgb(245, 247, 250)

$tool = New-Object System.Windows.Forms.ToolStrip
$tool.Dock = 'Top'
$tool.GripStyle = 'Hidden'
$tool.Padding = New-Object System.Windows.Forms.Padding(6, 4, 6, 4)
$tool.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 9)
$tool.BackColor = [System.Drawing.Color]::FromArgb(232, 236, 242)

function New-ToolBtn([string]$Text, [scriptblock]$Action, [bool]$Primary = $false) {
  $b = New-Object System.Windows.Forms.ToolStripButton
  $b.Text = $Text
  $b.DisplayStyle = 'Text'
  if ($Primary) {
    $b.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 9, [System.Drawing.FontStyle]::Bold)
    $b.ForeColor = [System.Drawing.Color]::FromArgb(20, 80, 160)
  }
  $b.Tag = $Action
  $b.Add_Click({
    param($sender, $e)
    & ([scriptblock]$sender.Tag)
  })
  return $b
}

$btnOpen = New-ToolBtn '打开项目' {
  $dir = Pick-Folder '选择插件项目文件夹（建议含 booxin-plugin.json）'
  if ($dir) { Open-Project $dir }
}
$btnSave = New-ToolBtn '保存' { [void](Save-CurrentFile) }
$btnPack = New-ToolBtn '一键打包' { Pack-Current } $true
$btnValidate = New-ToolBtn '校验' { Validate-Current }
$btnAi = New-ToolBtn 'Agent' { Toggle-AiPanel }
$btnAi.CheckOnClick = $true
$btnUpdate = New-ToolBtn '检查更新' { Check-StudioUpdate $false }
$btnCloseProject = New-ToolBtn '关闭项目' { Close-Project }

$btnNew = New-Object System.Windows.Forms.ToolStripDropDownButton
$btnNew.Text = '新建插件'
$btnNew.DisplayStyle = 'Text'
@(
  @{ t = '问候语'; k = 'greeting'; d = 'my-greeting' },
  @{ t = '字体插件'; k = 'font'; d = 'my-ui-font' },
  @{ t = '主页图标'; k = 'icon'; d = 'my-brand-icon' },
  @{ t = '完整主题包'; k = 'theme'; d = 'my-full-theme' }
) | ForEach-Object {
  $item = New-Object System.Windows.Forms.ToolStripMenuItem
  $item.Text = $_.t
  $item.Tag = $_
  $item.Add_Click({
    param($sender, $e)
    $meta = $sender.Tag
    New-Plugin $meta.k $meta.d
  })
  [void]$btnNew.DropDownItems.Add($item)
}

[void]$tool.Items.Add($btnOpen)
[void]$tool.Items.Add($btnNew)
[void]$tool.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator))
[void]$tool.Items.Add($btnSave)
[void]$tool.Items.Add($btnPack)
[void]$tool.Items.Add($btnValidate)
[void]$tool.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator))
[void]$tool.Items.Add($btnAi)
[void]$tool.Items.Add($btnUpdate)
[void]$tool.Items.Add($btnCloseProject)

$hostPanel = New-Object System.Windows.Forms.Panel
$hostPanel.Dock = 'Fill'

$splitHost = New-Object System.Windows.Forms.SplitContainer
$splitHost.Dock = 'Fill'
$splitHost.Orientation = 'Vertical'
$splitHost.FixedPanel = 'Panel2'
$splitHost.Panel2Collapsed = $true
$splitHost.SplitterWidth = 5
$splitHost.BackColor = [System.Drawing.Color]::FromArgb(220, 224, 230)

$contentHost = New-Object System.Windows.Forms.Panel
$contentHost.Dock = 'Fill'

# Welcome
$panelWelcome = New-Object System.Windows.Forms.Panel
$panelWelcome.Dock = 'Fill'
$panelWelcome.BackColor = [System.Drawing.Color]::FromArgb(245, 247, 250)

$welcomeInner = New-Object System.Windows.Forms.Panel
$welcomeInner.Size = New-Object System.Drawing.Size(560, 460)
$welcomeInner.BackColor = [System.Drawing.Color]::White
$welcomeInner.BorderStyle = 'FixedSingle'
$welcomeInner.Padding = New-Object System.Windows.Forms.Padding(28)

$lblBrand = New-Object System.Windows.Forms.Label
$lblBrand.Text = 'Booxin Studio'
$lblBrand.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 22, [System.Drawing.FontStyle]::Bold)
$lblBrand.ForeColor = [System.Drawing.Color]::FromArgb(24, 48, 88)
$lblBrand.Location = New-Object System.Drawing.Point(28, 24)
$lblBrand.AutoSize = $true

$lblSub = New-Object System.Windows.Forms.Label
$lblSub.Text = '微型插件 IDE · 编辑清单与资源，一键打包为启动器可安装的 zip'
$lblSub.ForeColor = [System.Drawing.Color]::FromArgb(100, 110, 128)
$lblSub.Location = New-Object System.Drawing.Point(30, 68)
$lblSub.Size = New-Object System.Drawing.Size(500, 36)

$btnWelcomeOpen = New-Object System.Windows.Forms.Button
$btnWelcomeOpen.Text = '打开项目'
$btnWelcomeOpen.Size = New-Object System.Drawing.Size(140, 38)
$btnWelcomeOpen.Location = New-Object System.Drawing.Point(30, 114)
$btnWelcomeOpen.FlatStyle = 'System'
$btnWelcomeOpen.Add_Click({
  $dir = Pick-Folder '选择插件项目文件夹'
  if ($dir) { Open-Project $dir }
})

$btnWelcomeNew = New-Object System.Windows.Forms.Button
$btnWelcomeNew.Text = '新建主题包'
$btnWelcomeNew.Size = New-Object System.Drawing.Size(140, 38)
$btnWelcomeNew.Location = New-Object System.Drawing.Point(182, 114)
$btnWelcomeNew.FlatStyle = 'System'
$btnWelcomeNew.Add_Click({ New-Plugin 'theme' 'my-full-theme' })

$lblHistory = New-Object System.Windows.Forms.Label
$lblHistory.Text = '最近打开'
$lblHistory.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 10, [System.Drawing.FontStyle]::Bold)
$lblHistory.Location = New-Object System.Drawing.Point(30, 172)
$lblHistory.AutoSize = $true

$btnClearRecent = New-Object System.Windows.Forms.LinkLabel
$btnClearRecent.Text = '清空历史'
$btnClearRecent.Location = New-Object System.Drawing.Point(460, 174)
$btnClearRecent.AutoSize = $true
$btnClearRecent.Add_Click({
  try {
    [void](Invoke-Ide -Action 'recent.clear')
    Refresh-Recent
  } catch {
    Append-Log ("清空失败: " + $_)
  }
})

$listRecent = New-Object System.Windows.Forms.ListBox
$listRecent.Location = New-Object System.Drawing.Point(30, 202)
$listRecent.Size = New-Object System.Drawing.Size(500, 190)
$listRecent.IntegralHeight = $false
$listRecent.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 9)
$listRecent.Add_DoubleClick({
  $i = $listRecent.SelectedIndex
  if ($i -lt 0) { return }
  if ($script:RecentPaths -and $i -lt $script:RecentPaths.Count) {
    Open-Project ([string]$script:RecentPaths[$i])
  }
})

$lblRecentEmpty = New-Object System.Windows.Forms.Label
$lblRecentEmpty.Text = '暂无历史项目'
$lblRecentEmpty.ForeColor = [System.Drawing.Color]::FromArgb(140, 148, 160)
$lblRecentEmpty.Location = New-Object System.Drawing.Point(40, 275)
$lblRecentEmpty.AutoSize = $true

$lblTip = New-Object System.Windows.Forms.Label
$lblTip.Text = '提示：双击历史项打开。打包后用启动器「插件」安装 zip。Ctrl+L 打开 Agent。'
$lblTip.ForeColor = [System.Drawing.Color]::FromArgb(120, 128, 140)
$lblTip.Location = New-Object System.Drawing.Point(30, 408)
$lblTip.Size = New-Object System.Drawing.Size(500, 36)

$welcomeInner.Controls.AddRange(@(
  $lblBrand, $lblSub, $btnWelcomeOpen, $btnWelcomeNew,
  $lblHistory, $btnClearRecent, $listRecent, $lblRecentEmpty, $lblTip
))
$panelWelcome.Controls.Add($welcomeInner)
$panelWelcome.Add_Resize({
  $welcomeInner.Left = [Math]::Max(24, [int](($panelWelcome.ClientSize.Width - $welcomeInner.Width) / 2))
  $welcomeInner.Top = [Math]::Max(24, [int](($panelWelcome.ClientSize.Height - $welcomeInner.Height) / 2))
})

# IDE
$panelIde = New-Object System.Windows.Forms.Panel
$panelIde.Dock = 'Fill'
$panelIde.Visible = $false

$splitMain = New-Object System.Windows.Forms.SplitContainer
$splitMain.Dock = 'Fill'
$splitMain.Orientation = 'Horizontal'

$splitEdit = New-Object System.Windows.Forms.SplitContainer
$splitEdit.Dock = 'Fill'
$splitEdit.Orientation = 'Vertical'

$panelTree = New-Object System.Windows.Forms.Panel
$panelTree.Dock = 'Fill'

$treeBar = New-Object System.Windows.Forms.Panel
$treeBar.Dock = 'Top'
$treeBar.Height = 36
$treeBar.BackColor = [System.Drawing.Color]::FromArgb(236, 240, 245)
$treeBar.Padding = New-Object System.Windows.Forms.Padding(6, 4, 6, 4)

$lblTree = New-Object System.Windows.Forms.Label
$lblTree.Text = '文件'
$lblTree.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 9, [System.Drawing.FontStyle]::Bold)
$lblTree.AutoSize = $true
$lblTree.Location = New-Object System.Drawing.Point(8, 8)

$btnNewFile = New-Object System.Windows.Forms.Button
$btnNewFile.Text = '新建文件'
$btnNewFile.FlatStyle = 'System'
$btnNewFile.Size = New-Object System.Drawing.Size(72, 26)
$btnNewFile.Anchor = 'Top, Right'
$btnNewFile.Add_Click({ New-ProjectFile })

$btnNewFolder = New-Object System.Windows.Forms.Button
$btnNewFolder.Text = '新建文件夹'
$btnNewFolder.FlatStyle = 'System'
$btnNewFolder.Size = New-Object System.Drawing.Size(84, 26)
$btnNewFolder.Anchor = 'Top, Right'
$btnNewFolder.Add_Click({ New-ProjectFolder })

$btnTreeRefresh = New-Object System.Windows.Forms.Button
$btnTreeRefresh.Text = '刷新'
$btnTreeRefresh.FlatStyle = 'System'
$btnTreeRefresh.Size = New-Object System.Drawing.Size(48, 26)
$btnTreeRefresh.Anchor = 'Top, Right'
$btnTreeRefresh.Add_Click({ Load-Tree })

$treeBar.Controls.AddRange(@($lblTree, $btnNewFile, $btnNewFolder, $btnTreeRefresh))
$treeBar.Add_Resize({
  $right = $treeBar.ClientSize.Width - 6
  $btnTreeRefresh.Left = $right - $btnTreeRefresh.Width
  $btnTreeRefresh.Top = 5
  $btnNewFolder.Left = $btnTreeRefresh.Left - 4 - $btnNewFolder.Width
  $btnNewFolder.Top = 5
  $btnNewFile.Left = $btnNewFolder.Left - 4 - $btnNewFile.Width
  $btnNewFile.Top = 5
})

$tree = New-Object System.Windows.Forms.TreeView
$tree.Dock = 'Fill'
$tree.HideSelection = $false
$tree.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 9)
$tree.Add_BeforeExpand({
  param($sender, $e)
  $node = $e.Node
  if ($node.Nodes.Count -eq 1 -and $node.Nodes[0].Text -eq '…') {
    Fill-Node $node
  }
})
$tree.Add_NodeMouseDoubleClick({
  param($sender, $e)
  $meta = $e.Node.Tag
  if ($null -eq $meta) { return }
  if ($meta.kind -eq 'file') {
    Open-FileInEditor ([string]$meta.path) ([bool]$meta.text)
  }
})

$treeMenu = New-Object System.Windows.Forms.ContextMenuStrip
@(
  @{ t = '新建文件'; a = { New-ProjectFile } },
  @{ t = '新建文件夹'; a = { New-ProjectFolder } },
  @{ t = '-' },
  @{ t = '重命名'; a = { Rename-TreeItem } },
  @{ t = '删除'; a = { Remove-TreeItem } },
  @{ t = '-' },
  @{ t = '刷新'; a = { Load-Tree } },
  @{ t = '在资源管理器中打开'; a = { Show-InExplorer } }
) | ForEach-Object {
  if ($_.t -eq '-') {
    [void]$treeMenu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator))
  } else {
    $mi = New-Object System.Windows.Forms.ToolStripMenuItem
    $mi.Text = $_.t
    $mi.Tag = $_.a
    $mi.Add_Click({
      param($sender, $e)
      & ([scriptblock]$sender.Tag)
    })
    [void]$treeMenu.Items.Add($mi)
  }
}
$tree.ContextMenuStrip = $treeMenu

$panelTree.Controls.Add($tree)
$panelTree.Controls.Add($treeBar)

$panelEditor = New-Object System.Windows.Forms.Panel
$panelEditor.Dock = 'Fill'

$lblEditorPath = New-Object System.Windows.Forms.Label
$lblEditorPath.Dock = 'Top'
$lblEditorPath.Height = 28
$lblEditorPath.TextAlign = 'MiddleLeft'
$lblEditorPath.Padding = New-Object System.Windows.Forms.Padding(10, 0, 8, 0)
$lblEditorPath.BackColor = [System.Drawing.Color]::FromArgb(236, 240, 245)
$lblEditorPath.ForeColor = [System.Drawing.Color]::FromArgb(70, 82, 100)
$lblEditorPath.Text = ''

$editor = New-Object System.Windows.Forms.TextBox
$editor.Multiline = $true
$editor.ScrollBars = 'Both'
$editor.AcceptsTab = $true
$editor.WordWrap = $false
$editor.Dock = 'Fill'
$editor.Font = New-Object System.Drawing.Font('Consolas', 11)
$editor.BackColor = [System.Drawing.Color]::FromArgb(252, 252, 253)
$editor.Add_TextChanged({ Mark-Dirty })

$panelEditor.Controls.Add($editor)
$panelEditor.Controls.Add($lblEditorPath)

$boxLog = New-Object System.Windows.Forms.TextBox
$boxLog.Multiline = $true
$boxLog.ScrollBars = 'Vertical'
$boxLog.ReadOnly = $true
$boxLog.Dock = 'Fill'
$boxLog.Font = New-Object System.Drawing.Font('Consolas', 9)
$boxLog.BackColor = [System.Drawing.Color]::FromArgb(245, 246, 248)

$splitEdit.Panel1.Controls.Add($panelTree)
$splitEdit.Panel2.Controls.Add($panelEditor)
$splitMain.Panel1.Controls.Add($splitEdit)
$splitMain.Panel2.Controls.Add($boxLog)
$panelIde.Controls.Add($splitMain)

$contentHost.Controls.Add($panelIde)
$contentHost.Controls.Add($panelWelcome)

# AI sidebar
$panelAi = New-Object System.Windows.Forms.Panel
$panelAi.Dock = 'Fill'
$panelAi.BackColor = [System.Drawing.Color]::FromArgb(248, 249, 251)

$aiHeader = New-Object System.Windows.Forms.Panel
$aiHeader.Dock = 'Top'
$aiHeader.Height = 52
$aiHeader.BackColor = [System.Drawing.Color]::FromArgb(236, 240, 245)
$aiHeader.Padding = New-Object System.Windows.Forms.Padding(10, 6, 8, 6)

$aiTitle = New-Object System.Windows.Forms.Label
$aiTitle.Text = 'Agent'
$aiTitle.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 11, [System.Drawing.FontStyle]::Bold)
$aiTitle.ForeColor = [System.Drawing.Color]::FromArgb(24, 48, 88)
$aiTitle.AutoSize = $true
$aiTitle.Location = New-Object System.Drawing.Point(10, 6)

$aiQuota = New-Object System.Windows.Forms.Label
$aiQuota.Text = '未登录'
$aiQuota.ForeColor = [System.Drawing.Color]::FromArgb(90, 110, 140)
$aiQuota.AutoSize = $true
$aiQuota.Location = New-Object System.Drawing.Point(10, 28)

$aiBtnClose = New-Object System.Windows.Forms.Button
$aiBtnClose.Text = '×'
$aiBtnClose.FlatStyle = 'Flat'
$aiBtnClose.Size = New-Object System.Drawing.Size(28, 26)
$aiBtnClose.Anchor = 'Top, Right'
$aiBtnClose.FlatAppearance.BorderSize = 0
$aiBtnClose.Add_Click({ Set-AiPanelVisible $false })

$aiBtnLogout = New-Object System.Windows.Forms.Button
$aiBtnLogout.Text = '退出'
$aiBtnLogout.FlatStyle = 'System'
$aiBtnLogout.Size = New-Object System.Drawing.Size(48, 26)
$aiBtnLogout.Anchor = 'Top, Right'
$aiBtnLogout.Visible = $false
$aiBtnLogout.Add_Click({ Do-AiLogout })

$aiBtnSub = New-Object System.Windows.Forms.Button
$aiBtnSub.Text = '订阅'
$aiBtnSub.FlatStyle = 'System'
$aiBtnSub.Size = New-Object System.Drawing.Size(48, 26)
$aiBtnSub.Anchor = 'Top, Right'
$aiBtnSub.Visible = $false
$aiBtnSub.Add_Click({ Start-AiSubscribe })

$aiBtnNew = New-Object System.Windows.Forms.Button
$aiBtnNew.Text = '新对话'
$aiBtnNew.FlatStyle = 'System'
$aiBtnNew.Size = New-Object System.Drawing.Size(60, 26)
$aiBtnNew.Anchor = 'Top, Right'
$aiBtnNew.Visible = $false
$aiBtnNew.Add_Click({ Clear-AiChat })

$aiHeader.Controls.AddRange(@($aiTitle, $aiQuota, $aiBtnNew, $aiBtnSub, $aiBtnLogout, $aiBtnClose))
$aiHeader.Add_Resize({
  $right = $aiHeader.ClientSize.Width - 8
  $aiBtnClose.Left = $right - $aiBtnClose.Width
  $aiBtnClose.Top = 12
  $aiBtnLogout.Left = $aiBtnClose.Left - 4 - $aiBtnLogout.Width
  $aiBtnLogout.Top = 12
  $aiBtnSub.Left = $aiBtnLogout.Left - 4 - $aiBtnSub.Width
  $aiBtnSub.Top = 12
  $aiBtnNew.Left = $aiBtnSub.Left - 4 - $aiBtnNew.Width
  $aiBtnNew.Top = 12
})

$panelAiLogin = New-Object System.Windows.Forms.Panel
$panelAiLogin.Dock = 'Fill'
$panelAiLogin.Padding = New-Object System.Windows.Forms.Padding(20)

$lblAiLoginHint = New-Object System.Windows.Forms.Label
$lblAiLoginHint.Text = '使用 Booxin 账号登录后即可与 Agent 对话'
$lblAiLoginHint.ForeColor = [System.Drawing.Color]::FromArgb(90, 100, 120)
$lblAiLoginHint.Location = New-Object System.Drawing.Point(20, 24)
$lblAiLoginHint.Size = New-Object System.Drawing.Size(340, 36)

$lblAiUser = New-Object System.Windows.Forms.Label
$lblAiUser.Text = '用户名'
$lblAiUser.Location = New-Object System.Drawing.Point(20, 72)
$lblAiUser.AutoSize = $true

$txtAiUser = New-Object System.Windows.Forms.TextBox
$txtAiUser.Location = New-Object System.Drawing.Point(20, 96)
$txtAiUser.Size = New-Object System.Drawing.Size(340, 28)
$txtAiUser.Anchor = 'Top, Left, Right'

$lblAiPass = New-Object System.Windows.Forms.Label
$lblAiPass.Text = '密码'
$lblAiPass.Location = New-Object System.Drawing.Point(20, 136)
$lblAiPass.AutoSize = $true

$txtAiPass = New-Object System.Windows.Forms.TextBox
$txtAiPass.Location = New-Object System.Drawing.Point(20, 160)
$txtAiPass.Size = New-Object System.Drawing.Size(340, 28)
$txtAiPass.UseSystemPasswordChar = $true
$txtAiPass.Anchor = 'Top, Left, Right'

$btnAiLogin = New-Object System.Windows.Forms.Button
$btnAiLogin.Text = '登录'
$btnAiLogin.Size = New-Object System.Drawing.Size(120, 34)
$btnAiLogin.Location = New-Object System.Drawing.Point(20, 208)
$btnAiLogin.FlatStyle = 'System'
$btnAiLogin.Add_Click({ Do-AiLogin })

$panelAiLogin.Controls.AddRange(@($lblAiLoginHint, $lblAiUser, $txtAiUser, $lblAiPass, $txtAiPass, $btnAiLogin))
$panelAiLogin.Add_Resize({
  $w = [Math]::Max(160, $panelAiLogin.ClientSize.Width - 40)
  $txtAiUser.Width = $w
  $txtAiPass.Width = $w
})

$panelAiChat = New-Object System.Windows.Forms.Panel
$panelAiChat.Dock = 'Fill'
$panelAiChat.Visible = $false

$aiFeed = New-Object System.Windows.Forms.RichTextBox
$aiFeed.Dock = 'Fill'
$aiFeed.ReadOnly = $true
$aiFeed.BorderStyle = 'None'
$aiFeed.BackColor = [System.Drawing.Color]::FromArgb(248, 249, 251)
$aiFeed.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 9)
$aiFeed.DetectUrls = $true

$aiComposer = New-Object System.Windows.Forms.Panel
$aiComposer.Dock = 'Bottom'
$aiComposer.Height = 140
$aiComposer.BackColor = [System.Drawing.Color]::FromArgb(236, 240, 245)
$aiComposer.Padding = New-Object System.Windows.Forms.Padding(10, 8, 10, 8)

$txtAiAsk = New-Object System.Windows.Forms.TextBox
$txtAiAsk.Multiline = $true
$txtAiAsk.ScrollBars = 'Vertical'
$txtAiAsk.Location = New-Object System.Drawing.Point(10, 8)
$txtAiAsk.Size = New-Object System.Drawing.Size(360, 72)
$txtAiAsk.Anchor = 'Top, Left, Right'
$txtAiAsk.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 9)

$chkAiContext = New-Object System.Windows.Forms.CheckBox
$chkAiContext.Text = '附带当前文件'
$chkAiContext.AutoSize = $true
$chkAiContext.Checked = $true
$chkAiContext.Location = New-Object System.Drawing.Point(10, 90)
$chkAiContext.Anchor = 'Bottom, Left'

$btnAiSend = New-Object System.Windows.Forms.Button
$btnAiSend.Text = '发送'
$btnAiSend.Size = New-Object System.Drawing.Size(88, 30)
$btnAiSend.Anchor = 'Bottom, Right'
$btnAiSend.FlatStyle = 'System'
$btnAiSend.Add_Click({ Send-AiMessage })

$aiComposer.Controls.AddRange(@($txtAiAsk, $chkAiContext, $btnAiSend))
$aiComposer.Add_Resize({
  $txtAiAsk.Width = [Math]::Max(120, $aiComposer.ClientSize.Width - 20)
  $btnAiSend.Left = $aiComposer.ClientSize.Width - 10 - $btnAiSend.Width
  $btnAiSend.Top = $aiComposer.ClientSize.Height - 10 - $btnAiSend.Height
  $chkAiContext.Top = $btnAiSend.Top + 4
})

$panelAiChat.Controls.Add($aiFeed)
$panelAiChat.Controls.Add($aiComposer)

$panelAi.Controls.Add($panelAiChat)
$panelAi.Controls.Add($panelAiLogin)
$panelAi.Controls.Add($aiHeader)

$splitHost.Panel1.Controls.Add($contentHost)
$splitHost.Panel2.Controls.Add($panelAi)
$hostPanel.Controls.Add($splitHost)

$status = New-Object System.Windows.Forms.StatusStrip
$status.Dock = 'Bottom'
$statusLabel = New-Object System.Windows.Forms.ToolStripStatusLabel
$statusLabel.Spring = $true
$statusLabel.TextAlign = 'MiddleLeft'
$statusLabel.Text = '就绪'
[void]$status.Items.Add($statusLabel)

$form.Controls.Add($hostPanel)
$form.Controls.Add($status)
$form.Controls.Add($tool)

$form.Add_KeyDown({
  param($sender, $e)
  if ($e.Control -and $e.KeyCode -eq 'S') {
    [void](Save-CurrentFile)
    $e.SuppressKeyPress = $true
  }
  if ($e.Control -and $e.KeyCode -eq 'O') {
    $dir = Pick-Folder '选择插件项目文件夹'
    if ($dir) { Open-Project $dir }
    $e.SuppressKeyPress = $true
  }
  if ($e.Control -and $e.KeyCode -eq 'B') {
    Pack-Current
    $e.SuppressKeyPress = $true
  }
  if ($e.Control -and $e.KeyCode -eq 'N') {
    if ($script:ProjectRoot) { New-ProjectFile }
    $e.SuppressKeyPress = $true
  }
  if ($e.Control -and $e.KeyCode -eq 'L') {
    Toggle-AiPanel
    $e.SuppressKeyPress = $true
  }
})
$form.KeyPreview = $true

$form.Add_FormClosing({
  param($sender, $e)
  if (-not (Confirm-SaveIfDirty)) {
    $e.Cancel = $true
  }
})

$txtAiPass.Add_KeyDown({
  param($sender, $e)
  if ($e.KeyCode -eq 'Enter') {
    Do-AiLogin
    $e.SuppressKeyPress = $true
  }
})

$txtAiAsk.Add_KeyDown({
  param($sender, $e)
  if ($e.Control -and $e.KeyCode -eq 'Enter') {
    Send-AiMessage
    $e.SuppressKeyPress = $true
  }
})

Show-Welcome
Append-Log 'Booxin Studio — 微型 IDE 就绪。Ctrl+L 切换 Agent 面板。'
Update-AiAuthUi

$form.Add_Shown({
  $script:AiPanelReady = $true
  try {
    $splitHost.Panel1MinSize = 480
    $splitHost.Panel2MinSize = 280
    $splitMain.Panel1MinSize = 80
    $splitMain.Panel2MinSize = 60
    $splitEdit.Panel1MinSize = 160
    $splitEdit.Panel2MinSize = 200
    $h = [Math]::Max(200, $splitMain.ClientSize.Height)
    $w = [Math]::Max(400, $splitEdit.ClientSize.Width)
    $splitMain.SplitterDistance = [Math]::Max(
      $splitMain.Panel1MinSize,
      [Math]::Min([int]($h * 0.74), $h - $splitMain.Panel2MinSize - 1)
    )
    $splitEdit.SplitterDistance = [Math]::Max(
      $splitEdit.Panel1MinSize,
      [Math]::Min([int]($w * 0.26), $w - $splitEdit.Panel2MinSize - 1)
    )
  } catch {
    Append-Log ("布局提示: " + $_)
  }
  Set-AiPanelVisible $true
  Refresh-AiSession $true
  Check-StudioUpdate $true
})

[void]$form.ShowDialog()

try {
  Invoke-RestMethod -Uri "http://127.0.0.1:$Port/shutdown" -Method Post `
    -Body (@{ token = $Token } | ConvertTo-Json) -ContentType 'application/json' -TimeoutSec 3 | Out-Null
} catch { }
