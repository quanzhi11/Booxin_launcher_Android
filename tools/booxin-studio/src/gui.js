#!/usr/bin/env node
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  clearAiChatMessages,
  deleteAiChat,
  getAiChat,
  listAiChats,
  newAiChat,
  saveAiChat,
} from './ai-chats.js';
import { spawn, spawnSync } from 'node:child_process';
import {
  agentChat,
  agentChatStream,
  agentChatWithTools,
  clearAiSession,
  createSubscription,
  formatQuotaLine,
  getAiSettings,
  getQuotaSnapshot,
  getSubscriptionStatus,
  loginWithPassword,
  normalizeUserKey,
  setAiSettings,
  validateAiSession,
} from './ai-agent.js';
import { COMMANDS } from './commands.js';
import { scaffoldTemplate } from './scaffold.js';
import {
  addRecentProject,
  clearRecentProjects,
  createTextFile,
  deletePath,
  getRecentProjects,
  listDir,
  listFsRoots,
  mkdirPath,
  projectSummary,
  readTextFile,
  renamePath,
  searchInProject,
  writeTextFile,
} from './ide-state.js';
import { packUiPlugin } from './pack.js';
import { pickFolder, pickSaveZip } from './pick.js';
import { APP_VERSION, checkStudioUpdate } from './studio-update.js';
import { listModelsForTier } from './studio-models.js';
import { attachTerminalServer } from './terminal-server.js';
import { printReport, validatePath } from './validate.js';

function moduleDir() {
  // SEA / bundled CJS: import.meta.url is empty — never call fileURLToPath(undefined).
  try {
    if (typeof import.meta !== 'undefined' && import.meta && import.meta.url) {
      return path.dirname(fileURLToPath(import.meta.url));
    }
  } catch {
    /* ignore */
  }
  return path.dirname(process.execPath);
}

const moduleDirectory = moduleDir();
const studioState = { projectRoot: null };

function webRoot() {
  const candidates = [
    path.join(path.dirname(process.execPath), 'web'),
    path.join(process.cwd(), 'web'),
    path.join(moduleDirectory, '..', 'web'),
    path.join(moduleDirectory, 'web'),
  ];
  for (const p of candidates) {
    if (fs.existsSync(path.join(p, 'index.html'))) return p;
  }
  throw new Error(
    `找不到 web/index.html。请把 web 文件夹和 BooxinStudio.exe 放在同一目录。\n已尝试: ${candidates.join(' | ')}`,
  );
}

function studioRoot() {
  const besideExe = path.dirname(process.execPath);
  if (fs.existsSync(path.join(besideExe, 'web', 'index.html'))) return besideExe;
  const besideModule = path.join(moduleDirectory, '..');
  if (fs.existsSync(path.join(besideModule, 'web', 'index.html'))) return besideModule;
  return besideExe;
}

function mimeOf(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  return (
    {
      '.html': 'text/html; charset=utf-8',
      '.js': 'text/javascript; charset=utf-8',
      '.mjs': 'text/javascript; charset=utf-8',
      '.css': 'text/css; charset=utf-8',
      '.json': 'application/json; charset=utf-8',
      '.svg': 'image/svg+xml',
      '.png': 'image/png',
      '.ico': 'image/x-icon',
      '.ttf': 'font/ttf',
      '.woff': 'font/woff',
      '.woff2': 'font/woff2',
      '.map': 'application/json',
    }[ext] || 'application/octet-stream'
  );
}

/** Local Monaco / xterm — prefer packaged web/vendor, fallback to node_modules (dev). */
function resolveVendorFile(urlPath) {
  const map = [
    [
      '/vendor/monaco/',
      [
        path.join(webRoot(), 'vendor', 'monaco'),
        path.join(studioRoot(), 'node_modules', 'monaco-editor', 'min'),
        path.join(process.cwd(), 'node_modules', 'monaco-editor', 'min'),
      ],
    ],
    [
      '/vendor/xterm/',
      [
        path.join(webRoot(), 'vendor', 'xterm'),
        path.join(studioRoot(), 'node_modules', '@xterm', 'xterm'),
        path.join(process.cwd(), 'node_modules', '@xterm', 'xterm'),
      ],
    ],
    [
      '/vendor/addon-fit/',
      [
        path.join(webRoot(), 'vendor', 'addon-fit'),
        path.join(studioRoot(), 'node_modules', '@xterm', 'addon-fit'),
        path.join(process.cwd(), 'node_modules', '@xterm', 'addon-fit'),
      ],
    ],
  ];
  for (const [prefix, roots] of map) {
    if (!urlPath.startsWith(prefix)) continue;
    const rel = urlPath.slice(prefix.length);
    for (const root of roots) {
      const rootAbs = path.resolve(root);
      if (!fs.existsSync(rootAbs)) continue;
      const filePath = path.resolve(rootAbs, rel);
      const rootPrefix = rootAbs.endsWith(path.sep) ? rootAbs : rootAbs + path.sep;
      if (!filePath.startsWith(rootPrefix) && filePath !== rootAbs) continue;
      if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) continue;
      return filePath;
    }
  }
  return null;
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      try {
        const raw = Buffer.concat(chunks).toString('utf8') || '{}';
        resolve(JSON.parse(raw));
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

function sendJson(res, status, obj) {
  const body = Buffer.from(JSON.stringify(obj), 'utf8');
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
  });
  res.end(body);
}

function sendFile(res, filePath, { cache = false } = {}) {
  const data = fs.readFileSync(filePath);
  res.writeHead(200, {
    'Content-Type': mimeOf(filePath),
    'Content-Length': data.length,
    'Cache-Control': cache ? 'public, max-age=86400' : 'no-store',
  });
  res.end(data);
}

async function handleIde(body) {
  const action = String(body.action || '').trim();
  switch (action) {
    case 'recent.get':
      return { recent: getRecentProjects() };
    case 'recent.add':
      return { recent: addRecentProject(String(body.path || '')) };
    case 'recent.clear':
      return { recent: clearRecentProjects() };
    case 'project.summary': {
      const project = projectSummary(String(body.path || ''));
      studioState.projectRoot = project.path;
      return { project };
    }
    case 'session.setCwd': {
      const p = String(body.path || '').trim();
      studioState.projectRoot = p || null;
      return { path: studioState.projectRoot };
    }
    case 'project.scaffold': {
      const kind = String(body.kind || '').trim();
      const parent = String(body.parent || '').trim();
      const id = String(body.id || body.defaultFolder || '').trim();
      if (!kind) throw new Error('缺少模板类型');
      if (!parent) throw new Error('缺少保存目录');
      if (!id) throw new Error('缺少插件 id');
      const out = path.join(parent, id);
      const created = scaffoldTemplate(kind, out, id);
      studioState.projectRoot = created.dir;
      return {
        path: created.dir,
        id: created.id,
        kind: created.kind,
        title: created.title,
        files: created.files || [],
      };
    }
    case 'fs.list':
      return { entries: listDir(String(body.path || '')) };
    case 'fs.roots':
      return { roots: listFsRoots() };
    case 'fs.parent': {
      const abs = path.resolve(String(body.path || ''));
      const parent = path.dirname(abs);
      return { path: parent === abs ? '' : parent };
    }
    case 'fs.read':
      return { content: readTextFile(String(body.path || '')), path: path.resolve(body.path) };
    case 'fs.write':
      return writeTextFile(String(body.path || ''), body.content);
    case 'fs.create':
      return createTextFile(String(body.path || ''), body.content ?? '');
    case 'fs.mkdir':
      return mkdirPath(String(body.path || ''));
    case 'fs.delete':
      return deletePath(String(body.path || ''));
    case 'fs.rename':
      return renamePath(String(body.path || ''), String(body.name || ''));
    case 'fs.search':
      return searchInProject(String(body.path || ''), String(body.query || ''));
    case 'dialog.pickFolder': {
      const selected = pickFolder(String(body.title || '选择文件夹'));
      return { path: selected || '' };
    }
    case 'dialog.pickSaveZip': {
      const selected = pickSaveZip(String(body.defaultName || 'plugin.zip'));
      return { path: selected || '' };
    }
    case 'app.version':
      return { version: APP_VERSION };
    case 'app.version.check':
      return await checkStudioUpdate();
    case 'shell.openUrl': {
      const u = String(body.url || '').trim();
      if (!/^https?:\/\//i.test(u)) throw new Error('仅支持 http(s) 链接');
      spawn('cmd', ['/c', 'start', '', u], { detached: true, stdio: 'ignore', windowsHide: true }).unref();
      return { opened: true };
    }
    case 'ai.history.list':
      return listAiChats();
    case 'ai.history.get':
      return getAiChat(String(body.id || ''));
    case 'ai.history.save':
      return saveAiChat({
        id: body.id,
        title: body.title,
        messages: body.messages,
        projectPath: body.projectPath,
        setActive: body.setActive !== false,
      });
    case 'ai.history.new':
      return newAiChat(String(body.projectPath || ''));
    case 'ai.history.delete':
      return deleteAiChat(String(body.id || ''));
    case 'ai.history.clear':
      return clearAiChatMessages(String(body.id || ''));
    case 'pack': {
      const dir = String(body.path || '').trim();
      if (!dir) throw new Error('缺少项目路径');
      const out = body.out ? String(body.out) : undefined;
      const result = packUiPlugin(dir, out);
      return {
        dest: result.dest,
        output: `打包完成\n${result.dest}\n\n${printReport(result.report)}`,
      };
    }
    case 'validate': {
      const dir = String(body.path || '').trim();
      if (!dir) throw new Error('缺少项目路径');
      return { output: printReport(validatePath(dir)) };
    }
    case 'run': {
      const cmd = String(body.cmd || '').trim();
      const args = Array.isArray(body.args) ? body.args.map(String) : [];
      const def = COMMANDS[cmd];
      if (!def) throw new Error(`未知命令: ${cmd}`);
      return { output: String(def.run(args) ?? '') };
    }
    case 'ai.settings.get':
      return { settings: getAiSettings() };
    case 'ai.settings.set': {
      const key = normalizeUserKey(body.userKey);
      return { settings: setAiSettings({ userKey: key }) };
    }
    case 'ai.login': {
      const settings = await loginWithPassword(body.username, body.password);
      let quotaLine = '';
      try {
        const snap = await getQuotaSnapshot(settings.userKey);
        quotaLine = formatQuotaLine(snap);
      } catch {
        /* ignore */
      }
      return { settings, quotaLine };
    }
    case 'ai.logout':
      return { settings: clearAiSession() };
    case 'ai.session.validate': {
      const settings = await validateAiSession();
      let quotaLine = '';
      if (settings.loggedIn) {
        try {
          const snap = await getQuotaSnapshot(settings.userKey);
          quotaLine = formatQuotaLine(snap);
        } catch {
          /* ignore */
        }
      }
      return { settings, quotaLine };
    }
    case 'ai.models': {
      let memberTier = 'None';
      const settings = getAiSettings();
      if (settings.loggedIn) {
        try {
          const snap = await getQuotaSnapshot(settings.userKey);
          memberTier = snap.memberTier || 'None';
        } catch {
          /* ignore */
        }
      }
      return {
        models: listModelsForTier(memberTier),
        selectedModel: settings.selectedModel || 'deepseek',
        memberTier,
      };
    }
    case 'ai.model.set': {
      const id = String(body.model || body.selectedModel || '').trim();
      if (!id) throw new Error('缺少模型');
      const settings = setAiSettings({ selectedModel: id });
      return { settings };
    }
    case 'ai.quota': {
      const snap = await getQuotaSnapshot(body.userKey);
      return { snapshot: snap, quotaLine: formatQuotaLine(snap) };
    }
    case 'ai.chat': {
      let history = [];
      if (typeof body.historyJson === 'string' && body.historyJson.trim()) {
        try {
          const parsed = JSON.parse(body.historyJson);
          if (Array.isArray(parsed)) history = parsed;
        } catch {
          /* ignore */
        }
      } else if (Array.isArray(body.history)) {
        history = body.history;
      }
      return await agentChat({
        message: body.message,
        userKey: body.userKey,
        context: body.context,
        history,
        model: body.model,
        projectSummary: body.projectSummary,
      });
    }
    case 'ai.subscribe': {
      const created = await createSubscription({
        userKey: body.userKey,
        plan: body.plan,
        payType: body.payType,
      });
      return { created };
    }
    case 'ai.status': {
      const status = await getSubscriptionStatus({
        outTradeNo: body.outTradeNo,
        userKey: body.userKey,
      });
      return {
        status,
        quotaLine: formatQuotaLine(status?.snapshot),
      };
    }
    default:
      throw new Error(`未知 action: ${action}`);
  }
}

function openStudioWindow(url) {
  // Prefer launching Edge directly — PowerShell + WinForms load is slow and feels stuck.
  const webviewDll = [
    path.join(webRoot(), 'Microsoft.Web.WebView2.WinForms.dll'),
    path.join(studioRoot(), 'lib', 'Microsoft.Web.WebView2.WinForms.dll'),
  ].find((p) => fs.existsSync(p));

  if (webviewDll) {
    const hostPs1 = path.join(webRoot(), 'host.ps1');
    if (fs.existsSync(hostPs1)) {
      const tmp = path.join(os.tmpdir(), `booxin-studio-host-${process.pid}.ps1`);
      fs.copyFileSync(hostPs1, tmp);
      const child = spawn(
        'powershell.exe',
        ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', tmp, '-Url', url, '-Title', 'Booxin Studio'],
        { detached: false, stdio: 'ignore', windowsHide: false },
      );
      child.on('exit', () => {
        try {
          fs.unlinkSync(tmp);
        } catch {
          /* ignore */
        }
      });
      return child;
    }
  }

  const candidates = [
    path.join(process.env['PROGRAMFILES(X86)'] || '', 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
    path.join(process.env.PROGRAMFILES || '', 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
    path.join(process.env.LOCALAPPDATA || '', 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
    'msedge',
  ];
  const profile = path.join(process.env.LOCALAPPDATA || os.tmpdir(), 'BooxinStudio', 'edge-profile');
  fs.mkdirSync(profile, { recursive: true });
  for (const exe of candidates) {
    try {
      if (exe !== 'msedge' && !fs.existsSync(exe)) continue;
      const child = spawn(
        exe,
        [
          `--app=${url}`,
          `--user-data-dir=${profile}`,
          '--no-first-run',
          '--no-default-browser-check',
          '--disable-extensions',
          '--disable-component-update',
          '--disable-sync',
          '--disable-background-networking',
          '--disable-features=TranslateUI,EdgeSidebar,msEdgeCollections,msSmartScreenProtection,msEdgeShoppingUI',
          '--window-size=1440,900',
          '--force-dark-mode',
        ],
        { detached: true, stdio: 'ignore', windowsHide: false },
      );
      child.unref();
      return child;
    } catch {
      /* try next */
    }
  }
  spawn('cmd', ['/c', 'start', '', url], { detached: true, stdio: 'ignore', windowsHide: true }).unref();
  return null;
}

async function main() {
  if (process.platform !== 'win32') {
    console.error('窗口版仅支持 Windows。请使用命令行版 BooxinStudio-CLI.exe');
    process.exitCode = 1;
    return;
  }

  const token = crypto.randomBytes(16).toString('hex');
  const root = webRoot();
  let shuttingDown = false;
  let windowChild = null;

  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url || '/', 'http://127.0.0.1');

      if (req.method === 'GET' && url.pathname === '/config.js') {
        const js = `window.STUDIO=${JSON.stringify({
          port: server.address().port,
          token,
          version: APP_VERSION,
        })};`;
        const body = Buffer.from(js, 'utf8');
        res.writeHead(200, {
          'Content-Type': 'text/javascript; charset=utf-8',
          'Content-Length': body.length,
          'Cache-Control': 'no-store',
        });
        res.end(body);
        return;
      }

      if (req.method === 'POST' && url.pathname === '/ide/ai-stream') {
        const body = await readJson(req);
        if (body.token !== token) {
          sendJson(res, 403, { ok: false, error: 'forbidden' });
          return;
        }
        let history = [];
        if (typeof body.historyJson === 'string' && body.historyJson.trim()) {
          try {
            const parsed = JSON.parse(body.historyJson);
            if (Array.isArray(parsed)) history = parsed;
          } catch {
            /* ignore */
          }
        } else if (Array.isArray(body.history)) {
          history = body.history;
        }
        res.writeHead(200, {
          'Content-Type': 'text/event-stream; charset=utf-8',
          'Cache-Control': 'no-cache, no-transform',
          Connection: 'keep-alive',
          'X-Accel-Buffering': 'no',
        });
        const writeEvent = (obj) => {
          res.write(`data: ${JSON.stringify(obj)}\n\n`);
        };
        try {
          const runner = body.useTools === false ? agentChatStream : agentChatWithTools;
          for await (const ev of runner({
            message: body.message,
            userKey: body.userKey,
            context: body.context,
            history,
            model: body.model,
            projectSummary: body.projectSummary,
            projectRoot: body.projectRoot || studioState.projectRoot || '',
          })) {
            writeEvent(ev);
          }
        } catch (e) {
          writeEvent({ type: 'error', message: e.message || String(e) });
        }
        res.end();
        return;
      }

      if (req.method === 'POST' && (url.pathname === '/ide' || url.pathname === '/run')) {
        const body = await readJson(req);
        if (body.token !== token) {
          sendJson(res, 403, { ok: false, error: 'forbidden' });
          return;
        }
        if (url.pathname === '/run' && !body.action) body.action = 'run';
        try {
          const data = await handleIde(body);
          sendJson(res, 200, { ok: true, ...data });
        } catch (e) {
          sendJson(res, 200, { ok: false, error: e.message || String(e) });
        }
        return;
      }

      if (req.method === 'POST' && url.pathname === '/shutdown') {
        const body = await readJson(req);
        if (body.token !== token) {
          sendJson(res, 403, { ok: false, error: 'forbidden' });
          return;
        }
        sendJson(res, 200, { ok: true });
        shuttingDown = true;
        setTimeout(() => {
          server.close();
          process.exit(0);
        }, 100);
        return;
      }

      if (req.method === 'GET') {
        let rel = decodeURIComponent(url.pathname);
        if (rel === '/') rel = '/index.html';

        if (rel.startsWith('/vendor/')) {
          const vendorFile = resolveVendorFile(rel);
          if (!vendorFile) {
            sendJson(res, 404, { ok: false, error: 'vendor not found' });
            return;
          }
          sendFile(res, vendorFile, { cache: true });
          return;
        }

        const safe = path.normalize(rel).replace(/^(\.\.[/\\])+/, '');
        const filePath = path.join(root, safe);
        if (!filePath.startsWith(root) || !fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
          sendJson(res, 404, { ok: false, error: 'not found' });
          return;
        }
        sendFile(res, filePath);
        return;
      }

      sendJson(res, 404, { ok: false, error: 'not found' });
    } catch (e) {
      sendJson(res, 500, { ok: false, error: e.message || String(e) });
    }
  });

  attachTerminalServer(server, {
    token,
    getCwd: () => studioState.projectRoot,
  });

  await new Promise((resolve, reject) => {
    server.listen(0, '127.0.0.1', resolve);
    server.on('error', reject);
  });
  const port = server.address().port;
  const studioUrl = `http://127.0.0.1:${port}/`;

  console.log(`Booxin Studio → ${studioUrl}`);
  windowChild = openStudioWindow(studioUrl);

  const keepAlive = setInterval(() => {}, 60_000);
  const shutdown = () => {
    clearInterval(keepAlive);
    if (shuttingDown) return;
    shuttingDown = true;
    try {
      server.close();
    } catch {
      /* ignore */
    }
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
  console.log('在本终端按 Ctrl+C 退出 Studio 服务。');
  void windowChild;
}

main().catch((e) => {
  const msg = e?.stack || e?.message || String(e);
  console.error(msg);
  try {
    const escaped = String(msg).slice(0, 700).replace(/'/g, "''");
    spawnSync(
      'powershell.exe',
      [
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-Command',
        `Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.MessageBox]::Show('${escaped}','Booxin Studio 启动失败','OK','Error') | Out-Null`,
      ],
      { windowsHide: true },
    );
  } catch {
    /* ignore */
  }
  process.exitCode = 1;
});
