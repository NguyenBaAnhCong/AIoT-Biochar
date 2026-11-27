const esc = (helpers, value) => helpers.escapeHtml(value ?? '—');
const fmt = (helpers, value) => helpers.numberText(value, 2);
const pageMeta = (meta) => `<small class="muted">Trang ${meta?.page || 1} · ${meta?.total || 0} mục</small>`;
const empty = (columns, text) => `<tr><td class="empty" colspan="${columns}">${text}</td></tr>`;

export const tv4Pages = [
  { id: 'quality', title: '08 · Data Quality & Audit', permission: 'data_quality.read', group: 'Dữ liệu & AI', icon: '✓' },
  { id: 'datasets', title: '09 · Dataset & Labeling', permission: 'dataset.read', group: 'Dữ liệu & AI', icon: '▤' },
];

async function qualityPage(h) {
  const canWrite = h.user?.permissions?.includes('data_quality.write');
  const issueQuery = new URLSearchParams(location.hash.split('?')[1] || '');
  const selectedType = issueQuery.get('issue_type') || '';
  const selectedStatus = issueQuery.get('status') || 'OPEN';
  const issuePage = Number(issueQuery.get('page') || 1);
  const issueSensor = issueQuery.get('sensor_id') || '';
  const issuePlot = issueQuery.get('plot_id') || '';
  const issueFrom = issueQuery.get('from') || '';
  const issueTo = issueQuery.get('to') || '';
  const [{ data: stats }, { data: rules }, { data: issues, meta }, { data: checks }, { data: audit }] = await Promise.all([
    h.api('/tv4/quality/statistics'), h.api('/tv4/quality/rules'),
    h.api(`/tv4/quality/issues?page=${issuePage}&limit=20${selectedType ? `&issue_type=${encodeURIComponent(selectedType)}` : ''}&status=${encodeURIComponent(selectedStatus)}${issueSensor?`&sensor_id=${encodeURIComponent(issueSensor)}`:''}${issuePlot?`&plot_id=${encodeURIComponent(issuePlot)}`:''}${issueFrom?`&from=${encodeURIComponent(issueFrom)}`:''}${issueTo?`&to=${encodeURIComponent(issueTo)}`:''}`),
    h.api('/tv4/quality/checks?page=1&limit=10'), h.api('/tv4/quality/audit?page=1&limit=10'),
  ]);
  const s = stats.summary;
  const cards = [
    ['Tổng observations', s.total_observations, ''],
    ['Hợp lệ', s.valid_observations, 'stat-ok'],
    ['Invalid', s.invalid_observations, 'stat-bad'],
    ['Missing', s.missing_issues, 'stat-warn'],
    ['Duplicate', s.duplicate_issues, 'stat-warn'],
    ['Tỷ lệ lỗi/trùng', `${fmt(h, s.error_duplicate_pct)}%`, s.error_duplicate_ok ? 'stat-ok' : 'stat-bad'],
    ['Missing sau xử lý', `${fmt(h, s.missing_after_processing_pct)}%`, s.missing_ok ? 'stat-ok' : 'stat-bad'],
    ['Quan sát hợp lệ trong dataset', s.dataset_valid_observations, s.observations_ok ? 'stat-ok' : 'stat-warn'],
  ].map(([label, value, css]) => `<article class="stat ${css}"><div class="stat-label">${label}</div><div class="stat-value">${esc(h, value)}</div></article>`).join('');
  const days = stats.by_day.map((day) => `<tr><td>${h.dateText(day.day)}</td><td>${day.observations}</td><td>${day.valid}</td><td>${day.invalid}</td><td>${day.missing}</td><td>${day.duplicates}</td><td>${day.issues}</td></tr>`).join('');
  const ruleRows = rules.rules.map((r) => `<tr><td><code>${esc(h,r.rule_code)}</code><div>${esc(h,r.name)}</div></td><td>${esc(h,r.target)} · ${esc(h,r.rule_type)}</td><td>${esc(h,r.parameter_code)}</td><td>${esc(h,r.severity)}</td><td><span class="badge ${r.is_active?'badge-ok':'badge-warn'}">${r.is_active?'Bật':'Tắt'}</span></td><td>${canWrite?`<div class="tv4-actions"><button class="btn btn-sm" data-action="tv4-rule-edit" data-id="${esc(h,r.rule_id)}" data-code="${esc(h,r.rule_code)}" data-name="${esc(h,r.name)}" data-target="${esc(h,r.target)}" data-type="${esc(h,r.rule_type)}" data-parameter="${esc(h,r.parameter_code||'')}" data-min="${esc(h,r.min_value??'')}" data-max="${esc(h,r.max_value??'')}" data-delta="${esc(h,r.max_delta??'')}" data-window="${esc(h,r.window_minutes??'')}" data-severity="${esc(h,r.severity)}" data-active="${r.is_active}">Sửa</button><button class="btn btn-sm" data-action="tv4-rule-toggle" data-id="${esc(h,r.rule_id)}" data-active="${r.is_active}">${r.is_active?'Tắt':'Bật'}</button></div>`:'—'}</td></tr>`).join('');
  const issueRows = issues.map((i) => `<tr><td>${h.dateText(i.observed_at)}</td><td><code>${esc(h,i.sensor_code || i.sensor_id)}</code><div>${esc(h,i.plot_code)}</div></td><td>${esc(h,i.issue_type)}</td><td>${esc(h,i.parameter_code)} · ${esc(h,i.observed_value ?? i.details)}</td><td><span class="badge ${i.severity==='ERROR'?'badge-bad':'badge-warn'}">${esc(h,i.severity)}</span> ${esc(h,i.status)}</td><td><div class="tv4-actions">${canWrite&&i.status==='OPEN'?`<button class="btn btn-sm" data-action="tv4-confirm" data-id="${i.issue_id}">Xác nhận</button><button class="btn btn-sm btn-danger" data-action="tv4-ignore" data-id="${i.issue_id}">Bỏ qua</button>`:''}${canWrite&&['OPEN','CONFIRMED'].includes(i.status)&&i.source_record_id?.match(/^\d+$/)?`<button class="btn btn-sm" data-action="tv4-correct" data-id="${i.issue_id}" data-parameter="${esc(h,i.parameter_code)}">Sửa</button>`:''}</div></td></tr>`).join('');
  const checkRows = checks.map((c) => `<tr><td>${h.dateText(c.started_at)}</td><td>${h.dateText(c.scope_from)} – ${h.dateText(c.scope_to)}</td><td>${c.records_checked}</td><td>${c.records_valid}</td><td>${c.records_invalid}</td><td>${fmt(h,c.pass_rate_pct)}%</td><td>${esc(h,c.status)}</td></tr>`).join('');
  const auditRows = audit.map((a) => `<tr><td>${h.dateText(a.occurred_at)}</td><td>${esc(h,a.action)}</td><td>${esc(h,a.source_table)} · ${esc(h,a.record_id)}</td><td>${esc(h,a.old_value)}</td><td>${esc(h,a.new_value)}</td><td>${esc(h,a.reason)}</td><td>${esc(h,a.actor_username || a.actor_type)}</td></tr>`).join('');
  const series = stats.by_day.map(d=>Number(d.invalid)+Number(d.missing)+Number(d.duplicates)).slice(-14);
  const max = Math.max(1,...series);
  const chart = series.map((v,i)=>`<div class="tv4-bar" title="${v} issue" style="height:${Math.max(3,Math.round(100*v/max))}%"><span>${v}</span></div>`).join('');
  return `<div id="notice" hidden></div><div class="page-header"><div><h1>08 · Data Quality & Audit</h1><p class="muted">QC trên sensor readings, thống kê theo dữ liệu PostgreSQL và audit lineage.</p></div>${canWrite?'<button class="btn btn-primary" data-action="tv4-run-qc">Chạy QC dữ liệu</button>':''}</div>
    <div class="grid-3 tv4-kpis">${cards}</div>
    <article class="card"><h2 class="card-head">Xu hướng lỗi theo ngày</h2><div class="tv4-chart">${chart || '<span class="muted">Chưa có dữ liệu theo ngày</span>'}</div></article>
    <article class="card"><div class="card-head"><h2>Danh sách issue</h2>${pageMeta(meta)}</div><form id="tv4-issue-filter" class="toolbar"><select name="issue_type"><option value="" ${selectedType===''?'selected':''}>Tất cả loại</option>${rules.issue_types.map(x=>`<option ${selectedType===x?'selected':''}>${x}</option>`).join('')}</select><select name="status">${rules.statuses.map(x=>`<option ${selectedStatus===x?'selected':''}>${x}</option>`).join('')}</select><input name="sensor_id" value="${esc(h,issueSensor)}" placeholder="Mã sensor UUID"><input name="plot_id" value="${esc(h,issuePlot)}" placeholder="Mã plot UUID"><label class="field"><span class="field-label">Từ ngày</span><input name="from_date" type="date" value="${esc(h,issueFrom.slice(0,10))}"></label><label class="field"><span class="field-label">Đến ngày</span><input name="to_date" type="date" value="${esc(h,issueTo.slice(0,10))}"></label><button class="btn" type="submit">Lọc</button></form><div class="table-wrap"><table class="table"><thead><tr><th>Thời gian</th><th>Sensor / plot</th><th>Loại</th><th>Giá trị</th><th>Mức độ / trạng thái</th><th>Thao tác</th></tr></thead><tbody>${issueRows || empty(6,'Không có issue phù hợp')}</tbody></table></div><div class="tv4-actions"><button class="btn btn-sm" data-action="tv4-issue-page" data-page="${Math.max(1,issuePage-1)}" ${issuePage<=1?'disabled':''}>Trang trước</button><button class="btn btn-sm" data-action="tv4-issue-page" data-page="${issuePage+1}" ${issuePage*20>=meta.total?'disabled':''}>Trang sau</button></div></article>
    <article class="card"><div class="card-head"><h2>Quy tắc QC</h2>${canWrite?'<button class="btn btn-sm" data-action="tv4-new-rule">Tạo rule</button>':''}</div><div class="table-wrap"><table class="table"><thead><tr><th>Rule</th><th>Loại</th><th>Chỉ tiêu</th><th>Mức độ</th><th>Trạng thái</th><th></th></tr></thead><tbody>${ruleRows || empty(6,'Chưa cấu hình rule')}</tbody></table></div></article>
    <article class="card"><h2 class="card-head">Lịch sử chạy QC</h2><div class="table-wrap"><table class="table"><thead><tr><th>Bắt đầu</th><th>Khoảng kiểm tra</th><th>Kiểm tra</th><th>Hợp lệ</th><th>Invalid</th><th>Pass rate</th><th>Trạng thái</th></tr></thead><tbody>${checkRows || empty(7,'Chưa chạy QC')}</tbody></table></div></article>
    <article class="card"><h2 class="card-head">Audit trail</h2><div class="table-wrap"><table class="table"><thead><tr><th>Thời điểm</th><th>Thao tác</th><th>Bản ghi</th><th>Giá trị cũ</th><th>Giá trị mới</th><th>Lý do</th><th>Người thực hiện</th></tr></thead><tbody>${auditRows || empty(7,'Chưa có audit TV4')}</tbody></table></div></article>`;
}

async function datasetPage(h) {
  const canWrite = h.user?.permissions?.includes('dataset.write');
  const { data: datasets } = await h.api('/tv4/datasets');
  const rows = datasets.map((d) => `<tr><td><button class="tv4-link" data-action="tv4-select-dataset" data-id="${d.dataset_id}">${esc(h,d.dataset_code)} · ${esc(h,d.name)}</button><div class="small muted">Tạo ${h.dateText(d.created_at)}</div></td><td>${esc(h,d.status || 'Chưa có version')} ${d.version_no ? `· v${d.version_no}` : ''}</td><td>${d.record_count ?? 0}</td><td>${d.valid_count ?? 0}</td><td>${d.train_count ?? 0} / ${d.validation_count ?? 0} / ${d.test_count ?? 0}</td><td>${d.leaking_groups ?? 0}</td></tr>`).join('');
  let detail = '<p class="muted">Chọn dataset trong danh sách để xem version, readiness, phân bố label và quan sát.</p>';
  const selected = h.state.selectedDataset || datasets[0]?.dataset_id;
  if (selected) {
    const [{data: info},{data: versions}] = await Promise.all([h.api(`/tv4/datasets/${selected}`),h.api(`/tv4/datasets/${selected}/versions`)]);
    const version = versions.find(item => item.dataset_version_id === h.state.selectedVersion) || versions[0];
    const labels = version ? await h.api(`/tv4/datasets/labels?version_id=${version.dataset_version_id}`) : {data:{labels:[],distribution:[]}};
    const recordsPage = h.state.datasetRecordsPage || 1;
    const records = version ? await h.api(`/tv4/datasets/${selected}/versions/${version.dataset_version_id}/records?page=${recordsPage}&limit=20`) : {data:[],meta:{total:0}};
    const readiness = version ? `<div class="grid-3"><div class="stat"><div class="stat-label">Tổng quan sát</div><div class="stat-value">${version.records ?? 0}</div></div><div class="stat ${version.meets_min_observations?'stat-ok':'stat-warn'}"><div class="stat-label">Hợp lệ / yêu cầu</div><div class="stat-value">${version.valid_records ?? 0} / ${version.min_valid_observations ?? info.dataset.min_valid_observations}</div></div><div class="stat ${version.leaking_groups?'stat-bad':'stat-ok'}"><div class="stat-label">Leakage</div><div class="stat-value">${version.leaking_groups ?? 0} nhóm</div></div></div><p><strong>Train / Validation / Test:</strong> ${version.train ?? 0} / ${version.val ?? 0} / ${version.test ?? 0} · <strong>Sẵn sàng:</strong> ${version.meets_min_observations?'Đủ số lượng':'Chưa đủ số lượng'} · <strong>Trạng thái:</strong> ${esc(h,version.status)}</p>
      ${canWrite?`<div class="tv4-actions"><button class="btn btn-primary" data-action="tv4-build" data-dataset="${selected}" data-version="${version.dataset_version_id}" ${!['DRAFT','BUILT'].includes(version.status)?'disabled':''}>Build</button><button class="btn" data-action="tv4-freeze" data-dataset="${selected}" data-version="${version.dataset_version_id}" ${version.status!=='BUILT'||!version.meets_min_observations||version.leaking_groups?'disabled':''}>Freeze</button><button class="btn" data-action="tv4-new-version" data-dataset="${selected}">Tạo version</button></div>`:''}<div class="tv4-actions"><button class="btn" data-action="tv4-export" data-dataset="${selected}" data-version="${version.dataset_version_id}" ${version.status!=='FROZEN'?'disabled':''}>Tải CSV</button></div>` : '<p>Dataset chưa có version.</p>';
    const labelRows = labels.data.distribution.map(x=>`<tr><td>${esc(h,x.label_code)} · ${esc(h,x.name_vi)}</td><td>${x.count}</td><td>${x.percentage}%</td></tr>`).join('');
    const recordRows = records.data.map(r=>`<tr><td>${h.dateText(r.observed_at)}</td><td>${esc(h,r.plot_code)}</td><td>${fmt(h,r.ph)}</td><td>${fmt(h,r.ec)}</td><td>${fmt(h,r.soil_moisture)}</td><td>${fmt(h,r.soil_temperature)}</td><td>${esc(h,r.split)}</td><td>${esc(h,r.label_code||'Chưa gán')}</td><td>${r.valid_flag?'Hợp lệ':'Không hợp lệ'}</td><td>${canWrite&&version.status==='BUILT'&&r.valid_flag?`<button class="btn btn-sm" data-action="tv4-label" data-dataset="${selected}" data-version="${version.dataset_version_id}" data-record="${r.record_id}">Gán nhãn</button>`:''}</td></tr>`).join('');
    detail = `<p class="muted">${esc(h,info.dataset.dataset_code)} · ${esc(h,info.dataset.purpose)} · Ngưỡng hợp lệ ${info.dataset.min_valid_observations}</p><form id="tv4-version-select" class="toolbar"><label class="field"><span class="field-label">Dataset version</span><select name="version_id">${versions.map(v=>`<option value="${v.dataset_version_id}" ${v.dataset_version_id===version?.dataset_version_id?'selected':''}>v${v.version_no} · ${esc(h,v.status)} · ${v.valid_records??0} hợp lệ</option>`).join('')}</select></label><button class="btn" type="submit">Xem version</button></form>${readiness}<div class="grid-2"><article class="card"><h3>Phân bố nhãn</h3><div class="table-wrap"><table class="table"><thead><tr><th>Nhãn</th><th>Số lượng</th><th>Tỷ lệ</th></tr></thead><tbody>${labelRows||empty(3,'Chưa có nhãn')}</tbody></table></div></article>${canWrite?`<article class="card"><h3>Tạo version</h3><form id="tv4-version-form" data-dataset="${selected}" class="form grid-2"><label class="field"><span class="field-label">Từ</span><input name="timeFrom" type="datetime-local" required></label><label class="field"><span class="field-label">Đến</span><input name="timeTo" type="datetime-local" required></label><label class="field"><span class="field-label">Tổng hợp</span><select name="aggregation"><option>RAW</option><option>HOURLY</option><option>DAILY</option></select></label><label class="field"><span class="field-label">Chia tập</span><select name="splitStrategy"><option>BY_TIME</option><option>BY_PLOT</option><option>BY_BLOCK</option><option>RANDOM</option></select></label><button class="btn btn-primary" type="submit">Tạo version</button></form></article>`:''}</div><article class="card"><h3>Observations ${version?pageMeta(records.meta):''}</h3><div class="table-wrap"><table class="table"><thead><tr><th>Thời gian</th><th>Plot</th><th>pH</th><th>EC</th><th>Ẩm</th><th>Nhiệt</th><th>Split</th><th>Label</th><th>QC</th><th></th></tr></thead><tbody>${recordRows||empty(10,'Version chưa build hoặc chưa có observations')}</tbody></table></div><div class="tv4-actions"><button class="btn btn-sm" data-action="tv4-record-page" data-page="${Math.max(1,recordsPage-1)}" ${recordsPage<=1?'disabled':''}>Trang trước</button><button class="btn btn-sm" data-action="tv4-record-page" data-page="${recordsPage+1}" ${recordsPage*20>=records.meta.total?'disabled':''}>Trang sau</button></div></article>`;
    if (versions.length === 0 && canWrite) {
      detail += `<article class="card"><h3>Tạo dataset version</h3><form id="tv4-version-form" data-dataset="${selected}" class="form grid-2"><label class="field"><span class="field-label">Từ</span><input name="timeFrom" type="datetime-local" required></label><label class="field"><span class="field-label">Đến</span><input name="timeTo" type="datetime-local" required></label><label class="field"><span class="field-label">Tổng hợp</span><select name="aggregation"><option>RAW</option><option>HOURLY</option><option>DAILY</option></select></label><label class="field"><span class="field-label">Chia tập</span><select name="splitStrategy"><option>BY_TIME</option><option>BY_PLOT</option><option>BY_BLOCK</option><option>RANDOM</option></select></label><button class="btn btn-primary" type="submit">Tạo version</button></form></article>`;
    }
  }
  return `<div id="notice" hidden></div><div class="page-header"><div><h1>09 · Dataset & Labeling</h1><p class="muted">Tạo version từ dữ liệu đã QC; nhóm split được giữ nguyên để hạn chế leakage.</p></div>${canWrite?'<button class="btn btn-primary" data-action="tv4-new-dataset">Tạo dataset</button>':''}</div><article class="card"><h2 class="card-head">Dataset</h2><div class="table-wrap"><table class="table"><thead><tr><th>Dataset</th><th>Version</th><th>Tổng</th><th>Hợp lệ</th><th>Train / Val / Test</th><th>Leakage</th></tr></thead><tbody>${rows||empty(6,'Chưa có dataset')}</tbody></table></div></article><article class="card"><h2 class="card-head">Chi tiết và readiness</h2>${detail}</article>`;
}

export async function renderTv4Page(page, helpers) {
  return page === 'quality' ? qualityPage(helpers) : datasetPage(helpers);
}

function reasonDialog(title, label = 'Lý do') {
  const reason = window.prompt(`${title}\n${label}:`);
  return reason?.trim() || null;
}

export async function handleTv4Action(action, button, h) {
  const id = encodeURIComponent(button.dataset.id || '');
  if (action === 'tv4-run-qc') {
    const {data:stats}=await h.api('/tv4/quality/statistics');
    const to = new Date(stats.summary.date_to); const from = new Date(stats.summary.date_from);
    if (!Number.isFinite(from.getTime()) || !Number.isFinite(to.getTime()) || from >= to) throw new Error('Không có khoảng dữ liệu hợp lệ để chạy QC.');
    await h.api('/tv4/quality/checks/run',{method:'POST',body:{from:from.toISOString(),to:to.toISOString()}});
    await h.navigate('quality'); h.notice('Đã chạy QC và kiểm tra slot dữ liệu thiếu.','success'); return true;
  }
  if (action === 'tv4-issue-page') {
    const query=new URLSearchParams(location.hash.split('?')[1]||'');query.set('page',button.dataset.page);location.hash=`quality?${query}`;await h.navigate('quality');return true;
  }
  if (action === 'tv4-record-page') { h.state.datasetRecordsPage=Number(button.dataset.page);await h.navigate('datasets');return true; }
  if (action === 'tv4-rule-toggle') {
    await h.api(`/tv4/quality/rules/${id}/active`,{method:'PATCH',body:{active:button.dataset.active!=='true'}});
    await h.navigate('quality'); h.notice('Đã cập nhật trạng thái rule.','success'); return true;
  }
  if (action === 'tv4-rule-edit') {
    const value=(name, fallback='')=>window.prompt(`${name}:`,button.dataset[fallback||name]||'');
    const name=value('Tên rule','name');if(name===null)return true;
    const target=button.dataset.target;const ruleType=button.dataset.type;
    const parameterCode=value('Mã chỉ tiêu','parameter')||null;
    const minInput=button.dataset.min!==''?value('Giá trị nhỏ nhất','min'):'';
    const maxInput=button.dataset.max!==''?value('Giá trị lớn nhất','max'):'';
    const deltaInput=button.dataset.delta!==''?value('Mức thay đổi tối đa','delta'):'';
    const windowInput=button.dataset.window!==''?value('Cửa sổ phút','window'):'';
    const minValue=minInput===''?null:Number(minInput),maxValue=maxInput===''?null:Number(maxInput);
    const maxDelta=deltaInput===''?null:Number(deltaInput),windowMinutes=windowInput===''?null:Number(windowInput);
    const severity=button.dataset.severity;
    await h.api(`/tv4/quality/rules/${id}`,{method:'PUT',body:{ruleCode:button.dataset.code,name,target,ruleType,parameterCode,minValue,maxValue,maxDelta,windowMinutes,severity,description:'Cập nhật từ màn hình TV4'}});
    await h.navigate('quality');h.notice('Đã cập nhật rule QC.','success');return true;
  }
  if (['tv4-confirm','tv4-ignore'].includes(action)) {
    const reason=reasonDialog(action==='tv4-confirm'?'Xác nhận issue':'Bỏ qua issue'); if(!reason)return true;
    await h.api(`/tv4/quality/issues/${id}/${action==='tv4-confirm'?'confirm':'ignore'}`,{method:'POST',body:{reason}});
    await h.navigate('quality'); h.notice('Đã cập nhật issue và audit.','success'); return true;
  }
  if (action === 'tv4-correct') {
    const value=window.prompt('Nhập giá trị đã hiệu chỉnh:'); if(value===null||!Number.isFinite(Number(value)))return true;
    const reason=reasonDialog('Sửa giá trị cảm biến');if(!reason)return true;
    await h.api(`/tv4/quality/issues/${id}/correct`,{method:'POST',body:{parameterCode:button.dataset.parameter,value:Number(value),reason}});
    await h.navigate('quality');h.notice('Đã sửa dữ liệu và lưu audit.','success');return true;
  }
  if (action === 'tv4-new-rule') {
    const ruleCode=window.prompt('Mã rule (ví dụ PH_RANGE):');if(!ruleCode)return true;
    const name=window.prompt('Tên rule:');if(!name)return true;
    const parameterCode=window.prompt('Mã chỉ tiêu (PH, EC, MOISTURE, SOIL_TEMP, N, P, K):','PH');
    const minValue=Number(window.prompt('Giá trị nhỏ nhất:','0'));const maxValue=Number(window.prompt('Giá trị lớn nhất:','14'));
    await h.api('/tv4/quality/rules',{method:'POST',body:{ruleCode,name,target:'SENSOR_READING',ruleType:'RANGE',parameterCode,minValue,maxValue,severity:'ERROR',description:'Tạo từ màn hình TV4'}});
    await h.navigate('quality');h.notice('Đã tạo rule QC.','success');return true;
  }
  if (action === 'tv4-new-dataset') {
    const datasetCode=window.prompt('Mã dataset:');if(!datasetCode)return true;const name=window.prompt('Tên dataset:');if(!name)return true;
    await h.api('/tv4/datasets',{method:'POST',body:{datasetCode,name,purpose:'TRAINING',minValidObservations:10000,description:'Dataset phục vụ SQI/ML'}});
    await h.navigate('datasets');h.notice('Đã tạo dataset.','success');return true;
  }
  if (action === 'tv4-select-dataset') { h.state.selectedDataset=id; await h.navigate('datasets'); return true; }
  const dataset=encodeURIComponent(button.dataset.dataset||'');const version=encodeURIComponent(button.dataset.version||'');
  if (action === 'tv4-build') { await h.api(`/tv4/datasets/${dataset}/versions/${version}/build`,{method:'POST'});await h.navigate('datasets');h.notice('Đã build dataset version.','success');return true; }
  if (action === 'tv4-freeze') { await h.api(`/tv4/datasets/${dataset}/versions/${version}/freeze`,{method:'POST'});await h.navigate('datasets');h.notice('Đã đóng băng dataset version.','success');return true; }
  if (action === 'tv4-export') {
    const response=await fetch(`/api/v1/tv4/datasets/${dataset}/versions/${version}/export.csv`,{headers:{Authorization:`Bearer ${sessionStorage.getItem('aiot.accessToken')}`}});
    if(!response.ok){const body=await response.json();throw new Error(body.error?.message||'Không xuất được CSV.');}
    const blob=await response.blob();const link=document.createElement('a');link.href=URL.createObjectURL(blob);link.download=`dataset-version-${version}.csv`;link.click();URL.revokeObjectURL(link.href);return true;
  }
  if (action === 'tv4-new-version') { h.state.selectedDataset=dataset;document.querySelector('#tv4-version-form')?.scrollIntoView({behavior:'smooth'});return true; }
  if (action === 'tv4-label') {
    const labels=await h.api(`/tv4/datasets/labels?version_id=${encodeURIComponent(button.dataset.version)}`);const choice=window.prompt(`Chọn mã nhãn:\n${labels.data.labels.map(x=>`${x.label_code} — ${x.name_vi}`).join('\n')}`);if(!choice)return true;
    const label=labels.data.labels.find(x=>x.label_code.toLowerCase()===choice.toLowerCase()||x.label_id===choice);if(!label)throw new Error('Không tìm thấy mã nhãn.');
    const reason=reasonDialog('Gán nhãn chuyên gia');if(!reason)return true;
    await h.api(`/tv4/datasets/${dataset}/versions/${version}/records/${encodeURIComponent(button.dataset.record)}/label`,{method:'PATCH',body:{labelId:label.label_id,reason}});
    await h.navigate('datasets');h.notice('Đã gán nhãn chuyên gia và ghi audit.','success');return true;
  }
  return false;
}

export async function handleTv4Submit(form,h) {
  if(form.id==='tv4-version-select') {h.state.selectedVersion=new FormData(form).get('version_id');h.state.datasetRecordsPage=1;await h.navigate('datasets');return true;}
  if (form.id==='tv4-issue-filter') {
    const values=Object.fromEntries(new FormData(form));const query=new URLSearchParams();for(const[k,v]of Object.entries(values))if(v&&!['from_date','to_date'].includes(k))query.set(k,v);
    if(values.from_date)query.set('from',new Date(`${values.from_date}T00:00:00`).toISOString());
    if(values.to_date){const next=new Date(`${values.to_date}T00:00:00`);next.setDate(next.getDate()+1);query.set('to',next.toISOString());}
    location.hash=`quality?${query}`;await h.navigate('quality');
    return true;
  }
  if (form.id==='tv4-version-form') {
    const input=Object.fromEntries(new FormData(form));const to=new Date(input.timeTo);const from=new Date(input.timeFrom);if(!(from<to))throw new Error('Thời điểm bắt đầu phải trước thời điểm kết thúc.');
    const id=encodeURIComponent(form.dataset.dataset);await h.api(`/tv4/datasets/${id}/versions`,{method:'POST',body:{timeFrom:from.toISOString(),timeTo:to.toISOString(),aggregation:input.aggregation,splitStrategy:input.splitStrategy,splitRatio:{TRAIN:.7,VAL:.15,TEST:.15},randomSeed:42,notes:'Tạo từ màn hình TV4'}});
    h.state.selectedDataset=id;await h.navigate('datasets');h.notice('Đã tạo dataset version dạng DRAFT.','success');return true;
  }
  return false;
}
