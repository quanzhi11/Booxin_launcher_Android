import './env.js';
import cors from 'cors';
import express from 'express';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  addComment,
  bumpDownload,
  createApplication,
  createCommentReport,
  createSkin,
  createSkinReport,
  deleteSkin,
  getApplication,
  getComment,
  getCommentCount,
  getSkin,
  getSkinRatingStats,
  getUserRating,
  listAllSkins,
  listApplications,
  listCommentReports,
  listComments,
  listPublishedSkins,
  listSkinReports,
  listSkinsByAuthor,
  releaseSkinVersion,
  resolveCommentReport,
  resolveSkinReport,
  setCommentHidden,
  toggleCommentLike,
  updateApplication,
  updateSkin,
  upsertRating,
} from './store.js';
import {
  adminLogout,
  adminPasswordLogin,
  optionalAuthUser,
  requireAdmin,
  requireAuthUser,
} from './auth.js';
import { mailStatus, sendRejectEmail } from './mail.js';
import { reviewPluginCommentReport } from './moderation.js';
import {
  MAX_UPLOAD_BYTES,
  publicUploadUrl,
  uploadMiddleware,
  uploadsDir,
  validateSkinPng,
} from './upload.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');
const PORT = Number(process.env.PORT || 5021);
const app = express();

app.use(cors());
app.use(express.json({ limit: '256kb' }));
app.use(express.static(path.join(rootDir, 'public')));
app.use('/uploads', express.static(uploadsDir()));

function normalizeOriginType(raw, tags = []) {
  const v = String(raw || '').trim().toLowerCase();
  if (v === 'reprint' || v === '转载') return 'reprint';
  if (v === 'original' || v === '原创' || v === '自制') return 'original';
  const list = Array.isArray(tags) ? tags : [];
  if (list.some((t) => String(t).trim() === '转载')) return 'reprint';
  if (list.some((t) => String(t).trim() === '原创')) return 'original';
  return '';
}

function parseAllowReprint(raw, originType, tags = []) {
  if (originType === 'reprint') return false;
  if (raw === true || raw === 'true' || raw === '1') return true;
  if (raw === false || raw === 'false' || raw === '0') return false;
  const list = Array.isArray(tags) ? tags : [];
  if (list.some((t) => String(t).includes('禁止转载'))) return false;
  if (list.some((t) => String(t).includes('允许转载'))) return true;
  return null;
}

function publicSkin(s) {
  const stats = getSkinRatingStats(s.id);
  const originType = normalizeOriginType(s.originType, s.tags);
  const allowReprint = parseAllowReprint(s.allowReprint, originType, s.tags);
  return {
    id: s.id,
    name: s.name,
    description: s.description,
    textureUrl: s.textureUrl,
    model: s.model || 'classic',
    tags: s.tags || [],
    version: s.version || '1.0.0',
    width: s.width,
    height: s.height,
    authorUserId: s.authorUserId,
    authorUsername: s.authorUsername,
    publishedAt: s.publishedAt,
    updatedAt: s.updatedAt,
    downloadCount: Number(s.downloadCount || 0),
    ratingAvg: stats.ratingAvg,
    ratingCount: stats.ratingCount,
    commentCount: getCommentCount(s.id),
    originType,
    source: String(s.source || '').trim(),
    allowReprint,
  };
}

function publicApplication(a) {
  const originType = normalizeOriginType(a.originType, a.tags);
  const allowReprint = parseAllowReprint(a.allowReprint, originType, a.tags);
  return {
    id: a.id,
    name: a.name,
    description: a.description,
    textureUrl: a.textureUrl,
    model: a.model || 'classic',
    tags: a.tags || [],
    version: a.version || '1.0.0',
    width: a.width,
    height: a.height,
    status: a.status,
    rejectReason: a.rejectReason || '',
    applicantUserId: a.applicantUserId,
    applicantUsername: a.applicantUsername,
    createdAt: a.createdAt,
    updatedAt: a.updatedAt,
    reviewedAt: a.reviewedAt,
    originType,
    source: String(a.source || '').trim(),
    allowReprint,
  };
}

function handleUpload(req, res) {
  return new Promise((resolve) => {
    uploadMiddleware(req, res, (err) => {
      if (err) {
        const oversize = err.code === 'LIMIT_FILE_SIZE';
        res.status(oversize ? 413 : 400).json({
          message: oversize
            ? `皮肤文件不能超过 ${MAX_UPLOAD_BYTES / 1024 / 1024}MB`
            : err.message || '上传失败',
        });
        resolve(null);
        return;
      }
      resolve(req.file || null);
    });
  });
}

app.get('/api/health', (_req, res) => {
  res.json({
    ok: true,
    service: 'booxin-skin-marketplace',
    mail: mailStatus(),
    maxUploadBytes: MAX_UPLOAD_BYTES,
  });
});

app.get('/api/skins', (req, res) => {
  const items = listPublishedSkins({
    q: req.query.q,
    model: req.query.model,
  }).map(publicSkin);
  res.json({ items });
});

app.get('/api/skins/:id', (req, res) => {
  const skin = getSkin(req.params.id);
  if (!skin || skin.enabled === false || skin.status !== 'published') {
    res.status(404).json({ message: '皮肤不存在' });
    return;
  }
  res.json(publicSkin(skin));
});

app.post('/api/skins/:id/download', (req, res) => {
  const skin = bumpDownload(req.params.id);
  if (!skin || skin.enabled === false || skin.status !== 'published') {
    res.status(404).json({ message: '皮肤不存在' });
    return;
  }
  res.json({ textureUrl: skin.textureUrl, downloadCount: skin.downloadCount });
});

app.get('/api/skins/:id/comments', optionalAuthUser, (req, res) => {
  const skin = getSkin(req.params.id);
  if (!skin || skin.enabled === false || skin.status !== 'published') {
    res.status(404).json({ message: '皮肤不存在' });
    return;
  }
  const limit = Number(req.query.limit || 50);
  const result = listComments(req.params.id, {
    limit,
    viewerUserId: req.booxinUser?.userId || '',
  });
  res.json(result);
});

app.post('/api/skins/:id/comments', requireAuthUser, (req, res) => {
  try {
    const comment = addComment({
      skinId: req.params.id,
      userId: req.booxinUser.userId,
      username: req.booxinUser.username,
      body: req.body?.body,
    });
    res.status(201).json(comment);
  } catch (err) {
    res.status(400).json({ message: err.message || '评论失败' });
  }
});

app.post('/api/skins/:id/comments/:commentId/like', requireAuthUser, (req, res) => {
  try {
    const result = toggleCommentLike({
      skinId: req.params.id,
      commentId: req.params.commentId,
      userId: req.booxinUser.userId,
    });
    res.json(result);
  } catch (err) {
    res.status(400).json({ message: err.message || '操作失败' });
  }
});

app.post('/api/skins/:id/comments/:commentId/report', requireAuthUser, async (req, res) => {
  try {
    const comment = getComment(req.params.id, req.params.commentId);
    if (!comment || comment.hidden) {
      res.status(404).json({ message: '评论不存在' });
      return;
    }
    if (comment.userId === req.booxinUser.userId) {
      res.status(400).json({ message: '不能举报自己的评论' });
      return;
    }
    const review = await reviewPluginCommentReport({
      body: comment.body,
      reason: req.body?.reason || '',
    });
    const report = createCommentReport({
      skinId: req.params.id,
      commentId: comment.id,
      reporterUserId: req.booxinUser.userId,
      reporterUsername: req.booxinUser.username,
      targetUserId: comment.userId,
      targetUsername: comment.username,
      commentBody: comment.body,
      reason: req.body?.reason || '',
      ...review,
    });
    if (review.reportValid === true && review.actionLevel >= 2 && review.actionLevel <= 3) {
      setCommentHidden(req.params.id, comment.id, true);
      report.actionTaken = 'comment_hidden';
    }
    res.json({
      reportId: report.id,
      aiReviewed: report.aiReviewed,
      reportValid: report.reportValid,
      category: report.category,
      actionLevel: report.actionLevel,
      actionLevelName: report.actionLevelName,
      aiSummary: report.aiSummary,
      actionTaken: report.actionTaken,
      message: report.message || review.message,
    });
  } catch (err) {
    res.status(400).json({ message: err.message || '举报失败' });
  }
});

app.get('/api/skins/:id/rating/mine', requireAuthUser, (req, res) => {
  const mine = getUserRating(req.params.id, req.booxinUser.userId);
  res.json({ score: mine?.score ?? null });
});

app.put('/api/skins/:id/rating', requireAuthUser, (req, res) => {
  try {
    const result = upsertRating({
      skinId: req.params.id,
      userId: req.booxinUser.userId,
      username: req.booxinUser.username,
      score: req.body?.score,
    });
    res.json({
      score: result.rating.score,
      ratingAvg: result.ratingAvg,
      ratingCount: result.ratingCount,
    });
  } catch (err) {
    res.status(400).json({ message: err.message || '评分失败' });
  }
});

app.post('/api/skins/:id/report', requireAuthUser, (req, res) => {
  try {
    const skin = getSkin(req.params.id);
    if (!skin || skin.enabled === false || skin.status !== 'published') {
      res.status(404).json({ message: '皮肤不存在' });
      return;
    }
    const report = createSkinReport({
      skinId: skin.id,
      skinName: skin.name,
      reporterUserId: req.booxinUser.userId,
      reporterUsername: req.booxinUser.username,
      category: req.body?.category || '',
      reason: req.body?.reason || '',
    });
    res.status(201).json({ reportId: report.id, message: '已提交举报，等待审核。' });
  } catch (err) {
    res.status(400).json({ message: err.message || '举报失败' });
  }
});

app.post('/api/applications/upload', requireAuthUser, async (req, res) => {
  const file = await handleUpload(req, res);
  if (!file) return;
  const check = await validateSkinPng(file.path);
  if (!check.ok) {
    fs.unlink(file.path, () => {});
    res.status(400).json({ message: check.message });
    return;
  }
  try {
    const textureUrl = publicUploadUrl(file.filename);
    const tags = String(req.body?.tags || '')
      .split(/[,，\s]+/)
      .map((t) => t.trim())
      .filter(Boolean);
    let originType = normalizeOriginType(req.body?.originType, tags);
    if (!originType) originType = 'original';
    const source = String(req.body?.source || '').trim();
    if (originType === 'reprint' && !source) {
      fs.unlink(file.path, () => {});
      res.status(400).json({ message: '转载皮肤请填写来源' });
      return;
    }
    const allowReprint =
      originType === 'reprint'
        ? false
        : parseAllowReprint(req.body?.allowReprint, originType, tags) !== false;
    const appRow = createApplication({
      applicantUserId: req.booxinUser.userId,
      applicantUsername: req.booxinUser.username,
      applicantEmail: req.booxinUser.email || '',
      name: req.body?.name,
      description: req.body?.description || '',
      textureUrl,
      model: req.body?.model,
      tags,
      width: check.width,
      height: check.height,
      version: req.body?.version || '1.0.0',
      originType,
      source,
      allowReprint,
    });
    res.status(201).json(publicApplication(appRow));
  } catch (err) {
    fs.unlink(file.path, () => {});
    res.status(400).json({ message: err.message || '提交失败' });
  }
});

app.get('/api/applications/mine', requireAuthUser, (req, res) => {
  res.json({
    items: listApplications({ applicantUserId: req.booxinUser.userId }).map(publicApplication),
  });
});

app.get('/api/me/skins', requireAuthUser, (req, res) => {
  res.json({ items: listSkinsByAuthor(req.booxinUser.userId).map(publicSkin) });
});

app.post('/api/skins/:id/release', requireAuthUser, async (req, res) => {
  const file = await handleUpload(req, res);
  if (!file) return;
  const check = await validateSkinPng(file.path);
  if (!check.ok) {
    fs.unlink(file.path, () => {});
    res.status(400).json({ message: check.message });
    return;
  }
  try {
    const skin = releaseSkinVersion(req.params.id, req.booxinUser.userId, {
      version: req.body?.version,
      textureUrl: publicUploadUrl(file.filename),
      description: req.body?.description,
      model: req.body?.model,
      width: check.width,
      height: check.height,
    });
    res.json(publicSkin(skin));
  } catch (err) {
    fs.unlink(file.path, () => {});
    res.status(400).json({ message: err.message || '更新失败' });
  }
});

app.post('/api/admin/login', async (req, res) => {
  const result = await adminPasswordLogin(req.body?.username, req.body?.password);
  if (!result.ok) {
    res.status(result.status || 401).json({ message: result.message });
    return;
  }
  res.json({
    accessToken: result.session.id,
    username: result.session.username,
    searchReady: result.searchReady,
  });
});

app.post('/api/admin/logout', requireAdmin, (req, res) => {
  adminLogout(req);
  res.json({ ok: true });
});

app.get('/api/admin/me', requireAdmin, (req, res) => {
  res.json({
    username: req.booxinAdmin.username,
    userId: req.booxinAdmin.userId,
    mode: req.booxinAdmin.mode,
  });
});

app.get('/api/admin/applications', requireAdmin, (req, res) => {
  const status = String(req.query.status || 'pending');
  res.json({
    items: listApplications(status === 'all' ? {} : { status }).map(publicApplication),
  });
});

app.post('/api/admin/applications/:id/approve', requireAdmin, (req, res) => {
  const appRow = getApplication(req.params.id);
  if (!appRow) {
    res.status(404).json({ message: '申请不存在' });
    return;
  }
  if (appRow.status !== 'pending') {
    res.status(400).json({ message: '该申请已处理' });
    return;
  }
  const skin = createSkin({
    name: appRow.name,
    description: appRow.description,
    textureUrl: appRow.textureUrl,
    model: appRow.model,
    tags: appRow.tags,
    version: appRow.version,
    width: appRow.width,
    height: appRow.height,
    authorUserId: appRow.applicantUserId,
    authorUsername: appRow.applicantUsername,
    applicationId: appRow.id,
    publishedBy: req.booxinAdmin.username,
    status: 'published',
    originType: appRow.originType,
    source: appRow.source,
    allowReprint: appRow.allowReprint,
  });
  updateApplication(appRow.id, {
    status: 'approved',
    reviewedAt: new Date().toISOString(),
    reviewedBy: req.booxinAdmin.username,
  });
  res.json({ skin: publicSkin(skin) });
});

app.post('/api/admin/applications/:id/reject', requireAdmin, async (req, res) => {
  const appRow = getApplication(req.params.id);
  if (!appRow) {
    res.status(404).json({ message: '申请不存在' });
    return;
  }
  const reason = String(req.body?.reason || '').trim() || '未通过审核';
  updateApplication(appRow.id, {
    status: 'rejected',
    rejectReason: reason,
    reviewedAt: new Date().toISOString(),
    reviewedBy: req.booxinAdmin.username,
  });
  const mail = await sendRejectEmail({
    to: appRow.applicantEmail,
    pluginName: appRow.name,
    reason,
    username: appRow.applicantUsername,
  });
  res.json({ ok: true, mail });
});

app.get('/api/admin/skins', requireAdmin, (_req, res) => {
  res.json({
    items: listAllSkins().map((s) => ({
      ...publicSkin(s),
      status: s.status,
      enabled: s.enabled !== false,
    })),
  });
});

app.post('/api/admin/skins/:id/takedown', requireAdmin, (req, res) => {
  const skin = updateSkin(req.params.id, {
    enabled: false,
    status: 'takedown',
  });
  if (!skin) {
    res.status(404).json({ message: '皮肤不存在' });
    return;
  }
  res.json(publicSkin(skin));
});

app.post('/api/admin/skins/:id/restore', requireAdmin, (req, res) => {
  const skin = updateSkin(req.params.id, {
    enabled: true,
    status: 'published',
  });
  if (!skin) {
    res.status(404).json({ message: '皮肤不存在' });
    return;
  }
  res.json(publicSkin(skin));
});

app.delete('/api/admin/skins/:id', requireAdmin, (req, res) => {
  const ok = deleteSkin(req.params.id);
  if (!ok) {
    res.status(404).json({ message: '皮肤不存在' });
    return;
  }
  res.json({ ok: true });
});

app.get('/api/admin/comment-reports', requireAdmin, (req, res) => {
  res.json({ items: listCommentReports({ status: req.query.status || '' }) });
});

app.post('/api/admin/comment-reports/:id/resolve', requireAdmin, (req, res) => {
  const row = resolveCommentReport(req.params.id, {
    status: req.body?.status || 'resolved',
    resolvedBy: req.booxinAdmin.username,
    note: req.body?.note || '',
  });
  if (!row) {
    res.status(404).json({ message: '举报不存在' });
    return;
  }
  res.json(row);
});

app.get('/api/admin/skin-reports', requireAdmin, (req, res) => {
  res.json({ items: listSkinReports({ status: req.query.status || '' }) });
});

app.post('/api/admin/skin-reports/:id/resolve', requireAdmin, (req, res) => {
  const row = resolveSkinReport(req.params.id, {
    status: req.body?.status || 'resolved',
    resolvedBy: req.booxinAdmin.username,
    note: req.body?.note || '',
  });
  if (!row) {
    res.status(404).json({ message: '举报不存在' });
    return;
  }
  res.json(row);
});

app.post('/api/admin/publish', requireAdmin, async (req, res) => {
  const file = await handleUpload(req, res);
  if (!file) return;
  const check = await validateSkinPng(file.path);
  if (!check.ok) {
    fs.unlink(file.path, () => {});
    res.status(400).json({ message: check.message });
    return;
  }
  try {
    const tags = String(req.body?.tags || '')
      .split(/[,，\s]+/)
      .map((t) => t.trim())
      .filter(Boolean);
    const skin = createSkin({
      name: req.body?.name,
      description: req.body?.description || '',
      textureUrl: publicUploadUrl(file.filename),
      model: req.body?.model,
      tags,
      version: req.body?.version || '1.0.0',
      width: check.width,
      height: check.height,
      authorUserId: req.body?.authorUserId || req.booxinAdmin.userId || 'admin',
      authorUsername: req.body?.authorUsername || req.booxinAdmin.username || 'admin',
      publishedBy: req.booxinAdmin.username,
      status: 'published',
    });
    res.status(201).json(publicSkin(skin));
  } catch (err) {
    fs.unlink(file.path, () => {});
    res.status(400).json({ message: err.message || '上架失败' });
  }
});

app.listen(PORT, () => {
  console.log(`Booxin skin marketplace listening on :${PORT}`);
});
