import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const SKIP_DIRS = new Set([
  'node_modules',
  '.git',
  '.svn',
  '.hg',
  'dist',
  'build',
  '.idea',
  '.vscode',
  '__pycache__',
]);

const TEXT_EXT = new Set([
  '.json',
  '.jsonc',
  '.txt',
  '.md',
  '.markdown',
  '.xml',
  '.html',
  '.htm',
  '.css',
  '.scss',
  '.less',
  '.js',
  '.mjs',
  '.cjs',
  '.ts',
  '.tsx',
  '.jsx',
  '.vue',
  '.svelte',
  '.kt',
  '.kts',
  '.java',
  '.gradle',
  '.properties',
  '.yml',
  '.yaml',
  '.toml',
  '.ini',
  '.cfg',
  '.conf',
  '.csv',
  '.svg',
  '.glsl',
  '.vert',
  '.frag',
  '.shader',
  '.log',
  '.gitignore',
  '.gitattributes',
  '.py',
  '.pyw',
  '.pyi',
  '.rs',
  '.go',
  '.rb',
  '.php',
  '.sh',
  '.bash',
  '.zsh',
  '.bat',
  '.cmd',
  '.ps1',
  '.psm1',
  '.sql',
  '.dart',
  '.lua',
  '.r',
  '.c',
  '.h',
  '.cpp',
  '.cc',
  '.cxx',
  '.hpp',
  '.cs',
  '.swift',
  '.m',
  '.mm',
  '.plist',
  '.env',
  '.editorconfig',
]);

const TEXT_NAMES = new Set([
  'readme',
  'license',
  'licence',
  'changelog',
  'booxin-plugin.json',
  'makefile',
  'dockerfile',
  'gemfile',
  'procfile',
]);

function statePath() {
  const dir = path.join(os.homedir(), 'AppData', 'Roaming', 'BooxinStudio');
  fs.mkdirSync(dir, { recursive: true });
  return path.join(dir, 'state.json');
}

function loadState() {
  try {
    const raw = fs.readFileSync(statePath(), 'utf8');
    const j = JSON.parse(raw);
    return {
      recentProjects: Array.isArray(j.recentProjects) ? j.recentProjects : [],
    };
  } catch {
    return { recentProjects: [] };
  }
}

function saveState(state) {
  fs.writeFileSync(statePath(), JSON.stringify(state, null, 2), 'utf8');
}

export function getRecentProjects() {
  const state = loadState();
  const kept = [];
  for (const item of state.recentProjects) {
    const p = typeof item === 'string' ? item : item?.path;
    if (!p) continue;
    if (!fs.existsSync(p) || !fs.statSync(p).isDirectory()) continue;
    kept.push({
      path: p,
      name: path.basename(p),
      openedAt: typeof item === 'object' && item?.openedAt ? item.openedAt : 0,
    });
  }
  if (kept.length !== state.recentProjects.length) {
    state.recentProjects = kept;
    saveState(state);
  }
  return kept.sort((a, b) => (b.openedAt || 0) - (a.openedAt || 0)).slice(0, 16);
}

export function addRecentProject(projectPath) {
  const abs = path.resolve(projectPath);
  if (!fs.existsSync(abs) || !fs.statSync(abs).isDirectory()) {
    throw new Error(`项目目录不存在: ${abs}`);
  }
  const state = loadState();
  const next = [
    { path: abs, name: path.basename(abs), openedAt: Date.now() },
    ...getRecentProjects().filter((x) => path.resolve(x.path) !== abs),
  ].slice(0, 16);
  state.recentProjects = next;
  saveState(state);
  return next;
}

export function clearRecentProjects() {
  saveState({ recentProjects: [] });
  return [];
}

export function isTextFile(filePath) {
  const base = path.basename(filePath).toLowerCase();
  if (TEXT_NAMES.has(base)) return true;
  const ext = path.extname(filePath).toLowerCase();
  if (TEXT_EXT.has(ext)) return true;
  // treat extensionless small files carefully
  return false;
}

export function listDir(dirPath) {
  const abs = path.resolve(dirPath);
  if (!fs.existsSync(abs) || !fs.statSync(abs).isDirectory()) {
    throw new Error(`目录不存在: ${abs}`);
  }
  const entries = fs.readdirSync(abs, { withFileTypes: true });
  const dirs = [];
  const files = [];
  for (const ent of entries) {
    if (ent.name.startsWith('.')) continue;
    if (ent.isDirectory()) {
      if (SKIP_DIRS.has(ent.name)) continue;
      dirs.push({ name: ent.name, path: path.join(abs, ent.name), kind: 'dir' });
    } else if (ent.isFile()) {
      files.push({
        name: ent.name,
        path: path.join(abs, ent.name),
        kind: 'file',
        text: isTextFile(path.join(abs, ent.name)),
      });
    }
  }
  dirs.sort((a, b) => a.name.localeCompare(b.name, 'en'));
  files.sort((a, b) => a.name.localeCompare(b.name, 'en'));
  return [...dirs, ...files];
}

/** Roots for in-app folder browser (Windows drives + home shortcuts). */
export function listFsRoots() {
  const roots = [];
  const home = os.homedir();
  const push = (name, p) => {
    try {
      const abs = path.resolve(p);
      if (fs.existsSync(abs) && fs.statSync(abs).isDirectory()) {
        roots.push({ name, path: abs, kind: 'dir' });
      }
    } catch {
      /* ignore */
    }
  };
  push('用户目录', home);
  push('桌面', path.join(home, 'Desktop'));
  push('文档', path.join(home, 'Documents'));
  push('下载', path.join(home, 'Downloads'));
  if (process.platform === 'win32') {
    for (const letter of 'CDEFGHIJKLMNOPQRSTUVWXYZ') {
      const drive = `${letter}:\\`;
      try {
        if (fs.existsSync(drive)) roots.push({ name: `${letter}:`, path: drive, kind: 'dir' });
      } catch {
        /* ignore */
      }
    }
  } else {
    push('/', '/');
  }
  return roots;
}

export function readTextFile(filePath) {
  const abs = path.resolve(filePath);
  if (!fs.existsSync(abs) || !fs.statSync(abs).isFile()) {
    throw new Error(`文件不存在: ${abs}`);
  }
  if (!isTextFile(abs)) {
    throw new Error('该文件不是可编辑的文本类型');
  }
  const st = fs.statSync(abs);
  if (st.size > 2_000_000) {
    throw new Error('文件过大（>2MB），请用外部编辑器打开');
  }
  return fs.readFileSync(abs, 'utf8');
}

export function writeTextFile(filePath, content) {
  const abs = path.resolve(filePath);
  const parent = path.dirname(abs);
  if (!fs.existsSync(parent)) {
    fs.mkdirSync(parent, { recursive: true });
  }
  if (fs.existsSync(abs) && !isTextFile(abs)) {
    throw new Error('拒绝写入非文本文件');
  }
  // allow creating new text files even if extension not yet known as text
  fs.writeFileSync(abs, String(content ?? ''), 'utf8');
  return { path: abs, bytes: Buffer.byteLength(String(content ?? ''), 'utf8') };
}

function assertSafeBaseName(name) {
  const n = String(name || '').trim();
  if (!n) throw new Error('名称不能为空');
  if (n === '.' || n === '..') throw new Error('非法名称');
  if (/[\\/:*?"<>|\0]/.test(n)) throw new Error('名称包含非法字符');
  return n;
}

/** Create a new empty text file (fails if exists). */
export function createTextFile(filePath, content = '') {
  const abs = path.resolve(filePath);
  if (fs.existsSync(abs)) throw new Error(`已存在: ${path.basename(abs)}`);
  const parent = path.dirname(abs);
  if (!fs.existsSync(parent)) {
    fs.mkdirSync(parent, { recursive: true });
  }
  fs.writeFileSync(abs, String(content ?? ''), 'utf8');
  return {
    path: abs,
    bytes: Buffer.byteLength(String(content ?? ''), 'utf8'),
    text: isTextFile(abs),
  };
}

export function mkdirPath(dirPath) {
  const abs = path.resolve(dirPath);
  if (fs.existsSync(abs)) throw new Error(`已存在: ${path.basename(abs)}`);
  fs.mkdirSync(abs, { recursive: false });
  return { path: abs };
}

export function deletePath(targetPath) {
  const abs = path.resolve(targetPath);
  if (!fs.existsSync(abs)) throw new Error(`不存在: ${abs}`);
  const st = fs.statSync(abs);
  if (st.isDirectory()) {
    const kids = fs.readdirSync(abs);
    if (kids.length > 0) {
      throw new Error('文件夹非空，请先删除其中的文件');
    }
    fs.rmdirSync(abs);
  } else {
    fs.unlinkSync(abs);
  }
  return { path: abs, kind: st.isDirectory() ? 'dir' : 'file' };
}

export function renamePath(fromPath, newName) {
  const from = path.resolve(fromPath);
  if (!fs.existsSync(from)) throw new Error(`不存在: ${from}`);
  const name = assertSafeBaseName(newName);
  const to = path.join(path.dirname(from), name);
  if (path.resolve(to) === from) return { path: from, from };
  if (fs.existsSync(to)) throw new Error(`目标已存在: ${name}`);
  fs.renameSync(from, to);
  return {
    path: to,
    from,
    text: fs.statSync(to).isFile() ? isTextFile(to) : false,
  };
}

/** Simple recursive text search under a project root. */
export function searchInProject(rootPath, query, { maxHits = 80 } = {}) {
  const root = path.resolve(rootPath);
  const q = String(query || '').trim();
  if (!q) throw new Error('请输入搜索内容');
  if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
    throw new Error(`目录不存在: ${root}`);
  }
  const hits = [];
  const walk = (dir) => {
    if (hits.length >= maxHits) return;
    let entries = [];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const ent of entries) {
      if (hits.length >= maxHits) break;
      if (ent.name.startsWith('.')) continue;
      const full = path.join(dir, ent.name);
      if (ent.isDirectory()) {
        if (SKIP_DIRS.has(ent.name)) continue;
        walk(full);
      } else if (ent.isFile() && isTextFile(full)) {
        let text = '';
        try {
          const st = fs.statSync(full);
          if (st.size > 800_000) continue;
          text = fs.readFileSync(full, 'utf8');
        } catch {
          continue;
        }
        const lines = text.split(/\r?\n/);
        for (let i = 0; i < lines.length; i++) {
          if (hits.length >= maxHits) break;
          if (lines[i].includes(q)) {
            hits.push({
              path: full,
              line: i + 1,
              text: lines[i].trim().slice(0, 200),
            });
          }
        }
      }
    }
  };
  walk(root);
  return { query: q, hits, truncated: hits.length >= maxHits };
}

export function projectSummary(dirPath) {
  const abs = path.resolve(dirPath);
  const manifestCandidates = [
    path.join(abs, 'booxin-plugin.json'),
    path.join(abs, 'booxin-plugin.jsonc'),
  ];
  let manifest = null;
  for (const m of manifestCandidates) {
    if (fs.existsSync(m)) {
      try {
        manifest = JSON.parse(fs.readFileSync(m, 'utf8'));
      } catch {
        manifest = { _raw: true };
      }
      break;
    }
  }
  return {
    path: abs,
    name: path.basename(abs),
    hasManifest: !!manifest,
    id: manifest?.id || null,
    nameField: manifest?.name || null,
    version: manifest?.version || null,
  };
}
