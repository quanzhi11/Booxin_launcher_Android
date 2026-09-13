/* global require, monaco, STUDIO */
const state = {
  projectRoot: null,
  currentFile: null,
  dirty: false,
  loading: false,
  tabs: [], // { path, name }
  treeCache: new Map(),
  agentOpen: true,
  aiLoggedIn: false,
  aiHistory: [],
  aiChatId: '',
  aiBusy: false,
  editor: null,
  term: null,
  termFit: null,
  termSocket: null,
  bottomTab: 'log',
};

const el = (id) => document.getElementById(id);

function cfg() {
  return window.STUDIO || {};
}

async function ide(action, fields = {}) {
  const { port, token } = cfg();
  const res = await fetch(`http://127.0.0.1:${port}/ide`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action, token, ...fields }),
  });
  const data = await res.json();
  if (!data.ok) throw new Error(data.error || '请求失败');
  return data;
}

function setStatus(text) {
  el('status').textContent = text || '就绪';
}

function log(text) {
  if (!text) return;
  const box = el('log');
  box.textContent += `${String(text).trimEnd()}\n`;
  box.scrollTop = box.scrollHeight;
}

function toast(message, kind = 'info') {
  const root = el('toast-root');
  if (!root) {
    setStatus(String(message || ''));
    return;
  }
  const n = document.createElement('div');
  n.className = `toast ${kind === 'error' ? 'error' : kind === 'ok' ? 'ok' : ''}`;
  n.textContent = String(message || '');
  root.appendChild(n);
  setTimeout(() => n.remove(), 3200);
}

function showModal({ title, body, input, okText = '确定', cancelText = '取消', showCancel = false }) {
  return new Promise((resolve) => {
    const root = el('modal-root');
    const titleEl = el('modal-title');
    const bodyEl = el('modal-body');
    const field = el('modal-field-wrap');
    const inputEl = el('modal-input');
    const ok = el('modal-ok');
    const cancel = el('modal-cancel');
    titleEl.textContent = title || '提示';
    bodyEl.textContent = body || '';
    const wantsInput = input != null;
    field.classList.toggle('hidden', !wantsInput);
    if (wantsInput) {
      inputEl.value = String(input.defaultValue ?? '');
      inputEl.placeholder = input.placeholder || '';
    }
    ok.textContent = okText;
    cancel.textContent = cancelText;
    cancel.classList.toggle('hidden', !showCancel);
    root.classList.remove('hidden');

    const close = (value) => {
      root.classList.add('hidden');
      ok.onclick = null;
      cancel.onclick = null;
      root.onkeydown = null;
      resolve(value);
    };
    ok.onclick = () => close(wantsInput ? inputEl.value : true);
    cancel.onclick = () => close(wantsInput ? null : false);
    root.onkeydown = (e) => {
      if (e.key === 'Escape' && showCancel) close(wantsInput ? null : false);
      if (e.key === 'Enter' && wantsInput) {
        e.preventDefault();
        close(inputEl.value);
      }
    };
    if (wantsInput) setTimeout(() => inputEl.focus(), 0);
    else setTimeout(() => ok.focus(), 0);
  });
}

function alertDialog(message, title = '提示') {
  return showModal({ title, body: String(message || ''), showCancel: false });
}

function confirmDialog(message, title = '确认') {
  return showModal({ title, body: String(message || ''), showCancel: true, okText: '确定', cancelText: '取消' });
}

async function promptDialog(message, defaultValue = '', title = '输入') {
  const v = await showModal({
    title,
    body: String(message || ''),
    input: { defaultValue },
    showCancel: true,
    okText: '确定',
    cancelText: '取消',
  });
  return v;
}

async function openExternal(url) {
  try {
    await ide('shell.openUrl', { url });
  } catch (e) {
    toast(e.message || '无法打开链接', 'error');
  }
}

/**
 * In-app folder / save picker (always on top of Studio, never behind Edge).
 * @returns {Promise<string|null>} folder path, or full zip path when mode=saveZip
 */
function pickFolderInApp({
  title = '选择文件夹',
  startPath = '',
  mode = 'folder', // folder | saveZip
  defaultName = 'plugin.zip',
} = {}) {
  return new Promise(async (resolve) => {
    const root = el('folder-picker');
    const list = el('folder-list');
    const pathInput = el('folder-path');
    const saveRow = el('folder-save-row');
    const fileInput = el('folder-filename');
    el('folder-title').textContent = title;
    saveRow.classList.toggle('hidden', mode !== 'saveZip');
    if (mode === 'saveZip') fileInput.value = defaultName;

    let current = '';
    let selected = '';
    let closed = false;

    const close = (value) => {
      if (closed) return;
      closed = true;
      root.classList.add('hidden');
      resolve(value);
    };

    const renderRoots = async () => {
      list.innerHTML = '<div class="hint" style="padding:10px">加载中…</div>';
      try {
        const r = await ide('fs.roots');
        current = '';
        pathInput.value = '';
        selected = '';
        list.innerHTML = '';
        for (const ent of r.roots || []) {
          const b = document.createElement('button');
          b.type = 'button';
          b.className = 'folder-item';
          b.innerHTML = `<span class="ico">💽</span><span>${escapeHtml(ent.name)} — ${escapeHtml(ent.path)}</span>`;
          b.addEventListener('click', () => {
            list.querySelectorAll('.folder-item').forEach((n) => n.classList.remove('active'));
            b.classList.add('active');
            selected = ent.path;
          });
          b.addEventListener('dblclick', () => loadDir(ent.path));
          list.appendChild(b);
        }
        el('folder-hint').textContent = '双击进入；或选中后点「选择此文件夹」';
      } catch (e) {
        list.innerHTML = `<div class="hint" style="padding:10px">${escapeHtml(e.message)}</div>`;
      }
    };

    const loadDir = async (dir) => {
      list.innerHTML = '<div class="hint" style="padding:10px">加载中…</div>';
      try {
        const r = await ide('fs.list', { path: dir });
        current = dir;
        selected = dir;
        pathInput.value = dir;
        list.innerHTML = '';
        const entries = (r.entries || []).filter((e) => mode === 'folder' || e.kind === 'dir' || /\.zip$/i.test(e.name));
        if (!entries.length) {
          list.innerHTML = '<div class="hint" style="padding:10px">（空目录）</div>';
        }
        for (const ent of entries) {
          const b = document.createElement('button');
          b.type = 'button';
          b.className = `folder-item ${ent.kind === 'file' ? 'file' : ''}`;
          b.innerHTML = `<span class="ico">${ent.kind === 'dir' ? '📁' : '📄'}</span><span>${escapeHtml(ent.name)}</span>`;
          b.addEventListener('click', () => {
            list.querySelectorAll('.folder-item').forEach((n) => n.classList.remove('active'));
            b.classList.add('active');
            selected = ent.kind === 'dir' ? ent.path : current;
            if (mode === 'saveZip' && ent.kind === 'file') fileInput.value = ent.name;
          });
          b.addEventListener('dblclick', () => {
            if (ent.kind === 'dir') loadDir(ent.path);
          });
          list.appendChild(b);
        }
        el('folder-hint').textContent = '双击进入子文件夹';
      } catch (e) {
        list.innerHTML = `<div class="hint" style="padding:10px">${escapeHtml(e.message)}</div>`;
        toast(e.message, 'error');
      }
    };

    el('folder-close').onclick = () => close(null);
    el('folder-cancel').onclick = () => close(null);
    el('folder-roots').onclick = () => renderRoots();
    el('folder-up').onclick = async () => {
      if (!current) return renderRoots();
      try {
        const r = await ide('fs.parent', { path: current });
        if (!r.path) return renderRoots();
        await loadDir(r.path);
      } catch {
        renderRoots();
      }
    };
    el('folder-go').onclick = () => {
      const p = pathInput.value.trim();
      if (p) loadDir(p);
    };
    pathInput.onkeydown = (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        el('folder-go').click();
      }
    };
    el('folder-ok').onclick = () => {
      const dir = selected || current || pathInput.value.trim();
      if (!dir) {
        toast('请先选择文件夹', 'error');
        return;
      }
      if (mode === 'saveZip') {
        let name = fileInput.value.trim() || defaultName;
        if (!/\.zip$/i.test(name)) name += '.zip';
        close(`${dir.replace(/[/\\]$/, '')}\\${name}`);
        return;
      }
      close(dir);
    };

    root.classList.remove('hidden');
    if (startPath) await loadDir(startPath);
    else await renderRoots();
  });
}

async function pickAndOpen() {
  try {
    const dir = await pickFolderInApp({ title: '打开插件项目文件夹' });
    if (!dir) {
      toast('已取消打开', 'error');
      return;
    }
    await openProject(dir);
  } catch (e) {
    toast(e.message, 'error');
    await alertDialog(e.message, '打开项目失败');
  }
}

async function newPlugin(kind, defaultFolder) {
  try {
    const parent = await pickFolderInApp({
      title: `新建「${kind}」— 选择保存位置`,
    });
    if (!parent) {
      toast('已取消新建', 'error');
      return;
    }
    const id = await promptDialog('插件 id（英文/数字/横线）', defaultFolder, '新建插件');
    if (!id) return;
    if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(id)) {
      toast('id 非法', 'error');
      return;
    }
    setStatus(`正在创建 ${kind} 模板…`);
    const r = await ide('project.scaffold', {
      kind,
      parent,
      id,
      defaultFolder,
    });
    log(`已创建 ${r.title}\n${r.path}\n文件: ${(r.files || []).join(', ')}`);
    toast(`已创建：${r.title}`, 'ok');
    await openProject(r.path);
    setStatus(r.path);
  } catch (e) {
    log(`新建失败: ${e.message}`);
    toast(e.message, 'error');
    await alertDialog(e.message, '新建失败');
  }
}

async function packProject() {
  if (!state.projectRoot) return;
  if (state.dirty) await saveFile();
  try {
    const out = await pickFolderInApp({
      title: '选择 zip 保存位置',
      startPath: state.projectRoot,
      mode: 'saveZip',
      defaultName: `${basename(state.projectRoot)}.zip`,
    });
    if (!out) {
      toast('已取消打包', 'error');
      return;
    }
    const r = await ide('pack', { path: state.projectRoot, out });
    log(r.output || '');
    toast(`打包完成：${r.dest}`, 'ok');
    await alertDialog(`打包完成\n${r.dest}`, '打包');
  } catch (e) {
    log(`打包失败: ${e.message}`);
    toast(e.message, 'error');
    await alertDialog(e.message, '打包失败');
  }
}

function basename(p) {
  return String(p || '').replace(/[/\\]+$/, '').split(/[/\\]/).pop() || p;
}

function langOf(filePath) {
  const n = basename(filePath).toLowerCase();
  const ext = n.includes('.') ? n.slice(n.lastIndexOf('.')) : '';
  const map = {
    '.json': 'json',
    '.jsonc': 'json',
    '.js': 'javascript',
    '.mjs': 'javascript',
    '.cjs': 'javascript',
    '.ts': 'typescript',
    '.tsx': 'typescript',
    '.jsx': 'javascript',
    '.html': 'html',
    '.htm': 'html',
    '.css': 'css',
    '.scss': 'scss',
    '.less': 'less',
    '.md': 'markdown',
    '.markdown': 'markdown',
    '.xml': 'xml',
    '.yml': 'yaml',
    '.yaml': 'yaml',
    '.txt': 'plaintext',
    '.svg': 'xml',
    '.py': 'python',
    '.pyw': 'python',
    '.pyi': 'python',
    '.rs': 'rust',
    '.go': 'go',
    '.java': 'java',
    '.kt': 'kotlin',
    '.kts': 'kotlin',
    '.cs': 'csharp',
    '.cpp': 'cpp',
    '.cc': 'cpp',
    '.cxx': 'cpp',
    '.c': 'c',
    '.h': 'c',
    '.hpp': 'cpp',
    '.php': 'php',
    '.rb': 'ruby',
    '.sql': 'sql',
    '.sh': 'shell',
    '.bash': 'shell',
    '.ps1': 'powershell',
    '.psm1': 'powershell',
    '.bat': 'bat',
    '.cmd': 'bat',
    '.lua': 'lua',
    '.r': 'r',
    '.dart': 'dart',
    '.swift': 'swift',
    '.vue': 'html',
  };
  if (n === 'booxin-plugin.json' || n === 'booxin-plugin.jsonc') return 'json';
  if (n === 'dockerfile') return 'dockerfile';
  return map[ext] || 'plaintext';
}

function updateTitle() {
  const proj = state.projectRoot ? basename(state.projectRoot) : '未打开项目';
  const file = state.currentFile ? ` — ${basename(state.currentFile)}` : '';
  const star = state.dirty ? ' *' : '';
  document.title = `Booxin Studio · ${proj}${file}${star}`;
}

function setProjectUi(open) {
  el('welcome').classList.toggle('hidden', open);
  el('ide').classList.toggle('hidden', !open);
  el('btn-save').disabled = !open;
  el('btn-pack').disabled = !open;
  el('btn-validate').disabled = !open;
  el('btn-close').disabled = !open;
  const fmt = el('btn-format');
  const goto = el('btn-goto');
  const search = el('btn-search');
  if (fmt) fmt.disabled = !open;
  if (goto) goto.disabled = !open;
  if (search) search.disabled = !open;
}

function renderTabs() {
  const box = el('tabs');
  box.innerHTML = '';
  for (const tab of state.tabs) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = `tab${tab.path === state.currentFile ? ' active' : ''}`;
    b.innerHTML = `<span>${escapeHtml(tab.name)}${state.dirty && tab.path === state.currentFile ? ' •' : ''}</span><span class="x" data-close="${escapeAttr(tab.path)}">×</span>`;
    b.addEventListener('click', (e) => {
      const close = e.target?.getAttribute?.('data-close');
      if (close) {
        e.stopPropagation();
        void closeTab(close);
        return;
      }
      openFile(tab.path, true);
    });
    box.appendChild(b);
  }
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
function escapeAttr(s) {
  return escapeHtml(s).replace(/'/g, '&#39;');
}

async function confirmDiscard() {
  if (!state.dirty) return true;
  return await confirmDialog('当前文件尚未保存，是否放弃修改？');
}

async function refreshRecent() {
  const list = el('recent-list');
  const empty = el('recent-empty');
  list.innerHTML = '';
  try {
    const r = await ide('recent.get');
    const items = r.recent || [];
    empty.classList.toggle('hidden', items.length > 0);
    for (const item of items) {
      const li = document.createElement('li');
      li.innerHTML = `<div class="name">${escapeHtml(item.name || basename(item.path))}</div><div class="path">${escapeHtml(item.path)}</div>`;
      li.addEventListener('dblclick', () => openProject(item.path));
      li.addEventListener('click', () => openProject(item.path));
      list.appendChild(li);
    }
  } catch (e) {
    log(`读取历史失败: ${e.message}`);
  }
}

async function openProject(dir) {
  if (!(await confirmDiscard())) return;
  try {
    await ensureMonaco();
    const sum = await ide('project.summary', { path: dir });
    await ide('recent.add', { path: dir });
    state.projectRoot = sum.project.path;
    state.currentFile = null;
    state.tabs = [];
    state.treeCache.clear();
    state.dirty = false;
    state.loading = true;
    state.editor?.setValue('');
    state.loading = false;
    setProjectUi(true);
    el('editor-path').textContent = '未打开文件 — 点击左侧文件开始编辑';
    renderTabs();
    await loadTree();
    syncTerminalCwd(state.projectRoot);
    setBottomTab('log');
    if (sum.project.hasManifest) {
      log(`项目: ${sum.project.name}  id=${sum.project.id}  v${sum.project.version}`);
    } else {
      log(`项目: ${sum.project.name}（未找到 booxin-plugin.json）`);
    }
    setStatus(state.projectRoot);
    updateTitle();
  } catch (e) {
    log(`打开失败: ${e.message}`);
    await alertDialog(e.message);
  }
}

async function closeProject() {
  if (!(await confirmDiscard())) return;
  state.projectRoot = null;
  state.currentFile = null;
  state.tabs = [];
  state.dirty = false;
  state.loading = true;
  state.editor?.setValue('');
  state.loading = false;
  setProjectUi(false);
  updateTitle();
  syncTerminalCwd(null);
  await refreshRecent();
  log('已关闭项目');
  setStatus('就绪');
}

async function loadTree() {
  const root = state.projectRoot;
  if (!root) return;
  const box = el('tree');
  box.innerHTML = '';
  const rootNode = await renderDirNode(root, basename(root), 0, true);
  box.appendChild(rootNode);
}

async function listDirCached(dir, force = false) {
  if (!force && state.treeCache.has(dir)) return state.treeCache.get(dir);
  const r = await ide('fs.list', { path: dir });
  const entries = r.entries || [];
  state.treeCache.set(dir, entries);
  return entries;
}

async function renderDirNode(dirPath, name, depth, expand) {
  const wrap = document.createElement('div');
  const row = document.createElement('div');
  row.className = 'tree-item';
  row.dataset.path = dirPath;
  row.dataset.kind = 'dir';
  row.innerHTML = `<span class="ico">${expand ? '▾' : '▸'}</span><span>${escapeHtml(name)}</span>`;
  const kids = document.createElement('div');
  kids.className = 'tree-children';
  kids.style.display = expand ? 'block' : 'none';
  row.addEventListener('click', async (e) => {
    e.stopPropagation();
    document.querySelectorAll('.tree-item.active').forEach((n) => n.classList.remove('active'));
    row.classList.add('active');
    const open = kids.style.display !== 'block';
    if (open) {
      kids.style.display = 'block';
      row.querySelector('.ico').textContent = '▾';
      await fillChildren(kids, dirPath);
    } else {
      kids.style.display = 'none';
      row.querySelector('.ico').textContent = '▸';
    }
  });
  row.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    document.querySelectorAll('.tree-item.active').forEach((n) => n.classList.remove('active'));
    row.classList.add('active');
    showTreeMenu(e.clientX, e.clientY, dirPath, 'dir');
  });
  wrap.appendChild(row);
  wrap.appendChild(kids);
  if (expand) await fillChildren(kids, dirPath);
  return wrap;
}

async function fillChildren(container, dirPath) {
  container.innerHTML = '';
  try {
    const entries = await listDirCached(dirPath, true);
    for (const ent of entries) {
      if (ent.kind === 'dir') {
        container.appendChild(await renderDirNode(ent.path, ent.name, 0, false));
      } else {
        const row = document.createElement('div');
        row.className = `tree-item${ent.path === state.currentFile ? ' active' : ''}`;
        row.dataset.path = ent.path;
        row.dataset.kind = 'file';
        row.dataset.text = ent.text ? '1' : '0';
        row.innerHTML = `<span class="ico">◈</span><span>${escapeHtml(ent.name)}</span>`;
        row.addEventListener('click', (e) => {
          e.stopPropagation();
          if (!ent.text) {
            log(`二进制/资源文件: ${ent.path}`);
            return;
          }
          openFile(ent.path);
        });
        row.addEventListener('contextmenu', (e) => {
          e.preventDefault();
          document.querySelectorAll('.tree-item.active').forEach((n) => n.classList.remove('active'));
          row.classList.add('active');
          showTreeMenu(e.clientX, e.clientY, ent.path, 'file');
        });
        container.appendChild(row);
      }
    }
  } catch (e) {
    log(`列出目录失败: ${e.message}`);
  }
}

function selectedTreeTarget() {
  const active = document.querySelector('.tree-item.active');
  if (!active) return { dir: state.projectRoot, path: state.projectRoot, kind: 'dir' };
  const path = active.dataset.path;
  const kind = active.dataset.kind;
  const dir = kind === 'dir' ? path : path.replace(/[/\\][^/\\]+$/, '');
  return { dir, path, kind };
}

function showTreeMenu(x, y, path, kind) {
  let menu = document.getElementById('ctx-menu');
  if (menu) menu.remove();
  menu = document.createElement('div');
  menu.id = 'ctx-menu';
  menu.style.cssText = `position:fixed;left:${x}px;top:${y}px;z-index:50;background:#1c2330;border:1px solid #2a3341;border-radius:8px;padding:4px;min-width:140px;`;
  const items = [
    ['新建文件', () => newFile()],
    ['新建文件夹', () => newFolder()],
    ['-'],
    ['重命名', () => renameItem(path)],
    ['删除', () => deleteItem(path, kind)],
    ['-'],
    ['刷新', () => loadTree()],
  ];
  for (const [label, fn] of items) {
    if (label === '-') {
      const hr = document.createElement('div');
      hr.style.cssText = 'height:1px;background:#2a3341;margin:4px 0';
      menu.appendChild(hr);
      continue;
    }
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = label;
    b.style.cssText = 'display:block;width:100%;text-align:left;border:0;background:transparent;padding:6px 10px;border-radius:6px';
    b.addEventListener('click', () => {
      menu.remove();
      fn();
    });
    menu.appendChild(b);
  }
  document.body.appendChild(menu);
  const close = () => {
    menu.remove();
    document.removeEventListener('click', close);
  };
  setTimeout(() => document.addEventListener('click', close), 0);
}

async function openFile(filePath, fromTab = false) {
  if (state.currentFile === filePath && !fromTab) return;
  if (state.currentFile && state.currentFile !== filePath && !(await confirmDiscard())) return;
  try {
    await ensureMonaco();
    const r = await ide('fs.read', { path: filePath });
    state.loading = true;
    const lang = langOf(r.path);
    monaco.editor.setModelLanguage(state.editor.getModel(), lang);
    state.editor.setValue(r.content || '');
    state.loading = false;
    state.currentFile = r.path;
    state.dirty = false;
    if (!state.tabs.some((t) => t.path === r.path)) {
      state.tabs.push({ path: r.path, name: basename(r.path) });
    }
    el('editor-path').textContent = r.path;
    renderTabs();
    updateTitle();
    setStatus(r.path);
    state.editor.focus();
    document.querySelectorAll('.tree-item').forEach((n) => {
      n.classList.toggle('active', n.dataset.path === r.path);
    });
  } catch (e) {
    log(`打开失败: ${e.message}`);
    await alertDialog(e.message);
  }
}

async function saveFile() {
  if (!state.currentFile || !state.editor) return;
  try {
    const content = state.editor.getValue();
    const r = await ide('fs.write', { path: state.currentFile, content });
    state.dirty = false;
    renderTabs();
    updateTitle();
    log(`已保存 (${r.bytes} 字节): ${state.currentFile}`);
    setStatus(`已保存 · ${state.currentFile}`);
  } catch (e) {
    log(`保存失败: ${e.message}`);
    await alertDialog(e.message);
  }
}

async function closeTab(path) {
  if (path === state.currentFile && state.dirty && !await confirmDialog('未保存，关闭？')) return;
  state.tabs = state.tabs.filter((t) => t.path !== path);
  if (state.currentFile === path) {
    const next = state.tabs[state.tabs.length - 1];
    if (next) openFile(next.path, true);
    else {
      state.currentFile = null;
      state.dirty = false;
      state.loading = true;
      state.editor.setValue('');
      state.loading = false;
      el('editor-path').textContent = '未打开文件';
      updateTitle();
    }
  }
  renderTabs();
}

async function newFile() {
  if (!state.projectRoot) return;
  const { dir } = selectedTreeTarget();
  const name = await promptDialog('文件名', 'untitled.txt');
  if (!name || /[\\/:*?"<>|]/.test(name)) return await alertDialog('文件名非法');
  const path = `${dir.replace(/[/\\]$/, '')}\\${name}`;
  try {
    const r = await ide('fs.create', { path, content: '' });
    state.treeCache.clear();
    await loadTree();
    await openFile(r.path);
    log(`已创建: ${r.path}`);
  } catch (e) {
    await alertDialog(e.message);
  }
}

async function newFolder() {
  if (!state.projectRoot) return;
  const { dir } = selectedTreeTarget();
  const name = await promptDialog('文件夹名', 'pages');
  if (!name || /[\\/:*?"<>|]/.test(name)) return await alertDialog('名称非法');
  const path = `${dir.replace(/[/\\]$/, '')}\\${name}`;
  try {
    const r = await ide('fs.mkdir', { path });
    state.treeCache.clear();
    await loadTree();
    log(`已创建文件夹: ${r.path}`);
  } catch (e) {
    await alertDialog(e.message);
  }
}

async function renameItem(path) {
  const old = basename(path);
  const name = await promptDialog('新名称', old);
  if (!name || name === old || /[\\/:*?"<>|]/.test(name)) return;
  try {
    const r = await ide('fs.rename', { path, name });
    if (state.currentFile === path) {
      state.currentFile = r.path;
      el('editor-path').textContent = r.path;
    }
    state.tabs = state.tabs.map((t) => (t.path === path ? { path: r.path, name } : t));
    state.treeCache.clear();
    await loadTree();
    renderTabs();
    updateTitle();
  } catch (e) {
    await alertDialog(e.message);
  }
}

async function deleteItem(path, kind) {
  if (path === state.projectRoot) return await alertDialog('不能删除项目根目录');
  if (!await confirmDialog(`确定删除${kind === 'dir' ? '文件夹' : '文件'}？\n${path}`)) return;
  try {
    await ide('fs.delete', { path });
    if (state.currentFile === path) {
      state.currentFile = null;
      state.dirty = false;
      state.loading = true;
      state.editor.setValue('');
      state.loading = false;
      el('editor-path').textContent = '未打开文件';
    }
    state.tabs = state.tabs.filter((t) => t.path !== path);
    state.treeCache.clear();
    await loadTree();
    renderTabs();
    log(`已删除: ${path}`);
  } catch (e) {
    await alertDialog(e.message);
  }
}

async function validateProject() {
  if (!state.projectRoot) return;
  try {
    const r = await ide('validate', { path: state.projectRoot });
    log(r.output || '');
  } catch (e) {
    log(`校验失败: ${e.message}`);
  }
}

function setAgentOpen(open) {
  state.agentOpen = open;
  el('ide').classList.toggle('agent-collapsed', !open);
  el('btn-agent').classList.toggle('primary', open);
}

function appendAi(role, content, { streaming = false } = {}) {
  const feed = el('ai-feed');
  const div = document.createElement('div');
  div.className = `bubble ${role}${streaming ? ' streaming' : ''}`;
  const label = role === 'user' ? '你' : role === 'assistant' ? 'Agent' : '系统';
  div.innerHTML = `<div class="role">${label}</div><div class="body"></div>`;
  const bodyEl = div.querySelector('.body');
  bodyEl.textContent = content || '';
  feed.appendChild(div);
  feed.scrollTop = feed.scrollHeight;
  return { div, bodyEl };
}

function finishAssistantBubble(div, content) {
  div.classList.remove('streaming');
  const bodyEl = div.querySelector('.body');
  if (bodyEl) bodyEl.textContent = content || '';
  const old = div.querySelector('.ai-actions');
  if (old) old.remove();
}

function projectSummaryLine() {
  if (!state.projectRoot) return '';
  const name = basename(state.projectRoot);
  const file = state.currentFile ? `\n当前文件: ${state.currentFile}` : '';
  return `路径: ${state.projectRoot}\n项目名: ${name}${file}`;
}

async function buildAgentContext() {
  const parts = [];
  if (el('ai-context')?.checked && state.currentFile && state.editor) {
    parts.push(`【当前文件 ${state.currentFile}】\n${state.editor.getValue().slice(0, 6000)}`);
  }
  if (el('ai-ctx-manifest')?.checked && state.projectRoot) {
    try {
      const man = `${state.projectRoot.replace(/[/\\]$/, '')}\\booxin-plugin.json`;
      const r = await ide('fs.read', { path: man });
      parts.push(`【booxin-plugin.json】\n${String(r.content || '').slice(0, 5000)}`);
    } catch {
      /* no manifest */
    }
  }
  return parts.join('\n\n');
}

function updateCtxMeta() {
  const n = Math.floor((state.aiHistory?.length || 0) / 2);
  const meta = el('ai-ctx-meta');
  if (meta) meta.textContent = `历史 ${n} 轮`;
}

async function persistAiChat() {
  if (!state.aiLoggedIn) return;
  try {
    const r = await ide('ai.history.save', {
      id: state.aiChatId || '',
      messages: state.aiHistory,
      projectPath: state.projectRoot || '',
    });
    state.aiChatId = r.chat?.id || state.aiChatId;
    await refreshChatList();
  } catch (e) {
    log(`保存对话失败: ${e.message}`);
  }
}

async function refreshChatList() {
  const sel = el('ai-chat-list');
  if (!sel) return;
  try {
    const r = await ide('ai.history.list');
    const chats = r.chats || [];
    sel.innerHTML = '';
    if (!chats.length) {
      const opt = document.createElement('option');
      opt.value = '';
      opt.textContent = '暂无历史';
      sel.appendChild(opt);
      return;
    }
    for (const c of chats) {
      const opt = document.createElement('option');
      opt.value = c.id;
      opt.textContent = `${c.title}（${c.messageCount}）`;
      if (c.id === state.aiChatId) opt.selected = true;
      sel.appendChild(opt);
    }
    if (state.aiChatId && !chats.some((c) => c.id === state.aiChatId) && chats[0]) {
      state.aiChatId = chats[0].id;
      sel.value = state.aiChatId;
    }
  } catch (e) {
    log(`读取历史失败: ${e.message}`);
  }
}

async function loadAiChat(id) {
  if (!id) return;
  try {
    const r = await ide('ai.history.get', { id });
    const chat = r.chat;
    state.aiChatId = chat.id;
    state.aiHistory = Array.isArray(chat.messages) ? chat.messages : [];
    el('ai-feed').innerHTML = '';
    if (!state.aiHistory.length) {
      appendAi('system', '空对话。开启「直接写文件」后，带路径的代码块会自动写入项目。');
    } else {
      for (const m of state.aiHistory) {
        appendAi(m.role === 'assistant' ? 'assistant' : 'user', m.content);
        if (m.role === 'assistant') {
          const last = el('ai-feed')?.lastElementChild;
          if (last) finishAssistantBubble(last, m.content);
        }
      }
    }
    updateCtxMeta();
    await refreshChatList();
  } catch (e) {
    toast(e.message, 'error');
  }
}

async function startNewAiChat() {
  try {
    const r = await ide('ai.history.new', { projectPath: state.projectRoot || '' });
    state.aiChatId = r.chat?.id || '';
    state.aiHistory = [];
    el('ai-feed').innerHTML = '';
    appendAi('system', '新对话已创建。勾选「直接写文件」可让 Agent 改完即落盘。');
    updateCtxMeta();
    await refreshChatList();
  } catch (e) {
    toast(e.message, 'error');
  }
}

async function deleteCurrentAiChat() {
  if (!state.aiChatId) return startNewAiChat();
  const ok = await confirmDialog('删除当前对话？此操作不可恢复。', '删除对话');
  if (!ok) return;
  try {
    const r = await ide('ai.history.delete', { id: state.aiChatId });
    const next = r.chats?.[0];
    if (next) await loadAiChat(next.id);
    else await startNewAiChat();
  } catch (e) {
    toast(e.message, 'error');
  }
}

async function autoWriteFromReply(content, bubbleDiv) {
  if (!el('ai-auto-write')?.checked) return;
  const blocks = parseCodeBlocks(content).filter((b) => b.file);
  if (!blocks.length) return;
  if (!state.projectRoot) {
    toast('未打开项目，无法自动写入', 'error');
    return;
  }
  const written = [];
  for (const block of blocks) {
    try {
      await applyCodeBlock(block, { silent: true });
      written.push(basename(block.file));
    } catch (e) {
      log(`自动写入失败 ${block.file}: ${e.message}`);
    }
  }
  if (written.length) {
    state.treeCache.clear();
    await loadTree();
    const last = blocks[blocks.length - 1];
    const full = resolveProjectPath(last.file);
    if (full) await openFile(full, true).catch(() => {});
    const note = document.createElement('div');
    note.className = 'write-note';
    note.textContent = `已直接写入：${written.join('、')}`;
    bubbleDiv?.appendChild(note);
    toast(`已写入 ${written.length} 个文件`, 'ok');
    log(`Agent 已写入: ${written.join(', ')}`);
    setStatus(`Agent 已写入 ${written.length} 个文件`);
  }
}

async function aiSend() {
  if (state.aiBusy) return;
  const message = el('ai-ask').value.trim();
  if (!message) return;
  if (!state.aiLoggedIn) return toast('请先登录', 'error');
  state.aiBusy = true;
  appendAi('user', message);
  el('ai-ask').value = '';
  const historyJson = JSON.stringify(state.aiHistory);
  const fields = {
    message,
    historyJson,
    model: el('ai-model')?.value || '',
    projectSummary: projectSummaryLine(),
    projectRoot: state.projectRoot || '',
  };
  const ctx = await buildAgentContext();
  if (ctx) fields.context = ctx;

  const { div, bodyEl } = appendAi('assistant', '', { streaming: true });
  const steps = document.createElement('div');
  steps.className = 'ai-steps';
  div.insertBefore(steps, bodyEl);
  let statusEl = null;
  let reply = '';
  const mutated = [];

  const ensureStatus = () => {
    if (!statusEl) {
      statusEl = document.createElement('div');
      statusEl.className = 'ai-status';
      steps.appendChild(statusEl);
    }
    return statusEl;
  };

  const toolCard = (id) => {
    let card = steps.querySelector(`[data-tool-id="${id}"]`);
    if (!card) {
      card = document.createElement('div');
      card.className = 'ai-tool running';
      card.dataset.toolId = id;
      card.innerHTML = `<div class="ai-tool-head"><span class="ai-tool-spin"></span><span class="ai-tool-title"></span></div><div class="ai-tool-detail"></div>`;
      steps.appendChild(card);
    }
    return card;
  };

  try {
    setStatus('Agent 工作中…');
    const { port, token } = cfg();
    const res = await fetch(`http://127.0.0.1:${port}/ide/ai-stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'ai.chat', token, ...fields }),
    });
    if (!res.ok) {
      const errText = await res.text();
      throw new Error(errText.slice(0, 200) || `HTTP ${res.status}`);
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buf = '';
    let donePayload = null;

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      const parts = buf.split(/\n\n/);
      buf = parts.pop() || '';
      for (const part of parts) {
        const line = part.split('\n').find((l) => l.startsWith('data:'));
        if (!line) continue;
        let ev;
        try {
          ev = JSON.parse(line.slice(5).trim());
        } catch {
          continue;
        }
        if (ev.type === 'status') {
          ensureStatus().textContent = ev.text || '…';
          el('ai-feed').scrollTop = el('ai-feed').scrollHeight;
          setStatus(ev.text || 'Agent 工作中…');
        } else if (ev.type === 'tool_start') {
          const card = toolCard(ev.id);
          card.classList.add('running');
          card.classList.remove('ok', 'err');
          const title = `${ev.label || ev.name || '工具'}${ev.args?.path ? ` · ${ev.args.path}` : ''}${ev.args?.query ? ` · ${ev.args.query}` : ''}${ev.args?.command ? ` · ${String(ev.args.command).slice(0, 40)}` : ''}`;
          card.querySelector('.ai-tool-title').textContent = title;
          card.querySelector('.ai-tool-detail').textContent = '执行中…';
          el('ai-feed').scrollTop = el('ai-feed').scrollHeight;
          setStatus(`正在${ev.label || ev.name}…`);
        } else if (ev.type === 'tool_end') {
          const card = toolCard(ev.id);
          card.classList.remove('running');
          card.classList.add(ev.ok ? 'ok' : 'err');
          const spin = card.querySelector('.ai-tool-spin');
          if (spin) spin.textContent = ev.ok ? '✓' : '✗';
          spin?.classList.remove('ai-tool-spin');
          card.querySelector('.ai-tool-title').textContent =
            `${ev.label || ev.name}${ev.path ? ` · ${ev.path}` : ''}`;
          card.querySelector('.ai-tool-detail').textContent = ev.summary || (ev.ok ? '完成' : '失败');
          if (ev.ok && ev.path) mutated.push(ev.path);
          el('ai-feed').scrollTop = el('ai-feed').scrollHeight;
          log(`Agent ${ev.label || ev.name}: ${ev.summary}`);
        } else if (ev.type === 'delta' && ev.text) {
          if (statusEl) statusEl.remove();
          statusEl = null;
          reply += ev.text;
          bodyEl.textContent = reply;
          el('ai-feed').scrollTop = el('ai-feed').scrollHeight;
        } else if (ev.type === 'done') {
          donePayload = ev;
          if (ev.reply != null && !reply) reply = ev.reply;
          if (Array.isArray(ev.mutated)) mutated.push(...ev.mutated);
        } else if (ev.type === 'error') {
          throw new Error(ev.message || 'Agent 失败');
        }
      }
    }

    if (!reply && donePayload?.reply) reply = donePayload.reply;
    if (!reply) reply = mutated.length ? `已完成操作，修改了 ${[...new Set(mutated)].join('、')}` : '（无回复）';
    finishAssistantBubble(div, reply);
    if (el('ai-auto-write')?.checked) await autoWriteFromReply(reply, div);
    if (mutated.length) {
      state.treeCache.clear();
      await loadTree();
      const last = [...new Set(mutated)].pop();
      const full = resolveProjectPath(last);
      if (full) await openFile(full, true).catch(() => {});
    }
    state.aiHistory.push({ role: 'user', content: message });
    state.aiHistory.push({ role: 'assistant', content: reply });
    updateCtxMeta();
    await persistAiChat();
    if (donePayload?.quotaLine) el('agent-quota').textContent = donePayload.quotaLine;
    setStatus(mutated.length ? `Agent 完成 · 已改 ${[...new Set(mutated)].length} 个文件` : 'Agent 完成');
  } catch (e) {
    div.remove();
    appendAi('system', e.message);
    setStatus('Agent 失败');
    toast(e.message, 'error');
  } finally {
    state.aiBusy = false;
  }
}

function parseCodeBlocks(text) {
  const blocks = [];
  const re = /```([^\n`]*)\n([\s\S]*?)```/g;
  let m;
  while ((m = re.exec(text))) {
    const meta = String(m[1] || '').trim();
    const code = String(m[2] || '').replace(/\n$/, '');
    if (!code.trim()) continue;
    let file = '';
    // patterns: "json booxin-plugin.json" | "booxin-plugin.json" | "json:path" | "path/file.html"
    const parts = meta.split(/\s+/).filter(Boolean);
    if (parts.length >= 2) {
      file = parts.slice(1).join(' ').replace(/^:/, '');
    } else if (parts.length === 1 && /[./\\]/.test(parts[0])) {
      file = parts[0];
    } else if (parts.length === 1 && parts[0].includes(':')) {
      file = parts[0].split(':').slice(1).join(':');
    }
    file = file.replace(/^["']|["']$/g, '').trim();
    blocks.push({ lang: parts[0] || '', file, code });
  }
  return blocks;
}

function resolveProjectPath(rel) {
  if (!rel) return null;
  if (/^[a-zA-Z]:[\\/]/.test(rel) || rel.startsWith('\\\\')) return rel;
  if (!state.projectRoot) return null;
  return `${state.projectRoot.replace(/[/\\]$/, '')}\\${rel.replace(/^[\\/]+/, '').replace(/\//g, '\\')}`;
}

async function applyCodeBlock(block, { silent = false } = {}) {
  try {
    if (block.file) {
      const full = resolveProjectPath(block.file);
      if (!full) {
        if (!silent) await alertDialog('请先打开项目');
        throw new Error('请先打开项目');
      }
      try {
        await ide('fs.read', { path: full });
        await ide('fs.write', { path: full, content: block.code });
      } catch {
        await ide('fs.create', { path: full, content: block.code }).catch(async () => {
          await ide('fs.write', { path: full, content: block.code });
        });
      }
      if (state.currentFile === full && state.editor) {
        state.loading = true;
        state.editor.setValue(block.code);
        state.loading = false;
        state.dirty = false;
        updateTitle();
        renderTabs();
      } else if (!silent) {
        state.treeCache.clear();
        await loadTree();
        await openFile(full, true);
      }
      log(`已写入: ${full}`);
      if (!silent) setStatus(`已应用 · ${basename(full)}`);
      return full;
    }
    if (!state.editor || !state.currentFile) {
      if (!silent) await alertDialog('请先打开要写入的文件');
      throw new Error('请先打开要写入的文件');
    }
    state.editor.setValue(block.code);
    state.dirty = true;
    updateTitle();
    renderTabs();
    log(`已应用到当前文件: ${state.currentFile}`);
    return state.currentFile;
  } catch (e) {
    if (!silent) await alertDialog(e.message);
    throw e;
  }
}

function insertAtCursor(code) {
  if (!state.editor) return;
  const sel = state.editor.getSelection();
  state.editor.executeEdits('ai-insert', [
    {
      range: sel,
      text: code,
      forceMoveMarkers: true,
    },
  ]);
  state.dirty = true;
  updateTitle();
  renderTabs();
}

async function formatDocument() {
  if (!state.editor) return;
  try {
    await state.editor.getAction('editor.action.formatDocument')?.run();
  } catch (e) {
    log(`格式化失败: ${e.message || e}`);
  }
}

async function gotoLine() {
  if (!state.editor) return;
  const n = await promptDialog('转到行号', '1');
  const line = parseInt(n, 10);
  if (!line || line < 1) return;
  state.editor.revealLineInCenter(line);
  state.editor.setPosition({ lineNumber: line, column: 1 });
  state.editor.focus();
}

async function runProjectSearch() {
  if (!state.projectRoot) return;
  const q = el('search-query').value.trim();
  if (!q) return;
  const box = el('search-results');
  box.innerHTML = '<div class="hint" style="padding:8px">搜索中…</div>';
  try {
    const r = await ide('fs.search', { path: state.projectRoot, query: q });
    box.innerHTML = '';
    const hits = r.hits || [];
    if (!hits.length) {
      box.innerHTML = '<div class="hint" style="padding:8px">无结果</div>';
      return;
    }
    for (const hit of hits) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'search-hit';
      b.innerHTML = `<div class="meta">${escapeHtml(hit.path)}:${hit.line}</div><div class="snip">${escapeHtml(hit.text)}</div>`;
      b.addEventListener('click', async () => {
        await openFile(hit.path);
        state.editor?.revealLineInCenter(hit.line);
        state.editor?.setPosition({ lineNumber: hit.line, column: 1 });
        state.editor?.focus();
      });
      box.appendChild(b);
    }
    if (r.truncated) {
      const tip = document.createElement('div');
      tip.className = 'hint';
      tip.style.padding = '8px';
      tip.textContent = '结果过多，已截断';
      box.appendChild(tip);
    }
  } catch (e) {
    box.innerHTML = `<div class="hint" style="padding:8px">${escapeHtml(e.message)}</div>`;
  }
}

function clearAiChat() {
  void startNewAiChat();
}

function updateAiAuthUi() {
  el('agent-login').classList.toggle('hidden', state.aiLoggedIn);
  el('agent-chat').classList.toggle('hidden', !state.aiLoggedIn);
  el('btn-ai-logout')?.classList.toggle('hidden', !state.aiLoggedIn);
}

async function refreshModelSelect() {
  const sel = el('ai-model');
  if (!sel) return;
  try {
    const r = await ide('ai.models');
    const selected = r.selectedModel || 'deepseek';
    sel.innerHTML = '';
    for (const m of r.models || []) {
      const opt = document.createElement('option');
      opt.value = m.id;
      opt.textContent = m.locked ? `${m.name}（${m.lockHint || '锁定'}）` : m.name;
      opt.disabled = !!m.locked;
      if (m.id === selected && !m.locked) opt.selected = true;
      sel.appendChild(opt);
    }
    if (![...sel.options].some((o) => o.selected && !o.disabled)) {
      const first = [...sel.options].find((o) => !o.disabled);
      if (first) first.selected = true;
    }
  } catch (e) {
    log(`模型列表: ${e.message}`);
  }
}

async function setSelectedModel(id) {
  try {
    await ide('ai.model.set', { model: id });
  } catch (e) {
    await alertDialog(e.message);
    await refreshModelSelect();
  }
}

async function refreshAiSession() {
  try {
    const r = await ide('ai.session.validate');
    state.aiLoggedIn = !!r.settings?.loggedIn;
    if (state.aiLoggedIn) {
      const name = r.settings.username || r.settings.userId || '已登录';
      el('agent-title').textContent = `Agent · ${name}`;
      el('agent-quota').textContent = r.quotaLine || '已登录';
      await refreshModelSelect();
      const listed = await ide('ai.history.list');
      if (listed.activeId) await loadAiChat(listed.activeId);
      else await startNewAiChat();
    } else {
      el('agent-title').textContent = 'Agent';
      el('agent-quota').textContent = '未登录';
    }
    updateAiAuthUi();
  } catch (e) {
    log(`AI 会话: ${e.message}`);
  }
}

async function aiLogin() {
  const username = el('ai-user').value.trim();
  const password = el('ai-pass').value;
  if (!username || !password) return toast('请输入用户名和密码', 'error');
  try {
    setStatus('正在登录…');
    const r = await ide('ai.login', { username, password });
    state.aiLoggedIn = !!r.settings?.loggedIn;
    el('ai-pass').value = '';
    el('agent-title').textContent = `Agent · ${r.settings.username || username}`;
    el('agent-quota').textContent = r.quotaLine || '已登录';
    updateAiAuthUi();
    await refreshModelSelect();
    const listed = await ide('ai.history.list');
    if (listed.activeId) await loadAiChat(listed.activeId);
    else await startNewAiChat();
    setStatus('Agent 已登录');
    toast('登录成功', 'ok');
  } catch (e) {
    toast(e.message, 'error');
    setStatus('登录失败');
  }
}

async function aiLogout() {
  try {
    await ide('ai.logout');
  } catch {
    /* ignore */
  }
  state.aiLoggedIn = false;
  el('agent-title').textContent = 'Agent';
  el('agent-quota').textContent = '未登录';
  updateAiAuthUi();
  clearAiChat();
}

async function checkUpdate(silent) {
  try {
    setStatus('正在检查更新…');
    const r = await ide('app.version.check');
    if (r.hasUpdate) {
      const ok = await confirmDialog(
        `发现新版本 ${r.latestVersion}（当前 ${r.currentVersion}）\n\n需手动下载安装。\n${r.notes || ''}\n\n打开下载页？`,
      );
      if (ok) await openExternal(r.pageUrl || r.downloadUrl);
      setStatus(`有新版本 ${r.latestVersion}`);
    } else if (!silent) {
      await alertDialog(`已是最新版本 ${r.currentVersion}`);
      setStatus(`就绪 · v${r.currentVersion}`);
    } else {
      log(`版本检查：已是最新 ${r.currentVersion}`);
      setStatus(`就绪 · v${r.currentVersion}`);
    }
  } catch (e) {
    if (!silent) await alertDialog(e.message);
    else log(`版本检查跳过: ${e.message}`);
  }
}

function setBottomTab(name) {
  state.bottomTab = name;
  document.querySelectorAll('.bottom-tab').forEach((b) => {
    b.classList.toggle('active', b.dataset.bottom === name);
  });
  el('log').classList.toggle('active', name === 'log');
  el('terminal').classList.toggle('active', name === 'terminal');
  el('search-panel')?.classList.toggle('active', name === 'search');
  if (name === 'terminal') {
    void ensureTerminal().then(() => {
      requestAnimationFrame(() => {
        state.termFit?.fit();
        state.term?.focus();
      });
    });
  }
  if (name === 'search') {
    el('search-query')?.focus();
  }
}

function termControl(msg) {
  if (state.termSocket?.readyState === WebSocket.OPEN) {
    state.termSocket.send(`\x1e${JSON.stringify(msg)}`);
  }
}

function syncTerminalCwd(dir) {
  if (dir) ide('session.setCwd', { path: dir }).catch(() => {});
  else ide('session.setCwd', { path: '' }).catch(() => {});
  termControl({ type: 'cwd', path: dir || '' });
}

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = src;
    s.async = false;
    s.onload = () => resolve();
    s.onerror = () => reject(new Error(`加载失败: ${src}`));
    document.head.appendChild(s);
  });
}

/** Monaco AMD loader captures UMD scripts; disable amd briefly so globals attach. */
async function loadScriptAsGlobal(src) {
  const def = window.define;
  const hadAmd = def && def.amd;
  if (hadAmd) {
    try {
      def.amd = false;
    } catch {
      /* ignore */
    }
  }
  try {
    await loadScript(src);
  } finally {
    if (hadAmd) {
      try {
        def.amd = hadAmd;
      } catch {
        /* ignore */
      }
    }
  }
}

function loadCss(href) {
  return new Promise((resolve, reject) => {
    if ([...document.querySelectorAll('link[rel=stylesheet]')].some((l) => l.href && l.href.includes(href))) {
      resolve();
      return;
    }
    const l = document.createElement('link');
    l.rel = 'stylesheet';
    l.href = href;
    l.onload = () => resolve();
    l.onerror = () => reject(new Error(`样式加载失败: ${href}`));
    document.head.appendChild(l);
  });
}

let xtermReady = null;
function ensureXterm() {
  if (typeof Terminal !== 'undefined') return Promise.resolve();
  if (!xtermReady) {
    xtermReady = (async () => {
      await loadCss('/vendor/xterm/css/xterm.css');
      await loadScriptAsGlobal('/vendor/xterm/lib/xterm.js');
      await loadScriptAsGlobal('/vendor/addon-fit/lib/addon-fit.js');
      if (typeof Terminal === 'undefined') {
        throw new Error('xterm 未暴露 Terminal（可能被 AMD 拦截）');
      }
    })().catch((e) => {
      xtermReady = null;
      throw e;
    });
  }
  return xtermReady;
}

function resolveFitAddonCtor() {
  const fad = window.FitAddon;
  if (!fad) return null;
  if (typeof fad === 'function') return fad;
  if (typeof fad.FitAddon === 'function') return fad.FitAddon;
  return null;
}

async function ensureTerminal() {
  if (state.term) {
    if (!state.termSocket || state.termSocket.readyState > 1) connectTerminalSocket();
    return;
  }
  try {
    await ensureXterm();
  } catch (e) {
    log(`终端组件加载失败: ${e.message}`);
    setStatus(`终端加载失败: ${e.message}`);
    return;
  }
  if (typeof Terminal === 'undefined') {
    log('终端组件未加载（xterm）');
    return;
  }
  const FitCtor = resolveFitAddonCtor();
  state.term = new Terminal({
    cursorBlink: true,
    fontSize: 13,
    fontFamily: 'Cascadia Code, Consolas, monospace',
    theme: {
      background: '#0b0f14',
      foreground: '#d6deea',
      cursor: '#3d8bfd',
      selectionBackground: '#264f7888',
    },
    convertEol: true,
    scrollback: 5000,
  });
  if (typeof FitCtor === 'function') {
    state.termFit = new FitCtor();
    state.term.loadAddon(state.termFit);
  }
  state.term.open(el('terminal'));
  state.term.onData((data) => {
    if (state.termSocket?.readyState === WebSocket.OPEN) {
      state.termSocket.send(data);
    }
  });
  connectTerminalSocket();
  requestAnimationFrame(() => state.termFit?.fit());
}

function waitTerminalSocket(timeoutMs = 4000) {
  return new Promise((resolve) => {
    if (state.termSocket?.readyState === WebSocket.OPEN) {
      resolve(true);
      return;
    }
    const started = Date.now();
    const tick = () => {
      if (state.termSocket?.readyState === WebSocket.OPEN) {
        resolve(true);
        return;
      }
      if (Date.now() - started > timeoutMs) {
        resolve(false);
        return;
      }
      setTimeout(tick, 50);
    };
    tick();
  });
}

async function newTerminal() {
  setBottomTab('terminal');
  await ensureTerminal();
  if (!state.term) return;
  state.term.clear();
  state.term.writeln('正在启动新终端…');
  const ok = await waitTerminalSocket();
  if (!ok) {
    connectTerminalSocket();
    await waitTerminalSocket();
  }
  termControl({ type: 'restart' });
  if (state.projectRoot) termControl({ type: 'cwd', path: state.projectRoot });
  requestAnimationFrame(() => {
    state.termFit?.fit();
    state.term?.focus();
  });
}

function connectTerminalSocket() {
  const { port, token } = cfg();
  if (!port || !token) return;
  try {
    state.termSocket?.close();
  } catch {
    /* ignore */
  }
  const ws = new WebSocket(`ws://127.0.0.1:${port}/terminal?token=${encodeURIComponent(token)}`);
  state.termSocket = ws;
  ws.onmessage = (ev) => {
    state.term?.write(typeof ev.data === 'string' ? ev.data : '');
  };
  ws.onopen = () => {
    if (state.projectRoot) termControl({ type: 'cwd', path: state.projectRoot });
  };
  ws.onclose = () => {
    state.term?.writeln('\r\n[终端连接已断开]');
  };
}

function bindSplits() {
  const ide = el('ide');
  const bindCol = (handleId, cssVar, min, max) => {
    const handle = el(handleId);
    let startX = 0;
    let start = 0;
    handle.addEventListener('mousedown', (e) => {
      startX = e.clientX;
      start = parseInt(getComputedStyle(ide).getPropertyValue(cssVar), 10) || 240;
      const move = (ev) => {
        const dx = ev.clientX - startX;
        const next = Math.max(min, Math.min(max, cssVar === '--agent-w' ? start - dx : start + dx));
        ide.style.setProperty(cssVar, `${next}px`);
      };
      const up = () => {
        document.removeEventListener('mousemove', move);
        document.removeEventListener('mouseup', up);
        state.termFit?.fit();
      };
      document.addEventListener('mousemove', move);
      document.addEventListener('mouseup', up);
    });
  };
  bindCol('split-sidebar', '--side-w', 160, 420);
  bindCol('split-agent', '--agent-w', 280, 520);
  const logHandle = el('split-log');
  let startY = 0;
  let startH = 0;
  logHandle.addEventListener('mousedown', (e) => {
    startY = e.clientY;
    startH = parseInt(getComputedStyle(ide).getPropertyValue('--log-h'), 10) || 140;
    const move = (ev) => {
      const dy = startY - ev.clientY;
      const next = Math.max(100, Math.min(420, startH + dy));
      ide.style.setProperty('--log-h', `${next}px`);
    };
    const up = () => {
      document.removeEventListener('mousemove', move);
      document.removeEventListener('mouseup', up);
      state.termFit?.fit();
    };
    document.addEventListener('mousemove', move);
    document.addEventListener('mouseup', up);
  });
}

function bindActions() {
  document.addEventListener('click', (e) => {
    const bottomTab = e.target.closest('[data-bottom]');
    if (bottomTab) {
      setBottomTab(bottomTab.dataset.bottom);
      return;
    }
    const btn = e.target.closest('[data-act]');
    if (!btn) return;
    const act = btn.dataset.act;
    if (act === 'open') pickAndOpen();
    if (act === 'save') saveFile();
    if (act === 'format') formatDocument();
    if (act === 'goto') gotoLine();
    if (act === 'search') {
      setBottomTab('search');
      el('search-query')?.focus();
    }
    if (act === 'run-search') runProjectSearch();
    if (act === 'pack') packProject();
    if (act === 'validate') validateProject();
    if (act === 'close') closeProject();
    if (act === 'update') checkUpdate(false);
    if (act === 'toggle-agent') setAgentOpen(!state.agentOpen);
    if (act === 'clear-recent') ide('recent.clear').then(refreshRecent);
    if (act === 'new') newPlugin(btn.dataset.kind, btn.dataset.default);
    if (act === 'new-file') newFile();
    if (act === 'new-folder') newFolder();
    if (act === 'refresh-tree') {
      state.treeCache.clear();
      loadTree();
    }
    if (act === 'ai-login') aiLogin();
    if (act === 'ai-logout') aiLogout();
    if (act === 'ai-new') void startNewAiChat();
    if (act === 'ai-del-chat') void deleteCurrentAiChat();
    if (act === 'ai-send') aiSend();
    if (act === 'term-new') {
      void newTerminal();
    }
    if (act === 'term-restart') {
      void newTerminal();
    }
    if (act === 'term-clear') {
      state.term?.clear();
    }
  });

  document.addEventListener('keydown', (e) => {
    if (e.ctrlKey && e.key.toLowerCase() === 's') {
      e.preventDefault();
      saveFile();
    }
    if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === 'f') {
      e.preventDefault();
      setBottomTab('search');
      el('search-query')?.focus();
    }
    if (e.ctrlKey && e.key.toLowerCase() === 'g') {
      e.preventDefault();
      gotoLine();
    }
    if (e.shiftKey && e.altKey && e.key.toLowerCase() === 'f') {
      e.preventDefault();
      formatDocument();
    }
    if (e.ctrlKey && e.key.toLowerCase() === 'o') {
      e.preventDefault();
      pickAndOpen();
    }
    if (e.ctrlKey && e.key.toLowerCase() === 'b') {
      e.preventDefault();
      packProject();
    }
    if (e.ctrlKey && e.key.toLowerCase() === 'n') {
      e.preventDefault();
      newFile();
    }
    if (e.ctrlKey && e.key.toLowerCase() === 'l') {
      e.preventDefault();
      setAgentOpen(!state.agentOpen);
    }
    if ((e.ctrlKey || e.metaKey) && e.key === '`') {
      e.preventDefault();
      setBottomTab(state.bottomTab === 'terminal' ? 'log' : 'terminal');
    }
    if (e.ctrlKey && e.key === 'Enter' && document.activeElement === el('ai-ask')) {
      e.preventDefault();
      aiSend();
    }
  });
}

function registerBooxinCompletions() {
  monaco.languages.registerCompletionItemProvider('json', {
    triggerCharacters: ['"', ':'],
    provideCompletionItems(model) {
      const path = state.currentFile || '';
      if (!/booxin-plugin\.jsonc?$/i.test(path.replace(/\\/g, '/'))) {
        return { suggestions: [] };
      }
      const keys = [
        'id', 'name', 'version', 'description', 'author', 'type', 'features',
        'theme', 'pages', 'assets', 'fonts', 'icon', 'greeting', 'control_layout',
        'customTheme', 'navLabels', 'welcomeText', 'launchButtonText',
      ];
      return {
        suggestions: keys.map((k) => ({
          label: k,
          kind: monaco.languages.CompletionItemKind.Property,
          insertText: k,
          documentation: `booxin-plugin 字段：${k}`,
        })),
      };
    },
  });
}

let monacoReady = null;

function ensureMonaco() {
  if (state.editor) return Promise.resolve();
  if (!monacoReady) monacoReady = initMonaco();
  return monacoReady;
}

function initMonaco() {
  return new Promise((resolve, reject) => {
    if (typeof require === 'undefined') {
      reject(new Error('Monaco loader 未加载，请确认已 npm install'));
      return;
    }
    const vsBase = '/vendor/monaco/vs';
    require.config({ paths: { vs: vsBase } });
    window.MonacoEnvironment = {
      getWorkerUrl() {
        return `data:text/javascript;charset=utf-8,${encodeURIComponent(`
          self.MonacoEnvironment = { baseUrl: '/vendor/monaco/' };
          importScripts('/vendor/monaco/vs/base/worker/workerMain.js');
        `)}`;
      },
    };
    require(['vs/editor/editor.main'], () => {
      try {
        monaco.editor.defineTheme('booxin-dark', {
          base: 'vs-dark',
          inherit: true,
          rules: [],
          colors: {
            'editor.background': '#0d1117',
            'editorLineNumber.foreground': '#6e7681',
            'editorCursor.foreground': '#3d8bfd',
            'editor.selectionBackground': '#264f7844',
          },
        });
        state.editor = monaco.editor.create(el('monaco'), {
          value: '',
          language: 'plaintext',
          theme: 'booxin-dark',
          fontSize: 14,
          fontFamily: 'Cascadia Code, JetBrains Mono, Consolas, monospace',
          minimap: { enabled: false },
          automaticLayout: true,
          tabSize: 2,
          insertSpaces: true,
          wordWrap: 'off',
          smoothScrolling: true,
          cursorBlinking: 'smooth',
          renderLineHighlight: 'line',
          bracketPairColorization: { enabled: true },
          suggestOnTriggerCharacters: true,
          quickSuggestions: { other: true, comments: false, strings: true },
          wordBasedSuggestions: 'currentDocument',
          snippetSuggestions: 'inline',
          formatOnPaste: true,
          autoClosingBrackets: 'languageDefined',
          autoClosingQuotes: 'languageDefined',
          matchBrackets: 'always',
          renderWhitespace: 'none',
          links: false,
        });
        state.editor.onDidChangeModelContent(() => {
          if (state.loading || !state.currentFile) return;
          state.dirty = true;
          updateTitle();
          renderTabs();
        });
        registerBooxinCompletions();
        resolve();
      } catch (e) {
        reject(e);
      }
    }, reject);
  });
}

async function boot() {
  if (!cfg().token || !cfg().port) {
    document.body.innerHTML = '<p style="padding:24px">Studio 配置缺失，请从 npm run gui 启动。</p>';
    return;
  }
  bindActions();
  bindSplits();
  el('ai-model')?.addEventListener('change', (e) => {
    setSelectedModel(e.target.value);
  });
  el('ai-chat-list')?.addEventListener('change', (e) => {
    const id = e.target.value;
    if (id) void loadAiChat(id);
  });
  el('search-query')?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      runProjectSearch();
    }
  });
  setProjectUi(false);
  setAgentOpen(true);
  updateAiAuthUi();
  setStatus('就绪');
  log('Booxin Studio 就绪。');
  // Don't block welcome UI on Monaco — load in background
  ensureMonaco()
    .then(() => setStatus(`就绪 · v${cfg().version || ''}`))
    .catch((e) => {
      console.error(e);
      setStatus(`编辑器加载失败: ${e.message || e}`);
      log(`编辑器加载失败: ${e.message || e}`);
    });
  refreshRecent().catch(() => {});
  refreshAiSession().catch(() => {});
  checkUpdate(true);
}

boot().catch((e) => {
  console.error(e);
  void alertDialog(e.message || String(e), '启动失败');
});
