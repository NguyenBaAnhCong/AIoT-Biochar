import { tv5Pages } from './tv5/core/navigation.js';

const API_ROOT = '/api/v1';
const TOKEN_KEY = 'aiot.accessToken';
const REFRESH_KEY = 'aiot.refreshToken';

const state = {
  accessToken: sessionStorage.getItem(TOKEN_KEY),
  refreshToken: sessionStorage.getItem(REFRESH_KEY),
  user: null,
  page: 'dashboard',
  roleId: null,
  userQuery: '',
  generatedKey: null,
  renderId: 0,
};

const root = document.querySelector('#root');
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (character) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
})[character]);
const dateText = (value) => value ? new Date(value).toLocaleString('vi-VN') : '—';
const numberText = (value, digits = 2) => value == null ? '—' : Number(value).toLocaleString('vi-VN', { maximumFractionDigits: digits });

const pages = [
  ...tv5Pages,
  { id: 'dashboard', title: 'Tổng quan', permission: 'reporting.read', group: 'Vận hành', icon: '◫' },
  { id: 'users', title: 'Người dùng', permission: 'iam.read', group: 'Quản trị', icon: '♙' },
  { id: 'roles', title: 'Vai trò & quyền', permission: 'iam.read', group: 'Quản trị', icon: '◇' },
  { id: 'api-keys', title: 'API keys', permission: 'iam.read', group: 'Quản trị', icon: '⌘' },
  { id: 'audit', title: 'Nhật ký', permission: 'audit.read', group: 'Quản trị', icon: '≡' },
  { id: 'ref', title: 'Danh mục chỉ tiêu', permission: null, group: 'Dữ liệu', icon: '▦' },
  { id: 'system', title: 'Trạng thái hệ thống', permission: 'iam.read', group: 'Hệ thống', icon: '⚙' },
  { id: 'account', title: 'Tài khoản của tôi', permission: null, group: 'Cá nhân', icon: '●' },
];

function clearSession() {
  state.accessToken = null;
  state.refreshToken = null;
  state.user = null;
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(REFRESH_KEY);
}

function setSession(data) {
  state.accessToken = data.accessToken;
  state.refreshToken = data.refreshToken || state.refreshToken;
  state.user = data.user;
  sessionStorage.setItem(TOKEN_KEY, state.accessToken);
  if (state.refreshToken) sessionStorage.setItem(REFRESH_KEY, state.refreshToken);
}

async function api(path, options = {}, mayRefresh = true) {
  const headers = new Headers(options.headers || {});
  headers.set('Accept', 'application/json');
  if (options.body !== undefined) headers.set('Content-Type', 'application/json');
  if (state.accessToken && !options.public) headers.set('Authorization', `Bearer ${state.accessToken}`);

  const response = await fetch(`${API_ROOT}${path}`, {
    method: options.method || 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  const text = await response.text();
  const result = text ? JSON.parse(text) : null;

  if (response.status === 401 && mayRefresh && state.refreshToken
      && path !== '/auth/login' && path !== '/auth/refresh') {
    try {
      const refreshed = await api('/auth/refresh', {
        method: 'POST', body: { refreshToken: state.refreshToken }, public: true,
      }, false);
      state.accessToken = refreshed.data.accessToken;
      state.user = refreshed.data.user;
      sessionStorage.setItem(TOKEN_KEY, state.accessToken);
      return api(path, options, false);
    } catch (error) {
      clearSession();
      renderLogin();
      throw error;
    }
  }

  if (!response.ok) {
    const error = new Error(result?.error?.message || `Yêu cầu thất bại (${response.status})`);
    error.status = response.status;
    error.code = result?.error?.code;
    error.details = result?.error?.details || [];
    throw error;
  }
  return { data: result?.data, meta: result?.meta, status: response.status };
}

function showLoginError(message) {
  const target = document.querySelector('#login-error');
  if (target) {
    target.hidden = !message;
    target.textContent = message || '';
  }
}

function renderLogin() {
  root.innerHTML = `
    <main class="login-page">
      <form class="login-card form" id="login-form">
        <div class="login-brand">
          <span class="brand-mark big" aria-hidden="true">🌱</span>
          <h1>Edge-AIoT Biochar</h1>
          <p class="muted">Giám sát sức khỏe đất và dữ liệu Biochar</p>
        </div>
        <div id="login-error" class="alert alert-error" role="alert" hidden></div>
        <label class="field"><span class="field-label">Tên đăng nhập hoặc email</span>
          <input name="username" autocomplete="username" required autofocus>
        </label>
        <label class="field"><span class="field-label">Mật khẩu</span>
          <input name="password" type="password" autocomplete="current-password" required>
        </label>
        <button class="btn btn-primary" type="submit">Đăng nhập</button>
        <p id="health-message" class="muted small center">Đang kiểm tra kết nối máy chủ…</p>
      </form>
    </main>`;
  api('/health', { public: true }, false)
    .then(({ data }) => {
      const message = document.querySelector('#health-message');
      if (message) message.textContent = `Đã kết nối PostgreSQL · ${data.domain_schemas} schema`;
    })
    .catch((error) => {
      const message = document.querySelector('#health-message');
      if (message) message.textContent = `Backend chưa sẵn sàng: ${error.message}`;
    });
}

function visiblePages() {
  return pages.filter((page) => !page.permission || state.user?.permissions?.includes(page.permission));
}

function startingPage(requested = 'dashboard') {
  const localPages = visiblePages().filter(page => !page.href);
  return (localPages.find(page => page.id === requested) || localPages[0])?.id;
}

function renderShell(content, title = '') {
  const allowed = visiblePages();
  const currentPage = allowed.find((page) => page.id === state.page) || allowed[0];
  if (!currentPage) {
    clearSession();
    renderLogin();
    return;
  }
  state.page = currentPage.id;
  const groups = [...new Set(allowed.map((page) => page.group))];
  const navigation = groups.map((group) => `
    <section class="nav-group">
      <div class="nav-group-title">${escapeHtml(group)}</div>
      ${allowed.filter((page) => page.group === group).map((page) => `
        <a class="nav-item ${page.id === state.page ? 'active' : ''}" href="${page.href || `#${page.id}`}" ${page.href ? '' : `data-page="${page.id}"`}>
          <span aria-hidden="true">${page.icon}</span>${escapeHtml(page.title)}
        </a>`).join('')}
    </section>`).join('');
  const initials = (state.user?.fullName || state.user?.username || '?').slice(0, 1).toUpperCase();
  root.innerHTML = `
    <div class="shell">
      <aside class="sidebar">
        <a class="brand" href="#dashboard" data-page="dashboard">
          <span class="brand-mark" aria-hidden="true">🌱</span>
          <span><strong>Edge-AIoT Biochar</strong><small>Quản lý sức khỏe đất</small></span>
        </a>
        ${navigation}
      </aside>
      <main class="main">
        <header class="topbar">
          <div><strong>${escapeHtml(title || currentPage.title)}</strong></div>
          <div class="user-box">
            <a class="user-link" href="#account" data-page="account">
              <span class="avatar" aria-hidden="true">${escapeHtml(initials)}</span>
              <span>${escapeHtml(state.user?.fullName || state.user?.username)}
                <small>${escapeHtml(state.user?.roles?.join(', ') || 'Người dùng')}</small>
              </span>
            </a>
            <button class="btn btn-sm" type="button" data-action="logout">Đăng xuất</button>
          </div>
        </header>
        <section class="content" id="page-content">${content}</section>
      </main>
    </div>`;
}

function notice(message, kind = 'error') {
  const host = document.querySelector('#notice');
  if (!host) return;
  host.hidden = !message;
  host.className = `alert ${kind === 'success' ? 'alert-ok' : 'alert-error'}`;
  host.textContent = message || '';
}

function loadingPage() {
  return '<div class="spinner-full"><span class="spinner" aria-label="Đang tải"></span></div>';
}

async function navigate(pageId) {
  const page = visiblePages().find((item) => item.id === pageId);
  if (!page) return;
  state.page = page.id;
  const renderId = ++state.renderId;
  renderShell(loadingPage(), page.title);
  try {
    const content = await renderPage(page.id);
    if (renderId === state.renderId) renderShell(content, page.title);
  } catch (error) {
    if (!state.user) {
      renderLogin();
    } else if (renderId === state.renderId) {
      renderShell(`<div id="notice" class="alert alert-error" role="alert">${escapeHtml(error.message)}</div>`, page.title);
    }
  }
}

async function renderPage(pageId) {
  if (pageId === 'dashboard') return renderDashboard();
  if (pageId === 'users') return renderUsers();
  if (pageId === 'roles') return renderRoles();
  if (pageId === 'api-keys') return renderApiKeys();
  if (pageId === 'audit') return renderAudit();
  if (pageId === 'ref') return renderReferenceParameters();
  if (pageId === 'system') return renderSystemStatus();
  if (pageId === 'account') return renderAccount();
  return '<div class="card">Không tìm thấy trang.</div>';
}

async function renderDashboard() {
  const { data } = await api('/dashboard/summary');
  const cards = data.acceptance.kpis.map((kpi) => {
    const stateClass = kpi.passed === true ? 'stat-ok' : kpi.passed === false ? 'stat-bad' : 'stat-warn';
    const stateText = kpi.passed === true ? 'Đạt' : kpi.passed === false ? 'Chưa đạt' : 'Chưa có dữ liệu';
    return `<article class="stat ${stateClass}">
      <div class="stat-label">${escapeHtml(kpi.name_vi)}</div>
      <div class="stat-value">${numberText(kpi.current_value)} <span class="stat-unit">${escapeHtml(kpi.unit)}</span></div>
      <div class="small muted">Mục tiêu ${escapeHtml(kpi.comparator)} ${numberText(kpi.target_value)} · ${stateText}</div>
    </article>`;
  }).join('');
  const plots = data.plots.map((plot) => `<tr>
    <td><strong>${escapeHtml(plot.plot_code)}</strong></td>
    <td>${escapeHtml(plot.site_code || '—')}</td>
    <td>${escapeHtml(plot.node_status || 'Chưa có node')}</td>
    <td>${dateText(plot.last_reading_at)}</td>
    <td>${numberText(plot.ph)} / ${numberText(plot.ec)}</td>
    <td>${numberText(plot.sqi_value, 3)} ${escapeHtml(plot.health_class || '')}</td>
    <td>${numberText(plot.open_alerts, 0)}</td>
  </tr>`).join('');
  return `<div id="notice" hidden></div>
    <div class="page-header"><div><h1>Tổng quan</h1><p class="muted">Cập nhật ${dateText(data.generated_at)}</p></div></div>
    <div class="grid-3">${cards}</div>
    <article class="card">
      <div class="card-head"><h2>Trạng thái các ô thí nghiệm</h2>
        <span class="badge ${data.open_alerts ? 'badge-warn' : 'badge-ok'}">${numberText(data.open_alerts, 0)} cảnh báo mở</span>
      </div>
      <div class="table-wrap"><table class="table"><thead><tr>
        <th>Ô</th><th>Địa điểm</th><th>Node</th><th>Lần đo gần nhất</th><th>pH / EC</th><th>SQI</th><th>Cảnh báo</th>
      </tr></thead><tbody>${plots || '<tr><td class="empty" colspan="7">Chưa có dữ liệu ô thí nghiệm</td></tr>'}</tbody></table></div>
    </article>`;
}

async function renderUsers() {
  const query = new URLSearchParams({ page: '1', limit: '50' });
  if (state.userQuery) query.set('q', state.userQuery);
  const [{ data: users, meta }, { data: roles }, { data: organizations }] = await Promise.all([
    api(`/users?${query}`), api('/roles'), api('/organizations'),
  ]);
  const roleOptions = roles.map((role) => `<option value="${escapeHtml(role.role_code)}" ${role.role_code === 'VIEWER' ? 'selected' : ''}>${escapeHtml(role.role_code)} — ${escapeHtml(role.name)}</option>`).join('');
  const organizationOptions = organizations.map((org) => `<option value="${escapeHtml(org.id)}">${escapeHtml(org.name)} (${escapeHtml(org.code)})</option>`).join('');
  const rows = users.map((user) => `<tr>
    <td><strong>${escapeHtml(user.username)}</strong><div class="small muted">${escapeHtml(user.email)}</div></td>
    <td>${escapeHtml(user.full_name)}</td>
    <td>${user.roles.map((role) => `<span class="badge">${escapeHtml(role)}</span>`).join(' ') || '—'}</td>
    <td><span class="badge ${user.is_active ? 'badge-ok' : 'badge-bad'}">${user.is_active ? 'Hoạt động' : 'Đã khóa'}</span></td>
    <td>${dateText(user.last_login_at)}</td>
    <td><button class="btn btn-sm ${user.is_active ? 'btn-danger' : ''}" data-action="toggle-user" data-id="${escapeHtml(user.id)}" data-active="${user.is_active}">
      ${user.is_active ? 'Khóa' : 'Mở khóa'}</button></td>
  </tr>`).join('');
  return `<div id="notice" hidden></div>
    <div class="page-header"><div><h1>Người dùng</h1><p class="muted">${meta?.total ?? users.length} tài khoản</p></div></div>
    <article class="card">
      <form id="user-filter" class="toolbar">
        <label class="field grow"><span class="field-label">Tìm theo tên, username hoặc email</span>
          <input name="q" value="${escapeHtml(state.userQuery)}" maxlength="100"></label>
        <button class="btn btn-primary" type="submit">Tìm kiếm</button>
      </form>
      <details class="card">
        <summary><strong>Tạo tài khoản</strong></summary>
        <form id="create-user-form" class="form grid-2" style="padding-top:16px">
          <label class="field"><span class="field-label">Username</span><input name="username" required minlength="3" maxlength="64" pattern="[a-zA-Z0-9_.-]+"></label>
          <label class="field"><span class="field-label">Email</span><input name="email" type="email" required maxlength="255"></label>
          <label class="field"><span class="field-label">Họ tên</span><input name="full_name" required maxlength="255"></label>
          <label class="field"><span class="field-label">Mật khẩu ban đầu</span><input name="password" type="password" required minlength="8" maxlength="72" autocomplete="new-password"></label>
          <label class="field"><span class="field-label">Vai trò (có thể chọn nhiều)</span><select name="role_codes" multiple>${roleOptions}</select></label>
          <label class="field"><span class="field-label">Tổ chức (không bắt buộc)</span><select name="organization_id"><option value="">Không gán tổ chức</option>${organizationOptions}</select></label>
          <div><button class="btn btn-primary" type="submit">Tạo người dùng</button></div>
        </form>
      </details>
      <div class="table-wrap"><table class="table"><thead><tr><th>Tài khoản</th><th>Họ tên</th><th>Vai trò</th><th>Trạng thái</th><th>Đăng nhập gần nhất</th><th></th></tr></thead>
      <tbody>${rows || '<tr><td colspan="6" class="empty">Không tìm thấy người dùng</td></tr>'}</tbody></table></div>
    </article>`;
}

async function renderRoles() {
  const [{ data: roles }, { data: permissions }] = await Promise.all([api('/roles'), api('/permissions')]);
  const selected = roles.find((role) => role.id === state.roleId) || roles[0];
  if (!selected) return '<div class="card">Chưa có vai trò.</div>';
  state.roleId = selected.id;
  const { data: detail } = await api(`/roles/${selected.id}`);
  const groups = [...new Set(permissions.map((permission) => permission.domain))];
  const permissionContent = groups.map((domain) => `<section class="perm-group">
    <h3>${escapeHtml(domain)}</h3><div class="perm-grid">
      ${permissions.filter((permission) => permission.domain === domain).map((permission) => {
        const code = permission.permission_code;
        const checked = detail.permissions.some((item) => item.permission_code === code);
        const locked = selected.role_code === 'ADMIN' && ['iam.admin', 'iam.read', 'iam.write'].includes(code);
        return `<label class="${locked ? 'locked' : ''}"><input type="checkbox" name="permission_codes" value="${escapeHtml(code)}" ${checked ? 'checked' : ''} ${locked ? 'disabled' : ''}>
          <span><code>${escapeHtml(code)}</code></span></label>`;
      }).join('')}
    </div></section>`).join('');
  return `<div id="notice" hidden></div><div class="page-header"><div><h1>Vai trò & quyền</h1><p class="muted">Cấu hình quyền hiệu lực theo vai trò.</p></div></div>
    <div class="roles-layout">
      <article class="card"><h2 class="card-head">Vai trò</h2>${roles.map((role) => `<button class="role-item ${role.id === selected.id ? 'active' : ''}" data-action="select-role" data-id="${escapeHtml(role.id)}">
        <strong>${escapeHtml(role.role_code)}</strong><small>${escapeHtml(role.name)} · ${role.active_user_count} người</small></button>`).join('')}</article>
      <article class="card"><div class="card-head"><div><h2>${escapeHtml(selected.name)}</h2><p class="muted">${escapeHtml(selected.description || '')}</p></div></div>
        <form id="permissions-form"><div class="checks-col">${permissionContent}</div>
          ${state.user.permissions.includes('iam.admin') ? '<button class="btn btn-primary" type="submit">Lưu quyền</button>' : ''}
        </form>
      </article>
    </div>`;
}

async function renderApiKeys() {
  const [{ data: keys }, { data: scopes }] = await Promise.all([api('/api-keys'), api('/api-keys/scopes')]);
  const scopeOptions = scopes.map((scope) => `<option value="${escapeHtml(scope)}">${escapeHtml(scope)}</option>`).join('');
  const rows = keys.map((key) => `<tr>
    <td><strong>${escapeHtml(key.label)}</strong><div class="mono muted">${escapeHtml(key.prefix)}…</div></td>
    <td>${key.scopes.map((scope) => `<span class="badge">${escapeHtml(scope)}</span>`).join(' ')}</td>
    <td><span class="badge ${key.status === 'ACTIVE' ? 'badge-ok' : 'badge-bad'}">${escapeHtml(key.status)}</span></td>
    <td>${dateText(key.last_used_at)}</td><td>${dateText(key.expires_at)}</td>
    <td>${key.status === 'ACTIVE' ? `<button class="btn btn-sm btn-danger" data-action="revoke-key" data-id="${escapeHtml(key.id)}">Thu hồi</button>` : ''}</td>
  </tr>`).join('');
  const generated = state.generatedKey ? `<div class="alert alert-ok"><div><strong>API key chỉ hiển thị lần này.</strong><br>
    <code class="secret">${escapeHtml(state.generatedKey)}</code></div>
    <button class="btn btn-sm" data-action="dismiss-key">Đã lưu</button></div>` : '';
  return `<div id="notice" hidden></div><div class="page-header"><div><h1>API keys</h1><p class="muted">Key bí mật chỉ lưu dạng SHA-256 và chỉ hiển thị một lần khi tạo.</p></div></div>
    ${generated}
    <article class="card"><h2 class="card-head">Tạo API key</h2>
      <form id="create-key-form" class="form grid-2">
        <label class="field"><span class="field-label">Nhãn</span><input name="label" required maxlength="128" placeholder="Gateway khu A"></label>
        <label class="field"><span class="field-label">Hết hạn sau (ngày, tùy chọn)</span><input name="expiresInDays" type="number" min="1" max="3650"></label>
        <label class="field"><span class="field-label">Phạm vi</span><select name="scopes" multiple required>${scopeOptions}</select></label>
        <div><button class="btn btn-primary" type="submit">Tạo key</button></div>
      </form>
    </article>
    <article class="card"><h2 class="card-head">Danh sách key</h2><div class="table-wrap"><table class="table">
      <thead><tr><th>Nhãn / tiền tố</th><th>Phạm vi</th><th>Trạng thái</th><th>Dùng gần nhất</th><th>Hết hạn</th><th></th></tr></thead>
      <tbody>${rows || '<tr><td colspan="6" class="empty">Chưa có API key</td></tr>'}</tbody>
    </table></div></article>`;
}

async function renderAudit() {
  const [{ data: summary }, { data: audit }, { data: access }] = await Promise.all([
    api('/audit/summary'), api('/audit/logs?limit=20'), api('/audit/access-logs?limit=20'),
  ]);
  const rows = audit.map((entry) => `<tr><td>${dateText(entry.occurred_at)}</td>
    <td>${escapeHtml(entry.actor_username || entry.actor_type)}</td><td>${escapeHtml(entry.schema_name)}.${escapeHtml(entry.table_name)}</td>
    <td><span class="badge">${escapeHtml(entry.operation)}</span></td><td><code>${escapeHtml(JSON.stringify(entry.record_pk))}</code></td></tr>`).join('');
  const accessRows = access.map((entry) => `<tr><td>${dateText(entry.occurred_at)}</td><td>${escapeHtml(entry.username || entry.api_key_prefix || '—')}</td>
    <td>${escapeHtml(entry.action)}</td><td>${escapeHtml(entry.http_method || '')} ${escapeHtml(entry.resource || '')}</td><td>${entry.status_code ?? '—'}</td></tr>`).join('');
  return `<div id="notice" hidden></div><div class="page-header"><div><h1>Nhật ký</h1><p class="muted">Hoạt động trong 24 giờ: ${summary.window_hours} giờ.</p></div></div>
    <div class="grid-3">
      <article class="stat"><div class="stat-label">Đăng nhập thành công</div><div class="stat-value">${summary.logins}</div></article>
      <article class="stat stat-warn"><div class="stat-label">Đăng nhập thất bại</div><div class="stat-value">${summary.failed_logins}</div></article>
      <article class="stat stat-bad"><div class="stat-label">Yêu cầu bị từ chối</div><div class="stat-value">${summary.denied_calls}</div></article>
    </div>
    <article class="card"><h2 class="card-head">Thay đổi dữ liệu</h2><div class="table-wrap"><table class="table">
      <thead><tr><th>Thời điểm</th><th>Người thực hiện</th><th>Bảng</th><th>Thao tác</th><th>Khóa bản ghi</th></tr></thead>
      <tbody>${rows || '<tr><td colspan="5" class="empty">Chưa có thay đổi</td></tr>'}</tbody></table></div></article>
    <article class="card"><h2 class="card-head">Truy cập gần đây</h2><div class="table-wrap"><table class="table">
      <thead><tr><th>Thời điểm</th><th>Người dùng / key</th><th>Loại</th><th>Endpoint</th><th>HTTP</th></tr></thead>
      <tbody>${accessRows || '<tr><td colspan="5" class="empty">Chưa có truy cập</td></tr>'}</tbody></table></div></article>`;
}

async function renderReferenceParameters() {
  const { data: parameters } = await api('/ref/parameters');
  const rows = parameters.map((item) => `<tr><td><code>${escapeHtml(item.parameter_code)}</code></td>
    <td>${escapeHtml(item.name_vi)}</td><td>${escapeHtml(item.category)}</td><td>${escapeHtml(item.unit)}</td>
    <td>${numberText(item.physical_min)} – ${numberText(item.physical_max)}</td><td>${escapeHtml(item.description || '')}</td></tr>`).join('');
  const canEdit = state.user.permissions.includes('iam.admin');
  return `<div id="notice" hidden></div><div class="page-header"><div><h1>Danh mục chỉ tiêu</h1><p class="muted">Danh mục dùng chung cho cảm biến, phòng thí nghiệm và SQI.</p></div></div>
    ${canEdit ? `<article class="card"><h2 class="card-head">Thêm chỉ tiêu</h2><form id="create-parameter-form" class="form grid-3">
      <label class="field"><span class="field-label">Mã</span><input name="parameter_code" required maxlength="32" pattern="[A-Za-z][A-Za-z0-9_]*"></label>
      <label class="field"><span class="field-label">Tên tiếng Việt</span><input name="name_vi" required maxlength="128"></label>
      <label class="field"><span class="field-label">Đơn vị</span><input name="unit" required maxlength="32"></label>
      <label class="field"><span class="field-label">Nhóm</span><select name="category" required>
        ${['SOIL_CHEM','SOIL_PHYS','SOIL_BIO','BIOCHAR','PLANT','ENV'].map((category) => `<option>${category}</option>`).join('')}</select></label>
      <label class="field"><span class="field-label">Giá trị nhỏ nhất</span><input name="physical_min" type="number" step="any"></label>
      <label class="field"><span class="field-label">Giá trị lớn nhất</span><input name="physical_max" type="number" step="any"></label>
      <div><button class="btn btn-primary" type="submit">Thêm chỉ tiêu</button></div>
    </form></article>` : ''}
    <article class="card"><div class="table-wrap"><table class="table"><thead><tr><th>Mã</th><th>Tên</th><th>Nhóm</th><th>Đơn vị</th><th>Giới hạn vật lý</th><th>Mô tả</th></tr></thead>
    <tbody>${rows || '<tr><td colspan="6" class="empty">Danh mục trống</td></tr>'}</tbody></table></div></article>`;
}

async function renderSystemStatus() {
  const [{ data: status }, { data: health }] = await Promise.all([api('/system/status'), api('/health', { public: true })]);
  const counts = status.counts;
  return `<div id="notice" hidden></div><div class="page-header"><div><h1>Trạng thái hệ thống</h1><p class="muted">Backend Java ${escapeHtml(status.server.java_version)} · ${escapeHtml(status.server.environment)}</p></div></div>
    <div class="grid-3">
      <article class="stat stat-ok"><div class="stat-label">Kết nối cơ sở dữ liệu</div><div class="stat-value">${escapeHtml(health.status)}</div></article>
      <article class="stat"><div class="stat-label">Schema nghiệp vụ</div><div class="stat-value">${status.database.domain_schemas}/16</div></article>
      <article class="stat"><div class="stat-label">Thời gian hoạt động</div><div class="stat-value">${numberText(status.server.uptime_seconds, 0)} <span class="stat-unit">giây</span></div></article>
    </div>
    <article class="card"><h2 class="card-head">Cơ sở dữ liệu</h2><dl class="kv">
      <dt>Tên</dt><dd>${escapeHtml(status.database.name)}</dd><dt>Kích thước</dt><dd>${numberText(Number(status.database.size_bytes) / (1024 * 1024))} MB</dd>
      <dt>Máy chủ</dt><dd>${escapeHtml(status.database.version)}</dd><dt>Thời điểm máy chủ</dt><dd>${dateText(status.database.server_time)}</dd>
    </dl></article>
    <article class="card"><h2 class="card-head">Số liệu trong 24 giờ</h2><dl class="kv">
      <dt>Người dùng</dt><dd>${counts.users} (${counts.active_users} đang hoạt động)</dd>
      <dt>API keys hoạt động</dt><dd>${counts.active_api_keys}</dd><dt>Thay đổi dữ liệu</dt><dd>${counts.changes_24h}</dd>
      <dt>Đăng nhập thất bại</dt><dd>${counts.failed_logins_24h}</dd>
    </dl></article>
    <article class="card"><h2 class="card-head">Migrations đã áp dụng</h2>
      <ul class="steps">${status.migrations.map((migration) => `<li><code>${escapeHtml(migration.filename)}</code> · ${dateText(migration.applied_at)}</li>`).join('') || '<li>Chưa có migration bổ sung.</li>'}</ul>
    </article>`;
}

async function renderAccount() {
  const { data: user } = await api('/auth/me');
  return `<div id="notice" hidden></div><div class="page-header"><div><h1>Tài khoản của tôi</h1><p class="muted">Thông tin cá nhân và bảo mật.</p></div></div>
    <article class="card"><dl class="kv"><dt>Username</dt><dd>${escapeHtml(user.username)}</dd><dt>Email</dt><dd>${escapeHtml(user.email)}</dd>
      <dt>Họ tên</dt><dd>${escapeHtml(user.fullName)}</dd><dt>Vai trò</dt><dd>${escapeHtml(user.roles.join(', '))}</dd>
      <dt>Quyền</dt><dd>${user.permissions.map((permission) => `<span class="badge">${escapeHtml(permission)}</span>`).join(' ')}</dd></dl>
    </article>
    <article class="card"><h2 class="card-head">Đổi mật khẩu</h2><form id="change-password-form" class="form form-inline">
      <label class="field"><span class="field-label">Mật khẩu hiện tại</span><input name="currentPassword" type="password" required autocomplete="current-password"></label>
      <label class="field"><span class="field-label">Mật khẩu mới (ít nhất 8 ký tự, có chữ và số)</span><input name="newPassword" type="password" required minlength="8" maxlength="72" autocomplete="new-password"></label>
      <button class="btn btn-primary" type="submit">Đổi mật khẩu</button>
    </form></article>`;
}

function selectedValues(form, name) {
  return [...form.querySelector(`[name="${name}"]`).selectedOptions].map((option) => option.value);
}

function formValues(form) {
  return Object.fromEntries(new FormData(form).entries());
}

root.addEventListener('click', async (event) => {
  const pageLink = event.target.closest('[data-page]');
  if (pageLink) {
    event.preventDefault();
    const pageId = pageLink.dataset.page;
    if (location.hash !== `#${pageId}`) history.pushState(null, '', `#${pageId}`);
    await navigate(pageId);
    return;
  }

  const button = event.target.closest('[data-action]');
  if (!button) return;
  const action = button.dataset.action;
  try {
    if (action === 'logout') {
      clearSession();
      state.generatedKey = null;
      renderLogin();
    } else if (action === 'toggle-user') {
      const active = button.dataset.active === 'true';
      await api(`/users/${encodeURIComponent(button.dataset.id)}/${active ? 'deactivate' : 'activate'}`, { method: 'POST' });
      await navigate('users');
    } else if (action === 'select-role') {
      state.roleId = button.dataset.id;
      await navigate('roles');
    } else if (action === 'revoke-key') {
      if (window.confirm('Thu hồi API key này? Thiết bị đang sử dụng sẽ không thể xác thực.')) {
        await api(`/api-keys/${encodeURIComponent(button.dataset.id)}/revoke`, { method: 'POST' });
        await navigate('api-keys');
      }
    } else if (action === 'dismiss-key') {
      state.generatedKey = null;
      await navigate('api-keys');
    }
  } catch (error) {
    notice(error.message);
  }
});

window.addEventListener('popstate', () => {
  const pageId = location.hash.slice(1);
  if (pageId && pageId !== state.page) navigate(pageId);
});

root.addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.target;
  try {
    if (form.id === 'login-form') {
      const input = formValues(form);
      const { data } = await api('/auth/login', { method: 'POST', body: input, public: true }, false);
      setSession(data);
      state.page = 'dashboard';
      await navigate(startingPage());
    } else if (form.id === 'user-filter') {
      state.userQuery = String(formValues(form).q || '').trim();
      await navigate('users');
    } else if (form.id === 'create-user-form') {
      const input = formValues(form);
      const body = {
        username: input.username,
        email: input.email,
        full_name: input.full_name,
        password: input.password,
        role_codes: selectedValues(form, 'role_codes'),
      };
      if (input.organization_id) body.organization_id = input.organization_id;
      await api('/users', { method: 'POST', body });
      await navigate('users');
      notice('Đã tạo người dùng.', 'success');
    } else if (form.id === 'permissions-form') {
      const codes = [...form.querySelectorAll('input[name="permission_codes"]:checked')].map((input) => input.value);
      await api(`/roles/${encodeURIComponent(state.roleId)}/permissions`, {
        method: 'PUT', body: { permission_codes: codes },
      });
      await navigate('roles');
      notice('Đã cập nhật quyền.', 'success');
    } else if (form.id === 'create-key-form') {
      const input = formValues(form);
      const expiresInDays = input.expiresInDays ? Number(input.expiresInDays) : null;
      const { data } = await api('/api-keys', {
        method: 'POST',
        body: { label: input.label, scopes: selectedValues(form, 'scopes'), expiresInDays },
      });
      state.generatedKey = data.key;
      await navigate('api-keys');
    } else if (form.id === 'create-parameter-form') {
      const input = formValues(form);
      const body = {
        parameter_code: input.parameter_code,
        name_vi: input.name_vi,
        unit: input.unit,
        category: input.category,
        physical_min: input.physical_min === '' ? null : Number(input.physical_min),
        physical_max: input.physical_max === '' ? null : Number(input.physical_max),
      };
      await api('/ref/parameters', { method: 'POST', body });
      await navigate('ref');
      notice('Đã thêm chỉ tiêu.', 'success');
    } else if (form.id === 'change-password-form') {
      await api('/auth/change-password', { method: 'POST', body: formValues(form) });
      form.reset();
      notice('Đã đổi mật khẩu.', 'success');
    }
  } catch (error) {
    if (form.id === 'login-form') showLoginError(error.message);
    else notice(error.message);
  }
});

async function start() {
  if (!state.accessToken && !state.refreshToken) {
    renderLogin();
    return;
  }
  try {
    const { data } = await api('/auth/me');
    state.user = data;
    const requested = location.hash.slice(1);
    await navigate(startingPage(requested || state.page));
  } catch {
    clearSession();
    renderLogin();
  }
}

start();
