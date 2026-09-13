import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

function writePs1(name, script) {
  const tmp = path.join(os.tmpdir(), `${name}-${process.pid}-${Date.now()}.ps1`);
  // BOM helps PowerShell parse Chinese titles correctly
  fs.writeFileSync(tmp, `\uFEFF${script}`, 'utf8');
  return tmp;
}

function runPs1(tmp) {
  const r = spawnSync(
    'powershell.exe',
    ['-STA', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', tmp],
    {
      encoding: 'utf8',
      windowsHide: true,
      timeout: 300_000,
      maxBuffer: 2 * 1024 * 1024,
    },
  );
  const out = `${r.stdout || ''}${r.stderr || ''}`.trim();
  if (r.error) throw r.error;
  // Prefer last non-empty line (dialog path); ignore Add-Type noise
  const lines = out
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean)
    .filter((l) => !/^Add-Type/i.test(l) && !/^WARNING/i.test(l));
  return lines[lines.length - 1] || '';
}

function ownerFormPrelude() {
  return `
Add-Type -AssemblyName System.Windows.Forms | Out-Null
Add-Type -AssemblyName System.Drawing | Out-Null
[System.Windows.Forms.Application]::EnableVisualStyles()
$owner = New-Object System.Windows.Forms.Form
$owner.Text = 'Booxin Studio'
$owner.TopMost = $true
$owner.ShowInTaskbar = $false
$owner.FormBorderStyle = 'FixedToolWindow'
$owner.StartPosition = 'Manual'
$owner.Size = New-Object System.Drawing.Size(1,1)
$owner.Location = New-Object System.Drawing.Point(-32000,-32000)
$owner.Opacity = 0
$owner.Show()
$owner.Activate()
try {
  $sig = '[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);'
  $type = Add-Type -MemberDefinition $sig -Name 'BooxinFg' -Namespace Win32 -PassThru
  [void]$type::SetForegroundWindow($owner.Handle)
} catch {}
`;
}

/** Windows folder picker; returns absolute path or null. */
export function pickFolder(title = '选择插件文件夹') {
  if (process.platform !== 'win32') return null;
  const desc = String(title || '选择文件夹').replace(/'/g, "''");
  const script = `
${ownerFormPrelude()}
try {
  $d = New-Object System.Windows.Forms.FolderBrowserDialog
  $d.Description = '${desc}'
  $d.ShowNewFolderButton = $true
  try { $d.UseDescriptionForTitle = $true } catch {}
  $r = $d.ShowDialog($owner)
  if ($r -eq [System.Windows.Forms.DialogResult]::OK -and $d.SelectedPath) {
    [Console]::Out.WriteLine($d.SelectedPath)
  }
} finally {
  try { $owner.Close() } catch {}
  try { $owner.Dispose() } catch {}
}
`;
  const tmp = writePs1('booxin-studio-pick', script);
  try {
    const out = runPs1(tmp);
    if (out && fs.existsSync(out) && fs.statSync(out).isDirectory()) return out;
    return null;
  } finally {
    try {
      fs.unlinkSync(tmp);
    } catch {
      /* ignore */
    }
  }
}

/** Windows save-file picker for zip. */
export function pickSaveZip(defaultName = 'plugin.zip') {
  if (process.platform !== 'win32') return null;
  const name = String(defaultName || 'plugin.zip').replace(/'/g, "''");
  const script = `
${ownerFormPrelude()}
try {
  $d = New-Object System.Windows.Forms.SaveFileDialog
  $d.Filter = 'Zip (*.zip)|*.zip'
  $d.FileName = '${name}'
  $d.AddExtension = $true
  $d.DefaultExt = 'zip'
  $d.OverwritePrompt = $true
  $r = $d.ShowDialog($owner)
  if ($r -eq [System.Windows.Forms.DialogResult]::OK -and $d.FileName) {
    [Console]::Out.WriteLine($d.FileName)
  }
} finally {
  try { $owner.Close() } catch {}
  try { $owner.Dispose() } catch {}
}
`;
  const tmp = writePs1('booxin-studio-save', script);
  try {
    const out = runPs1(tmp);
    return out || null;
  } finally {
    try {
      fs.unlinkSync(tmp);
    } catch {
      /* ignore */
    }
  }
}
