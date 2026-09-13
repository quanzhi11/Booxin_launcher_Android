import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { normalizePluginType } from './types.js';
import { normalizePlatforms } from './platforms.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const dataDir = path.resolve(__dirname, '../data');
const dbPath = path.join(dataDir, 'marketplace.json');

function emptyDb() {
  return {
    applications: [],
    plugins: [],
    ratings: [],
    comments: [],
    commentLikes: [],
    commentReports: [],
  };
}

function ensureDb() {
  if (!fs.existsSync(dataDir)) fs.mkdirSync(dataDir, { recursive: true });
  if (!fs.existsSync(dbPath)) {
    fs.writeFileSync(dbPath, JSON.stringify(emptyDb(), null, 2), 'utf8');
  }
}

function readDb() {
  ensureDb();
  try {
    return { ...emptyDb(), ...JSON.parse(fs.readFileSync(dbPath, 'utf8')) };
  } catch {
    return emptyDb();
  }
}

function writeDb(db) {
  ensureDb();
  const tmp = `${dbPath}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(db, null, 2), 'utf8');
  fs.renameSync(tmp, dbPath);
}

export function listPublishedPlugins() {
  return readDb()
    .plugins.filter((p) => p.enabled !== false)
    .sort((a, b) => String(b.publishedAt || '').localeCompare(String(a.publishedAt || '')));
}

/** Dotted version compare; >0 if a newer than b. */
export function compareVersions(a, b) {
  const parts = (v) =>
    String(v || '')
      .trim()
      .replace(/^[vV]/, '')
      .split(/[.\-_]/)
      .map((x) => {
        const n = parseInt(String(x).replace(/\D/g, ''), 10);
        return Number.isFinite(n) ? n : 0;
      });
  const pa = parts(a);
  const pb = parts(b);
  const n = Math.max(pa.length, pb.length);
  for (let i = 0; i < n; i++) {
    const x = pa[i] || 0;
    const y = pb[i] || 0;
    if (x !== y) return x - y;
  }
  return 0;
}

export function listPluginsByDeveloper(userId) {
  const uid = String(userId || '').trim();
  if (!uid) return [];
  return readDb()
    .plugins.filter((p) => String(p.developerUserId || '') === uid)
    .sort((a, b) => String(b.updatedAt || b.publishedAt || '').localeCompare(String(a.updatedAt || a.publishedAt || '')));
}

/**
 * Owner release: bump version + downloadUrl (+ optional meta).
 * Requires version strictly newer than current.
 */
export function releasePluginVersion(id, developerUserId, patch) {
  const cur = getPlugin(id);
  if (!cur || cur.enabled === false) throw new Error('插件不存在或未上架。');
  if (String(cur.developerUserId || '') !== String(developerUserId || '')) {
    throw new Error('只能更新自己上架的插件。');
  }
  const nextVersion = String(patch?.version || '').trim() || '';
  if (!nextVersion) throw new Error('请填写新版本号。');
  const curVersion = String(cur.version || '1.0.0').trim() || '1.0.0';
  if (compareVersions(nextVersion, curVersion) <= 0) {
    throw new Error(`新版本号须高于当前版本（当前 v${curVersion}）。`);
  }
  const downloadUrl = String(patch?.downloadUrl || '').trim();
  if (!downloadUrl) throw new Error('请上传插件文件或填写下载直链。');

  const payload = {
    version: nextVersion,
    downloadUrl,
  };
  if (patch?.description != null) payload.description = String(patch.description);
  if (patch?.name != null && String(patch.name).trim()) payload.name = String(patch.name).trim();
  if (patch?.type != null) payload.type = patch.type;
  if (patch?.platforms != null) payload.platforms = patch.platforms;
  return updatePlugin(id, payload);
}

export function listAllPlugins() {
  return readDb().plugins.sort((a, b) =>
    String(b.publishedAt || '').localeCompare(String(a.publishedAt || '')),
  );
}

export function getPlugin(id) {
  return readDb().plugins.find((p) => p.id === id) || null;
}

export function createPlugin(input) {
  const db = readDb();
  const now = new Date().toISOString();
  const plugin = {
    id: randomUUID(),
    name: String(input.name || '').trim(),
    description: String(input.description || '').trim(),
    downloadUrl: String(input.downloadUrl || '').trim(),
    type: normalizePluginType(input.type, 'other'),
    platforms: normalizePlatforms(input.platforms),
    version: String(input.version || '1.0.0').trim() || '1.0.0',
    developerUserId: String(input.developerUserId || '').trim(),
    developerUsername: String(input.developerUsername || '').trim(),
    applicationId: input.applicationId || null,
    publishedAt: now,
    publishedBy: String(input.publishedBy || '').trim(),
    enabled: input.enabled !== false,
    updatedAt: now,
  };
  if (!plugin.name) throw new Error('插件名称不能为空');
  if (!plugin.downloadUrl) throw new Error('下载链接不能为空');
  if (!plugin.developerUserId) throw new Error('必须选择开发者账号');
  db.plugins.push(plugin);
  writeDb(db);
  return plugin;
}

export function updatePlugin(id, patch) {
  const db = readDb();
  const idx = db.plugins.findIndex((p) => p.id === id);
  if (idx < 0) return null;
  const cur = db.plugins[idx];
  const next = {
    ...cur,
    ...patch,
    id: cur.id,
    updatedAt: new Date().toISOString(),
  };
  if (patch.name != null) next.name = String(patch.name).trim();
  if (patch.description != null) next.description = String(patch.description).trim();
  if (patch.downloadUrl != null) next.downloadUrl = String(patch.downloadUrl).trim();
  if (patch.type != null) next.type = normalizePluginType(patch.type, cur.type || 'other');
  if (patch.platforms != null) next.platforms = normalizePlatforms(patch.platforms);
  if (patch.version != null) {
    const v = String(patch.version).trim();
    next.version = v || cur.version || '1.0.0';
  }
  if (patch.developerUserId != null) next.developerUserId = String(patch.developerUserId).trim();
  if (patch.developerUsername != null) {
    next.developerUsername = String(patch.developerUsername).trim();
  }
  if (patch.enabled != null) next.enabled = !!patch.enabled;
  db.plugins[idx] = next;
  writeDb(db);
  return next;
}

export function deletePlugin(id) {
  const db = readDb();
  const before = db.plugins.length;
  db.plugins = db.plugins.filter((p) => p.id !== id);
  if (db.plugins.length === before) return false;
  db.ratings = (db.ratings || []).filter((r) => r.pluginId !== id);
  const removedCommentIds = new Set(
    (db.comments || []).filter((c) => c.pluginId === id).map((c) => c.id),
  );
  db.comments = (db.comments || []).filter((c) => c.pluginId !== id);
  db.commentLikes = (db.commentLikes || []).filter(
    (l) => l.pluginId !== id && !removedCommentIds.has(l.commentId),
  );
  db.commentReports = (db.commentReports || []).filter((r) => r.pluginId !== id);
  writeDb(db);
  return true;
}

const MAX_COMMENT_LEN = 500;

export function getPluginRatingStats(pluginId) {
  const ratings = (readDb().ratings || []).filter((r) => r.pluginId === pluginId);
  if (!ratings.length) {
    return { ratingAvg: 0, ratingCount: 0 };
  }
  const sum = ratings.reduce((acc, r) => acc + Number(r.score || 0), 0);
  const avg = Math.round((sum / ratings.length) * 10) / 10;
  return { ratingAvg: avg, ratingCount: ratings.length };
}

export function getUserRating(pluginId, userId) {
  const row = (readDb().ratings || []).find(
    (r) => r.pluginId === pluginId && r.userId === userId,
  );
  return row ? { score: Number(row.score), updatedAt: row.updatedAt } : null;
}

export function upsertRating({ pluginId, userId, username, score }) {
  const plugin = getPlugin(pluginId);
  if (!plugin || plugin.enabled === false) throw new Error('插件不存在或未上架。');
  const n = Number(score);
  if (!Number.isInteger(n) || n < 1 || n > 5) {
    throw new Error('评分须为 1–5 的整数。');
  }
  const db = readDb();
  if (!Array.isArray(db.ratings)) db.ratings = [];
  const now = new Date().toISOString();
  const idx = db.ratings.findIndex((r) => r.pluginId === pluginId && r.userId === userId);
  const row = {
    id: idx >= 0 ? db.ratings[idx].id : randomUUID(),
    pluginId,
    userId: String(userId),
    username: String(username || '').trim(),
    score: n,
    createdAt: idx >= 0 ? db.ratings[idx].createdAt : now,
    updatedAt: now,
  };
  if (idx >= 0) db.ratings[idx] = row;
  else db.ratings.push(row);
  writeDb(db);
  return { rating: row, ...getPluginRatingStats(pluginId) };
}

export function listComments(pluginId, { limit = 50, offset = 0, viewerUserId = '' } = {}) {
  const db = readDb();
  const likes = db.commentLikes || [];
  const all = (db.comments || [])
    .filter((c) => c.pluginId === pluginId && !c.hidden)
    .sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || '')));
  const start = Math.max(0, Number(offset) || 0);
  const take = Math.min(100, Math.max(1, Number(limit) || 50));
  const items = all.slice(start, start + take).map((c) => {
    const likeCount = likes.filter((l) => l.commentId === c.id).length;
    const likedByMe = viewerUserId
      ? likes.some((l) => l.commentId === c.id && l.userId === viewerUserId)
      : false;
    return {
      id: c.id,
      pluginId: c.pluginId,
      userId: c.userId,
      username: c.username,
      body: c.body,
      createdAt: c.createdAt,
      likeCount,
      likedByMe,
    };
  });
  return {
    items,
    total: all.length,
  };
}

export function addComment({ pluginId, userId, username, body }) {
  const plugin = getPlugin(pluginId);
  if (!plugin || plugin.enabled === false) throw new Error('插件不存在或未上架。');
  const text = String(body || '').trim();
  if (!text) throw new Error('评论不能为空。');
  if (text.length > MAX_COMMENT_LEN) {
    throw new Error(`评论不能超过 ${MAX_COMMENT_LEN} 字。`);
  }
  const db = readDb();
  if (!Array.isArray(db.comments)) db.comments = [];
  const now = new Date().toISOString();
  const row = {
    id: randomUUID(),
    pluginId,
    userId: String(userId),
    username: String(username || '').trim(),
    body: text,
    createdAt: now,
    hidden: false,
  };
  db.comments.unshift(row);
  writeDb(db);
  return {
    ...row,
    likeCount: 0,
    likedByMe: false,
  };
}

export function deleteComment(pluginId, commentId, { userId, isAdmin = false } = {}) {
  const db = readDb();
  if (!Array.isArray(db.comments)) db.comments = [];
  const idx = db.comments.findIndex((c) => c.id === commentId && c.pluginId === pluginId);
  if (idx < 0) return false;
  const row = db.comments[idx];
  if (!isAdmin && row.userId !== userId) {
    throw new Error('无权删除该评论。');
  }
  db.comments.splice(idx, 1);
  db.commentLikes = (db.commentLikes || []).filter((l) => l.commentId !== commentId);
  writeDb(db);
  return true;
}

export function getComment(pluginId, commentId) {
  return (
    (readDb().comments || []).find((c) => c.id === commentId && c.pluginId === pluginId) || null
  );
}

export function setCommentHidden(pluginId, commentId, hidden) {
  const db = readDb();
  const idx = (db.comments || []).findIndex((c) => c.id === commentId && c.pluginId === pluginId);
  if (idx < 0) return null;
  db.comments[idx] = { ...db.comments[idx], hidden: !!hidden };
  writeDb(db);
  return db.comments[idx];
}

export function toggleCommentLike({ pluginId, commentId, userId }) {
  const comment = getComment(pluginId, commentId);
  if (!comment || comment.hidden) throw new Error('评论不存在。');
  const plugin = getPlugin(pluginId);
  if (!plugin || plugin.enabled === false) throw new Error('插件不存在或未上架。');
  const db = readDb();
  if (!Array.isArray(db.commentLikes)) db.commentLikes = [];
  const idx = db.commentLikes.findIndex(
    (l) => l.commentId === commentId && l.userId === userId,
  );
  let liked;
  if (idx >= 0) {
    db.commentLikes.splice(idx, 1);
    liked = false;
  } else {
    db.commentLikes.push({
      id: randomUUID(),
      pluginId,
      commentId,
      userId: String(userId),
      createdAt: new Date().toISOString(),
    });
    liked = true;
  }
  writeDb(db);
  const likeCount = (readDb().commentLikes || []).filter((l) => l.commentId === commentId).length;
  return { liked, likeCount };
}

export function createCommentReport(input) {
  const db = readDb();
  if (!Array.isArray(db.commentReports)) db.commentReports = [];
  const dup = db.commentReports.find(
    (r) =>
      r.commentId === input.commentId &&
      r.reporterUserId === input.reporterUserId &&
      r.status !== 'dismissed',
  );
  if (dup) throw new Error('你已举报过这条评论。');
  const now = new Date().toISOString();
  const row = {
    id: randomUUID(),
    pluginId: input.pluginId,
    commentId: input.commentId,
    reporterUserId: String(input.reporterUserId),
    reporterUsername: String(input.reporterUsername || '').trim(),
    targetUserId: String(input.targetUserId || ''),
    targetUsername: String(input.targetUsername || '').trim(),
    commentBody: String(input.commentBody || ''),
    reason: String(input.reason || '').trim(),
    status: input.status || 'pending',
    aiReviewed: !!input.aiReviewed,
    reportValid: input.reportValid ?? null,
    category: input.category || 'pending',
    actionLevel: Number(input.actionLevel || 0),
    actionLevelName: input.actionLevelName || '无处罚',
    aiSummary: input.aiSummary || '',
    actionTaken: input.actionTaken || '',
    message: input.message || '',
    createdAt: now,
    resolvedAt: null,
    resolvedBy: '',
  };
  db.commentReports.unshift(row);
  writeDb(db);
  return row;
}

export function listCommentReports({ status = '' } = {}) {
  let list = readDb().commentReports || [];
  if (status) list = list.filter((r) => r.status === status);
  return list.sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || '')));
}

export function resolveCommentReport(id, { status, resolvedBy, note = '' } = {}) {
  const db = readDb();
  const idx = (db.commentReports || []).findIndex((r) => r.id === id);
  if (idx < 0) return null;
  const cur = db.commentReports[idx];
  const next = {
    ...cur,
    status: status || cur.status,
    resolvedAt: new Date().toISOString(),
    resolvedBy: String(resolvedBy || ''),
    actionTaken: note || cur.actionTaken,
  };
  db.commentReports[idx] = next;
  writeDb(db);
  return next;
}

export function getCommentCount(pluginId) {
  return (readDb().comments || []).filter((c) => c.pluginId === pluginId && !c.hidden).length;
}

export function createApplication(input) {
  const db = readDb();
  const now = new Date().toISOString();
  const app = {
    id: randomUUID(),
    applicantUserId: String(input.applicantUserId || '').trim(),
    applicantUsername: String(input.applicantUsername || '').trim(),
    applicantEmail: String(input.applicantEmail || '').trim(),
    name: String(input.name || '').trim(),
    downloadUrl: String(input.downloadUrl || '').trim(),
    description: String(input.description || '').trim(),
    type: normalizePluginType(input.type, 'other'),
    platforms: normalizePlatforms(input.platforms),
    status: 'pending',
    rejectReason: '',
    reviewedAt: null,
    reviewedBy: '',
    createdAt: now,
    updatedAt: now,
  };
  if (!app.applicantUserId) throw new Error('缺少申请人');
  if (!app.name) throw new Error('插件名称不能为空');
  if (!app.downloadUrl) throw new Error('下载链接不能为空');
  db.applications.unshift(app);
  writeDb(db);
  return app;
}

export function listApplications(filter = {}) {
  let list = readDb().applications;
  if (filter.status) {
    list = list.filter((a) => a.status === filter.status);
  }
  if (filter.applicantUserId) {
    list = list.filter((a) => a.applicantUserId === filter.applicantUserId);
  }
  return list.sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || '')));
}

export function getApplication(id) {
  return readDb().applications.find((a) => a.id === id) || null;
}

export function updateApplication(id, patch) {
  const db = readDb();
  const idx = db.applications.findIndex((a) => a.id === id);
  if (idx < 0) return null;
  const cur = db.applications[idx];
  const next = {
    ...cur,
    ...patch,
    id: cur.id,
    updatedAt: new Date().toISOString(),
  };
  db.applications[idx] = next;
  writeDb(db);
  return next;
}
