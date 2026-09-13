/**
 * Embedded terminal: WebSocket + PowerShell (pipe mode, Windows).
 */
import { spawn } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { WebSocketServer } from 'ws';

/**
 * @param {import('node:http').Server} server
 * @param {{ token: string, getCwd: () => string | null }} opts
 */
export function attachTerminalServer(server, { token, getCwd }) {
  const wss = new WebSocketServer({ noServer: true });

  server.on('upgrade', (req, socket, head) => {
    try {
      const url = new URL(req.url || '/', 'http://127.0.0.1');
      if (url.pathname !== '/terminal') {
        socket.destroy();
        return;
      }
      if (url.searchParams.get('token') !== token) {
        socket.write('HTTP/1.1 403 Forbidden\r\n\r\n');
        socket.destroy();
        return;
      }
      wss.handleUpgrade(req, socket, head, (ws) => {
        wss.emit('connection', ws, req);
      });
    } catch {
      socket.destroy();
    }
  });

  wss.on('connection', (ws) => {
    let shell = null;
    let currentCwd = safeCwd(getCwd());

    const startShell = (cwd) => {
      killShell(shell);
      currentCwd = safeCwd(cwd || getCwd());
      shell = spawn(
        'powershell.exe',
        ['-NoLogo', '-ExecutionPolicy', 'Bypass'],
        {
          cwd: currentCwd,
          env: {
            ...process.env,
            TERM: 'xterm-256color',
          },
          windowsHide: true,
          stdio: ['pipe', 'pipe', 'pipe'],
        },
      );

      const push = (buf) => {
        if (ws.readyState === ws.OPEN) {
          ws.send(buf.toString('utf8'));
        }
      };

      shell.stdout.on('data', push);
      shell.stderr.on('data', push);
      shell.on('exit', (code) => {
        if (ws.readyState === ws.OPEN) {
          ws.send(`\r\n[进程已退出 code=${code ?? '?'} — 输入任意键重新启动]\r\n`);
        }
        shell = null;
      });
      shell.on('error', (err) => {
        if (ws.readyState === ws.OPEN) {
          ws.send(`\r\n[终端启动失败: ${err.message}]\r\n`);
        }
      });

      // Make PowerShell output more terminal-friendly
      try {
        shell.stdin.write(
          "$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new(); " +
            "chcp 65001 > $null; " +
            "Write-Host ''\r\n" +
            "Write-Host 'Booxin Studio 终端 = Windows PowerShell（管道模式）' -ForegroundColor Cyan\r\n" +
            "Write-Host '可执行普通命令；交互式 TUI / 方向键程序可能异常。' -ForegroundColor DarkGray\r\n" +
            `Write-Host \"工作目录: ${currentCwd.replace(/"/g, '')}\" -ForegroundColor DarkCyan\r\n` +
            "Write-Host ''\r\n",
        );
      } catch {
        /* ignore */
      }
    };

    startShell(currentCwd);

    ws.on('message', (data) => {
      const text = Buffer.isBuffer(data) ? data.toString('utf8') : String(data);
      // Control messages are JSON prefixed with \x1e
      if (text.startsWith('\x1e')) {
        try {
          const msg = JSON.parse(text.slice(1));
      if (msg.type === 'cwd') {
            startShell(msg.path || getCwd());
            return;
          }
          if (msg.type === 'restart') {
            startShell(currentCwd || getCwd());
            return;
          }
          if (msg.type === 'resize') {
            // pipe mode: ignore cols/rows (no PTY)
            return;
          }
        } catch {
          /* fall through as input */
        }
      }

      if (!shell || !shell.stdin.writable) {
        startShell(currentCwd);
      }
      try {
        shell?.stdin.write(text);
      } catch {
        /* ignore */
      }
    });

    ws.on('close', () => killShell(shell));
    ws.on('error', () => killShell(shell));
  });

  return wss;
}

function safeCwd(dir) {
  const fallback = os.homedir();
  if (!dir) return fallback;
  try {
    const abs = path.resolve(dir);
    return abs;
  } catch {
    return fallback;
  }
}

function killShell(shell) {
  if (!shell || shell.killed) return;
  try {
    shell.stdin?.end();
  } catch {
    /* ignore */
  }
  try {
    shell.kill();
  } catch {
    /* ignore */
  }
}
