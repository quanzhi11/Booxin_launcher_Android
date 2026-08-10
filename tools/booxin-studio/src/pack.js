import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { findManifest, validatePath } from './validate.js';

export function packUiPlugin(dirPath, outPath) {
  const absDir = path.resolve(dirPath);
  if (!fs.existsSync(absDir) || !fs.statSync(absDir).isDirectory()) {
    throw new Error(`目录不存在: ${absDir}`);
  }
  const report = validatePath(absDir);
  if (!report.ok) {
    throw new Error(`校验失败，拒绝打包：\n${report.errors.join('\n')}`);
  }
  const manifest = findManifest(absDir);
  const id = report.id;
  const dest = path.resolve(outPath || path.join(absDir, '..', `${id}.zip`));
  if (fs.existsSync(dest)) fs.unlinkSync(dest);

  // Pack directory contents (not the parent folder name) so zip root has booxin-plugin.json.
  const entries = fs.readdirSync(absDir).map((name) => path.join(absDir, name));
  if (!entries.length) throw new Error('目录为空');

  if (process.platform === 'win32') {
    const psFiles = entries
      .map((f) => `'${f.replace(/'/g, "''")}'`)
      .join(',');
    const ps = `
$ErrorActionPreference='Stop'
$dest='${dest.replace(/'/g, "''")}'
$files=@(${psFiles})
Compress-Archive -Path $files -DestinationPath $dest -Force
`;
    const r = spawnSync(
      'powershell.exe',
      ['-NoProfile', '-Command', ps],
      { encoding: 'utf8' },
    );
    if (r.status !== 0) {
      throw new Error(r.stderr || r.stdout || 'Compress-Archive 失败');
    }
  } else {
    const r = spawnSync(
      'zip',
      ['-r', '-q', dest, ...entries.map((e) => path.basename(e))],
      { cwd: absDir, encoding: 'utf8' },
    );
    if (r.status !== 0) {
      throw new Error(r.stderr || 'zip 命令失败（请安装 zip）');
    }
  }

  if (!fs.existsSync(dest) || fs.statSync(dest).size <= 0) {
    throw new Error(`打包结果无效: ${dest}`);
  }
  return { dest, report, manifest };
}
