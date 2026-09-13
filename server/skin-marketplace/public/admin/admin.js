const TOKEN_KEY = 'booxin_skin_admin_token';

/** Behind nginx `/skin-api/` the browser still needs that prefix on API calls. */
function apiBase() {
  const path = location.pathname || '';
  const marker = '/skin-api';
  const i = path.indexOf(marker);
  if (i >= 0) return path.slice(0, i + marker.length);
  return '';
}

const API_BASE = apiBase();

function token() {
  return localStorage.getItem(TOKEN_KEY) || '';
}

function setToken(v) {
  if (v) localStorage.setItem(TOKEN_KEY, v);
  else localStorage.removeItem(TOKEN_KEY);
}

async function api(path, options = {}) {
  const headers = Object.assign({ Accept: 'application/json' }, options.headers || {});
  const t = token();
  if (t) headers.Authorization = `Bearer ${t}`;
  if (options.json) {
    headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(options.json);
  }
  const url = path.startsWith('http') ? path : `${API_BASE}${path}`;
  const res = await fetch(url, { ...options, headers });
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { message: text };
  }
  if (!res.ok) {
    const err = new Error(data?.message || `HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return data;
}

function $(id) {
  return document.getElementById(id);
}

function showLogin(show) {
  $('loginGate').classList.toggle('hidden', !show);
  $('appShell').classList.toggle('hidden', show);
}

function openViewer(title, textureUrl) {
  $('viewerTitle').textContent = title || '3D 预览';
  const url = `../viewer/viewer.html?skin=${encodeURIComponent(textureUrl)}&rotate=1`;
  $('viewerFrame').src = url;
  $('viewerModal').classList.add('open');
}

function closeViewer() {
  $('viewerModal').classList.remove('open');
  $('viewerFrame').src = 'about:blank';
}

function cardHtml(item, bodyExtra = '') {
  return `
    <article class="card review-card">
      <div class="preview-grid">
        <div>
          <img class="skin-thumb" src="${item.textureUrl}" alt="" />
          <button type="button" class="btn-preview" data-url="${item.textureUrl}" data-name="${item.name}">3D 穿戴预览</button>
        </div>
        <div>
          <h3>${escapeHtml(item.name)}</h3>
          <p class="muted">${escapeHtml(item.description || '无简介')} · ${item.model || 'classic'} · v${item.version || '1.0.0'}</p>
          <p class="muted">作者 ${escapeHtml(item.authorUsername || item.applicantUsername || '-')} · ${item.width || '?'}x${item.height || '?'}</p>
          <p class="muted">${escapeHtml(item.originType === 'reprint' ? '转载' : item.originType === 'original' ? '原创' : '来源未标注')}${item.source ? ' · ' + escapeHtml(item.source) : ''} · ${item.allowReprint === false ? '禁止转载' : item.allowReprint === true ? '允许转载' : ''}</p>
          ${bodyExtra}
        </div>
      </div>
    </article>`;
}

function escapeHtml(s) {
  return String(s || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

async function refreshPending() {
  const data = await api('/api/admin/applications?status=pending');
  const root = $('pendingList');
  if (!data.items?.length) {
    root.innerHTML = '<p class="muted">暂无待审申请</p>';
    return;
  }
  root.innerHTML = data.items
    .map(
      (a) =>
        cardHtml(a, `
          <div class="actions">
            <button type="button" class="primary btn-approve" data-id="${a.id}">通过</button>
            <button type="button" class="btn-reject" data-id="${a.id}">拒绝</button>
          </div>`),
    )
    .join('');
}

async function refreshSkins() {
  const data = await api('/api/admin/skins');
  const root = $('skinList');
  if (!data.items?.length) {
    root.innerHTML = '<p class="muted">暂无上架皮肤</p>';
    return;
  }
  root.innerHTML = data.items
    .map(
      (s) =>
        cardHtml(s, `
          <p class="muted">状态 ${s.status} · 下载 ${s.downloadCount || 0} · ★ ${s.ratingAvg} (${s.ratingCount})</p>
          <div class="actions">
            ${
              s.enabled === false || s.status === 'takedown'
                ? `<button type="button" class="btn-restore" data-id="${s.id}">恢复上架</button>`
                : `<button type="button" class="btn-takedown" data-id="${s.id}">下架</button>`
            }
            <button type="button" class="btn-delete" data-id="${s.id}">删除</button>
          </div>`),
    )
    .join('');
}

async function refreshSkinReports() {
  const data = await api('/api/admin/skin-reports?status=pending');
  const root = $('skinReportList');
  if (!data.items?.length) {
    root.innerHTML = '<p class="muted">暂无皮肤举报</p>';
    return;
  }
  root.innerHTML = data.items
    .map(
      (r) => `
      <article class="card">
        <h3>${escapeHtml(r.skinName)}</h3>
        <p class="muted">举报人 ${escapeHtml(r.reporterUsername)} · ${escapeHtml(r.category || '未分类')} · ${escapeHtml(r.reason || '未填补充')}</p>
        <div class="actions">
          <button type="button" class="btn-resolve-skin" data-id="${r.id}">标记已处理</button>
          <button type="button" class="btn-takedown" data-id="${r.skinId}">下架皮肤</button>
        </div>
      </article>`,
    )
    .join('');
}

async function refreshReports() {
  const data = await api('/api/admin/comment-reports?status=pending');
  const root = $('reportList');
  if (!data.items?.length) {
    root.innerHTML = '<p class="muted">暂无评论举报</p>';
    return;
  }
  root.innerHTML = data.items
    .map(
      (r) => `
      <article class="card">
        <h3>${escapeHtml(r.targetUsername || '用户')}</h3>
        <p>${escapeHtml(r.commentBody)}</p>
        <p class="muted">${escapeHtml(r.message || r.aiSummary || '')}</p>
        <button type="button" class="btn-resolve-comment" data-id="${r.id}">标记已处理</button>
      </article>`,
    )
    .join('');
}

async function boot() {
  document.querySelectorAll('.tabs button').forEach((btn) => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.tabs button').forEach((b) => b.classList.remove('active'));
      document.querySelectorAll('.panel').forEach((p) => p.classList.remove('active'));
      btn.classList.add('active');
      $(`tab-${btn.dataset.tab}`).classList.add('active');
    });
  });

  $('loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    $('loginMsg').textContent = '登录中…';
    try {
      const data = await api('/api/admin/login', {
        method: 'POST',
        json: {
          username: $('loginUsername').value,
          password: $('loginPassword').value,
        },
      });
      setToken(data.accessToken);
      $('authHint').textContent = data.username || 'admin';
      showLogin(false);
      await refreshPending();
    } catch (err) {
      $('loginMsg').textContent = err.message || '登录失败';
    }
  });

  $('btnLogout').addEventListener('click', async () => {
    try {
      await api('/api/admin/logout', { method: 'POST' });
    } catch {
      // ignore
    }
    setToken('');
    showLogin(true);
  });

  $('btnRefreshPending').addEventListener('click', () => refreshPending().catch(alert));
  $('btnRefreshSkins').addEventListener('click', () => refreshSkins().catch(alert));
  $('btnRefreshSkinReports').addEventListener('click', () => refreshSkinReports().catch(alert));
  $('btnRefreshReports').addEventListener('click', () => refreshReports().catch(alert));
  $('btnCloseViewer').addEventListener('click', closeViewer);

  document.body.addEventListener('click', async (e) => {
    const t = e.target;
    if (!(t instanceof HTMLElement)) return;
    if (t.classList.contains('btn-preview')) {
      openViewer(t.dataset.name, t.dataset.url);
      return;
    }
    try {
      if (t.classList.contains('btn-approve')) {
        await api(`/api/admin/applications/${t.dataset.id}/approve`, { method: 'POST', json: {} });
        await refreshPending();
        await refreshSkins();
      } else if (t.classList.contains('btn-reject')) {
        const reason = prompt('拒绝原因', '不符合社区规范') || '';
        await api(`/api/admin/applications/${t.dataset.id}/reject`, {
          method: 'POST',
          json: { reason },
        });
        await refreshPending();
      } else if (t.classList.contains('btn-takedown')) {
        await api(`/api/admin/skins/${t.dataset.id}/takedown`, { method: 'POST', json: {} });
        await refreshSkins();
      } else if (t.classList.contains('btn-restore')) {
        await api(`/api/admin/skins/${t.dataset.id}/restore`, { method: 'POST', json: {} });
        await refreshSkins();
      } else if (t.classList.contains('btn-delete')) {
        if (!confirm('确定删除该皮肤？')) return;
        await api(`/api/admin/skins/${t.dataset.id}`, { method: 'DELETE' });
        await refreshSkins();
      } else if (t.classList.contains('btn-resolve-skin')) {
        await api(`/api/admin/skin-reports/${t.dataset.id}/resolve`, {
          method: 'POST',
          json: { status: 'resolved' },
        });
        await refreshSkinReports();
      } else if (t.classList.contains('btn-resolve-comment')) {
        await api(`/api/admin/comment-reports/${t.dataset.id}/resolve`, {
          method: 'POST',
          json: { status: 'resolved' },
        });
        await refreshReports();
      }
    } catch (err) {
      alert(err.message || '操作失败');
    }
  });

  $('publishForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    const fd = new FormData(form);
    $('publishMsg').textContent = '上传中…';
    try {
      const res = await fetch(`${API_BASE}/api/admin/publish`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token()}`, Accept: 'application/json' },
        body: fd,
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || '上架失败');
      $('publishMsg').textContent = `已上架：${data.name}`;
      form.reset();
      await refreshSkins();
    } catch (err) {
      $('publishMsg').textContent = err.message || '上架失败';
    }
  });

  if (!token()) {
    showLogin(true);
    return;
  }
  try {
    const me = await api('/api/admin/me');
    $('authHint').textContent = me.username || 'admin';
    showLogin(false);
    await refreshPending();
  } catch {
    setToken('');
    showLogin(true);
  }
}

boot();
