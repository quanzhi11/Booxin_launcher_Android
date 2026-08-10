import fs from 'node:fs';
import path from 'node:path';
import { DEFAULT_PACKAGE, ensureDevice, runAdb, shell } from './adb.js';
import { packUiPlugin } from './pack.js';
import { pickFolder, pickSaveZip } from './pick.js';
import { listTemplates, scaffoldTemplate } from './scaffold.js';
import { printReport, validatePath } from './validate.js';

function arg(args, i, name) {
  const v = args[i];
  if (!v) throw new Error(`缺少参数: ${name}`);
  return v;
}

function writeFileViaRunAs(packageName, relPath, contentBuf) {
  const b64 = contentBuf.toString('base64');
  if (b64.length > 350_000) {
    throw new Error(`文件过大，无法经 shell 写入: ${relPath}`);
  }
  const r = runAdb([
    'shell',
    `run-as ${packageName} sh -c 'echo ${b64} | base64 -d > ${relPath}'`,
  ]);
  if (r.status !== 0) {
    throw new Error(r.stderr || `写入失败: ${relPath}`);
  }
}

export const COMMANDS = {
  help: {
    usage: 'help',
    desc: '显示命令列表',
    run() {
      return helpText();
    },
  },

  doctor: {
    usage: 'doctor',
    desc: '检查 node / adb / 设备',
    run() {
      const lines = [];
      lines.push(`node: ${process.version}`);
      lines.push(`platform: ${process.platform}`);
      try {
        const v = runAdb(['version']);
        lines.push(v.stdout.split(/\r?\n/)[0] || 'adb: ok');
      } catch (e) {
        lines.push(`adb: FAIL (${e.message})`);
        return lines.join('\n');
      }
      try {
        const devices = ensureDevice();
        lines.push(`devices: ${devices.join(', ')}`);
      } catch (e) {
        lines.push(`devices: FAIL (${e.message})`);
      }
      lines.push(`package: ${DEFAULT_PACKAGE}`);
      return lines.join('\n');
    },
  },

  validate: {
    usage: 'validate <dir|manifest.json>',
    desc: '校验 UI / 渲染器清单',
    run(args) {
      return printReport(validatePath(arg(args, 0, 'path')));
    },
  },

  new: {
    usage: 'new <greeting|font|icon|theme> [out-dir] [id]',
    desc: '新建插件模板目录（推荐从此开始）',
    run(args) {
      const kind = arg(args, 0, 'template');
      const created = scaffoldTemplate(kind, args[1], args[2]);
      const bin = process.pkg ? 'BooxinStudio-CLI.exe' : 'booxin-studio';
      return [
        `已创建: ${created.dir}`,
        `模板: ${created.title} (${created.kind})`,
        `id: ${created.id}`,
        '',
        '下一步：按 README 放入资源后，用窗口版点「打包」，或执行',
        `  ${bin} pack "${created.dir}"`,
        '然后把 zip 放到启动器「插件」里安装/上架。',
      ].join('\n');
    },
  },

  templates: {
    usage: 'templates',
    desc: '列出可新建的插件模板',
    run() {
      return listTemplates()
        .map((t) => `- ${t.key.padEnd(10)} ${t.title}  → 默认目录 ${t.folder}`)
        .join('\n');
    },
  },

  pack: {
    usage: 'pack [plugin-dir] [out.zip]',
    desc: '打包 UI 插件 zip（可弹窗选文件夹；主推流程）',
    run(args) {
      let dir = args[0];
      if (!dir) {
        dir = pickFolder('选择要打包的插件文件夹（内含 booxin-plugin.json）');
        if (!dir) throw new Error('已取消选择文件夹');
      }
      let out = args[1];
      if (!out && process.platform === 'win32' && !args[0]) {
        // When using folder picker, also offer save path.
        const base = path.basename(path.resolve(dir));
        out = pickSaveZip(`${base}.zip`) || undefined;
      }
      const { dest, report } = packUiPlugin(dir, out);
      return [
        printReport(report),
        `packed: ${dest}`,
        '',
        '安装方式（推荐）：启动器 → 插件 → 申请上架/本机安装，选这个 zip',
        '（push 仅适合 debug 包调试，正式版常失败）',
      ].join('\n');
    },
  },

  push: {
    usage: 'push <plugin-dir> [package]',
    desc: '【高级/常失败】adb 推到手机；正式版建议改用 pack',
    run(args) {
      const dir = arg(args, 0, 'plugin-dir');
      const packageName = args[1] || DEFAULT_PACKAGE;
      ensureDevice();
      const report = validatePath(dir);
      if (!report.ok) throw new Error(`校验失败:\n${report.errors.join('\n')}`);
      const id = report.id;
      const absDir = path.resolve(dir);
      const mk = runAdb([
        'shell',
        `run-as ${packageName} mkdir -p files/booxin-runtime/ui-plugins/${id}`,
      ]);
      if (mk.status !== 0) {
        throw new Error(
          mk.stderr ||
            'run-as 失败：release 包可能不允许。请用 debug 包，或 pack 后走启动器安装。',
        );
      }
      const warnings = [];
      for (const name of fs.readdirSync(absDir)) {
        if (name.startsWith('.')) continue;
        const full = path.join(absDir, name);
        if (!fs.statSync(full).isFile()) continue;
        try {
          writeFileViaRunAs(
            packageName,
            `files/booxin-runtime/ui-plugins/${id}/${name}`,
            fs.readFileSync(full),
          );
        } catch (e) {
          warnings.push(`${name}: ${e.message}`);
        }
      }
      writeFileViaRunAs(
        packageName,
        `files/booxin-runtime/ui-plugins/${id}/.ready`,
        Buffer.from(String(Date.now())),
      );
      return [
        printReport(report),
        `pushed: files/booxin-runtime/ui-plugins/${id}/`,
        ...warnings.map((w) => `WARN: ${w}`),
        '下一步：打开启动器 → 插件 → 本机插件 → 刷新 → 回主页验证',
      ].join('\n');
    },
  },

  list: {
    usage: 'list [package]',
    desc: '列出手机上已安装的本机 UI 插件',
    run(args) {
      const packageName = args[0] || DEFAULT_PACKAGE;
      ensureDevice();
      const out = shell('ls -1 files/booxin-runtime/ui-plugins 2>/dev/null || true', {
        packageName,
      });
      const ids = out
        .split(/\r?\n/)
        .map((s) => s.trim())
        .filter(Boolean);
      if (!ids.length) return '(空) 未发现 ui-plugins';
      return ids
        .map((id) => {
          const disabled = shell(
            `test -f files/booxin-runtime/ui-plugins/${id}/.disabled && echo yes || echo no`,
            { packageName },
          ).trim();
          const hasJson = shell(
            `test -f files/booxin-runtime/ui-plugins/${id}/booxin-plugin.json && echo yes || echo no`,
            { packageName },
          ).trim();
          return `- ${id}  manifest=${hasJson}  disabled=${disabled}`;
        })
        .join('\n');
    },
  },

  disable: {
    usage: 'disable <id> [package]',
    desc: '禁用本机 UI 插件',
    run(args) {
      const id = arg(args, 0, 'id');
      const packageName = args[1] || DEFAULT_PACKAGE;
      ensureDevice();
      shell(`echo 1 > files/booxin-runtime/ui-plugins/${id}/.disabled`, {
        packageName,
      });
      return `disabled: ${id}`;
    },
  },

  enable: {
    usage: 'enable <id> [package]',
    desc: '启用本机 UI 插件',
    run(args) {
      const id = arg(args, 0, 'id');
      const packageName = args[1] || DEFAULT_PACKAGE;
      ensureDevice();
      shell(`rm -f files/booxin-runtime/ui-plugins/${id}/.disabled`, {
        packageName,
      });
      return `enabled: ${id}`;
    },
  },

  uninstall: {
    usage: 'uninstall <id> [package]',
    desc: '卸载本机 UI 插件目录',
    run(args) {
      const id = arg(args, 0, 'id');
      const packageName = args[1] || DEFAULT_PACKAGE;
      ensureDevice();
      shell(`rm -rf files/booxin-runtime/ui-plugins/${id}`, { packageName });
      return `uninstalled: ${id}`;
    },
  },

  pull: {
    usage: 'pull <id> [out-dir] [package]',
    desc: '从手机拉回某个 UI 插件清单',
    run(args) {
      const id = arg(args, 0, 'id');
      const outDir = path.resolve(args[1] || `./pulled-${id}`);
      const packageName = args[2] || DEFAULT_PACKAGE;
      ensureDevice();
      fs.mkdirSync(outDir, { recursive: true });
      const r = runAdb([
        'shell',
        `run-as ${packageName} cat files/booxin-runtime/ui-plugins/${id}/booxin-plugin.json`,
      ]);
      if (r.status !== 0 || !r.stdout.trim()) {
        throw new Error(r.stderr || '读取清单失败');
      }
      const dest = path.join(outDir, 'booxin-plugin.json');
      fs.writeFileSync(dest, r.stdout.endsWith('\n') ? r.stdout : `${r.stdout}\n`);
      return `saved: ${dest}`;
    },
  },
};

export function helpText() {
  const bin = process.pkg ? 'BooxinStudio-CLI.exe' : 'booxin-studio';
  const lines = [
    'Booxin Studio — 命令行版',
    '',
    '推荐：双击 BooxinStudio.exe（窗口版）',
    '',
    '命令行流程:',
    `  1) ${bin} new theme`,
    '  2) 按 README 放入图片/字体',
    `  3) ${bin} pack`,
    '  4) 启动器里安装/上架 zip',
    '',
    '命令:',
  ];
  for (const [, c] of Object.entries(COMMANDS)) {
    lines.push(`  ${c.usage.padEnd(42)} ${c.desc}`);
  }
  lines.push('');
  lines.push('示例:');
  lines.push(`  ${bin} new theme D:\\plugins\\cool-theme cool-theme`);
  lines.push(`  ${bin} pack`);
  lines.push(`  ${bin} validate D:\\plugins\\cool-theme`);
  return lines.join('\n');
}

export function menuText() {
  return [
    '========== Booxin Studio ==========',
    '官方插件打包（推荐用菜单，少敲命令）',
    '',
    '  1  新建：问候语插件',
    '  2  新建：字体插件',
    '  3  新建：主页图标插件',
    '  4  新建：完整主题包（功能最多）',
    '  5  打包 zip（弹窗选文件夹）',
    '  6  校验插件目录',
    '  7  环境检查 doctor',
    '  8  显示全部命令 help',
    '  0  退出',
    '',
    '说明：装到手机请用启动器安装 zip；',
    'adb push 仅 debug 调试用，正式版常失败。',
    '===================================',
  ].join('\n');
}

export function runLine(line) {
  const trimmed = line.trim();
  if (!trimmed) return '';
  const parts = tokenize(trimmed);
  const cmd = parts[0];
  const args = parts.slice(1);
  const def = COMMANDS[cmd];
  if (!def) throw new Error(`未知命令: ${cmd}（输入 help 查看）`);
  return def.run(args) ?? '';
}

function tokenize(line) {
  const out = [];
  let cur = '';
  let quote = null;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (quote) {
      if (ch === quote) quote = null;
      else cur += ch;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      continue;
    }
    if (/\s/.test(ch)) {
      if (cur) {
        out.push(cur);
        cur = '';
      }
      continue;
    }
    cur += ch;
  }
  if (cur) out.push(cur);
  return out;
}
