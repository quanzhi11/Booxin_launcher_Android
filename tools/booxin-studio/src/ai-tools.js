/**
 * Studio Agent tools — project-scoped file/search/edit/shell ops.
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {
  createTextFile,
  deletePath,
  listDir,
  readTextFile,
  searchInProject,
  writeTextFile,
} from './ide-state.js';

export const AGENT_TOOLS = [
  {
    name: 'list_dir',
    label: '列出目录',
    desc: '列出项目内某目录的文件与文件夹',
    args: { path: '相对路径，默认 .' },
  },
  {
    name: 'read_file',
    label: '查看文件',
    desc: '读取文本文件内容',
    args: { path: '相对路径', offset: '可选起始行(1-based)', limit: '可选行数' },
  },
  {
    name: 'search_files',
    label: '查找内容',
    desc: '在项目文本中搜索关键字',
    args: { query: '搜索词' },
  },
  {
    name: 'glob_files',
    label: '查找文件',
    desc: '按文件名关键字列出匹配路径',
    args: { query: '文件名片段，如 .html 或 about' },
  },
  {
    name: 'create_file',
    label: '创建文件',
    desc: '创建新文件（已存在则失败）',
    args: { path: '相对路径', content: '文件内容' },
  },
  {
    name: 'write_file',
    label: '写入文件',
    desc: '创建或覆盖整个文件',
    args: { path: '相对路径', content: '完整内容' },
  },
  {
    name: 'str_replace',
    label: '替换文本',
    desc: '在文件中精确替换一段文本',
    args: { path: '相对路径', old_string: '旧文本', new_string: '新文本', replace_all: '可选 true' },
  },
  {
    name: 'insert_lines',
    label: '插入文本',
    desc: '在指定行后插入内容（line=0 表示文件开头）',
    args: { path: '相对路径', line: '行号', content: '插入内容' },
  },
  {
    name: 'delete_lines',
    label: '删除行',
    desc: '删除从 start_line 到 end_line（含）',
    args: { path: '相对路径', start_line: '起始行', end_line: '结束行' },
  },
  {
    name: 'delete_file',
    label: '删除文件',
    desc: '删除文件；空文件夹也可删',
    args: { path: '相对路径' },
  },
  {
    name: 'run_command',
    label: '运行命令',
    desc: '在项目目录执行 PowerShell 命令（超时 25s）',
    args: { command: '命令字符串' },
  },
];

export function toolsPromptBlock() {
  const lines = AGENT_TOOLS.map(
    (t) => `- ${t.name}: ${t.desc} | 参数: ${JSON.stringify(t.args)}`,
  );
  return [
    '你可以使用工具修改/查看项目。需要工具时输出 TOOL 块（可多个）。',
    '',
    '【小参数工具】用单行 JSON：',
    '<<<TOOL',
    '{"name":"read_file","args":{"path":"booxin-plugin.json"}}',
    'TOOL>>>',
    '',
    '【写文件 / 创建文件】禁止把长正文塞进 JSON（会截断）。必须用路径头 + --- + 正文：',
    '<<<TOOL',
    'name: write_file',
    'path: pages/about.html',
    '---',
    '<!DOCTYPE html>',
    '...页面内容...',
    'TOOL>>>',
    '',
    '规则：',
    '1. 路径相对项目根，用正斜杠；一次只写一个文件。',
    '2. 先 read_file / list_dir / search_files，再改。',
    '3. 小改动优先 str_replace；新建/整文件用 write_file（heredoc 格式）。',
    '4. 清单 JSON 可先 write 短文件，再 str_replace 补字段。',
    '5. 工具未完成前不要做总结；全部完成后用简体中文总结（勿再输出 TOOL）。',
    '6. 不要伪造工具结果；若上轮 TOOL 被截断，改用 heredoc 重写，不要续写半截 JSON。',
    '可用工具：',
    ...lines,
  ].join('\n');
}

/** True when <<<TOOL opened but TOOL>>> missing (model truncated). */
export function hasIncompleteToolBlock(text) {
  const s = String(text || '');
  const opens = (s.match(/<<<TOOL\b/gi) || []).length;
  const closes = (s.match(/TOOL>>>/gi) || []).length;
  return opens > closes;
}

export function parseToolCalls(text) {
  const calls = [];
  const re = /<<<TOOL\s*([\s\S]*?)TOOL>>>/gi;
  let m;
  while ((m = re.exec(String(text || '')))) {
    const raw = m[1].trim();
    const parsed = parseOneToolBody(raw);
    if (parsed) calls.push(parsed);
  }
  if (!calls.length) {
    const re2 = /```tool\s*([\s\S]*?)```/gi;
    while ((m = re2.exec(String(text || '')))) {
      const parsed = parseOneToolBody(m[1].trim());
      if (parsed) calls.push(parsed);
    }
  }
  return calls;
}

function parseOneToolBody(raw) {
  if (!raw) return null;
  // JSON form
  if (raw.startsWith('{')) {
    try {
      const obj = JSON.parse(raw);
      const name = String(obj.name || obj.tool || '').trim();
      if (!name) return null;
      return { name, args: obj.args && typeof obj.args === 'object' ? obj.args : {} };
    } catch {
      // truncated / invalid JSON — ignore here (caller handles incomplete)
      return null;
    }
  }
  // Heredoc form:
  // name: write_file
  // path: a/b.html
  // ---
  // body...
  const sep = raw.search(/\n---\s*\n/);
  let header = raw;
  let body = '';
  if (sep >= 0) {
    header = raw.slice(0, sep);
    body = raw.slice(sep).replace(/^\n---\s*\n/, '');
  }
  const meta = {};
  for (const line of header.split(/\r?\n/)) {
    const idx = line.indexOf(':');
    if (idx <= 0) continue;
    const k = line.slice(0, idx).trim().toLowerCase();
    const v = line.slice(idx + 1).trim();
    if (k) meta[k] = v;
  }
  const name = String(meta.name || meta.tool || '').trim();
  if (!name) return null;
  const args = { ...meta };
  delete args.name;
  delete args.tool;
  if (body !== '' || name === 'write_file' || name === 'create_file' || name === 'insert_lines') {
    args.content = body;
  }
  if (meta.replace_all != null) {
    args.replace_all = /^(1|true|yes)$/i.test(String(meta.replace_all));
  }
  return { name, args };
}

export function stripToolCalls(text) {
  return String(text || '')
    .replace(/<<<TOOL[\s\S]*?TOOL>>>/gi, '')
    .replace(/```tool[\s\S]*?```/gi, '')
    .replace(/<<<TOOL[\s\S]*$/gi, '') // drop truncated tail
    .trim();
}

function assertProject(projectRoot) {
  const root = path.resolve(String(projectRoot || ''));
  if (!root || !fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
    throw new Error('请先打开项目后再使用 Agent 工具');
  }
  return root;
}

function resolveInProject(projectRoot, relPath) {
  const root = assertProject(projectRoot);
  const rel = String(relPath || '.').replace(/\\/g, '/').replace(/^\/+/, '');
  const abs = path.resolve(root, rel === '.' ? '' : rel);
  const rootWithSep = root.endsWith(path.sep) ? root : root + path.sep;
  if (abs !== root && !abs.startsWith(rootWithSep)) {
    throw new Error(`路径越界: ${relPath}`);
  }
  return { root, abs, rel: path.relative(root, abs).replace(/\\/g, '/') || '.' };
}

function toolLabel(name) {
  return AGENT_TOOLS.find((t) => t.name === name)?.label || name;
}

export function executeAgentTool(projectRoot, name, args = {}) {
  const started = Date.now();
  try {
    const result = runTool(projectRoot, name, args || {});
    return {
      ok: true,
      name,
      label: toolLabel(name),
      args,
      summary: result.summary,
      detail: result.detail,
      ms: Date.now() - started,
      mutate: !!result.mutate,
      path: result.path || '',
    };
  } catch (e) {
    return {
      ok: false,
      name,
      label: toolLabel(name),
      args,
      summary: e.message || String(e),
      detail: e.message || String(e),
      ms: Date.now() - started,
      mutate: false,
      path: '',
    };
  }
}

function runTool(projectRoot, name, args) {
  switch (name) {
    case 'list_dir': {
      const { abs, rel } = resolveInProject(projectRoot, args.path || '.');
      const entries = listDir(abs);
      const lines = entries.map((e) => `${e.kind === 'dir' ? '📁' : '📄'} ${e.name}`);
      return {
        summary: `${rel} · ${entries.length} 项`,
        detail: lines.join('\n') || '(空目录)',
      };
    }
    case 'read_file': {
      const { abs, rel } = resolveInProject(projectRoot, args.path);
      const content = readTextFile(abs);
      const lines = content.split(/\r?\n/);
      const offset = Math.max(1, parseInt(args.offset, 10) || 1);
      const limit = Math.min(400, Math.max(1, parseInt(args.limit, 10) || 200));
      const slice = lines.slice(offset - 1, offset - 1 + limit);
      const numbered = slice.map((l, i) => `${offset + i}|${l}`).join('\n');
      return {
        summary: `读取 ${rel}（${lines.length} 行）`,
        detail: numbered.slice(0, 12000),
        path: rel,
      };
    }
    case 'search_files': {
      const root = assertProject(projectRoot);
      const q = String(args.query || '').trim();
      if (!q) throw new Error('缺少 query');
      const r = searchInProject(root, q, { maxHits: 40 });
      const detail = (r.hits || [])
        .map((h) => `${path.relative(root, h.path).replace(/\\/g, '/')}:${h.line}: ${h.text}`)
        .join('\n');
      return {
        summary: `搜索「${q}」· ${r.hits?.length || 0} 处`,
        detail: detail || '无结果',
      };
    }
    case 'glob_files': {
      const root = assertProject(projectRoot);
      const q = String(args.query || '').trim().toLowerCase();
      if (!q) throw new Error('缺少 query');
      const hits = [];
      const walk = (dir) => {
        for (const ent of listDir(dir)) {
          if (ent.kind === 'dir') walk(ent.path);
          else if (ent.name.toLowerCase().includes(q) || ent.path.toLowerCase().includes(q)) {
            hits.push(path.relative(root, ent.path).replace(/\\/g, '/'));
          }
          if (hits.length >= 60) return;
        }
      };
      walk(root);
      return {
        summary: `文件名匹配「${q}」· ${hits.length} 个`,
        detail: hits.join('\n') || '无结果',
      };
    }
    case 'create_file': {
      const { abs, rel } = resolveInProject(projectRoot, args.path);
      createTextFile(abs, args.content ?? '');
      return { summary: `已创建 ${rel}`, detail: rel, mutate: true, path: rel };
    }
    case 'write_file': {
      const { abs, rel } = resolveInProject(projectRoot, args.path);
      writeTextFile(abs, args.content ?? '');
      return { summary: `已写入 ${rel}`, detail: rel, mutate: true, path: rel };
    }
    case 'str_replace': {
      const { abs, rel } = resolveInProject(projectRoot, args.path);
      const oldS = String(args.old_string ?? '');
      const newS = String(args.new_string ?? '');
      if (!oldS) throw new Error('缺少 old_string');
      let text = readTextFile(abs);
      if (!text.includes(oldS)) throw new Error('未找到要替换的文本');
      const all = args.replace_all === true || args.replace_all === 'true';
      if (all) text = text.split(oldS).join(newS);
      else text = text.replace(oldS, newS);
      writeTextFile(abs, text);
      return { summary: `已替换 ${rel}`, detail: rel, mutate: true, path: rel };
    }
    case 'insert_lines': {
      const { abs, rel } = resolveInProject(projectRoot, args.path);
      const line = parseInt(args.line, 10);
      if (!Number.isFinite(line) || line < 0) throw new Error('line 无效');
      const insert = String(args.content ?? '');
      const lines = readTextFile(abs).split(/\r?\n/);
      const at = Math.min(lines.length, Math.max(0, line));
      const chunk = insert.replace(/\r\n/g, '\n').split('\n');
      lines.splice(at, 0, ...chunk);
      writeTextFile(abs, lines.join('\n'));
      return { summary: `已在 ${rel}:${at} 后插入`, detail: rel, mutate: true, path: rel };
    }
    case 'delete_lines': {
      const { abs, rel } = resolveInProject(projectRoot, args.path);
      const start = parseInt(args.start_line, 10);
      const end = parseInt(args.end_line, 10);
      if (!start || !end || start < 1 || end < start) throw new Error('行号无效');
      const lines = readTextFile(abs).split(/\r?\n/);
      lines.splice(start - 1, end - start + 1);
      writeTextFile(abs, lines.join('\n'));
      return { summary: `已删除 ${rel} L${start}-${end}`, detail: rel, mutate: true, path: rel };
    }
    case 'delete_file': {
      const { abs, rel } = resolveInProject(projectRoot, args.path);
      deletePath(abs);
      return { summary: `已删除 ${rel}`, detail: rel, mutate: true, path: rel };
    }
    case 'run_command': {
      assertProject(projectRoot);
      const command = String(args.command || '').trim();
      if (!command) throw new Error('缺少 command');
      if (/[;&|]{2}|`|\$\(|irm\s|Invoke-WebRequest|curl\s+http/i.test(command) && /https?:\/\//i.test(command)) {
        // still allow; just note — keep soft restriction on destructive
      }
      if (/\b(format|rm\s+-rf|Remove-Item\s+-Recurse\s+C:\\)/i.test(command)) {
        throw new Error('拒绝执行危险命令');
      }
      const r = spawnSync(
        'powershell.exe',
        ['-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
        {
          cwd: path.resolve(projectRoot),
          encoding: 'utf8',
          timeout: 25_000,
          windowsHide: true,
          maxBuffer: 1024 * 1024,
        },
      );
      const out = `${r.stdout || ''}${r.stderr || ''}`.trim() || `(exit ${r.status})`;
      if (r.error) throw new Error(r.error.message);
      return {
        summary: r.status === 0 ? '命令完成' : `命令退出码 ${r.status}`,
        detail: out.slice(0, 8000),
      };
    }
    default:
      throw new Error(`未知工具: ${name}`);
  }
}
