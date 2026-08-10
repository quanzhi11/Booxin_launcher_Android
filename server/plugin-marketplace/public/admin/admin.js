const $ = (id) => document.getElementById(id);

/** When behind nginx `/plugin-api/` → strip prefix on proxy_pass; browser still needs prefix. */
function apiBase() {
  const path = location.pathname || '';
  const marker = '/plugin-api';
  const i = path.indexOf(marker);
  if (i >= 0) return path.slice(0, i + marker.length);
  return '';
}

const API_BASE = apiBase();
const TOKEN_KEY = 'booxin_plugin_admin_session';

const state = {
  token: localStorage.getItem(TOKEN_KEY) || '',
  username: '',
  searchReady: false,
};

function headers(json = true) {
  const h = {};
  if (json) h['Content-Type'] = 'application/json';
  if (state.token) h.Authorization = `Bearer ${state.token}`;
  return h;
}

async function api(path, opts = {}) {
  const url = path.startsWith('http') ? path : `${API_BASE}${path}`;
  const res = await fetch(url, {
    ...opts,
    headers: { ...headers(!(opts.body instanceof FormData)), ...(opts.headers || {}) },
  });
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { message: text };
  }
  if (!res.ok) {
    if (res.status === 401) {
      clearSession();
      showLogin(data?.message || '请重新登录');
    }
    throw new Error(data?.message || `HTTP ${res.status}`);
  }
  return data;
}

function clearSession() {
  state.token = '';
  state.username = '';
  state.searchReady = false;
  localStorage.removeItem(TOKEN_KEY);
}

function showLogin(msg = '') {
  $('loginGate').classList.remove('hidden');
  $('appShell').classList.add('hidden');
  if (msg) $('loginMsg').textContent = msg;
}

function showApp() {
  $('loginGate').classList.add('hidden');
  $('appShell').classList.remove('hidden');
  $('authHint').textContent = state.searchReady
    ? `已登录：${state.username}（可搜索开发者）`
    : `已登录：${state.username}`;
}

async function restoreSession() {
  if (!state.token) {
    showLogin();
    return;
  }
  try {
    const me = await api('/api/admin/me');
    state.username = me.username || 'admin';
    state.searchReady = !!me.searchReady;
    showApp();
    await Promise.all([loadPending(), loadPlugins(), loadReports()]);
  } catch {
    clearSession();
    showLogin();
  }
}

$('loginForm').onsubmit = async (e) => {
  e.preventDefault();
  $('loginMsg').textContent = '登录中…';
  try {
    const res = await fetch(`${API_BASE}/api/admin/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({
        username: $('loginUsername').value.trim(),
        password: $('loginPassword').value,
      }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data?.message || `登录失败 (${res.status})`);
    state.token = data.token;
    state.username = data.username || $('loginUsername').value.trim();
    state.searchReady = !!data.searchReady;
    localStorage.setItem(TOKEN_KEY, state.token);
    $('loginPassword').value = '';
    $('loginMsg').textContent = '';
    showApp();
    await Promise.all([loadPending(), loadPlugins(), loadReports()]);
  } catch (err) {
    $('loginMsg').textContent = err.message || '登录失败';
  }
};

$('btnLogout').onclick = async () => {
  try {
    await api('/api/admin/logout', { method: 'POST' });
  } catch {
    // ignore
  }
  clearSession();
  showLogin('已退出');
};

document.querySelectorAll('.tabs button').forEach((btn) => {
  btn.onclick = () => {
    document.querySelectorAll('.tabs button').forEach((b) => b.classList.remove('active'));
    document.querySelectorAll('.panel').forEach((p) => p.classList.remove('active'));
    btn.classList.add('active');
    $(`tab-${btn.dataset.tab}`).classList.add('active');
    if (btn.dataset.tab === 'reports') loadReports().catch(() => {});
  };
});

function esc(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

async function loadReports() {
  const data = await api('/api/admin/reports');
  const root = $('reportList');
  if (!root) return;
  root.innerHTML = '';
  if (!data.items?.length) {
    root.innerHTML = '<p class="muted">暂无评论举报</p>';
    return;
  }
  for (const r of data.items) {
    const el = document.createElement('article');
    el.className = 'card';
    const badge =
      r.status === 'pending' ? 'pending' : r.status === 'actioned' ? 'approved' : 'rejected';
    el.innerHTML = `
      <h3>举报 <span class="badge ${badge}">${esc(r.status)}</span></h3>
      <p class="muted">举报人：${esc(r.reporterUsername)} → 被举报：${esc(r.targetUsername)}</p>
      <p>${esc(r.commentBody || '')}</p>
      <p class="muted">原因：${esc(r.reason || '未填写')} · 等级：${esc(r.actionLevelName || '')} · ${esc(r.category || '')}</p>
      <p class="muted">${esc(r.aiSummary || r.message || '')}</p>
      <p class="muted">${esc(r.createdAt)}</p>
      <div class="row">
        <button type="button" data-id="${esc(r.id)}" data-action="hide" class="btn-report primary">隐藏评论</button>
        <button type="button" data-id="${esc(r.id)}" data-action="restore" class="btn-report">恢复评论</button>
        <button type="button" data-id="${esc(r.id)}" data-action="dismiss" class="btn-report">忽略</button>
      </div>`;
    root.appendChild(el);
  }
  root.querySelectorAll('.btn-report').forEach((btn) => {
    btn.onclick = async () => {
      await api(`/api/admin/reports/${btn.dataset.id}/resolve`, {
        method: 'POST',
        body: JSON.stringify({ action: btn.dataset.action }),
      });
      await loadReports();
    };
  });
}

async function loadPending() {
  const data = await api('/api/admin/applications?status=pending');
  const root = $('pendingList');
  root.innerHTML = '';
  if (!data.items?.length) {
    root.innerHTML = '<p class="muted">暂无待审申请</p>';
    return;
  }
  for (const a of data.items) {
    const el = document.createElement('article');
    el.className = 'card';
    el.innerHTML = `
      <h3>${esc(a.name)} <span class="badge pending">${esc(a.status)}</span></h3>
      <p class="muted">申请人：${esc(a.applicantUsername)} · ${esc(a.applicantEmail || '无邮箱')}</p>
      <p>${esc(a.description || '')}</p>
      <p class="muted">直链：${esc(a.downloadUrl)}</p>
      <p class="muted">提交于 ${esc(a.createdAt)}</p>
      <div class="row">
        <button type="button" data-id="${esc(a.id)}" class="btn-review primary">审核</button>
      </div>`;
    root.appendChild(el);
  }
  root.querySelectorAll('.btn-review').forEach((btn) => {
    btn.onclick = () => openReview(data.items.find((x) => x.id === btn.dataset.id));
  });
}

async function loadPlugins() {
  const data = await api('/api/admin/plugins');
  const root = $('pluginList');
  root.innerHTML = '';
  if (!data.items?.length) {
    root.innerHTML = '<p class="muted">暂无上架插件</p>';
    return;
  }
  for (const p of data.items) {
    const el = document.createElement('article');
    el.className = 'card';
    el.innerHTML = `
      <h3>${esc(p.name)} <span class="badge ${p.enabled === false ? 'rejected' : 'approved'}">${
        p.enabled === false ? '已下架' : '上架中'
      }</span></h3>
      <p class="muted">开发者：${esc(p.developerUsername)} (${esc(p.developerUserId)})</p>
      <p>${esc(p.description || '')}</p>
      <p class="muted">直链：${esc(p.downloadUrl)}</p>
      <div class="row">
        <button type="button" data-id="${esc(p.id)}" class="btn-toggle">${
          p.enabled === false ? '重新上架' : '下架'
        }</button>
        <button type="button" data-id="${esc(p.id)}" class="btn-del danger">删除</button>
      </div>`;
    root.appendChild(el);
  }
  root.querySelectorAll('.btn-toggle').forEach((btn) => {
    btn.onclick = async () => {
      const item = data.items.find((x) => x.id === btn.dataset.id);
      await api(`/api/admin/plugins/${btn.dataset.id}`, {
        method: 'PATCH',
        body: JSON.stringify({ enabled: item.enabled === false }),
      });
      await loadPlugins();
    };
  });
  root.querySelectorAll('.btn-del').forEach((btn) => {
    btn.onclick = async () => {
      if (!confirm('确定删除？')) return;
      await api(`/api/admin/plugins/${btn.dataset.id}`, { method: 'DELETE' });
      await loadPlugins();
    };
  });
}

function bindDevSearch(queryId, resultsId, onPick) {
  return async () => {
    const box = $(resultsId);
    const q = $(queryId).value.trim();
    if (!q) {
      box.innerHTML = '<p class="muted">请输入用户名关键词</p>';
      return;
    }
    box.innerHTML = '<p class="muted">搜索中…</p>';
    try {
      const data = await api(`/api/admin/users/search?query=${encodeURIComponent(q)}`);
      box.innerHTML = '';
      for (const u of data.items || []) {
        const b = document.createElement('button');
        b.type = 'button';
        b.textContent = `${u.username} · ${u.id}${u.email ? ` · ${u.email}` : ''}`;
        b.onclick = () => onPick(u);
        box.appendChild(b);
      }
      if (!data.items?.length) box.innerHTML = '<p class="muted">无结果</p>';
    } catch (err) {
      box.innerHTML = `<p class="muted">${esc(err?.message || '搜索失败')}</p>`;
    }
  };
}

$('btnSearchDev').onclick = bindDevSearch('devQuery', 'devResults', (u) => {
  $('developerUserId').value = u.id;
  $('devSelected').textContent = `已选：${u.username} (${u.id})`;
});

$('btnReviewSearchDev').onclick = bindDevSearch('reviewDevQuery', 'reviewDevResults', (u) => {
  $('reviewDeveloperUserId').value = u.id;
  $('reviewDeveloperUsername').value = u.username || '';
  $('reviewDevSelected').textContent = `已选：${u.username} (${u.id})`;
});

function openReview(app) {
  if (!app) return;
  const dlg = $('reviewDialog');
  dlg.classList.remove('reject-mode');
  $('reviewAppId').value = app.id;
  $('reviewName').value = app.name || '';
  $('reviewDescription').value = app.description || '';
  $('reviewUrl').value = app.downloadUrl || '';
  $('reviewType').value = app.type || 'renderer';
  $('reviewDeveloperUserId').value = app.applicantUserId || '';
  $('reviewDeveloperUsername').value = app.applicantUsername || '';
  $('reviewDevSelected').textContent = app.applicantUserId
    ? `已选：${app.applicantUsername || ''} (${app.applicantUserId})`
    : '未选择';
  $('rejectReason').value = '';
  $('sendEmail').checked = true;
  $('reviewMsg').textContent = '';
  dlg.showModal();
}

$('btnCloseReview').onclick = () => $('reviewDialog').close();

$('btnApprove').onclick = async () => {
  try {
    $('reviewMsg').textContent = '提交中…';
    await api(`/api/admin/applications/${$('reviewAppId').value}/approve`, {
      method: 'POST',
      body: JSON.stringify({
        name: $('reviewName').value.trim(),
        description: $('reviewDescription').value.trim(),
        downloadUrl: $('reviewUrl').value.trim(),
        type: $('reviewType').value,
        developerUserId: $('reviewDeveloperUserId').value.trim(),
        developerUsername: $('reviewDeveloperUsername').value.trim(),
      }),
    });
    $('reviewDialog').close();
    await loadPending();
    await loadPlugins();
  } catch (e) {
    $('reviewMsg').textContent = e.message;
  }
};

$('btnReject').onclick = async () => {
  const dlg = $('reviewDialog');
  if (!dlg.classList.contains('reject-mode')) {
    dlg.classList.add('reject-mode');
    $('reviewMsg').textContent = '请填写拒绝原因，再点一次「拒绝」。';
    return;
  }
  try {
    $('reviewMsg').textContent = '提交中…';
    const data = await api(`/api/admin/applications/${$('reviewAppId').value}/reject`, {
      method: 'POST',
      body: JSON.stringify({
        reason: $('rejectReason').value.trim(),
        sendEmail: $('sendEmail').checked,
      }),
    });
    $('reviewMsg').textContent = data.email?.message || '已拒绝';
    setTimeout(async () => {
      dlg.close();
      await loadPending();
    }, 600);
  } catch (e) {
    $('reviewMsg').textContent = e.message;
  }
};

$('publishForm').onsubmit = async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  try {
    await api('/api/admin/plugins', {
      method: 'POST',
      body: JSON.stringify({
        name: fd.get('name'),
        description: fd.get('description'),
        downloadUrl: fd.get('downloadUrl'),
        type: fd.get('type'),
        developerUserId: fd.get('developerUserId'),
      }),
    });
    alert('已上架');
    e.target.reset();
    $('devSelected').textContent = '未选择开发者';
    await loadPlugins();
  } catch (err) {
    alert(err.message);
  }
};

$('btnRefreshPending').onclick = () => loadPending().catch((e) => alert(e.message));
$('btnRefreshPlugins').onclick = () => loadPlugins().catch((e) => alert(e.message));
$('btnRefreshReports').onclick = () => loadReports().catch((e) => alert(e.message));

restoreSession();
