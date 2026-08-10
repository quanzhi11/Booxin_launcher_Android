import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/** Windows folder picker via PowerShell; returns absolute path or null. */
export function pickFolder(title = '选择插件文件夹') {
  if (process.platform !== 'win32') return null;
  const script = `
Add-Type -AssemblyName System.Windows.Forms | Out-Null
$d = New-Object System.Windows.Forms.FolderBrowserDialog
$d.Description = '${title.replace(/'/g, "''")}'
$d.ShowNewFolderButton = $true
if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
  Write-Output $d.SelectedPath
}
`;
  const tmp = path.join(os.tmpdir(), `booxin-studio-pick-${Date.now()}.ps1`);
  fs.writeFileSync(tmp, script, 'utf8');
  try {
    const r = spawnSync(
      'powershell.exe',
      ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', tmp],
      { encoding: 'utf8' },
    );
    const out = (r.stdout || '').trim();
    return out || null;
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
  const script = `
Add-Type -AssemblyName System.Windows.Forms | Out-Null
$d = New-Object System.Windows.Forms.SaveFileDialog
$d.Filter = 'Zip (*.zip)|*.zip'
$d.FileName = '${defaultName.replace(/'/g, "''")}'
$d.AddExtension = $true
$d.DefaultExt = 'zip'
if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
  Write-Output $d.FileName
}
`;
  const tmp = path.join(os.tmpdir(), `booxin-studio-save-${Date.now()}.ps1`);
  fs.writeFileSync(tmp, script, 'utf8');
  try {
    const r = spawnSync(
      'powershell.exe',
      ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', tmp],
      { encoding: 'utf8' },
    );
    const out = (r.stdout || '').trim();
    return out || null;
  } finally {
    try {
      fs.unlinkSync(tmp);
    } catch {
      /* ignore */
    }
  }
}
