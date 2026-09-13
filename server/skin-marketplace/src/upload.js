import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import multer from 'multer';
import { PNG } from 'pngjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const MAX_UPLOAD_BYTES = 2 * 1024 * 1024; // 2MB skin PNG

export function uploadsDir() {
  const dir = path.resolve(__dirname, '../data/uploads');
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  return dir;
}

const storage = multer.diskStorage({
  destination: (_req, _file, cb) => cb(null, uploadsDir()),
  filename: (_req, file, cb) => {
    const safe = String(file.originalname || 'skin.png')
      .replace(/[\\/:*?"<>|]/g, '_')
      .slice(0, 80);
    const base = safe.toLowerCase().endsWith('.png') ? safe : `${safe}.png`;
    cb(null, `${Date.now()}-${randomUUID().slice(0, 8)}-${base}`);
  },
});

export const uploadMiddleware = multer({
  storage,
  limits: { fileSize: MAX_UPLOAD_BYTES, files: 1 },
  fileFilter: (_req, file, cb) => {
    const ok =
      file.mimetype === 'image/png' ||
      String(file.originalname || '').toLowerCase().endsWith('.png');
    cb(ok ? null : new Error('仅支持 PNG 皮肤文件'), ok);
  },
}).single('file');

export function publicOrigin() {
  return String(process.env.PUBLIC_ORIGIN || 'https://boonix.art/skin-api').replace(/\/$/, '');
}

export function publicUploadUrl(filename) {
  return `${publicOrigin()}/uploads/${encodeURIComponent(filename)}`;
}

/** Validate Minecraft skin PNG: width multiple of 64, height == width or width/2. */
export function validateSkinPng(filePath) {
  return new Promise((resolve) => {
    fs.createReadStream(filePath)
      .pipe(new PNG())
      .on('parsed', function onParsed() {
        const w = this.width;
        const h = this.height;
        const ok = w >= 64 && w % 64 === 0 && (h === w || h * 2 === w);
        if (!ok) {
          resolve({
            ok: false,
            message: `无效皮肤尺寸 ${w}x${h}（需 64x64 / 64x32 或其整数倍）`,
          });
          return;
        }
        resolve({ ok: true, width: w, height: h });
      })
      .on('error', () => resolve({ ok: false, message: 'PNG 解析失败' }));
  });
}
