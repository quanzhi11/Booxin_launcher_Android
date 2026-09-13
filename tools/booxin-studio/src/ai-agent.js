/**
 * Booxin Studio AI Agent — talks to https://boonix.art/ai-api with channel=studio.
 * Quotas are daily; UI must never display yuan amounts.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { resolveModel } from './studio-models.js';
import { buildSystemPrompt } from './ai-knowledge.js';
import {
  executeAgentTool,
  parseToolCalls,
  stripToolCalls,
  toolsPromptBlock,
  hasIncompleteToolBlock,
  AGENT_TOOLS,
} from './ai-tools.js';

const CHANNEL = 'studio';
const ACCESS_KEY = 'booxin-launcher-client-v3322';
const BASE_URL = 'https://boonix.art/ai-api';
const AUTH_API = 'https://boonix.art/bbx/api/auth';
const CHAT_PATH = '/api/ai/chat/completions';
const DEFAULT_MODEL_ID = 'deepseek';

function statePath() {
  const dir = path.join(os.homedir(), 'AppData', 'Roaming', 'BooxinStudio');
  fs.mkdirSync(dir, { recursive: true });
  return path.join(dir, 'ai-settings.json');
}

function emptySettings() {
  return {
    userKey: '',
    userId: '',
    username: '',
    accessToken: '',
    selectedModel: DEFAULT_MODEL_ID,
  };
}

export function getAiSettings() {
  try {
    const j = JSON.parse(fs.readFileSync(statePath(), 'utf8'));
    const userId = typeof j.userId === 'string' ? j.userId.trim() : '';
    const username = typeof j.username === 'string' ? j.username.trim() : '';
    const accessToken = typeof j.accessToken === 'string' ? j.accessToken.trim() : '';
    let userKey = typeof j.userKey === 'string' ? j.userKey.trim() : '';
    if (!userKey && userId) userKey = normalizeUserKey(userId);
    const selectedModel =
      typeof j.selectedModel === 'string' && j.selectedModel.trim()
        ? j.selectedModel.trim()
        : DEFAULT_MODEL_ID;
    return {
      userKey,
      userId,
      username,
      accessToken,
      selectedModel,
      loggedIn: !!(userKey && (userId || username)),
    };
  } catch {
    return { ...emptySettings(), loggedIn: false };
  }
}

export function setAiSettings(partial = {}) {
  const cur = getAiSettings();
  const next = {
    userKey: partial.userKey != null ? String(partial.userKey).trim() : cur.userKey,
    userId: partial.userId != null ? String(partial.userId).trim() : cur.userId,
    username: partial.username != null ? String(partial.username).trim() : cur.username,
    accessToken: partial.accessToken != null ? String(partial.accessToken).trim() : cur.accessToken,
    selectedModel:
      partial.selectedModel != null ? String(partial.selectedModel).trim() : cur.selectedModel,
  };
  if (next.userId && !next.userKey) next.userKey = normalizeUserKey(next.userId);
  next.userKey = normalizeUserKey(next.userKey);
  if (!next.selectedModel) next.selectedModel = DEFAULT_MODEL_ID;
  fs.writeFileSync(statePath(), JSON.stringify(next, null, 2), 'utf8');
  return { ...next, loggedIn: !!(next.userKey && (next.userId || next.username)) };
}

export function clearAiSession() {
  const blank = emptySettings();
  fs.writeFileSync(statePath(), JSON.stringify(blank, null, 2), 'utf8');
  return { ...blank, loggedIn: false };
}

/** Normalize UUID / booxin:uuid → booxin:uuid */
export function normalizeUserKey(raw) {
  let key = String(raw || '').trim();
  if (!key) return '';
  if (key.toLowerCase().startsWith('mobile:') || key.toLowerCase().startsWith('studio:')) {
    key = key.replace(/^(mobile|studio):/i, '');
  }
  if (!key.toLowerCase().startsWith('booxin:')) {
    const n = key.replace(/-/g, '').toLowerCase();
    if (/^[0-9a-f]{32}$/.test(n)) key = `booxin:${n}`;
    else if (/^[0-9a-f-]{36}$/i.test(key)) key = `booxin:${key.replace(/-/g, '').toLowerCase()}`;
    else if (/^[0-9a-f]{8,}$/i.test(n)) key = `booxin:${n}`;
  }
  return key;
}

/**
 * Username + password login against Booxin auth API.
 * Stores session locally; password is never saved.
 */
export async function loginWithPassword(username, password) {
  const user = String(username || '').trim();
  const pass = String(password || '');
  if (!user || !pass) throw new Error('请输入用户名和密码');

  const res = await fetch(`${AUTH_API}/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      'User-Agent': 'BooxinStudio/2.0',
    },
    body: JSON.stringify({ username: user, password: pass }),
  });
  const text = await res.text();
  let payload = null;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch {
    /* ignore */
  }
  if (!res.ok) {
    throw new Error(payload?.message || text?.slice(0, 200) || `登录失败 (${res.status})`);
  }
  const accessToken = String(
    payload?.accessToken || payload?.token || payload?.access_token || '',
  ).trim();
  const u = payload?.user || {};
  const userId = String(u.id || payload?.userId || '').trim();
  const uname = String(u.username || user || '').trim();
  if (!accessToken) throw new Error('登录成功但未返回令牌');
  if (!userId) throw new Error('登录成功但未返回用户 ID');

  const settings = setAiSettings({
    accessToken,
    userId,
    username: uname,
    userKey: normalizeUserKey(userId),
  });
  return settings;
}

export async function validateAiSession() {
  const cur = getAiSettings();
  if (!cur.accessToken) return { ...cur, loggedIn: false };
  try {
    const res = await fetch(`${AUTH_API}/validate`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'User-Agent': 'BooxinStudio/2.0',
      },
      body: JSON.stringify({ accessToken: cur.accessToken }),
    });
    if (!res.ok) {
      clearAiSession();
      return { ...emptySettings(), loggedIn: false };
    }
    const payload = await res.json();
    if (!payload?.isValid || !payload.user?.id) {
      clearAiSession();
      return { ...emptySettings(), loggedIn: false };
    }
    return setAiSettings({
      accessToken: cur.accessToken,
      userId: String(payload.user.id),
      username: String(payload.user.username || cur.username || ''),
      userKey: normalizeUserKey(String(payload.user.id)),
    });
  } catch {
    return cur;
  }
}

function authHeaders(extra = {}) {
  return {
    Authorization: `Bearer ${ACCESS_KEY}`,
    'Content-Type': 'application/json',
    'User-Agent': 'BooxinStudio/2.0',
    ...extra,
  };
}

async function apiJson(method, urlPath, body) {
  const url = `${BASE_URL.replace(/\/$/, '')}${urlPath}`;
  const res = await fetch(url, {
    method,
    headers: authHeaders(method === 'GET' ? {} : { 'Content-Type': 'application/json' }),
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = {};
  try {
    json = text ? JSON.parse(text) : {};
  } catch {
    json = { message: text.slice(0, 200) };
  }
  if (!res.ok) {
    const err = new Error(json.message || json.error || `HTTP ${res.status}`);
    err.status = res.status;
    err.payload = json;
    throw err;
  }
  return json;
}

/** Human-readable quota without yuan. */
export function formatQuotaLine(snapshot) {
  if (!snapshot) return '额度未知';
  const used =
    (snapshot.chatCostFen || 0) +
    (snapshot.agentCostFen || 0) +
    (snapshot.playerCostFen || 0);
  const limit = Math.max(0, snapshot.weeklyLimitFen || 0);
  const remainPct = limit > 0 ? Math.max(0, Math.round(100 - (used * 100) / limit)) : 0;
  const tier = snapshot.isMember
    ? tierLabel(snapshot.memberTier)
    : '免费';
  const period = snapshot.periodLabel || '今日';
  return `${period}剩余 ${remainPct}% · ${tier}`;
}

function tierLabel(tier) {
  switch (String(tier || '')) {
    case 'Ultra':
      return 'Atelier';
    case 'ProMax':
      return 'Hearth';
    case 'Pro':
      return 'Shelter';
    default:
      return '免费';
  }
}

export async function getQuotaSnapshot(userKey) {
  const key = normalizeUserKey(userKey || getAiSettings().userKey);
  if (!key) throw new Error('请先登录 Booxin 账号（右侧 Agent 面板）');
  const q = new URLSearchParams({ userKey: key, channel: CHANNEL });
  return apiJson('GET', `/api/ai/quota/snapshot?${q}`);
}

export async function consumeQuota({
  userKey,
  tokens,
  promptTokens,
  completionTokens,
  modelId,
  provider,
}) {
  const key = normalizeUserKey(userKey || getAiSettings().userKey);
  return apiJson('POST', '/api/ai/quota/consume', {
    userKey: key,
    channel: CHANNEL,
    quotaType: 'Agent',
    tokens: tokens || 0,
    promptTokens: promptTokens || 0,
    completionTokens: completionTokens || 0,
    provider: provider || 'DeepSeekV4Pro',
    modelId: modelId || 'deepseek-v4-flash',
  });
}

export async function createSubscription({ userKey, plan, payType }) {
  const key = normalizeUserKey(userKey || getAiSettings().userKey);
  if (!key) throw new Error('请先登录 Booxin 账号');
  return apiJson('POST', '/api/ai/subscription/create', {
    userKey: key,
    plan: plan || 'Pro',
    payType: payType || 'alipay',
    channel: CHANNEL,
  });
}

export async function getSubscriptionStatus({ outTradeNo, userKey }) {
  const key = normalizeUserKey(userKey || getAiSettings().userKey);
  const q = new URLSearchParams({
    outTradeNo: String(outTradeNo || ''),
    userKey: key,
    channel: CHANNEL,
  });
  return apiJson('GET', `/api/ai/subscription/status?${q}`);
}

/**
 * Agent chat: completion + consume quota. Supports multi-turn history.
 * @param {{ message: string, userKey?: string, context?: string, history?: Array<{role:string,content:string}>, model?: string, projectSummary?: string }} opts
 */
export async function agentChat(opts) {
  const prepared = await prepareAgentRequest(opts);
  if (prepared.early) return prepared.early;

  const { key, profile, snap0, body } = prepared;
  const url = `${BASE_URL.replace(/\/$/, '')}${CHAT_PATH}`;
  const res = await fetch(url, {
    method: 'POST',
    headers: authHeaders({ 'X-AI-Provider': profile.providerHeader }),
    body: JSON.stringify({ ...body, stream: false }),
  });
  const text = await res.text();
  let json = {};
  try {
    json = text ? JSON.parse(text) : {};
  } catch {
    throw new Error(`AI 响应无效 (${res.status}): ${text.slice(0, 240)}`);
  }
  if (!res.ok) {
    const detail =
      json.error?.message ||
      json.message ||
      json.error ||
      (typeof json === 'string' ? json : text.slice(0, 240));
    throw new Error(`AI HTTP ${res.status}: ${detail}`);
  }

  const reply =
    json.choices?.[0]?.message?.content?.trim() ||
    json.error?.message ||
    '（无回复）';

  return finalizeAgentReply({
    reply,
    usage: json.usage || {},
    key,
    profile,
    snap0,
  });
}

/**
 * Streaming agent chat. Yields { type:'delta'|'done'|'error', ... }.
 * Falls back to non-stream if upstream rejects stream.
 */
export async function* agentChatStream(opts) {
  const prepared = await prepareAgentRequest(opts);
  if (prepared.early) {
    yield { type: 'done', ...prepared.early };
    return;
  }

  const { key, profile, snap0, body } = prepared;
  const url = `${BASE_URL.replace(/\/$/, '')}${CHAT_PATH}`;

  let res;
  try {
    res = await fetch(url, {
      method: 'POST',
      headers: authHeaders({ 'X-AI-Provider': profile.providerHeader }),
      body: JSON.stringify({ ...body, stream: true }),
    });
  } catch (e) {
    yield { type: 'error', message: e.message || String(e) };
    return;
  }

  const ctype = String(res.headers.get('content-type') || '');
  if (!res.ok) {
    const text = await res.text();
    let detail = text.slice(0, 240);
    try {
      const j = JSON.parse(text);
      detail = j.error?.message || j.message || j.error || detail;
    } catch {
      /* ignore */
    }
    // fallback to non-stream
    try {
      const full = await agentChat(opts);
      for (const ch of typewriterChunks(full.reply || '')) {
        yield { type: 'delta', text: ch };
      }
      yield { type: 'done', ...full };
    } catch (e) {
      yield { type: 'error', message: `AI HTTP ${res.status}: ${detail}` };
    }
    return;
  }

  // Non-SSE JSON body despite stream:true → typewriter locally
  if (!ctype.includes('text/event-stream') && !ctype.includes('octet-stream')) {
    const text = await res.text();
    try {
      const json = JSON.parse(text);
      const reply = json.choices?.[0]?.message?.content || '';
      for (const ch of typewriterChunks(reply)) {
        yield { type: 'delta', text: ch };
      }
      const done = await finalizeAgentReply({
        reply,
        usage: json.usage || {},
        key,
        profile,
        snap0,
      });
      yield { type: 'done', ...done };
      return;
    } catch {
      let reply = '';
      for (const line of text.split('\n')) {
        const delta = parseSseDataLine(line);
        if (!delta || delta === '[DONE]') continue;
        reply += delta;
        yield { type: 'delta', text: delta };
      }
      if (reply.trim()) {
        const done = await finalizeAgentReply({
          reply,
          usage: {},
          key,
          profile,
          snap0,
        });
        yield { type: 'done', ...done };
        return;
      }
      // empty → fall through to non-stream below via reader failure path
    }
  }

  let reply = '';
  let usage = {};
  const reader = res.body?.getReader?.();
  if (!reader) {
    try {
      const full = await agentChat(opts);
      for (const ch of typewriterChunks(full.reply || '')) {
        yield { type: 'delta', text: ch };
      }
      yield { type: 'done', ...full };
    } catch (e) {
      yield { type: 'error', message: e.message || String(e) };
    }
    return;
  }

  const decoder = new TextDecoder('utf-8');
  let buf = '';
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    const lines = buf.split(/\r?\n/);
    buf = lines.pop() || '';
    for (const line of lines) {
      const delta = parseSseDataLine(line);
      if (delta === null) continue;
      if (delta === '[DONE]') continue;
      reply += delta;
      yield { type: 'delta', text: delta };
      if (line.includes('"usage"')) {
        try {
          const payload = JSON.parse(line.replace(/^data:\s*/, ''));
          if (payload.usage) usage = payload.usage;
        } catch {
          /* ignore */
        }
      }
    }
  }

  if (!reply.trim()) {
    try {
      const full = await agentChat(opts);
      for (const ch of typewriterChunks(full.reply || '')) {
        yield { type: 'delta', text: ch };
      }
      yield { type: 'done', ...full };
    } catch (e) {
      yield { type: 'error', message: e.message || String(e) };
    }
    return;
  }

  const done = await finalizeAgentReply({
    reply,
    usage,
    key,
    profile,
    snap0,
  });
  yield { type: 'done', ...done };
}

async function prepareAgentRequest({
  message,
  userKey,
  context,
  history,
  model,
  projectSummary,
  projectRoot,
  enableTools = true,
}) {
  const settings = getAiSettings();
  const key = normalizeUserKey(userKey || settings.userKey);
  if (!key) throw new Error('请先登录 Booxin 账号（右侧 Agent 面板）');
  const trimmed = String(message || '').trim();
  if (!trimmed) throw new Error('请输入问题');

  const snap0 = await getQuotaSnapshot(key);
  const used0 =
    (snap0.chatCostFen || 0) + (snap0.agentCostFen || 0) + (snap0.playerCostFen || 0);
  const limit0 = snap0.weeklyLimitFen || 0;
  if (limit0 > 0 && used0 >= limit0) {
    return {
      early: {
        reply: '今日 Studio AI 额度已用完，请明天再试。',
        quotaError: true,
        quotaLine: formatQuotaLine(snap0),
        snapshot: snap0,
      },
    };
  }

  const profile = resolveModel(model || settings.selectedModel, snap0.memberTier);
  if (profile.id !== (model || settings.selectedModel)) {
    setAiSettings({ selectedModel: profile.id });
  }

  let system = buildSystemPrompt(projectSummary || '');
  if (enableTools) {
    system = `${system}\n\n${toolsPromptBlock()}`;
    if (projectRoot) system += `\n当前项目根目录（工具路径相对此目录）: ${projectRoot}`;
  }
  const userContent = context
    ? `【当前文件上下文】\n${String(context).slice(0, 6000)}\n\n【用户问题】\n${trimmed}`
    : trimmed;

  const messages = [{ role: 'system', content: system.slice(0, 14000) }];
  const prior = Array.isArray(history) ? history : [];
  // Keep recent turns short & clean — TOOL junk / huge pastes cause empty multi-turn replies
  for (const turn of prior.slice(-8)) {
    const role = turn?.role === 'assistant' ? 'assistant' : 'user';
    let content = String(turn?.content || '').trim();
    if (!content) continue;
    content = stripToolCalls(content)
      .replace(/\n{3,}/g, '\n\n')
      .slice(0, 3500);
    if (!content) continue;
    messages.push({ role, content });
  }
  messages.push({ role: 'user', content: userContent.slice(0, 8000) });

  const maxTokens = Math.min(Math.max(800, profile.maxTokens || 2048), 4096);
  // tool rounds need headroom; bump when tools enabled
  const tokenBudget = enableTools ? Math.max(maxTokens, 3200) : maxTokens;
  const body = {
    model: profile.modelId,
    messages,
    temperature: 0.3,
    max_tokens: Math.min(tokenBudget, 4096),
    top_p: 0.9,
  };

  return { key, profile, snap0, body, projectRoot: projectRoot || '' };
}

async function completeOnce(profile, messages, maxTokens) {
  const url = `${BASE_URL.replace(/\/$/, '')}${CHAT_PATH}`;
  const res = await fetch(url, {
    method: 'POST',
    headers: authHeaders({ 'X-AI-Provider': profile.providerHeader }),
    body: JSON.stringify({
      model: profile.modelId,
      messages,
      temperature: 0.3,
      max_tokens: maxTokens,
      top_p: 0.9,
      stream: false,
    }),
  });
  const text = await res.text();
  let json = {};
  try {
    json = text ? JSON.parse(text) : {};
  } catch {
    throw new Error(`AI 响应无效 (${res.status}): ${text.slice(0, 240)}`);
  }
  if (!res.ok) {
    const detail =
      json.error?.message ||
      json.message ||
      json.error ||
      (typeof json === 'string' ? json : text.slice(0, 240));
    throw new Error(`AI HTTP ${res.status}: ${detail}`);
  }
  const reply =
    json.choices?.[0]?.message?.content?.trim() ||
    json.error?.message ||
    '';
  return { reply, usage: json.usage || {} };
}

/**
 * Cursor-style agent loop with tools. Yields status / tool_start / tool_end / delta / done.
 */
export async function* agentChatWithTools(opts) {
  const prepared = await prepareAgentRequest({ ...opts, enableTools: true });
  if (prepared.early) {
    yield { type: 'done', ...prepared.early };
    return;
  }

  const { key, profile, snap0, body, projectRoot } = prepared;
  const messages = body.messages.map((m) => ({ ...m }));
  const maxTokens = body.max_tokens;
  let lastUsage = {};
  const mutated = [];

  for (let round = 0; round < 10; round++) {
    yield {
      type: 'status',
      text: round === 0 ? 'Agent 思考中…' : `继续执行（第 ${round + 1} 轮）…`,
    };

    let reply = '';
    try {
      const once = await completeOnce(profile, messages, maxTokens);
      reply = once.reply || '';
      lastUsage = once.usage || lastUsage;
    } catch (e) {
      yield { type: 'error', message: e.message || String(e) };
      return;
    }

    // Empty model output on multi-turn: nudge once then fail clearly
    if (!String(reply).trim()) {
      yield { type: 'status', text: '模型返回空内容，正在重试…' };
      messages.push({
        role: 'user',
        content: '请直接用简体中文回答上一个问题。不要输出空内容；若需改文件再用 TOOL 块。',
      });
      try {
        const once = await completeOnce(profile, messages, maxTokens);
        reply = once.reply || '';
        lastUsage = once.usage || lastUsage;
      } catch (e) {
        yield { type: 'error', message: e.message || String(e) };
        return;
      }
      if (!String(reply).trim()) {
        yield { type: 'error', message: '模型连续返回空内容，请新开对话或缩短上下文后再试' };
        return;
      }
    }

    const calls = parseToolCalls(reply);
    if (!calls.length) {
      // Truncated TOOL block (common when write_file JSON is too long)
      if (hasIncompleteToolBlock(reply) || /<<<TOOL\b/i.test(reply)) {
        yield {
          type: 'status',
          text: '工具调用被截断，正在改用更稳妥的写入格式重试…',
        };
        messages.push({ role: 'assistant', content: reply.slice(0, 12000) });
        messages.push({
          role: 'user',
          content:
            '你上一条的 <<<TOOL 没有完整结束（缺少 TOOL>>>），或 JSON 被截断了。' +
            '请不要续写半截 JSON。改用 heredoc 格式，一次只写一个短文件：\n' +
            '<<<TOOL\nname: write_file\npath: 相对路径\n---\n文件正文\nTOOL>>>\n' +
            '若 booxin-plugin.json 较长，可先写最小清单，再用 str_replace 追加字段。',
        });
        continue;
      }
      const clean = stripToolCalls(reply) || reply || '（无回复）';
      for (const ch of typewriterChunks(clean)) {
        yield { type: 'delta', text: ch };
      }
      const done = await finalizeAgentReply({
        reply: clean,
        usage: lastUsage,
        key,
        profile,
        snap0,
      });
      yield { type: 'done', ...done, mutated };
      return;
    }

    const prose = stripToolCalls(reply);
    if (prose) {
      yield { type: 'status', text: prose.slice(0, 240) };
    }

    const toolResults = [];
    for (let i = 0; i < calls.length; i++) {
      const call = calls[i];
      const id = `t${round}_${i}`;
      const safeArgs = { ...(call.args || {}) };
      if (typeof safeArgs.content === 'string' && safeArgs.content.length > 120) {
        safeArgs.content = `（正文 ${safeArgs.content.length} 字符）`;
      }
      if (typeof safeArgs.new_string === 'string' && safeArgs.new_string.length > 120) {
        safeArgs.new_string = `（${safeArgs.new_string.length} 字符）`;
      }
      yield {
        type: 'tool_start',
        id,
        name: call.name,
        label: AGENT_TOOLS.find((t) => t.name === call.name)?.label || call.name,
        args: safeArgs,
      };
      const result = executeAgentTool(projectRoot, call.name, call.args);
      if (result.mutate && result.path) mutated.push(result.path);
      yield { type: 'tool_end', id, ...result };
      toolResults.push({
        name: result.name,
        ok: result.ok,
        summary: result.summary,
        detail: String(result.detail || '').slice(0, 6000),
      });
    }

    messages.push({ role: 'assistant', content: reply.slice(0, 10000) });
    messages.push({
      role: 'user',
      content:
        '工具已执行，结果如下（JSON）。若任务未完成请继续调用工具；若已完成请用中文总结，不要再输出 TOOL 块。\n' +
        JSON.stringify(toolResults, null, 2).slice(0, 14000),
    });
  }

  const fallback = '已达到工具轮次上限，请根据已执行步骤继续说明需求。';
  yield { type: 'delta', text: fallback };
  const done = await finalizeAgentReply({
    reply: fallback,
    usage: lastUsage,
    key,
    profile,
    snap0,
  });
  yield { type: 'done', ...done, mutated };
}

async function finalizeAgentReply({ reply, usage, key, profile, snap0 }) {
  const promptTokens = usage.prompt_tokens || 0;
  const completionTokens = usage.completion_tokens || Math.max(1, Math.ceil(String(reply || '').length / 3));
  const total = usage.total_tokens || promptTokens + completionTokens || 200;

  let snapshot = snap0;
  try {
    const consumed = await consumeQuota({
      userKey: key,
      tokens: total,
      promptTokens,
      completionTokens,
      modelId: profile.modelId,
      provider: profile.providerHeader,
    });
    if (consumed?.snapshot) snapshot = consumed.snapshot;
    if (consumed && consumed.success === false) {
      return {
        reply: consumed.message || '额度不足',
        quotaError: true,
        quotaLine: formatQuotaLine(consumed.snapshot || snap0),
        snapshot: consumed.snapshot || snap0,
        model: profile.id,
      };
    }
  } catch {
    /* ignore consume failure after reply */
  }

  return {
    reply,
    quotaError: false,
    quotaLine: formatQuotaLine(snapshot),
    snapshot,
    model: profile.id,
  };
}

function parseSseDataLine(line) {
  const trimmed = String(line || '').trim();
  if (!trimmed.startsWith('data:')) return null;
  const data = trimmed.slice(5).trim();
  if (!data || data === '[DONE]') return data === '[DONE]' ? '[DONE]' : null;
  try {
    const json = JSON.parse(data);
    const delta =
      json.choices?.[0]?.delta?.content ||
      json.choices?.[0]?.message?.content ||
      json.delta ||
      '';
    if (typeof delta === 'string' && delta) return delta;
    return null;
  } catch {
    return data;
  }
}

/** Soft typewriter chunks when upstream is non-streaming. */
function* typewriterChunks(text, size = 8) {
  const s = String(text || '');
  for (let i = 0; i < s.length; i += size) {
    yield s.slice(i, i + size);
  }
}
