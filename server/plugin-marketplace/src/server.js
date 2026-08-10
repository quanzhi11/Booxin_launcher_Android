import './env.js';
import cors from 'cors';
import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  addComment,
  createApplication,
  createCommentReport,
  createPlugin,
  deleteComment,
  deletePlugin,
  getApplication,
  getComment,
  getCommentCount,
  getPlugin,
  getPluginRatingStats,
  getUserRating,
  listAllPlugins,
  listApplications,
  listCommentReports,
  listComments,
  listPublishedPlugins,
  resolveCommentReport,
  setCommentHidden,
  toggleCommentLike,
  updateApplication,
  updatePlugin,
  upsertRating,
} from './store.js';
import {
  adminLogout,
  adminPasswordLogin,
  fetchAuthUser,
  optionalAuthUser,
  requireAdmin,
  requireAuthUser,
  searchAuthUsers,
} from './auth.js';
import { mailStatus, sendRejectEmail } from './mail.js';
import { reviewPluginCommentReport } from './moderation.js';
import { listPluginTypes, normalizePluginType } from './types.js';
import {
  MAX_UPLOAD_BYTES,
  contactInfo,
  publicUploadUrl,
  uploadMiddleware,
  uploadsDir,
} from './upload.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');

const PORT = Number(process.env.PORT || 5020);
const app = express();
app.use(cors());
app.use(express.json({ limit: '256kb' }));
app.use(express.static(path.join(rootDir, 'public')));
app.use('/uploads', express.static(uploadsDir()));

function publicPlugin(p) {
  const stats = getPluginRatingStats(p.id);
  return {
    id: p.id,
    name: p.name,
    description: p.description,
    downloadUrl: p.downloadUrl,
    type: p.type,
    developerUserId: p.developerUserId,
    developerUsername: p.developerUsername,
    publishedAt: p.publishedAt,
    updatedAt: p.updatedAt,
    ratingAvg: stats.ratingAvg,
    ratingCount: stats.ratingCount,
    commentCount: getCommentCount(p.id),
  };
}

function publicApplication(a) {
  return {
    id: a.id,
    name: a.name,
    description: a.description,
    downloadUrl: a.downloadUrl,
    type: a.type,
    status: a.status,
    rejectReason: a.rejectReason || '',
    createdAt: a.createdAt,
    updatedAt: a.updatedAt,
    reviewedAt: a.reviewedAt,
  };
}

app.get('/api/health', (_req, res) => {
  res.json({ ok: true, service: 'booxin-plugin-marketplace', mail: mailStatus() });
});

app.get('/api/plugin-types', (_req, res) => {
  res.json({ items: listPluginTypes() });
});

app.get('/api/contact', (_req, res) => {
  res.json(contactInfo());
});

/** Public catalog. Hosted uploads are under /uploads; external links also allowed. */
app.get('/api/plugins', (req, res) => {
  const type = typeof req.query.type === 'string' ? normalizePluginType(req.query.type, '') : '';
  let items = listPublishedPlugins();
  if (type) items = items.filter((p) => p.type === type);
  res.json({ items: items.map(publicPlugin), types: listPluginTypes() });
});

app.get('/api/plugins/:id', (req, res) => {
  const p = getPlugin(req.params.id);
  if (!p || p.enabled === false) {
    res.status(404).json({ message: '插件不存在或未上架。' });
    return;
  }
  res.json(publicPlugin(p));
});

app.get('/api/plugins/:id/comments', optionalAuthUser, (req, res) => {
  const p = getPlugin(req.params.id);
  if (!p || p.enabled === false) {
    res.status(404).json({ message: '插件不存在或未上架。' });
    return;
  }
  const limit = Number(req.query.limit || 50);
  const offset = Number(req.query.offset || 0);
  const { items, total } = listComments(req.params.id, {
    limit,
    offset,
    viewerUserId: req.booxinUser?.userId || '',
  });
  res.json({ items, total });
});

app.get('/api/plugins/:id/rating/mine', requireAuthUser, (req, res) => {
  const p = getPlugin(req.params.id);
  if (!p || p.enabled === false) {
    res.status(404).json({ message: '插件不存在或未上架。' });
    return;
  }
  const mine = getUserRating(req.params.id, req.booxinUser.userId);
  res.json({ score: mine?.score ?? null, updatedAt: mine?.updatedAt ?? null });
});

app.put('/api/plugins/:id/rating', requireAuthUser, (req, res) => {
  try {
    const result = upsertRating({
      pluginId: req.params.id,
      userId: req.booxinUser.userId,
      username: req.booxinUser.username,
      score: req.body?.score,
    });
    res.json({
      score: result.rating.score,
      ratingAvg: result.ratingAvg,
      ratingCount: result.ratingCount,
      updatedAt: result.rating.updatedAt,
    });
  } catch (err) {
    const msg = err?.message || '评分失败';
    const code = msg.includes('不存在') ? 404 : 400;
    res.status(code).json({ message: msg });
  }
});

app.post('/api/plugins/:id/comments', requireAuthUser, (req, res) => {
  try {
    const row = addComment({
      pluginId: req.params.id,
      userId: req.booxinUser.userId,
      username: req.booxinUser.username,
      body: req.body?.body ?? req.body?.text,
    });
    res.status(201).json({
      ...row,
      commentCount: getCommentCount(row.pluginId),
    });
  } catch (err) {
    const msg = err?.message || '评论失败';
    const code = msg.includes('不存在') ? 404 : 400;
    res.status(code).json({ message: msg });
  }
});

app.post('/api/plugins/:id/comments/:commentId/like', requireAuthUser, (req, res) => {
  try {
    const result = toggleCommentLike({
      pluginId: req.params.id,
      commentId: req.params.commentId,
      userId: req.booxinUser.userId,
    });
    res.json(result);
  } catch (err) {
    const msg = err?.message || '点赞失败';
    const code = msg.includes('不存在') ? 404 : 400;
    res.status(code).json({ message: msg });
  }
});

app.post('/api/plugins/:id/comments/:commentId/report', requireAuthUser, async (req, res) => {
  try {
    const comment = getComment(req.params.id, req.params.commentId);
    if (!comment || comment.hidden) {
      res.status(404).json({ message: '评论不存在。' });
      return;
    }
    if (comment.userId === req.booxinUser.userId) {
      res.status(400).json({ message: '不能举报自己的评论。' });
      return;
    }
    const reason = String(req.body?.reason || '').trim();
    const review = await reviewPluginCommentReport({
      body: comment.body,
      reason,
    });
    if (review.hideComment) {
      setCommentHidden(req.params.id, req.params.commentId, true);
    }
    const row = createCommentReport({
      pluginId: req.params.id,
      commentId: req.params.commentId,
      reporterUserId: req.booxinUser.userId,
      reporterUsername: req.booxinUser.username,
      targetUserId: comment.userId,
      targetUsername: comment.username,
      commentBody: comment.body,
      reason,
      status: review.hideComment
        ? 'actioned'
        : review.reportValid === false
          ? 'dismissed'
          : 'pending',
      aiReviewed: review.aiReviewed,
      reportValid: review.reportValid,
      category: review.category,
      actionLevel: review.actionLevel,
      actionLevelName: review.actionLevelName,
      aiSummary: review.aiSummary,
      actionTaken: review.actionTaken,
      message: review.message,
    });
    // Mirror desktop DirectMessageReportResult fields for client UI.
    res.status(201).json({
      reportId: row.id,
      aiReviewed: row.aiReviewed,
      reportValid: row.reportValid,
      category: row.category,
      actionLevel: row.actionLevel,
      actionLevelName: row.actionLevelName,
      aiSummary: row.aiSummary,
      actionTaken: row.actionTaken,
      contextMessageCount: 0,
      message: row.message,
    });
  } catch (err) {
    const msg = err?.message || '举报失败';
    const code = msg.includes('已举报') ? 409 : msg.includes('不存在') ? 404 : 400;
    res.status(code).json({ message: msg });
  }
});

app.delete('/api/plugins/:id/comments/:commentId', requireAuthUser, (req, res) => {
  try {
    const ok = deleteComment(req.params.id, req.params.commentId, {
      userId: req.booxinUser.userId,
      isAdmin: false,
    });
    if (!ok) {
      res.status(404).json({ message: '评论不存在。' });
      return;
    }
    res.json({ ok: true, commentCount: getCommentCount(req.params.id) });
  } catch (err) {
    res.status(403).json({ message: err?.message || '无权删除' });
  }
});

app.post('/api/applications', requireAuthUser, (req, res) => {
  try {
    const downloadUrl = String(req.body?.downloadUrl || '').trim();
    if (!downloadUrl) {
      res.status(400).json({
        message: '请上传插件文件，或填写下载直链。超过 250MB 请加 QQ 私聊。',
        contact: contactInfo(),
      });
      return;
    }
    const appRow = createApplication({
      applicantUserId: req.booxinUser.userId,
      applicantUsername: req.booxinUser.username,
      applicantEmail: req.booxinUser.email || '',
      name: req.body?.name,
      downloadUrl,
      description: req.body?.description,
      type: normalizePluginType(req.body?.type, 'other'),
    });
    res.status(201).json(publicApplication(appRow));
  } catch (err) {
    res.status(400).json({ message: err?.message || '提交失败' });
  }
});

/** Multipart upload: fields name, description, type + file (≤250MB). */
app.post('/api/applications/upload', requireAuthUser, (req, res) => {
  uploadMiddleware(req, res, (err) => {
    if (err) {
      if (err.code === 'LIMIT_FILE_SIZE') {
        res.status(413).json({
          message: `文件超过 ${MAX_UPLOAD_BYTES / 1024 / 1024}MB 上限，请添加 QQ 私聊提交。`,
          contact: contactInfo(),
        });
        return;
      }
      res.status(400).json({ message: err.message || '上传失败', contact: contactInfo() });
      return;
    }
    try {
      if (!req.file) {
        res.status(400).json({
          message: '请选择要上传的插件文件（≤250MB）。',
          contact: contactInfo(),
        });
        return;
      }
      const downloadUrl = publicUploadUrl(req.file.filename);
      const appRow = createApplication({
        applicantUserId: req.booxinUser.userId,
        applicantUsername: req.booxinUser.username,
        applicantEmail: req.booxinUser.email || '',
        name: req.body?.name || req.file.originalname,
        downloadUrl,
        description: req.body?.description || '',
        type: normalizePluginType(req.body?.type, 'other'),
      });
      res.status(201).json({
        ...publicApplication(appRow),
        hosted: true,
        size: req.file.size,
      });
    } catch (e) {
      res.status(400).json({ message: e?.message || '提交失败' });
    }
  });
});

app.get('/api/applications/mine', requireAuthUser, (req, res) => {
  const items = listApplications({ applicantUserId: req.booxinUser.userId }).map(publicApplication);
  res.json({ items });
});

app.get('/api/applications/:id', requireAuthUser, (req, res) => {
  const row = getApplication(req.params.id);
  if (!row) {
    res.status(404).json({ message: '申请不存在。' });
    return;
  }
  if (row.applicantUserId !== req.booxinUser.userId) {
    res.status(403).json({ message: '无权查看该申请。' });
    return;
  }
  res.json(publicApplication(row));
});

app.post('/api/admin/login', async (req, res) => {
  const result = await adminPasswordLogin(req.body?.username, req.body?.password);
  if (!result.ok) {
    res.status(result.status || 401).json({ message: result.message });
    return;
  }
  res.json({
    token: result.session.id,
    username: result.session.username,
    searchReady: !!result.searchReady,
    message: result.searchReady
      ? '登录成功'
      : '登录成功（开发者搜索需配置联机账号或 BOOXIN_AUTH_USERNAME/PASSWORD）',
  });
});

app.post('/api/admin/logout', (req, res) => {
  adminLogout(req);
  res.json({ ok: true });
});

app.get('/api/admin/me', requireAdmin, (req, res) => {
  res.json({
    username: req.booxinAdmin.username,
    userId: req.booxinAdmin.userId,
    mode: req.booxinAdmin.mode,
    searchReady: !!req.booxinAdmin.accessToken,
  });
});

app.get('/api/admin/applications', requireAdmin, (req, res) => {
  const status = typeof req.query.status === 'string' ? req.query.status : '';
  const items = listApplications(status ? { status } : {});
  res.json({ items });
});

app.get('/api/admin/plugins', requireAdmin, (_req, res) => {
  res.json({ items: listAllPlugins() });
});

app.get('/api/admin/reports', requireAdmin, (req, res) => {
  const status = typeof req.query.status === 'string' ? req.query.status : '';
  res.json({ items: listCommentReports(status ? { status } : {}) });
});

app.post('/api/admin/reports/:id/resolve', requireAdmin, (req, res) => {
  const action = String(req.body?.action || '').trim(); // hide | restore | dismiss
  const items = listCommentReports();
  const row = items.find((r) => r.id === req.params.id);
  if (!row) {
    res.status(404).json({ message: '举报不存在。' });
    return;
  }
  try {
    if (action === 'hide') {
      setCommentHidden(row.pluginId, row.commentId, true);
    } else if (action === 'restore') {
      setCommentHidden(row.pluginId, row.commentId, false);
    }
    const status =
      action === 'dismiss' ? 'dismissed' : action === 'hide' || action === 'restore' ? 'actioned' : 'pending';
    const updated = resolveCommentReport(row.id, {
      status,
      resolvedBy: req.booxinAdmin.username || req.booxinAdmin.userId,
      note: String(req.body?.note || action || ''),
    });
    res.json({ report: updated });
  } catch (err) {
    res.status(400).json({ message: err?.message || '处理失败' });
  }
});

app.get('/api/admin/users/search', requireAdmin, async (req, res) => {
  const q = String(req.query.query || req.query.q || '').trim();
  if (!q) {
    res.json({ items: [] });
    return;
  }
  const token = req.booxinAdmin.accessToken;
  if (!token) {
    res.status(400).json({
      message:
        '当前登录无法搜索开发者。请用联机管理员账号密码登录，或在 .env 配置 BOOXIN_AUTH_USERNAME / BOOXIN_AUTH_PASSWORD。',
    });
    return;
  }
  try {
    const payload = await searchAuthUsers(token, q, Number(req.query.limit || 20));
    // Auth API returns { results: [...] } (same as launcher MultiplayerApi).
    const items = Array.isArray(payload)
      ? payload
      : Array.isArray(payload?.results)
        ? payload.results
        : Array.isArray(payload?.items)
          ? payload.items
          : Array.isArray(payload?.users)
            ? payload.users
            : [];
    res.json({
      items: items
        .map((u) => ({
          id: String(u.id || u.userId || ''),
          username: String(u.username || ''),
          email: u.email ? String(u.email) : undefined,
          avatarUrl: u.avatarUrl || null,
        }))
        .filter((u) => u.id || u.username),
    });
  } catch (err) {
    res.status(502).json({ message: err?.message || '用户搜索失败' });
  }
});

/** Direct shelf (no prior application). */
app.post('/api/admin/plugins', requireAdmin, async (req, res) => {
  try {
    let developerUsername = String(req.body?.developerUsername || '').trim();
    const developerUserId = String(req.body?.developerUserId || '').trim();
    if (developerUserId && !developerUsername && req.booxinAdmin.accessToken) {
      const u = await fetchAuthUser(req.booxinAdmin.accessToken, developerUserId);
      if (u?.username) developerUsername = String(u.username);
    }
    const plugin = createPlugin({
      name: req.body?.name,
      description: req.body?.description,
      downloadUrl: req.body?.downloadUrl,
      type: normalizePluginType(req.body?.type, 'other'),
      developerUserId,
      developerUsername: developerUsername || developerUserId,
      publishedBy: req.booxinAdmin.username || req.booxinAdmin.userId,
      enabled: true,
    });
    res.status(201).json(plugin);
  } catch (err) {
    res.status(400).json({ message: err?.message || '上架失败' });
  }
});

app.patch('/api/admin/plugins/:id', requireAdmin, async (req, res) => {
  const cur = getPlugin(req.params.id);
  if (!cur) {
    res.status(404).json({ message: '插件不存在。' });
    return;
  }
  try {
    const patch = { ...req.body };
    if (patch.developerUserId && !patch.developerUsername && req.booxinAdmin.accessToken) {
      const u = await fetchAuthUser(req.booxinAdmin.accessToken, patch.developerUserId);
      if (u?.username) patch.developerUsername = String(u.username);
    }
    const next = updatePlugin(req.params.id, patch);
    res.json(next);
  } catch (err) {
    res.status(400).json({ message: err?.message || '更新失败' });
  }
});

app.delete('/api/admin/plugins/:id', requireAdmin, (req, res) => {
  if (!deletePlugin(req.params.id)) {
    res.status(404).json({ message: '插件不存在。' });
    return;
  }
  res.json({ ok: true });
});

app.post('/api/admin/applications/:id/approve', requireAdmin, async (req, res) => {
  const row = getApplication(req.params.id);
  if (!row) {
    res.status(404).json({ message: '申请不存在。' });
    return;
  }
  if (row.status !== 'pending') {
    res.status(400).json({ message: `当前状态为 ${row.status}，无法通过。` });
    return;
  }
  try {
    let developerUserId = String(req.body?.developerUserId || row.applicantUserId).trim();
    let developerUsername = String(
      req.body?.developerUsername || row.applicantUsername || '',
    ).trim();
    if (developerUserId && !developerUsername && req.booxinAdmin.accessToken) {
      const u = await fetchAuthUser(req.booxinAdmin.accessToken, developerUserId);
      if (u?.username) developerUsername = String(u.username);
    }
    const plugin = createPlugin({
      name: req.body?.name ?? row.name,
      description: req.body?.description ?? row.description,
      downloadUrl: req.body?.downloadUrl ?? row.downloadUrl,
      type: req.body?.type ?? row.type,
      developerUserId,
      developerUsername: developerUsername || developerUserId,
      applicationId: row.id,
      publishedBy: req.booxinAdmin.username || req.booxinAdmin.userId,
      enabled: true,
    });
    const updated = updateApplication(row.id, {
      status: 'approved',
      rejectReason: '',
      reviewedAt: new Date().toISOString(),
      reviewedBy: req.booxinAdmin.username || req.booxinAdmin.userId,
    });
    res.json({ application: updated, plugin });
  } catch (err) {
    res.status(400).json({ message: err?.message || '审核通过失败' });
  }
});

app.post('/api/admin/applications/:id/reject', requireAdmin, async (req, res) => {
  const row = getApplication(req.params.id);
  if (!row) {
    res.status(404).json({ message: '申请不存在。' });
    return;
  }
  if (row.status !== 'pending') {
    res.status(400).json({ message: `当前状态为 ${row.status}，无法拒绝。` });
    return;
  }
  const reason = String(req.body?.reason || '').trim();
  if (!reason) {
    res.status(400).json({ message: '请填写拒绝原因。' });
    return;
  }
  const sendEmail = req.body?.sendEmail !== false;
  const updated = updateApplication(row.id, {
    status: 'rejected',
    rejectReason: reason,
    reviewedAt: new Date().toISOString(),
    reviewedBy: req.booxinAdmin.username || req.booxinAdmin.userId,
  });

  let emailResult = { ok: false, skipped: true, message: '未请求发送邮件' };
  if (sendEmail) {
    emailResult = await sendRejectEmail({
      to: row.applicantEmail,
      pluginName: row.name,
      reason,
      username: row.applicantUsername,
    });
  }
  res.json({ application: updated, email: emailResult });
});

app.get('/admin', (req, res) => {
  const base = String(process.env.PUBLIC_BASE || '').replace(/\/$/, '');
  // Prefer same host path prefix when reverse-proxied (e.g. /plugin-api).
  const forwarded = String(req.headers['x-forwarded-prefix'] || '').replace(/\/$/, '');
  const prefix = base || forwarded;
  res.redirect(301, `${prefix}/admin/`);
});

app.listen(PORT, () => {
  const secretOn = !!String(process.env.ADMIN_SECRET || '').trim();
  console.log(`booxin-plugin-marketplace listening on :${PORT}`);
  console.log(`admin UI: http://127.0.0.1:${PORT}/admin/`);
  console.log(`ADMIN_SECRET loaded: ${secretOn ? 'yes' : 'NO'}`);
  console.log(`mail configured: ${mailStatus().configured ? 'yes' : 'no'}`);
});
