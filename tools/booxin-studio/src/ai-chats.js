/**
 * Persisted Agent chat sessions under %AppData%/BooxinStudio/ai-chats.json
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';

function chatsPath() {
  const dir = path.join(os.homedir(), 'AppData', 'Roaming', 'BooxinStudio');
  fs.mkdirSync(dir, { recursive: true });
  return path.join(dir, 'ai-chats.json');
}

function readStore() {
  try {
    const j = JSON.parse(fs.readFileSync(chatsPath(), 'utf8'));
    if (!j || typeof j !== 'object') return { activeId: '', chats: [] };
    return {
      activeId: typeof j.activeId === 'string' ? j.activeId : '',
      chats: Array.isArray(j.chats) ? j.chats : [],
    };
  } catch {
    return { activeId: '', chats: [] };
  }
}

function writeStore(store) {
  fs.writeFileSync(chatsPath(), JSON.stringify(store, null, 2), 'utf8');
}

function trimMessages(messages) {
  const list = Array.isArray(messages) ? messages : [];
  return list
    .filter((m) => m && (m.role === 'user' || m.role === 'assistant') && String(m.content || '').trim())
    .slice(-80)
    .map((m) => ({
      role: m.role,
      content: String(m.content).slice(0, 12000),
    }));
}

export function listAiChats() {
  const store = readStore();
  return {
    activeId: store.activeId,
    chats: store.chats
      .map((c) => ({
        id: c.id,
        title: c.title || '未命名对话',
        updatedAt: c.updatedAt || 0,
        messageCount: Array.isArray(c.messages) ? c.messages.length : 0,
        projectPath: c.projectPath || '',
      }))
      .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
      .slice(0, 40),
  };
}

export function getAiChat(id) {
  const store = readStore();
  const chat = store.chats.find((c) => c.id === id);
  if (!chat) throw new Error('对话不存在');
  return { chat, activeId: store.activeId };
}

export function saveAiChat({ id, title, messages, projectPath, setActive = true }) {
  const store = readStore();
  const mid = String(id || '').trim() || crypto.randomBytes(8).toString('hex');
  const msgs = trimMessages(messages);
  const firstUser = msgs.find((m) => m.role === 'user');
  const autoTitle =
    String(title || '').trim() ||
    (firstUser ? firstUser.content.replace(/\s+/g, ' ').slice(0, 36) : '新对话');
  const next = {
    id: mid,
    title: autoTitle,
    messages: msgs,
    projectPath: String(projectPath || ''),
    updatedAt: Date.now(),
  };
  const idx = store.chats.findIndex((c) => c.id === mid);
  if (idx >= 0) store.chats[idx] = { ...store.chats[idx], ...next };
  else store.chats.unshift(next);
  store.chats = store.chats.slice(0, 40);
  if (setActive) store.activeId = mid;
  writeStore(store);
  return { chat: next, activeId: store.activeId };
}

export function deleteAiChat(id) {
  const store = readStore();
  store.chats = store.chats.filter((c) => c.id !== id);
  if (store.activeId === id) store.activeId = store.chats[0]?.id || '';
  writeStore(store);
  return listAiChats();
}

export function clearAiChatMessages(id) {
  const store = readStore();
  const chat = store.chats.find((c) => c.id === id);
  if (!chat) {
    return saveAiChat({ id: '', title: '新对话', messages: [], setActive: true });
  }
  chat.messages = [];
  chat.updatedAt = Date.now();
  writeStore(store);
  return { chat, activeId: store.activeId };
}

export function newAiChat(projectPath = '') {
  return saveAiChat({
    id: '',
    title: '新对话',
    messages: [],
    projectPath,
    setActive: true,
  });
}
