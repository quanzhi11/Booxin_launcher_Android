import fs from 'node:fs';
import path from 'node:path';

const ILLEGAL_ID = /[\\/:*?"<>|]/;
const KNOWN_UI_FEATURES = new Set([
  'homeGreetingByTime',
  'customLauncherIcon',
  'customFont',
  'customTheme',
]);
const DEFAULT_ICON_CANDIDATES = [
  'icon.png',
  'icon.webp',
  'icon.jpg',
  'icon.jpeg',
  'launcher-icon.png',
  'assets/icon.png',
];
const DEFAULT_FONT_CANDIDATES = [
  'font.ttf',
  'font.otf',
  'fonts/font.ttf',
  'fonts/font.otf',
  'assets/font.ttf',
  'assets/font.otf',
];
const DEFAULT_BG_IMAGE = [
  'backgrounds/bg.png',
  'backgrounds/bg.webp',
  'backgrounds/bg.jpg',
  'background.png',
  'bg.png',
  'assets/background.png',
];
const DEFAULT_BG_VIDEO = [
  'backgrounds/loop.mp4',
  'backgrounds/bg.mp4',
  'background.mp4',
  'bg.mp4',
  'assets/background.mp4',
];
const FONT_EXT = new Set(['.ttf', '.otf', '.ttc']);
const IMAGE_EXT = new Set(['.png', '.webp', '.jpg', '.jpeg']);
const VIDEO_EXT = new Set(['.mp4', '.webm', '.mkv']);

export function readJson(file) {
  const text = fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '');
  try {
    return JSON.parse(text);
  } catch (e) {
    throw new Error(`JSON 无法解析: ${file}\n${e.message}`);
  }
}

export function findManifest(targetPath) {
  const abs = path.resolve(targetPath);
  if (!fs.existsSync(abs)) throw new Error(`路径不存在: ${abs}`);
  const st = fs.statSync(abs);
  if (st.isFile()) {
    const base = path.basename(abs);
    if (base === 'booxin-plugin.json' || base === 'plugin.json') return abs;
    throw new Error(`请指向 booxin-plugin.json 或 plugin.json，当前是: ${base}`);
  }
  const ui = path.join(abs, 'booxin-plugin.json');
  if (fs.existsSync(ui)) return ui;
  const native = path.join(abs, 'plugin.json');
  if (fs.existsSync(native)) return native;
  const assets = path.join(abs, 'assets', 'plugin.json');
  if (fs.existsSync(assets)) return assets;
  throw new Error(`目录内未找到 booxin-plugin.json / plugin.json: ${abs}`);
}

function firstDeclared(obj, features, keys) {
  for (const k of keys) {
    const v = obj[k] ?? features?.[k];
    const s = v != null ? String(v).trim() : '';
    if (s) return s.replace(/\\/g, '/').replace(/^\/+/, '');
  }
  return '';
}

function fileExistsNearManifest(manifestFile, relativePath) {
  if (!relativePath || relativePath.includes('..')) return false;
  const dir = path.dirname(manifestFile);
  const full = path.join(dir, relativePath);
  try {
    return fs.existsSync(full) && fs.statSync(full).isFile() && fs.statSync(full).size > 0;
  } catch {
    return false;
  }
}

function checkAsset(fileHint, declared, defaults, extSet, label, errors, warnings) {
  let resolved = null;
  if (declared) {
    if (declared.includes('..')) {
      errors.push(`${label} 路径不能包含 ..`);
      return null;
    }
    const ext = path.extname(declared).toLowerCase();
    if (ext && extSet && !extSet.has(ext)) {
      errors.push(`${label} 扩展名不支持: ${ext}`);
      return null;
    }
    if (fileHint && !fileExistsNearManifest(fileHint, declared)) {
      errors.push(`${label} 已声明但找不到文件: ${declared}`);
      return null;
    }
    return declared;
  }
  if (fileHint) {
    const hit = defaults.find((c) => fileExistsNearManifest(fileHint, c));
    if (hit) {
      warnings.push(`${label} 未声明路径，将使用默认候选: ${hit}`);
      return hit;
    }
  }
  return resolved;
}

export function validateUiManifest(obj, fileHint = '') {
  const errors = [];
  const warnings = [];
  const id = String(obj.id ?? '').trim();
  if (!id) errors.push('id 不能为空（安装会失败：plugin id 为空）');
  if (id && ILLEGAL_ID.test(id)) {
    warnings.push(`id 含非法字符，安装时会被替换为下划线: ${id}`);
  }
  const name = obj.name != null ? String(obj.name) : '';
  if (!name) warnings.push('建议填写 name（否则列表显示 id）');
  const version = obj.version != null ? String(obj.version) : '';
  if (!version) warnings.push('建议填写 version（默认 1.0.0）');

  const features = obj.features && typeof obj.features === 'object' ? obj.features : {};
  const theme = obj.theme && typeof obj.theme === 'object' ? obj.theme : null;
  const greeting =
    features.homeGreetingByTime === true || obj.homeGreetingByTime === true;
  const customIcon =
    features.customLauncherIcon === true || obj.customLauncherIcon === true;
  const customFont = features.customFont === true || obj.customFont === true;
  const customTheme =
    features.customTheme === true ||
    obj.customTheme === true ||
    theme != null;
  const launcherIcon = firstDeclared(obj, features, ['launcherIcon', 'icon']);
  const fontFile = firstDeclared(obj, features, ['font', 'fontFile']);
  const bgImage = theme
    ? firstDeclared(theme, null, ['backgroundImage', 'background'])
    : '';
  const bgVideo = theme ? firstDeclared(theme, null, ['backgroundVideo']) : '';

  if (!greeting && !customIcon && !customFont && !customTheme) {
    warnings.push(
      '未开启已接线 feature（homeGreetingByTime / customLauncherIcon / customFont / customTheme）。',
    );
  }

  for (const k of Object.keys(features)) {
    if (!KNOWN_UI_FEATURES.has(k)) {
      warnings.push(`未知 feature「${k}」会被忽略（可保留以兼容未来版本）`);
    }
  }

  let iconResolved = null;
  if (customIcon || customTheme) {
    iconResolved = checkAsset(
      fileHint,
      launcherIcon,
      DEFAULT_ICON_CANDIDATES,
      IMAGE_EXT,
      '图标',
      customIcon ? errors : [],
      warnings,
    );
    // For theme packs icon is optional — only warn.
    if ((customIcon || customTheme) && !iconResolved && fileHint && customIcon) {
      errors.push('customLauncherIcon 已开启，但未找到图标文件');
    } else if (customTheme && !iconResolved && !launcherIcon) {
      warnings.push('主题包未找到 icon.png（可选）');
    }
  }

  let fontResolved = null;
  if (customFont || customTheme) {
    if (fontFile || customFont) {
      fontResolved = checkAsset(
        fileHint,
        fontFile,
        DEFAULT_FONT_CANDIDATES,
        FONT_EXT,
        '字体',
        customFont ? errors : [],
        warnings,
      );
      if (customFont && !fontResolved && fileHint) {
        errors.push('customFont 已开启，但未找到字体文件');
      }
    } else if (customTheme) {
      fontResolved = checkAsset(
        fileHint,
        '',
        DEFAULT_FONT_CANDIDATES,
        FONT_EXT,
        '字体',
        [],
        warnings,
      );
    }
  }

  let bgImageResolved = null;
  let bgVideoResolved = null;
  if (customTheme) {
    if (!theme) {
      warnings.push('customTheme 已开启但缺少 theme 对象（仍可凭默认路径找壁纸）');
    }
    if (bgImage) {
      bgImageResolved = checkAsset(
        fileHint,
        bgImage,
        DEFAULT_BG_IMAGE,
        IMAGE_EXT,
        '壁纸图',
        errors,
        warnings,
      );
    } else {
      bgImageResolved = checkAsset(
        fileHint,
        '',
        DEFAULT_BG_IMAGE,
        IMAGE_EXT,
        '壁纸图',
        [],
        warnings,
      );
    }
    if (bgVideo) {
      bgVideoResolved = checkAsset(
        fileHint,
        bgVideo,
        DEFAULT_BG_VIDEO,
        VIDEO_EXT,
        '壁纸视频',
        errors,
        warnings,
      );
    } else {
      bgVideoResolved = checkAsset(
        fileHint,
        '',
        DEFAULT_BG_VIDEO,
        VIDEO_EXT,
        '壁纸视频',
        [],
        warnings,
      );
    }
    if (theme?.colors && typeof theme.colors === 'object') {
      for (const [k, v] of Object.entries(theme.colors)) {
        const s = String(v ?? '').trim();
        if (!s) continue;
        if (!/^#?[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/.test(s)) {
          warnings.push(`theme.colors.${k} 颜色格式可能无效: ${s}`);
        }
      }
    }
    if (theme && typeof theme === 'object') {
      const stringKeys = [
        'brandName',
        'welcome',
        'welcomeMorning',
        'welcomeNoon',
        'welcomeEvening',
        'homeLaunchText',
        'homeSwitchVersionText',
        'homeAccountText',
        'homeStatusText',
        'homeSelectedVersionLabel',
      ];
      for (const k of stringKeys) {
        if (theme[k] != null && typeof theme[k] !== 'string') {
          warnings.push(`theme.${k} 应为字符串`);
        }
      }
      if (theme.hideOrbs != null && typeof theme.hideOrbs !== 'boolean') {
        warnings.push('theme.hideOrbs 应为 true/false');
      }
      if (theme.applyAllText != null && typeof theme.applyAllText !== 'boolean') {
        warnings.push('theme.applyAllText 应为 true/false');
      }
      if (
        theme.backgroundAlpha != null &&
        (typeof theme.backgroundAlpha !== 'number' ||
          theme.backgroundAlpha < 0 ||
          theme.backgroundAlpha > 1)
      ) {
        warnings.push('theme.backgroundAlpha 应为 0~1 数字');
      }
    }
  }

  return {
    kind: 'ui',
    file: fileHint,
    id: id || null,
    name: name || id || null,
    version: version || '1.0.0',
    greeting,
    customLauncherIcon: customIcon || customTheme,
    launcherIcon: iconResolved || launcherIcon || null,
    customFont: customFont || customTheme,
    fontFile: fontResolved || fontFile || null,
    customTheme,
    backgroundImage: bgImageResolved || bgImage || null,
    backgroundVideo: bgVideoResolved || bgVideo || null,
    errors,
    warnings,
    ok: errors.length === 0,
  };
}

export function validateRendererManifest(obj, fileHint = '') {
  const errors = [];
  const warnings = [];
  const id = String(obj.id ?? '').trim();
  const glLib = String(obj.glLib ?? '').trim();
  if (!id) errors.push('id 不能为空');
  if (!glLib) errors.push('glLib 不能为空（主 so 文件名）');
  if (glLib && (glLib.includes('/') || glLib.includes('\\'))) {
    errors.push('glLib 必须是文件名，不能是路径');
  }
  if (!String(obj.name ?? '').trim()) warnings.push('建议填写 name');
  if (!String(obj.version ?? '').trim()) warnings.push('建议填写 version');
  const type = String(obj.type ?? 'RENDERER').toUpperCase();
  if (type !== 'RENDERER' && type !== 'DRIVER') {
    warnings.push(`type=${obj.type}，清单内常用 RENDERER / DRIVER`);
  }
  return {
    kind: 'renderer',
    file: fileHint,
    id: id || null,
    glLib: glLib || null,
    name: String(obj.name || id || ''),
    version: String(obj.version || '1.0'),
    errors,
    warnings,
    ok: errors.length === 0,
  };
}

export function validatePath(targetPath) {
  const file = findManifest(targetPath);
  const obj = readJson(file);
  const base = path.basename(file);
  if (base === 'booxin-plugin.json') {
    return validateUiManifest(obj, file);
  }
  return validateRendererManifest(obj, file);
}

export function printReport(report) {
  const lines = [];
  lines.push(`[${report.ok ? 'OK' : 'FAIL'}] ${report.kind}  ${report.file}`);
  if (report.id) lines.push(`  id: ${report.id}`);
  if (report.name) lines.push(`  name: ${report.name}`);
  if (report.version) lines.push(`  version: ${report.version}`);
  if (report.kind === 'ui') {
    lines.push(`  homeGreetingByTime: ${report.greeting ? 'true' : 'false'}`);
    lines.push(
      `  customLauncherIcon: ${report.customLauncherIcon ? 'true' : 'false'}` +
        (report.launcherIcon ? ` (${report.launcherIcon})` : ''),
    );
    lines.push(
      `  customFont: ${report.customFont ? 'true' : 'false'}` +
        (report.fontFile ? ` (${report.fontFile})` : ''),
    );
    lines.push(`  customTheme: ${report.customTheme ? 'true' : 'false'}`);
    if (report.backgroundImage) lines.push(`  backgroundImage: ${report.backgroundImage}`);
    if (report.backgroundVideo) lines.push(`  backgroundVideo: ${report.backgroundVideo}`);
  }
  if (report.glLib) lines.push(`  glLib: ${report.glLib}`);
  for (const e of report.errors) lines.push(`  ERROR: ${e}`);
  for (const w of report.warnings) lines.push(`  WARN:  ${w}`);
  return lines.join('\n');
}
