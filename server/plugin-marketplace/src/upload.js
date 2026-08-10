import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import multer from 'multer';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const MAX_UPLOAD_BYTES = 250 * 1024 * 1024; // 250MB
export const QQ_CONTACT = {
  qq: '1018364788',
  name: 'zrr',
  message: '文件超过 250MB，请添加 QQ 私聊单独提交。',
  qrPath: '/contact/qq.png',
};

export function uploadsDir() {
  const dir = path.resolve(__dirname, '../data/uploads');
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  return dir;
}

const storage = multer.diskStorage({
  destination: (_req, _file, cb) => cb(null, uploadsDir()),
  filename: (_req, file, cb) => {
    const safe = String(file.originalname || 'plugin.bin')
      .replace(/[\\/:*?"<>|]/g, '_')
      .slice(0, 80);
    cb(null, `${Date.now()}-${randomUUID().slice(0, 8)}-${safe}`);
  },
});

export const uploadMiddleware = multer({
  storage,
  limits: { fileSize: MAX_UPLOAD_BYTES, files: 1 },
}).single('file');

export function publicOrigin() {
  return String(process.env.PUBLIC_ORIGIN || 'https://boonix.art/plugin-api').replace(/\/$/, '');
}

export function publicUploadUrl(filename) {
  return `${publicOrigin()}/uploads/${encodeURIComponent(filename)}`;
}

export function contactInfo() {
  return {
    ...QQ_CONTACT,
    maxUploadBytes: MAX_UPLOAD_BYTES,
    maxUploadMb: 250,
    qrUrl: `${publicOrigin()}${QQ_CONTACT.qrPath}`,
  };
}
