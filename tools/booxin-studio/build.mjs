import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as esbuild from 'esbuild';

const root = path.dirname(fileURLToPath(import.meta.url));
const dist = path.join(root, 'dist');
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const appVersion = String(pkg.version || '2.0.0');
const webSrc = path.join(root, 'web');

function wipeDist() {
  if (!fs.existsSync(dist)) {
    fs.mkdirSync(dist, { recursive: true });
    return;
  }
  try {
    fs.rmSync(dist, { recursive: true, force: true });
  } catch {
    const bak = path.join(root, `dist-old-${Date.now()}`);
    try {
      fs.renameSync(dist, bak);
      console.warn('dist 被占用，已改名为', path.basename(bak));
    } catch (e) {
      throw new Error(`无法清理 dist（请先关闭 BooxinStudio.exe）: ${e.message}`);
    }
  }
  fs.mkdirSync(dist, { recursive: true });
}
wipeDist();

function copyDir(src, dest) {
  fs.mkdirSync(dest, { recursive: true });
  for (const name of fs.readdirSync(src)) {
    const from = path.join(src, name);
    const to = path.join(dest, name);
    if (fs.statSync(from).isDirectory()) copyDir(from, to);
    else fs.copyFileSync(from, to);
  }
}
copyDir(webSrc, path.join(dist, 'web'));

// Package Monaco / xterm into web/vendor so SEA exe works offline without node_modules
const vendorCopies = [
  [path.join(root, 'node_modules', 'monaco-editor', 'min'), path.join(dist, 'web', 'vendor', 'monaco')],
  [path.join(root, 'node_modules', '@xterm', 'xterm'), path.join(dist, 'web', 'vendor', 'xterm')],
  [path.join(root, 'node_modules', '@xterm', 'addon-fit'), path.join(dist, 'web', 'vendor', 'addon-fit')],
];
for (const [from, to] of vendorCopies) {
  if (!fs.existsSync(from)) {
    throw new Error(`缺少依赖: ${from}（请先 npm install）`);
  }
  console.log('Copy vendor →', path.relative(root, to));
  copyDir(from, to);
}

const common = {
  bundle: true,
  platform: 'node',
  format: 'cjs',
  target: 'node20',
  logLevel: 'info',
};

await esbuild.build({
  ...common,
  entryPoints: [path.join(root, 'src', 'cli.js')],
  outfile: path.join(dist, 'cli.cjs'),
  banner: {
    // SEA / pkg 兼容：标记为可执行包，CLI 无参时打印帮助
    js: `process.pkg = process.pkg || { sea: true };\nglobalThis.BOOXIN_STUDIO_VERSION = ${JSON.stringify(appVersion)};`,
  },
});

await esbuild.build({
  ...common,
  entryPoints: [path.join(root, 'src', 'gui.js')],
  outfile: path.join(dist, 'gui.cjs'),
  banner: {
    js: `process.pkg = process.pkg || { sea: true };\nglobalThis.BOOXIN_STUDIO_VERSION = ${JSON.stringify(appVersion)};\n`,
  },
  define: {
    // Prevent empty-import-meta from becoming runtime crashes in helpers
    'import.meta.url': '""',
  },
});

function findPostject() {
  const candidates = [
    path.join(root, 'node_modules', 'postject', 'dist', 'cli.js'),
    path.join(root, 'node_modules', '@yao-pkg', 'pkg', 'node_modules', 'postject', 'dist', 'cli.js'),
  ];
  for (const c of candidates) {
    if (fs.existsSync(c)) return c;
  }
  throw new Error('postject not found；请先 npm install');
}

/** 用本机 node.exe + SEA blob 生成单文件 exe（无需 pkg 远程基座） */
function buildSea(entryCjs, outExeName) {
  const configPath = path.join(dist, `${outExeName}.sea-config.json`);
  const blobPath = path.join(dist, `${outExeName}.blob`);
  const outExe = path.join(dist, `${outExeName}.exe`);
  fs.writeFileSync(
    configPath,
    JSON.stringify(
      {
        main: path.basename(entryCjs),
        output: path.basename(blobPath),
        disableExperimentalSEAWarning: true,
      },
      null,
      2,
    ),
  );

  const gen = spawnSync(
    process.execPath,
    ['--experimental-sea-config', path.basename(configPath)],
    { cwd: dist, stdio: 'inherit' },
  );
  if (gen.status !== 0) {
    throw new Error(`SEA config failed for ${outExeName}`);
  }

  fs.copyFileSync(process.execPath, outExe);

  // Windows: strip signature if signtool available (optional)
  const postject = findPostject();
  const fuse = 'NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2';
  const inj = spawnSync(
    process.execPath,
    [
      postject,
      outExe,
      'NODE_SEA_BLOB',
      blobPath,
      '--sentinel-fuse',
      fuse,
    ],
    { cwd: root, stdio: 'inherit' },
  );
  if (inj.status !== 0) {
    throw new Error(`postject failed for ${outExeName}`);
  }

  try {
    fs.unlinkSync(blobPath);
    fs.unlinkSync(configPath);
  } catch {
    /* ignore */
  }
}

console.log('Building SEA executables from local Node...');
buildSea(path.join(dist, 'cli.cjs'), 'BooxinStudio-CLI');
buildSea(path.join(dist, 'gui.cjs'), 'BooxinStudio');

const readme = `Booxin Studio ${appVersion}
==================

微型插件 IDE（免安装 Node）：

1) BooxinStudio.exe          窗口版 IDE（推荐）
2) BooxinStudio-CLI.exe      命令行版

窗口版（Edge 应用窗口 + Monaco 编辑器）：
  · 语法高亮、括号匹配、智能补全（含 booxin-plugin.json 字段提示）
  · 左侧文件树：新建/重命名/删除；中间多标签编辑；右侧 Agent
  · 顶部一键打包 / 校验 / 检查更新
  · 有新版本时仅提示并打开下载页（需手动下载）
  · 快捷键 Ctrl+O 打开 / Ctrl+S 保存 / Ctrl+B 打包 / Ctrl+N 新建文件 / Ctrl+L Agent

发布时请将整个 dist 目录一起分发（BooxinStudio.exe + web/ 必须同目录）。
web/vendor 含 Monaco / 终端组件，勿删。

命令行版示例：
  BooxinStudio-CLI.exe new theme
  BooxinStudio-CLI.exe pack
  BooxinStudio-CLI.exe validate 插件目录
  BooxinStudio-CLI.exe help

装到手机请用启动器「插件」安装 zip。

AI 账号：右侧 Agent 面板用用户名+密码登录（与启动器联机账号相同）。
更新清单：https://www.boonix.art/server_update/studio.json
下载页：https://www.boonix.art/server_update/studio.html
（Studio 只提示手动下载，不自动拉取安装包）
`;
fs.writeFileSync(path.join(dist, '使用说明.txt'), '\uFEFF' + readme, 'utf8');
fs.writeFileSync(path.join(dist, 'README.txt'), readme, 'utf8');

console.log('Done →', dist);
for (const name of fs.readdirSync(dist)) {
  const st = fs.statSync(path.join(dist, name));
  if (st.isFile()) {
    console.log(`  ${name}  (${Math.round(st.size / 1024)} KB)`);
  } else if (st.isDirectory()) {
    console.log(`  ${name}/`);
  }
}
