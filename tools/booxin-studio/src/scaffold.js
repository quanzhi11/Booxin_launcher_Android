import fs from 'node:fs';
import path from 'node:path';

const TEMPLATES = {
  greeting: {
    title: '按时段问候',
    folder: 'my-greeting',
    manifest: (id) => ({
      schema: 1,
      id,
      name: '按时段问候',
      version: '1.0.0',
      type: 'ui',
      minLauncher: '5.3.6',
      description: '启用后主页显示早上好 / 中午好 / 晚上好',
      features: { homeGreetingByTime: true },
      theme: {
        welcomeMorning: '早上好',
        welcomeNoon: '中午好',
        welcomeEvening: '晚上好',
      },
    }),
    readme:
      '按时段问候插件\n\n1. 用 Studio「一键打包」生成 zip\n2. 启动器 → 本机插件 → 导入 zip → 启用\n3. 可在 booxin-plugin.json 的 theme 里改三句问候语\n',
  },
  font: {
    title: '自定义字体',
    folder: 'my-ui-font',
    manifest: (id) => ({
      schema: 1,
      id,
      name: '自定义界面字体',
      version: '1.0.0',
      type: 'ui',
      minLauncher: '5.3.6',
      description: '启用后启动器界面使用包内 fonts/font.ttf',
      font: 'fonts/font.ttf',
      features: { customFont: true },
    }),
    dirs: ['fonts'],
    files: {
      'fonts/README.txt':
        '把你的字体文件重命名为 font.ttf（或 .otf 后改清单里的 font 字段）放到本目录。\n',
    },
    readme:
      '自定义字体插件\n\n1. 将 .ttf/.otf 放到 fonts\\font.ttf\n2. Studio「一键打包」\n3. 启动器导入 zip 并启用\n',
  },
  icon: {
    title: '主页品牌图标',
    folder: 'my-brand-icon',
    manifest: (id) => ({
      schema: 1,
      id,
      name: '自定义主页图标',
      version: '1.0.0',
      type: 'ui',
      minLauncher: '5.3.6',
      description: '启用后主页左侧品牌图标换成 icon.png',
      launcherIcon: 'icon.png',
      features: { customLauncherIcon: true },
    }),
    files: {
      'ICON.txt': '请把 PNG 图标命名为 icon.png 放在本目录（建议正方形、透明底）。\n',
    },
    readme:
      '主页图标插件\n\n1. 放入 icon.png\n2. Studio「一键打包」\n3. 启动器导入 zip 并启用\n',
  },
  theme: {
    title: '完整主题包',
    folder: 'my-full-theme',
    manifest: (id) => ({
      schema: 1,
      id,
      name: '完整主题包',
      version: '1.0.0',
      type: 'ui',
      minLauncher: '5.3.6',
      description: '品牌 / 配色 / 壁纸 / 问候 / 导航文案',
      launcherIcon: 'icon.png',
      font: 'fonts/font.ttf',
      features: {
        customTheme: true,
        homeGreetingByTime: true,
        customPages: true,
      },
      theme: {
        brandName: 'MyLauncher',
        welcomeMorning: '早上好',
        welcomeNoon: '中午好',
        welcomeEvening: '晚上好',
        homeLaunchText: '开始游戏',
        homeSwitchVersionText: '切换版本',
        homeAccountText: '账号管理',
        homeStatusText: '主题包已启用',
        homeSelectedVersionLabel: '当前版本',
        hideOrbs: true,
        backgroundImage: 'backgrounds/bg.png',
        backgroundAlpha: 0.85,
        colors: {
          primary: '#449AD8',
          accent: '#FF8A2B',
          text: '#F2F6FF',
          textSecondary: '#B4C0D4',
          rim: '#88FFFFFF',
          glassPrimary: '#CC2A6FA8',
          surface: '#141A26',
          navSelected: '#FFFFFF',
          navUnselected: '#B4C0D4',
        },
        nav: {
          home: '主页',
          versions: '版本',
          community: '社区',
          multiplayer: '联机',
          ai: 'AI',
          pluginStore: '插件',
          settings: '设置',
        },
      },
      pages: [
        {
          id: 'about',
          title: '关于主题',
          path: 'pages/about.html',
        },
      ],
    }),
    dirs: ['fonts', 'backgrounds', 'pages'],
    files: {
      'fonts/README.txt': '可选：放入 font.ttf\n',
      'backgrounds/README.txt': '可选：放入 bg.png（或 loop.mp4）\n',
      'ICON.txt': '可选：放入 icon.png\n',
      'pages/about.html': `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>关于主题</title>
  <style>
    body { margin: 0; font-family: sans-serif; background: #141A26; color: #F2F6FF; padding: 24px; }
    h1 { font-size: 20px; }
    p { color: #B4C0D4; line-height: 1.6; }
    button { margin-top: 16px; padding: 10px 16px; border: 0; border-radius: 8px; background: #449AD8; color: #fff; }
  </style>
</head>
<body>
  <h1>主题包示例页</h1>
  <p>这是 customPages 示例。可用启动器桥 toast / navigate / close。</p>
  <button type="button" onclick="booxinToast()">提示</button>
  <script>
    function booxinToast() {
      try { window.BooxinBridge && BooxinBridge.toast('主题页 OK'); }
      catch (e) { alert('主题页 OK'); }
    }
  </script>
</body>
</html>
`,
    },
    readme:
      '完整主题包\n\n放入（可选）：\n  icon.png\n  fonts\\\\font.ttf\n  backgrounds\\\\bg.png\n\n编辑 booxin-plugin.json 改配色/文案。\nStudio「一键打包」后导入启动器。\n',
  },
};

export function listTemplates() {
  return Object.entries(TEMPLATES).map(([key, t]) => ({ key, title: t.title, folder: t.folder }));
}

export function scaffoldTemplate(kind, outDir, idOverride) {
  const tpl = TEMPLATES[kind];
  if (!tpl) {
    throw new Error(`未知模板: ${kind}（可选: ${Object.keys(TEMPLATES).join(', ')}）`);
  }
  const abs = path.resolve(outDir || path.join(process.cwd(), tpl.folder));
  if (fs.existsSync(abs) && fs.readdirSync(abs).length > 0) {
    throw new Error(`目录非空，换个路径: ${abs}`);
  }
  fs.mkdirSync(abs, { recursive: true });
  for (const d of tpl.dirs || []) {
    fs.mkdirSync(path.join(abs, d), { recursive: true });
  }
  const id = String(idOverride || path.basename(abs) || tpl.folder).trim() || tpl.folder;
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(id)) {
    throw new Error('插件 id 仅允许英文、数字、点、下划线、横线');
  }
  const manifest = tpl.manifest(id);
  fs.writeFileSync(
    path.join(abs, 'booxin-plugin.json'),
    `${JSON.stringify(manifest, null, 2)}\n`,
    'utf8',
  );
  if (tpl.readme) {
    fs.writeFileSync(path.join(abs, 'README.txt'), tpl.readme, 'utf8');
  }
  for (const [rel, content] of Object.entries(tpl.files || {})) {
    const fp = path.join(abs, rel);
    fs.mkdirSync(path.dirname(fp), { recursive: true });
    fs.writeFileSync(fp, content, 'utf8');
  }
  return { dir: abs, id, kind, title: tpl.title, files: listCreated(abs) };
}

function listCreated(root) {
  const out = [];
  const walk = (dir, prefix = '') => {
    for (const name of fs.readdirSync(dir)) {
      const abs = path.join(dir, name);
      const rel = prefix ? `${prefix}/${name}` : name;
      if (fs.statSync(abs).isDirectory()) walk(abs, rel);
      else out.push(rel.replace(/\\/g, '/'));
    }
  };
  walk(root);
  return out;
}
