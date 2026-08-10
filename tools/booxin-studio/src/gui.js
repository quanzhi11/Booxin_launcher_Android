#!/usr/bin/env node
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { COMMANDS } from './commands.js';

function loadFormPs1() {
  if (typeof globalThis.GUI_FORM_PS1 === 'string' && globalThis.GUI_FORM_PS1) {
    return globalThis.GUI_FORM_PS1;
  }
  const candidates = [
    path.join(process.cwd(), 'src', 'gui-form.ps1'),
    path.join(process.cwd(), 'gui-form.ps1'),
  ];
  for (const p of candidates) {
    if (fs.existsSync(p)) return fs.readFileSync(p, 'utf8');
  }
  throw new Error('找不到 gui-form.ps1（开发时请在 tools/booxin-studio 目录运行 npm run gui）');
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

async function main() {
  if (process.platform !== 'win32') {
    console.error('窗口版仅支持 Windows。请使用命令行版 BooxinStudio-CLI.exe');
    process.exitCode = 1;
    return;
  }

  const token = crypto.randomBytes(16).toString('hex');
  let shuttingDown = false;

  const server = http.createServer(async (req, res) => {
    try {
      if (req.method === 'POST' && req.url === '/run') {
        const body = await readJson(req);
        if (body.token !== token) {
          sendJson(res, 403, { ok: false, error: 'forbidden' });
          return;
        }
        const cmd = String(body.cmd || '').trim();
        const args = Array.isArray(body.args) ? body.args.map(String) : [];
        const def = COMMANDS[cmd];
        if (!def) {
          sendJson(res, 400, { ok: false, error: `未知命令: ${cmd}` });
          return;
        }
        try {
          const output = def.run(args) ?? '';
          sendJson(res, 200, { ok: true, output: String(output) });
        } catch (e) {
          sendJson(res, 200, { ok: false, error: e.message || String(e) });
        }
        return;
      }
      if (req.method === 'POST' && req.url === '/shutdown') {
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
      sendJson(res, 404, { ok: false, error: 'not found' });
    } catch (e) {
      sendJson(res, 500, { ok: false, error: e.message || String(e) });
    }
  });

  await new Promise((resolve, reject) => {
    server.listen(0, '127.0.0.1', resolve);
    server.on('error', reject);
  });
  const port = server.address().port;

  const tmp = path.join(os.tmpdir(), `booxin-studio-gui-${process.pid}.ps1`);
  // Windows PowerShell 5.x 读无 BOM 的 UTF-8 会乱码并导致脚本解析失败
  fs.writeFileSync(tmp, `\uFEFF${loadFormPs1()}`, 'utf8');

  const child = spawn(
    'powershell.exe',
    [
      '-NoProfile',
      '-ExecutionPolicy',
      'Bypass',
      '-File',
      tmp,
      '-Port',
      String(port),
      '-Token',
      token,
    ],
    { stdio: 'inherit', windowsHide: false },
  );

  const cleanup = () => {
    try {
      fs.unlinkSync(tmp);
    } catch {
      /* ignore */
    }
  };

  child.on('exit', (code) => {
    cleanup();
    if (!shuttingDown) {
      server.close();
      process.exit(code ?? 0);
    }
  });

  process.on('exit', cleanup);
}

main().catch((e) => {
  console.error(e.message || e);
  process.exitCode = 1;
});
