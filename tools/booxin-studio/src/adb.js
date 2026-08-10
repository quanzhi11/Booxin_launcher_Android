import { spawnSync } from 'node:child_process';

export const DEFAULT_PACKAGE = 'com.booxin.launcher';

export function runAdb(args, { inherit = false } = {}) {
  const r = spawnSync('adb', args, {
    encoding: 'utf8',
    shell: false,
    stdio: inherit ? 'inherit' : 'pipe',
  });
  if (r.error) {
    const err = new Error(
      r.error.code === 'ENOENT'
        ? '未找到 adb。请安装 Android platform-tools 并加入 PATH。'
        : r.error.message,
    );
    err.cause = r.error;
    throw err;
  }
  return {
    status: r.status ?? 1,
    stdout: (r.stdout || '').trim(),
    stderr: (r.stderr || '').trim(),
  };
}

export function ensureDevice() {
  const { status, stdout, stderr } = runAdb(['devices']);
  if (status !== 0) throw new Error(stderr || 'adb devices 失败');
  const lines = stdout
    .split(/\r?\n/)
    .slice(1)
    .map((l) => l.trim())
    .filter(Boolean);
  const ready = lines.filter((l) => /\tdevice$/.test(l));
  if (!ready.length) {
    throw new Error('未检测到已授权设备。请连接手机并允许 USB 调试。');
  }
  return ready.map((l) => l.split(/\s+/)[0]);
}

export function shell(cmd, { packageName = DEFAULT_PACKAGE } = {}) {
  // Prefer run-as for app-private files.
  const wrapped = `run-as ${packageName} sh -c ${shellQuote(cmd)}`;
  const r = runAdb(['shell', wrapped]);
  if (r.status !== 0) {
    // Fallback without run-as (rooted / debuggable variants).
    const direct = runAdb(['shell', cmd]);
    if (direct.status !== 0) {
      throw new Error(
        r.stderr ||
          direct.stderr ||
          `adb shell 失败（run-as 与直连都失败）。命令：${cmd}`,
      );
    }
    return direct.stdout;
  }
  return r.stdout;
}

function shellQuote(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}
