import fs from 'node:fs';
import path from 'node:path';

const TEMPLATES = {
  greeting: {
    title: '按时段问候',
    folder: 'my-greeting',
    manifest: (id) => ({
      id,
      name: '按时段问候',
      version: '1.0.0',
      type: 'ui',
      description: '启用后主页显示早上好/中午好/晚上好',
      features: { homeGreetingByTime: true },
    }),
  },
  font: {
    title: '自定义字体',
    folder: 'my-ui-font',
    manifest: (id) => ({
      id,
      name: '自定义界面字体',
      version: '1.0.0',
      type: 'ui',
      description: '启用后启动器界面使用包内 fonts/font.ttf',
      font: 'fonts/font.ttf',
      features: { customFont: true },
    }),
    dirs: ['fonts'],
    readme: '把 .ttf/.otf 放到 fonts\\font.ttf，然后执行：\n  booxin-studio pack .\n',
  },
  icon: {
    title: '主页品牌图标',
    folder: 'my-brand-icon',
    manifest: (id) => ({
      id,
      name: '自定义主页图标',
      version: '1.0.0',
      type: 'ui',
      description: '启用后主页左侧品牌图标换成 icon.png',
      launcherIcon: 'icon.png',
      features: { customLauncherIcon: true },
    }),
    readme: '把图片命名为 icon.png 放在本目录，然后：\n  booxin-studio pack .\n',
  },
  theme: {
    title: '完整主题包',
    folder: 'my-full-theme',
    manifest: (id) => ({
      id,
      name: 'Full Theme Pack',
      version: '1.0.0',
      type: 'ui',
      description: '品牌/配色/壁纸/主页按钮文案/字体/图标',
      launcherIcon: 'icon.png',
      font: 'fonts/font.ttf',
      features: {
        customTheme: true,
        homeGreetingByTime: true,
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
    }),
    dirs: ['fonts', 'backgrounds'],
    readme:
      '放入：\n  icon.png\n  fonts\\font.ttf\n  backgrounds\\bg.png（可选 loop.mp4）\n然后：\n  booxin-studio pack .\n',
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
  const id = String(idOverride || tpl.folder).trim() || tpl.folder;
  const manifest = tpl.manifest(id);
  fs.writeFileSync(
    path.join(abs, 'booxin-plugin.json'),
    `${JSON.stringify(manifest, null, 2)}\n`,
    'utf8',
  );
  if (tpl.readme) {
    fs.writeFileSync(path.join(abs, 'README.txt'), tpl.readme, 'utf8');
  }
  return { dir: abs, id, kind, title: tpl.title };
}
