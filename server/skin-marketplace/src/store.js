import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const dataDir = path.resolve(__dirname, '../data');
const dbPath = path.join(dataDir, 'skins.json');

function emptyDb() {
  return {
    applications: [],
    skins: [],
    ratings: [],
    comments: [],
    commentLikes: [],
    commentReports: [],
    skinReports: [],
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

function normalizeModel(model) {
  return String(model || '').toLowerCase() === 'slim' ? 'slim' : 'classic';
}

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

export function listPublishedSkins({ q = '', model = '' } = {}) {
  const query = String(q || '').trim().toLowerCase();
  const modelFilter = String(model || '').trim().toLowerCase();
  return readDb()
    .skins.filter((s) => s.enabled !== false && s.status === 'published')
    .filter((s) => {
      if (modelFilter && normalizeModel(s.model) !== normalizeModel(modelFilter)) return false;
      if (!query) return true;
      const hay = `${s.name}\n${s.description}\n${s.authorUsername}\n${(s.tags || []).join(' ')}`.toLowerCase();
      return hay.includes(query);
    })
    .sort((a, b) => String(b.publishedAt || '').localeCompare(String(a.publishedAt || '')));
}

export function listAllSkins() {
  return readDb().skins.sort((a, b) =>
    String(b.updatedAt || b.publishedAt || '').localeCompare(String(a.updatedAt || a.publishedAt || '')),
  );
}

export function listSkinsByAuthor(userId) {
  const uid = String(userId || '').trim();
  if (!uid) return [];
  return readDb()
    .skins.filter((s) => String(s.authorUserId || '') === uid)
    .sort((a, b) => String(b.updatedAt || '').localeCompare(String(a.updatedAt || '')));
}

export function getSkin(id) {
  return readDb().skins.find((s) => s.id === id) || null;
}

export function createSkin(input) {
  const db = readDb();
  const now = new Date().toISOString();
  const tags = Array.isArray(input.tags)
    ? input.tags.map((t) => String(t).trim()).filter(Boolean).slice(0, 12)
    : [];
  let originType = String(input.originType || '').trim().toLowerCase();
  if (originType === '转载') originType = 'reprint';
  if (originType === '原创' || originType === '自制') originType = 'original';
  if (originType !== 'reprint' && originType !== 'original') {
    originType = tags.includes('转载') ? 'reprint' : tags.includes('原创') ? 'original' : '';
  }
  const source = String(input.source || '').trim();
  const allowReprint =
    originType === 'reprint' ? false : input.allowReprint === false ? false : true;
  const skin = {
    id: randomUUID(),
    name: String(input.name || '').trim(),
    description: String(input.description || '').trim(),
    textureUrl: String(input.textureUrl || '').trim(),
    model: normalizeModel(input.model),
    tags,
    version: String(input.version || '1.0.0').trim() || '1.0.0',
    width: Number(input.width || 64),
    height: Number(input.height || 64),
    authorUserId: String(input.authorUserId || '').trim(),
    authorUsername: String(input.authorUsername || '').trim(),
    applicationId: input.applicationId || null,
    status: input.status || 'published',
    enabled: input.enabled !== false,
    publishedAt: now,
    publishedBy: String(input.publishedBy || '').trim(),
    updatedAt: now,
    downloadCount: 0,
    originType,
    source,
    allowReprint,
  };
  if (!skin.name) throw new Error('皮肤名称不能为空');
  if (!skin.textureUrl) throw new Error('皮肤纹理不能为空');
  if (!skin.authorUserId) throw new Error('必须指定作者账号');
  if (originType === 'reprint' && !source) throw new Error('转载皮肤请填写来源');
  db.skins.push(skin);
  writeDb(db);
  return skin;
}

export function updateSkin(id, patch) {
  const db = readDb();
  const idx = db.skins.findIndex((s) => s.id === id);
  if (idx < 0) return null;
  const cur = db.skins[idx];
  const next = {
    ...cur,
    ...patch,
    id: cur.id,
    updatedAt: new Date().toISOString(),
  };
  if (patch.name != null) next.name = String(patch.name).trim();
  if (patch.description != null) next.description = String(patch.description).trim();
  if (patch.textureUrl != null) next.textureUrl = String(patch.textureUrl).trim();
  if (patch.model != null) next.model = normalizeModel(patch.model);
  if (patch.version != null) {
    const v = String(patch.version).trim();
    next.version = v || cur.version || '1.0.0';
  }
  if (patch.tags != null) {
    next.tags = Array.isArray(patch.tags)
      ? patch.tags.map((t) => String(t).trim()).filter(Boolean).slice(0, 12)
      : cur.tags;
  }
  if (patch.enabled != null) next.enabled = !!patch.enabled;
  if (patch.status != null) next.status = String(patch.status);
  if (patch.width != null) next.width = Number(patch.width);
  if (patch.height != null) next.height = Number(patch.height);
  db.skins[idx] = next;
  writeDb(db);
  return next;
}

export function bumpDownload(id) {
  const skin = getSkin(id);
  if (!skin) return null;
  return updateSkin(id, { downloadCount: Number(skin.downloadCount || 0) + 1 });
}

export function releaseSkinVersion(id, authorUserId, patch) {
  const cur = getSkin(id);
  if (!cur || cur.enabled === false || cur.status !== 'published') {
    throw new Error('皮肤不存在或未上架。');
  }
  if (String(cur.authorUserId || '') !== String(authorUserId || '')) {
    throw new Error('只能更新自己上架的皮肤。');
  }
  const nextVersion = String(patch?.version || '').trim();
  if (!nextVersion) throw new Error('请填写新版本号。');
  const curVersion = String(cur.version || '1.0.0').trim() || '1.0.0';
  if (compareVersions(nextVersion, curVersion) <= 0) {
    throw new Error(`新版本号须高于当前版本（当前 v${curVersion}）。`);
  }
  const textureUrl = String(patch?.textureUrl || '').trim();
  if (!textureUrl) throw new Error('请上传新的皮肤 PNG。');
  return updateSkin(id, {
    version: nextVersion,
    textureUrl,
    description: patch.description != null ? patch.description : cur.description,
    model: patch.model != null ? patch.model : cur.model,
    width: patch.width != null ? patch.width : cur.width,
    height: patch.height != null ? patch.height : cur.height,
  });
}

export function deleteSkin(id) {
  const db = readDb();
  const before = db.skins.length;
  db.skins = db.skins.filter((s) => s.id !== id);
  if (db.skins.length === before) return false;
  db.ratings = (db.ratings || []).filter((r) => r.skinId !== id);
  const removedCommentIds = new Set(
    (db.comments || []).filter((c) => c.skinId === id).map((c) => c.id),
  );
  db.comments = (db.comments || []).filter((c) => c.skinId !== id);
  db.commentLikes = (db.commentLikes || []).filter(
    (l) => l.skinId !== id && !removedCommentIds.has(l.commentId),
  );
  db.commentReports = (db.commentReports || []).filter((r) => r.skinId !== id);
  db.skinReports = (db.skinReports || []).filter((r) => r.skinId !== id);
  writeDb(db);
  return true;
}

const MAX_COMMENT_LEN = 500;

export function getSkinRatingStats(skinId) {
  const ratings = (readDb().ratings || []).filter((r) => r.skinId === skinId);
  if (!ratings.length) return { ratingAvg: 0, ratingCount: 0 };
  const sum = ratings.reduce((acc, r) => acc + Number(r.score || 0), 0);
  const avg = Math.round((sum / ratings.length) * 10) / 10;
  return { ratingAvg: avg, ratingCount: ratings.length };
}

export function getUserRating(skinId, userId) {
  const row = (readDb().ratings || []).find((r) => r.skinId === skinId && r.userId === userId);
  return row ? { score: Number(row.score), updatedAt: row.updatedAt } : null;
}

export function upsertRating({ skinId, userId, username, score }) {
  const skin = getSkin(skinId);
  if (!skin || skin.enabled === false || skin.status !== 'published') {
    throw new Error('皮肤不存在或未上架。');
  }
  const n = Number(score);
  if (!Number.isInteger(n) || n < 1 || n > 5) throw new Error('评分须为 1–5 的整数。');
  const db = readDb();
  if (!Array.isArray(db.ratings)) db.ratings = [];
  const now = new Date().toISOString();
  const idx = db.ratings.findIndex((r) => r.skinId === skinId && r.userId === userId);
  const row = {
    id: idx >= 0 ? db.ratings[idx].id : randomUUID(),
    skinId,
    userId: String(userId),
    username: String(username || '').trim(),
    score: n,
    createdAt: idx >= 0 ? db.ratings[idx].createdAt : now,
    updatedAt: now,
  };
  if (idx >= 0) db.ratings[idx] = row;
  else db.ratings.push(row);
  writeDb(db);
  return { rating: row, ...getSkinRatingStats(skinId) };
}

export function getCommentCount(skinId) {
  return (readDb().comments || []).filter((c) => c.skinId === skinId && !c.hidden).length;
}

export function listComments(skinId, { limit = 50, offset = 0, viewerUserId = '' } = {}) {
  const db = readDb();
  const likes = db.commentLikes || [];
  const all = (db.comments || [])
    .filter((c) => c.skinId === skinId && !c.hidden)
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
      skinId: c.skinId,
      userId: c.userId,
      username: c.username,
      body: c.body,
      createdAt: c.createdAt,
      likeCount,
      likedByMe,
    };
  });
  return { items, total: all.length };
}

export function addComment({ skinId, userId, username, body }) {
  const skin = getSkin(skinId);
  if (!skin || skin.enabled === false || skin.status !== 'published') {
    throw new Error('皮肤不存在或未上架。');
  }
  const text = String(body || '').trim();
  if (!text) throw new Error('评论不能为空。');
  if (text.length > MAX_COMMENT_LEN) throw new Error(`评论不能超过 ${MAX_COMMENT_LEN} 字。`);
  const db = readDb();
  if (!Array.isArray(db.comments)) db.comments = [];
  const row = {
    id: randomUUID(),
    skinId,
    userId: String(userId),
    username: String(username || '').trim(),
    body: text,
    createdAt: new Date().toISOString(),
    hidden: false,
  };
  db.comments.unshift(row);
  writeDb(db);
  return { ...row, likeCount: 0, likedByMe: false };
}

export function getComment(skinId, commentId) {
  return (readDb().comments || []).find((c) => c.id === commentId && c.skinId === skinId) || null;
}

export function setCommentHidden(skinId, commentId, hidden) {
  const db = readDb();
  const idx = (db.comments || []).findIndex((c) => c.id === commentId && c.skinId === skinId);
  if (idx < 0) return null;
  db.comments[idx] = { ...db.comments[idx], hidden: !!hidden };
  writeDb(db);
  return db.comments[idx];
}

export function toggleCommentLike({ skinId, commentId, userId }) {
  const comment = getComment(skinId, commentId);
  if (!comment || comment.hidden) throw new Error('评论不存在。');
  const skin = getSkin(skinId);
  if (!skin || skin.enabled === false || skin.status !== 'published') {
    throw new Error('皮肤不存在或未上架。');
  }
  const db = readDb();
  if (!Array.isArray(db.commentLikes)) db.commentLikes = [];
  const idx = db.commentLikes.findIndex((l) => l.commentId === commentId && l.userId === userId);
  let liked;
  if (idx >= 0) {
    db.commentLikes.splice(idx, 1);
    liked = false;
  } else {
    db.commentLikes.push({
      id: randomUUID(),
      skinId,
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
  const row = {
    id: randomUUID(),
    skinId: input.skinId,
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
    createdAt: new Date().toISOString(),
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

export function createSkinReport(input) {
  const db = readDb();
  if (!Array.isArray(db.skinReports)) db.skinReports = [];
  const dup = db.skinReports.find(
    (r) =>
      r.skinId === input.skinId &&
      r.reporterUserId === input.reporterUserId &&
      r.status === 'pending',
  );
  if (dup) throw new Error('你已举报过该皮肤。');
  const row = {
    id: randomUUID(),
    skinId: input.skinId,
    skinName: String(input.skinName || ''),
    reporterUserId: String(input.reporterUserId),
    reporterUsername: String(input.reporterUsername || '').trim(),
    category: String(input.category || '').trim(),
    reason: String(input.reason || '').trim(),
    status: 'pending',
    createdAt: new Date().toISOString(),
    resolvedAt: null,
    resolvedBy: '',
  };
  db.skinReports.unshift(row);
  writeDb(db);
  return row;
}

export function listSkinReports({ status = '' } = {}) {
  let list = readDb().skinReports || [];
  if (status) list = list.filter((r) => r.status === status);
  return list.sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || '')));
}

export function resolveSkinReport(id, { status, resolvedBy, note = '' } = {}) {
  const db = readDb();
  const idx = (db.skinReports || []).findIndex((r) => r.id === id);
  if (idx < 0) return null;
  const cur = db.skinReports[idx];
  const next = {
    ...cur,
    status: status || cur.status,
    resolvedAt: new Date().toISOString(),
    resolvedBy: String(resolvedBy || ''),
    note: note || '',
  };
  db.skinReports[idx] = next;
  writeDb(db);
  return next;
}

export function createApplication(input) {
  const db = readDb();
  const now = new Date().toISOString();
  const tags = Array.isArray(input.tags)
    ? input.tags.map((t) => String(t).trim()).filter(Boolean).slice(0, 12)
    : [];
  let originType = String(input.originType || '').trim().toLowerCase();
  if (originType === '转载') originType = 'reprint';
  if (originType === '原创' || originType === '自制') originType = 'original';
  if (originType !== 'reprint' && originType !== 'original') originType = 'original';
  const source = String(input.source || '').trim();
  const allowReprint = originType === 'reprint' ? false : input.allowReprint !== false;
  const app = {
    id: randomUUID(),
    applicantUserId: String(input.applicantUserId || '').trim(),
    applicantUsername: String(input.applicantUsername || '').trim(),
    applicantEmail: String(input.applicantEmail || '').trim(),
    name: String(input.name || '').trim(),
    description: String(input.description || '').trim(),
    textureUrl: String(input.textureUrl || '').trim(),
    model: normalizeModel(input.model),
    tags,
    width: Number(input.width || 64),
    height: Number(input.height || 64),
    version: String(input.version || '1.0.0').trim() || '1.0.0',
    originType,
    source,
    allowReprint,
    status: 'pending',
    rejectReason: '',
    reviewedAt: null,
    reviewedBy: '',
    createdAt: now,
    updatedAt: now,
  };
  if (!app.applicantUserId) throw new Error('缺少申请人');
  if (!app.name) throw new Error('皮肤名称不能为空');
  if (!app.textureUrl) throw new Error('请上传皮肤 PNG');
  if (originType === 'reprint' && !source) throw new Error('转载皮肤请填写来源');
  db.applications.unshift(app);
  writeDb(db);
  return app;
}

export function listApplications(filter = {}) {
  let list = readDb().applications;
  if (filter.status) list = list.filter((a) => a.status === filter.status);
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
