import {
  createAdminSession,
  destroyAdminSession,
  getAdminSession,
} from './adminSessions.js';

function authApiBase() {
  return (process.env.BOOXIN_AUTH_API_BASE || 'https://boonix.art/bbx/api/auth/').replace(
    /\/?$/,
    '/',
  );
}

function adminUserIds() {
  return new Set(
    String(process.env.ADMIN_USER_IDS || '')
      .split(',')
      .map((s) => s.trim().toLowerCase())
      .filter(Boolean),
  );
}

function adminSecret() {
  return String(process.env.ADMIN_SECRET || '').trim();
}

function localAdminUsername() {
  return String(process.env.ADMIN_USERNAME || '').trim();
}

function localAdminPassword() {
  return String(process.env.ADMIN_PASSWORD || process.env.ADMIN_SECRET || '').trim();
}

export function extractBearerToken(req) {
  const header = req.header('Authorization') || '';
  if (header.startsWith('Bearer ')) return header.slice(7).trim();
  if (typeof req.body?.accessToken === 'string') return req.body.accessToken.trim();
  return '';
}

export async function validateToken(accessToken) {
  if (!accessToken) {
    return { ok: false, status: 401, message: '缺少登录令牌。' };
  }
  try {
    const response = await fetch(`${authApiBase()}validate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ accessToken }),
    });
    if (!response.ok) {
      return { ok: false, status: 401, message: '登录令牌无效或已过期。' };
    }
    const payload = await response.json();
    if (!payload?.isValid || !payload.user?.id) {
      return { ok: false, status: 401, message: '登录令牌无效或已过期。' };
    }
    return {
      ok: true,
      userId: String(payload.user.id),
      username: String(payload.user.username || ''),
      email: String(payload.user.email || ''),
      isEmailVerified: !!payload.user.isEmailVerified,
      accessToken,
    };
  } catch (err) {
    console.warn('Auth validate failed:', err);
    return { ok: false, status: 503, message: '无法连接联机认证服务器。' };
  }
}

export async function loginBooxin(username, password) {
  const response = await fetch(`${authApiBase()}login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({
      username: String(username || '').trim(),
      password: String(password || ''),
    }),
  });
  const text = await response.text().catch(() => '');
  let payload = null;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch {
    payload = null;
  }
  if (!response.ok) {
    throw new Error(payload?.message || text || `登录失败 (${response.status})`);
  }
  const accessToken = String(
    payload?.accessToken || payload?.token || payload?.access_token || '',
  ).trim();
  const user = payload?.user || {};
  const userId = String(user.id || payload?.userId || '').trim();
  const uname = String(user.username || username || '').trim();
  if (!accessToken) {
    throw new Error('联机登录成功但未返回 accessToken。');
  }
  return { accessToken, userId, username: uname, email: String(user.email || '') };
}

export async function fetchAuthMe(accessToken) {
  try {
    const response = await fetch(`${authApiBase()}me`, {
      headers: {
        Accept: 'application/json',
        Authorization: `Bearer ${accessToken}`,
      },
    });
    if (!response.ok) return null;
    return await response.json();
  } catch {
    return null;
  }
}

export async function searchAuthUsers(accessToken, query, limit = 20) {
  const q = encodeURIComponent(String(query || '').trim());
  const response = await fetch(`${authApiBase()}users/search?query=${q}&limit=${limit}`, {
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
    },
  });
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    let message = text || `用户搜索失败 (${response.status})`;
    try {
      const body = text ? JSON.parse(text) : null;
      if (body?.message) message = String(body.message);
    } catch {
      // keep raw text
    }
    throw new Error(message);
  }
  return response.json();
}

export async function fetchAuthUser(accessToken, userId) {
  const response = await fetch(`${authApiBase()}users/${encodeURIComponent(userId)}`, {
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
    },
  });
  if (!response.ok) return null;
  return response.json();
}

export async function requireAuthUser(req, res, next) {
  const result = await validateToken(extractBearerToken(req));
  if (!result.ok) {
    res.status(result.status).json({ message: result.message });
    return;
  }
  if (!result.email) {
    const me = await fetchAuthMe(result.accessToken);
    if (me?.email) {
      result.email = String(me.email);
      result.isEmailVerified = !!me.isEmailVerified;
      if (!result.username && me.username) result.username = String(me.username);
    }
  }
  req.booxinUser = result;
  next();
}

/** Attach user when Bearer present; otherwise continue anonymously. */
export async function optionalAuthUser(req, _res, next) {
  const token = extractBearerToken(req);
  if (!token) {
    next();
    return;
  }
  const result = await validateToken(token);
  if (result.ok) req.booxinUser = result;
  next();
}

export function isAdminUserId(userId) {
  const ids = adminUserIds();
  if (!ids.size) return false;
  return ids.has(String(userId || '').toLowerCase());
}

/**
 * Password login for admin UI.
 * 1) Local ADMIN_USERNAME / ADMIN_PASSWORD (or ADMIN_SECRET as password)
 * 2) Or Booxin account login + ADMIN_USER_IDS allow-list
 */
export async function adminPasswordLogin(username, password) {
  const u = String(username || '').trim();
  const p = String(password || '');
  if (!u || !p) {
    return { ok: false, status: 400, message: '请输入用户名和密码。' };
  }

  const localUser = localAdminUsername();
  const localPass = localAdminPassword();
  if (localUser && localPass && u === localUser && p === localPass) {
    let accessToken = '';
    let userId = 'local-admin';
    // Prefer dedicated Booxin service account for developer search.
    const searchUser = String(process.env.BOOXIN_AUTH_USERNAME || u).trim();
    const searchPass = String(process.env.BOOXIN_AUTH_PASSWORD || p);
    try {
      const booxin = await loginBooxin(searchUser, searchPass);
      accessToken = booxin.accessToken;
      userId = booxin.userId || userId;
    } catch (err) {
      console.warn('Local admin ok, Booxin login for search skipped:', err?.message || err);
    }
    const session = createAdminSession({
      username: u,
      userId,
      accessToken,
      mode: 'local-password',
    });
    return {
      ok: true,
      session,
      searchReady: !!accessToken,
    };
  }

  try {
    const booxin = await loginBooxin(u, p);
    if (!isAdminUserId(booxin.userId)) {
      return { ok: false, status: 403, message: '该联机账号无管理员权限。' };
    }
    const session = createAdminSession({
      username: booxin.username || u,
      userId: booxin.userId,
      accessToken: booxin.accessToken,
      mode: 'booxin-password',
    });
    return { ok: true, session, searchReady: true };
  } catch (err) {
    // If local admin not configured, surface Booxin error; else generic.
    if (localUser && localPass) {
      return { ok: false, status: 401, message: '用户名或密码错误。' };
    }
    return { ok: false, status: 401, message: err?.message || '登录失败。' };
  }
}

export async function requireAdmin(req, res, next) {
  const bearer = extractBearerToken(req);
  const session = getAdminSession(bearer);
  if (session) {
    req.booxinAdmin = {
      mode: session.mode,
      userId: session.userId,
      username: session.username,
      accessToken: session.accessToken || '',
      sessionId: session.id,
    };
    next();
    return;
  }

  const secret = String(req.header('X-Admin-Secret') || '').trim();
  const expected = adminSecret();
  if (expected && secret && secret === expected) {
    req.booxinAdmin = {
      mode: 'secret',
      userId: 'admin-secret',
      username: 'admin',
      accessToken: bearer || '',
    };
    next();
    return;
  }

  const result = await validateToken(bearer);
  if (!result.ok) {
    if (secret) {
      res.status(401).json({
        message: expected
          ? '登录已失效，请重新用密码登录。'
          : '请先登录管理后台。',
      });
      return;
    }
    res.status(result.status).json({
      message: result.message === '缺少登录令牌。' ? '请先登录管理后台。' : result.message,
    });
    return;
  }
  if (!isAdminUserId(result.userId)) {
    res.status(403).json({ message: '无管理员权限。' });
    return;
  }
  req.booxinAdmin = {
    mode: 'user',
    userId: result.userId,
    username: result.username,
    accessToken: result.accessToken,
  };
  next();
}

export function adminLogout(req) {
  destroyAdminSession(extractBearerToken(req));
}

export const AUTH_API_BASE = authApiBase();
