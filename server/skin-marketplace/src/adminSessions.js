import crypto from 'node:crypto';

const sessions = new Map();
const TTL_MS = 7 * 24 * 60 * 60 * 1000;

function prune() {
  const now = Date.now();
  for (const [id, s] of sessions) {
    if (!s?.expiresAt || s.expiresAt <= now) sessions.delete(id);
  }
}

export function createAdminSession({
  username,
  userId = '',
  accessToken = '',
  mode = 'password',
}) {
  prune();
  const id = crypto.randomBytes(32).toString('hex');
  const session = {
    id,
    username: String(username || 'admin'),
    userId: String(userId || ''),
    accessToken: String(accessToken || ''),
    mode,
    createdAt: Date.now(),
    expiresAt: Date.now() + TTL_MS,
  };
  sessions.set(id, session);
  return session;
}

export function getAdminSession(token) {
  if (!token) return null;
  prune();
  const s = sessions.get(String(token));
  if (!s) return null;
  if (s.expiresAt <= Date.now()) {
    sessions.delete(token);
    return null;
  }
  // sliding expiry
  s.expiresAt = Date.now() + TTL_MS;
  return s;
}

export function destroyAdminSession(token) {
  if (!token) return;
  sessions.delete(String(token));
}
