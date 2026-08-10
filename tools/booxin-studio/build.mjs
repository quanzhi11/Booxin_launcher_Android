import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as esbuild from 'esbuild';

const root = path.dirname(fileURLToPath(import.meta.url));
const dist = path.join(root, 'dist');
const ps1 = fs.readFileSync(path.join(root, 'src', 'gui-form.ps1'), 'utf8');

fs.rmSync(dist, { recursive: true, force: true });
fs.mkdirSync(dist, { recursive: true });

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
    js: 'process.pkg = process.pkg || { sea: true };',
  },
});

await esbuild.build({
  ...common,
  entryPoints: [path.join(root, 'src', 'gui.js')],
  outfile: path.join(dist, 'gui.cjs'),
  banner: {
    js: `process.pkg = process.pkg || { sea: true };\nglobalThis.GUI_FORM_PS1 = ${JSON.stringify(ps1)};`,
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

const readme = `Booxin Studio 1.3.0
==================

两个版本（免安装 Node）：

1) BooxinStudio.exe          窗口版（推荐）
2) BooxinStudio-CLI.exe      命令行版

窗口版：双击即可，按钮新建模板 / 打包 / 校验。

命令行版示例：
  BooxinStudio-CLI.exe new theme
  BooxinStudio-CLI.exe pack
  BooxinStudio-CLI.exe validate 插件目录
  BooxinStudio-CLI.exe help

装到手机请用启动器「插件」安装 zip。
`;
fs.writeFileSync(path.join(dist, '使用说明.txt'), '\uFEFF' + readme, 'utf8');
fs.writeFileSync(path.join(dist, 'README.txt'), readme, 'utf8');

console.log('Done →', dist);
for (const name of fs.readdirSync(dist)) {
  const st = fs.statSync(path.join(dist, name));
  if (st.isFile()) {
    console.log(`  ${name}  (${Math.round(st.size / 1024)} KB)`);
  }
}
