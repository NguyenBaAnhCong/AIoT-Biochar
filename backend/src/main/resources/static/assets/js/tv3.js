// Màn hình TV3: 05 Thí nghiệm & nghiệm thức, 06 Biochar & làm giàu, 07 Dữ liệu phòng thí nghiệm.
// Mỗi tab được khai báo bằng dữ liệu (cột, trường nhập, bộ lọc); phần dựng bảng/biểu mẫu dùng chung.

const PAGE_SIZE = 20;
const METALS = ['Pb', 'Cd', 'As', 'Hg'];

export const tv3Pages = [
  { id: 'experiments', title: 'Thí nghiệm & nghiệm thức', permission: 'experiment.read', group: 'Thí nghiệm & PTN', icon: '⚗' },
  { id: 'biochar', title: 'Biochar & làm giàu', permission: 'biochar.read', group: 'Thí nghiệm & PTN', icon: '♨' },
  { id: 'lab', title: 'Phòng thí nghiệm', permission: 'laboratory.read', group: 'Thí nghiệm & PTN', icon: '◉' },
];

export function createTv3({ api, appState, navigate, notice, escapeHtml: esc, dateText, numberText }) {
  const initialView = () => ({ tab: {}, filters: {}, pageNo: {}, editing: null, trace: null });
  let view = initialView();
  let rowsById = new Map();
  let lookups = {};

  const can = (permission) => appState.user?.permissions?.includes(permission);

  const dayText = (value) => value ? value.split('-').reverse().join('/') : '—';
  const num = (value, digits = 3) => numberText(value, digits);
  const text = (value) => esc(value ?? '—');
  const badge = (value, tone = '') => `<span class="badge ${tone}">${esc(value)}</span>`;
  const TONES = {
    PASSED: 'badge-ok', APPROVED: 'badge-ok', ACTIVE: 'badge-ok', RUNNING: 'badge-ok', COMPLETED: 'badge-info',
    VERIFIED: 'badge-info', PENDING: 'badge-warn', DRAFT: 'badge-warn', FALLOW: 'badge-warn',
    FAILED: 'badge-bad', REJECTED: 'badge-bad', CANCELLED: 'badge-bad',
  };
  const status = (value) => badge(value, TONES[value] || '');

  // ---------- danh sách chọn ----------
  const LOOKUPS = {
    sites: { path: '/sites', permission: 'farm.read', id: 'site_id', label: (r) => `${r.site_code} — ${r.name}` },
    plots: { path: '/plots', permission: 'farm.read', id: 'plot_id', label: (r) => `${r.site_code} / ${r.plot_code}` },
    crops: { path: '/crops', permission: 'farm.read', id: 'crop_id', label: (r) => `${r.crop_code} — ${r.name_vi}` },
    seasons: { path: '/crop-seasons', permission: 'farm.read', id: 'crop_season_id', label: (r) => `${r.plot_code} · ${r.season_code} · ${r.crop_name}` },
    experiments: { path: '/experiments', permission: 'experiment.read', id: 'experiment_id', label: (r) => `${r.experiment_code} — ${r.title}` },
    treatments: { path: '/treatments', permission: 'experiment.read', id: 'treatment_id', label: (r) => `${r.experiment_code} / ${r.treatment_code} — ${r.name}` },
    stages: { path: '/growth-stages', permission: 'experiment.read', id: 'growth_stage_id', label: (r) => `${r.crop_code} · ${r.stage_order}. ${r.name_vi}` },
    feedstock: { path: '/biochar/feedstock-batches', permission: 'biochar.read', id: 'feedstock_batch_id', label: (r) => `${r.batch_code} (${r.mass_kg} kg, đã dùng ${r.used_mass_kg})` },
    biochars: { path: '/biochar/batches', permission: 'biochar.read', id: 'biochar_batch_id', label: (r) => `${r.batch_code} (${r.safety_status})` },
    enrichments: { path: '/biochar/enrichment-batches', permission: 'biochar.read', id: 'enrichment_batch_id', label: (r) => `${r.batch_code} ← ${r.biochar_batch_code}` },
    samples: { path: '/samples', permission: 'laboratory.read', id: 'sample_id', label: (r) => `${r.sample_code} · ô ${r.plot_code}` },
    methods: { path: '/lab-methods', permission: 'laboratory.read', id: 'method_id', label: (r) => `${r.method_code} (${r.parameter_code}) — ${r.name}` },
    parameters: { path: '/ref/parameters', permission: null, id: 'parameter_code', label: (r) => `${r.parameter_code} — ${r.name_vi} (${r.unit})` },
  };

  async function loadLookups(names) {
    lookups = {};
    await Promise.all([...new Set(names)].map(async (name) => {
      const source = LOOKUPS[name];
      if (source.permission && !can(source.permission)) {
        lookups[name] = [];
        return;
      }
      const query = source.path === '/ref/parameters' ? '' : '?limit=200';
      const { data } = await api(source.path + query);
      lookups[name] = data.map((row) => [row[source.id], source.label(row)]);
    }));
  }

  // ---------- khai báo trường nhập ----------
  const field = (name, label, type = 'text', extra = {}) => ({ name, label, type, ...extra });
  const choose = (name, label, values, extra = {}) => field(name, label, 'select', { options: values.map((v) => [v, v]), ...extra });
  const pick = (name, label, lookup, extra = {}) => field(name, label, 'select', { lookup, ...extra });
  const yesNo = (name, label, extra = {}) => field(name, label, 'bool', { options: [['true', 'Có'], ['false', 'Không']], ...extra });
  const required = { required: true };

  const FEEDSTOCK_TYPES = ['LITCHI_PRUNING', 'LITCHI_BRANCH', 'LITCHI_LEAF', 'LITCHI_PEEL', 'LITCHI_SEED', 'MIXED', 'OTHER'];
  const SAFETY = ['PENDING', 'PASSED', 'FAILED'];
  const RESULT_STATUSES = ['DRAFT', 'VERIFIED', 'APPROVED', 'REJECTED'];

  // ---------- khai báo các tab ----------
  const ENTITIES = {
    experiments: {
      title: 'Thí nghiệm', path: '/experiments', id: 'experiment_id', domain: 'experiment', search: true,
      filters: [choose('status', 'Trạng thái', ['PLANNED', 'RUNNING', 'COMPLETED', 'CANCELLED'])],
      fields: [
        pick('site_id', 'Địa điểm', 'sites', required), field('experiment_code', 'Mã thí nghiệm', 'text', required),
        field('title', 'Tên thí nghiệm', 'text', required),
        choose('design_type', 'Kiểu bố trí', ['RCBD', 'CRD', 'SPLIT_PLOT', 'FACTORIAL', 'OBSERVATIONAL']),
        field('replicates', 'Số lần lặp', 'number'), field('started_on', 'Ngày bắt đầu', 'date', required),
        field('ended_on', 'Ngày kết thúc', 'date'), choose('status', 'Trạng thái', ['PLANNED', 'RUNNING', 'COMPLETED', 'CANCELLED']),
        field('objective', 'Mục tiêu', 'textarea'),
      ],
      columns: [
        ['Mã', (r) => `<strong>${esc(r.experiment_code)}</strong>`], ['Tên', (r) => esc(r.title)],
        ['Địa điểm', (r) => esc(r.site_code)], ['Bố trí', (r) => `${esc(r.design_type)} × ${r.replicates}`],
        ['Thời gian', (r) => `${dayText(r.started_on)} → ${dayText(r.ended_on)}`],
        ['Nghiệm thức', (r) => r.treatment_count], ['Trạng thái', (r) => status(r.status)],
      ],
    },
    treatments: {
      title: 'Nghiệm thức', path: '/treatments', id: 'treatment_id', domain: 'experiment', search: true,
      filters: [pick('experiment_id', 'Thí nghiệm', 'experiments')],
      fields: [
        pick('experiment_id', 'Thí nghiệm', 'experiments', required), field('treatment_code', 'Mã nghiệm thức', 'text', required),
        field('name', 'Tên', 'text', required), field('biochar_dose_kg_m2', 'Liều biochar (kg/m²)', 'number'),
        yesNo('control_flag', 'Đối chứng'), pick('biochar_batch_id', 'Lô biochar', 'biochars'),
        pick('enrichment_batch_id', 'Lô làm giàu', 'enrichments'), field('microbial_formula', 'Công thức vi sinh'),
        field('fertilizer_desc', 'Phân bón'), field('description', 'Mô tả', 'textarea'),
      ],
      columns: [
        ['Mã', (r) => `<strong>${esc(r.treatment_code)}</strong>`], ['Tên', (r) => esc(r.name)],
        ['Thí nghiệm', (r) => esc(r.experiment_code)], ['Liều (kg/m²)', (r) => num(r.biochar_dose_kg_m2)],
        ['Lô biochar', (r) => r.biochar_batch_code ? `${esc(r.biochar_batch_code)} ${status(r.biochar_safety_status)}` : '—'],
        ['Làm giàu', (r) => text(r.enrichment_batch_code)], ['Đối chứng', (r) => r.control_flag ? badge('ĐC', 'badge-info') : '—'],
      ],
    },
    applications: {
      title: 'Gán nghiệm thức cho ô', path: '/treatment-applications', id: 'application_id', domain: 'experiment',
      filters: [pick('experiment_id', 'Thí nghiệm', 'experiments'), pick('plot_id', 'Ô', 'plots')],
      fields: [
        pick('treatment_id', 'Nghiệm thức', 'treatments', required), pick('plot_id', 'Ô thí nghiệm', 'plots', required),
        pick('crop_season_id', 'Mùa vụ', 'seasons'), field('replicate_no', 'Lần lặp', 'number'),
        field('applied_on', 'Ngày bón', 'date', required),
        field('effective_from', 'Hiệu lực từ (trống = 00:00 ngày bón)', 'datetime'),
        field('effective_to', 'Hết hiệu lực', 'datetime'), field('actual_dose_kg_m2', 'Liều thực tế (kg/m²)', 'number'),
        field('notes', 'Ghi chú', 'textarea'),
      ],
      columns: [
        ['Ô', (r) => `<strong>${esc(r.plot_code)}</strong>`], ['Nghiệm thức', (r) => `${esc(r.experiment_code)} / ${esc(r.treatment_code)}`],
        ['Mùa vụ', (r) => text(r.season_code)], ['Liều (kg/m²)', (r) => num(r.effective_dose_kg_m2)],
        ['Ngày bón', (r) => dayText(r.applied_on)], ['Hiệu lực', (r) => `${dateText(r.effective_from)} → ${dateText(r.effective_to)}`],
        ['', (r) => r.is_active ? badge('Đang hiệu lực', 'badge-ok') : ''],
      ],
      extraActions: (r) => r.effective_to ? '' : rowButton('tv3-end', r.application_id, 'Kết thúc'),
    },
    plotStages: {
      title: 'Giai đoạn sinh trưởng theo ô', path: '/plot-growth-stages', id: 'plot_growth_stage_id', domain: 'experiment',
      filters: [pick('plot_id', 'Ô', 'plots')],
      fields: [
        pick('crop_season_id', 'Mùa vụ', 'seasons', required), pick('growth_stage_id', 'Giai đoạn', 'stages', required),
        field('started_at', 'Bắt đầu', 'datetime', required), field('ended_at', 'Kết thúc', 'datetime'),
      ],
      columns: [
        ['Ô', (r) => `<strong>${esc(r.plot_code)}</strong>`], ['Mùa vụ', (r) => esc(r.season_code)],
        ['Giai đoạn', (r) => `${r.stage_order}. ${esc(r.stage_name)}`], ['Bắt đầu', (r) => dateText(r.started_at)],
        ['Kết thúc', (r) => dateText(r.ended_at)], ['Người ghi', (r) => text(r.observed_by_name)],
      ],
    },
    stages: {
      title: 'Danh mục giai đoạn', path: '/growth-stages', id: 'growth_stage_id', domain: 'experiment',
      filters: [pick('crop_id', 'Cây trồng', 'crops')],
      fields: [
        pick('crop_id', 'Cây trồng', 'crops', required), field('stage_code', 'Mã giai đoạn', 'text', required),
        field('name_vi', 'Tên', 'text', required), field('stage_order', 'Thứ tự', 'number', required),
        field('typical_days_from_planting', 'Số ngày sau trồng (tham khảo)', 'number'),
      ],
      columns: [
        ['Cây trồng', (r) => esc(r.crop_name)], ['Thứ tự', (r) => r.stage_order], ['Mã', (r) => esc(r.stage_code)],
        ['Tên', (r) => esc(r.name_vi)], ['Ngày sau trồng', (r) => text(r.typical_days_from_planting)],
      ],
    },
    sites: {
      title: 'Địa điểm', path: '/sites', id: 'site_id', domain: 'farm', search: true, filters: [],
      fields: [
        field('site_code', 'Mã địa điểm', 'text', required), field('name', 'Tên', 'text', required),
        field('province', 'Tỉnh'), field('district', 'Huyện'), field('latitude', 'Vĩ độ', 'number'),
        field('longitude', 'Kinh độ', 'number'), field('elevation_m', 'Độ cao (m)', 'number'), field('climate_zone', 'Vùng khí hậu'),
      ],
      columns: [
        ['Mã', (r) => `<strong>${esc(r.site_code)}</strong>`], ['Tên', (r) => esc(r.name)],
        ['Vị trí', (r) => esc([r.district, r.province].filter(Boolean).join(', ') || '—')], ['Số ô', (r) => r.plot_count],
      ],
    },
    plots: {
      title: 'Ô thí nghiệm', path: '/plots', id: 'plot_id', domain: 'farm', search: true,
      filters: [pick('site_id', 'Địa điểm', 'sites')],
      fields: [
        pick('site_id', 'Địa điểm', 'sites', required), field('plot_code', 'Mã ô', 'text', required),
        field('area_m2', 'Diện tích (m²)', 'number', required), field('soil_type', 'Loại đất', 'text', required),
        field('soil_texture', 'Thành phần cơ giới'), field('block_no', 'Khối lặp', 'number'),
        field('latitude', 'Vĩ độ', 'number'), field('longitude', 'Kinh độ', 'number'),
        choose('status', 'Trạng thái', ['ACTIVE', 'FALLOW', 'INACTIVE', 'RETIRED']),
      ],
      columns: [
        ['Ô', (r) => `<strong>${esc(r.plot_code)}</strong>`], ['Địa điểm', (r) => esc(r.site_code)],
        ['Diện tích', (r) => `${num(r.area_m2, 2)} m²`], ['Đất', (r) => esc(r.soil_type)], ['Khối', (r) => text(r.block_no)],
        ['Nghiệm thức hiện tại', (r) => r.current_treatment_code
          ? `${esc(r.current_treatment_code)} · ${num(r.current_biochar_dose_kg_m2)} kg/m²` : '—'],
        ['Trạng thái', (r) => status(r.status)],
      ],
    },
    crops: {
      title: 'Cây trồng', path: '/crops', id: 'crop_id', domain: 'farm', search: true, filters: [],
      fields: [
        field('crop_code', 'Mã cây', 'text', required), field('name_vi', 'Tên tiếng Việt', 'text', required),
        field('scientific_name', 'Tên khoa học'), field('variety', 'Giống'),
      ],
      columns: [
        ['Mã', (r) => `<strong>${esc(r.crop_code)}</strong>`], ['Tên', (r) => esc(r.name_vi)],
        ['Tên khoa học', (r) => text(r.scientific_name)], ['Giống', (r) => text(r.variety)],
      ],
    },
    seasons: {
      title: 'Mùa vụ', path: '/crop-seasons', id: 'crop_season_id', domain: 'farm', search: true,
      filters: [pick('plot_id', 'Ô', 'plots')],
      fields: [
        pick('plot_id', 'Ô thí nghiệm', 'plots', required), pick('crop_id', 'Cây trồng', 'crops', required),
        field('season_code', 'Mã mùa vụ', 'text', required), field('planted_on', 'Ngày trồng', 'date', required),
        field('harvested_on', 'Ngày thu hoạch', 'date'), field('planting_density', 'Mật độ trồng', 'number'),
        field('notes', 'Ghi chú', 'textarea'),
      ],
      columns: [
        ['Ô', (r) => `<strong>${esc(r.plot_code)}</strong>`], ['Mùa vụ', (r) => esc(r.season_code)],
        ['Cây trồng', (r) => esc(r.crop_name)], ['Thời gian', (r) => `${dayText(r.planted_on)} → ${dayText(r.harvested_on)}`],
      ],
    },
    feedstock: {
      title: 'Lô phụ phẩm vải', path: '/biochar/feedstock-batches', id: 'feedstock_batch_id', domain: 'biochar', search: true,
      filters: [choose('feedstock_type', 'Loại phụ phẩm', FEEDSTOCK_TYPES)],
      fields: [
        field('batch_code', 'Mã lô', 'text', required), choose('feedstock_type', 'Loại phụ phẩm', FEEDSTOCK_TYPES),
        field('source_location', 'Nơi thu gom'), field('collected_on', 'Ngày thu gom', 'date', required),
        field('mass_kg', 'Khối lượng (kg)', 'number', required), field('moisture_pct', 'Độ ẩm (%)', 'number'),
        field('pretreatment', 'Tiền xử lý'), field('notes', 'Ghi chú', 'textarea'),
      ],
      columns: [
        ['Mã lô', (r) => `<strong>${esc(r.batch_code)}</strong>`], ['Loại', (r) => esc(r.feedstock_type)],
        ['Nguồn', (r) => text(r.source_location)], ['Thu gom', (r) => dayText(r.collected_on)],
        ['Khối lượng', (r) => `${num(r.mass_kg, 2)} kg`], ['Đã nhiệt phân', (r) => `${num(r.used_mass_kg, 2)} kg`],
        ['Độ ẩm', (r) => r.moisture_pct == null ? '—' : `${num(r.moisture_pct, 1)}%`],
      ],
    },
    biochars: {
      title: 'Lô biochar', path: '/biochar/batches', id: 'biochar_batch_id', domain: 'biochar', search: true,
      filters: [choose('safety_status', 'An toàn', SAFETY)],
      fields: [
        field('batch_code', 'Mã lô', 'text', required), pick('feedstock_batch_id', 'Lô phụ phẩm', 'feedstock', required),
        field('produced_on', 'Ngày sản xuất', 'date', required), field('reactor_type', 'Loại lò'),
        field('pyrolysis_temp_c', 'Nhiệt độ nhiệt phân (°C)', 'number'), field('residence_time_min', 'Thời gian lưu (phút)', 'number'),
        field('heating_rate_c_min', 'Tốc độ gia nhiệt (°C/phút)', 'number'), field('input_mass_kg', 'Khối lượng vào (kg)', 'number'),
        field('output_mass_kg', 'Khối lượng ra (kg)', 'number'), field('ph', 'pH', 'number'), field('ec_ds_m', 'EC (dS/m)', 'number'),
        field('carbon_pct', 'Cacbon (%)', 'number'), field('ash_pct', 'Tro (%)', 'number'),
        field('surface_area_m2_g', 'Diện tích bề mặt (m²/g)', 'number'), field('cec_cmol_kg', 'CEC (cmol/kg)', 'number'),
        field('heavy_metals_mg_kg', 'Kim loại nặng (mg/kg)', 'metals'),
        choose('safety_status', 'Kết luận an toàn', SAFETY), field('notes', 'Ghi chú', 'textarea'),
      ],
      columns: [
        ['Mã lô', (r) => `<strong>${esc(r.batch_code)}</strong><div class="small muted">từ ${esc(r.feedstock_batch_code)}</div>`],
        ['Sản xuất', (r) => dayText(r.produced_on)],
        ['Nhiệt phân', (r) => `${num(r.pyrolysis_temp_c, 1)} °C · ${num(r.residence_time_min, 1)} phút`],
        ['Hiệu suất', (r) => r.yield_pct == null ? '—' : `${num(r.yield_pct, 2)}%`],
        ['pH · C · tro', (r) => `${num(r.ph, 2)} · ${num(r.carbon_pct, 1)}% · ${num(r.ash_pct, 1)}%`],
        ['Kim loại nặng (mg/kg)', (r) => r.heavy_metals_mg_kg
          ? Object.entries(r.heavy_metals_mg_kg).map(([k, v]) => `${esc(k)} ${num(v, 4)}`).join(', ') : '—'],
        ['An toàn', (r) => status(r.safety_status)],
      ],
      readActions: (r) => rowButton('tv3-trace', r.biochar_batch_id, 'Truy xuất'),
      extraActions: (r) => [
        r.safety_status !== 'PASSED' && r.heavy_metals_mg_kg ? rowButton('tv3-safety-pass', r.biochar_batch_id, 'Đạt') : '',
        r.safety_status !== 'FAILED' ? rowButton('tv3-safety-fail', r.biochar_batch_id, 'Không đạt') : '',
      ].join(''),
    },
    enrichments: {
      title: 'Lô làm giàu', path: '/biochar/enrichment-batches', id: 'enrichment_batch_id', domain: 'biochar', search: true,
      filters: [pick('biochar_batch_id', 'Lô biochar', 'biochars')],
      fields: [
        field('batch_code', 'Mã lô', 'text', required), pick('biochar_batch_id', 'Lô biochar nguồn', 'biochars', required),
        choose('enrichment_type', 'Kiểu làm giàu', ['MICROBIAL', 'NUTRIENT', 'COMPOST', 'MIXED'], required),
        field('microbial_formula', 'Công thức vi sinh'), field('nutrient_formula', 'Công thức dinh dưỡng'),
        field('ratio_desc', 'Tỉ lệ phối trộn'), field('started_on', 'Ngày bắt đầu ủ', 'date', required),
        field('finished_on', 'Ngày kết thúc ủ', 'date'), field('notes', 'Ghi chú', 'textarea'),
      ],
      columns: [
        ['Mã lô', (r) => `<strong>${esc(r.batch_code)}</strong>`],
        ['Từ biochar', (r) => `${esc(r.biochar_batch_code)} ${status(r.biochar_safety_status)}`],
        ['Kiểu', (r) => esc(r.enrichment_type)], ['Công thức', (r) => text(r.microbial_formula || r.nutrient_formula)],
        ['Thời gian ủ', (r) => `${dayText(r.started_on)} → ${dayText(r.finished_on)}`],
        ['Số ngày', (r) => text(r.incubation_days)],
      ],
    },
    enrichmentResults: {
      title: 'Kết quả làm giàu', path: '/biochar/enrichment-results', id: 'enrichment_result_id', domain: 'biochar',
      filters: [pick('enrichment_batch_id', 'Lô làm giàu', 'enrichments')],
      fields: [
        pick('enrichment_batch_id', 'Lô làm giàu', 'enrichments', required),
        field('parameter_code', 'Mã chỉ tiêu (vd CFU, N)', 'text', required), field('value', 'Giá trị', 'number', required),
        field('unit', 'Đơn vị', 'text', required), field('measured_on', 'Ngày đo', 'date', required),
        field('method', 'Phương pháp'), yesNo('pass_flag', 'Đạt yêu cầu'),
      ],
      columns: [
        ['Lô', (r) => `<strong>${esc(r.enrichment_batch_code)}</strong>`], ['Chỉ tiêu', (r) => esc(r.parameter_code)],
        ['Giá trị', (r) => `${num(r.value, 4)} ${esc(r.unit)}`], ['Ngày đo', (r) => dayText(r.measured_on)],
        ['Phương pháp', (r) => text(r.method)],
        ['Đạt', (r) => r.pass_flag == null ? '—' : badge(r.pass_flag ? 'Đạt' : 'Không đạt', r.pass_flag ? 'badge-ok' : 'badge-bad')],
      ],
    },
    results: {
      title: 'Kết quả & duyệt', noun: 'Kết quả phân tích', path: '/lab-results', id: 'lab_result_id', domain: 'laboratory', search: true, selectable: true,
      filters: [choose('result_status', 'Trạng thái', RESULT_STATUSES), pick('plot_id', 'Ô', 'plots'), pick('parameter_code', 'Chỉ tiêu', 'parameters')],
      fields: [
        pick('sample_id', 'Mẫu', 'samples', required), pick('parameter_code', 'Chỉ tiêu', 'parameters', required),
        field('value', 'Giá trị', 'number', required), field('unit', 'Đơn vị (trống = đơn vị chuẩn)'),
        pick('method_id', 'Phương pháp', 'methods'), field('replicate_no', 'Lần lặp', 'number'),
        yesNo('below_lod', 'Dưới giới hạn phát hiện'), field('analyzed_at', 'Thời điểm phân tích', 'datetime'),
      ],
      columns: [
        ['Mẫu', (r) => `<strong>${esc(r.sample_code)}</strong><div class="small muted">ô ${esc(r.plot_code)} · ${dateText(r.sampled_at)}</div>`],
        ['Chỉ tiêu', (r) => `${esc(r.parameter_code)}${r.replicate_no > 1 ? ` (lặp ${r.replicate_no})` : ''}`],
        ['Giá trị', (r) => `${r.below_lod ? '&lt; ' : ''}${num(r.value, 4)} ${esc(r.unit)}`],
        ['Phương pháp', (r) => text(r.method_code)], ['Người nhập', (r) => text(r.analyst_name)],
        ['Người duyệt', (r) => text(r.approved_by_name)], ['Trạng thái', (r) => status(r.result_status)],
      ],
      editable: (r) => r.result_status === 'DRAFT' || r.result_status === 'REJECTED',
      extraActions: (r) => reviewActions(r.result_status).map(([action, label]) => rowButton(`tv3-review-${action}`, r.lab_result_id, label)).join(''),
    },
    samples: {
      title: 'Mẫu đất', path: '/samples', id: 'sample_id', domain: 'laboratory', search: true,
      filters: [pick('plot_id', 'Ô', 'plots'), choose('status', 'Trạng thái', ['COLLECTED', 'RECEIVED', 'ANALYZING', 'COMPLETED', 'REJECTED'])],
      fields: [
        field('sample_code', 'Mã mẫu', 'text', required), pick('plot_id', 'Ô thí nghiệm', 'plots', required),
        choose('sample_type', 'Loại mẫu', ['SOIL', 'SOIL_SOLUTION', 'PLANT', 'BIOCHAR']),
        field('sampled_at', 'Thời điểm lấy mẫu', 'datetime', required), field('depth_from_cm', 'Độ sâu từ (cm)', 'number'),
        field('depth_to_cm', 'Độ sâu đến (cm)', 'number'), field('composite_of', 'Số mẫu đơn gộp', 'number'),
        field('received_at', 'Thời điểm nhận mẫu', 'datetime'),
        choose('status', 'Trạng thái', ['COLLECTED', 'RECEIVED', 'ANALYZING', 'COMPLETED', 'REJECTED']),
        field('notes', 'Ghi chú', 'textarea'),
      ],
      columns: [
        ['Mã mẫu', (r) => `<strong>${esc(r.sample_code)}</strong>`], ['Ô', (r) => esc(r.plot_code)],
        ['Loại', (r) => esc(r.sample_type)], ['Lấy lúc', (r) => dateText(r.sampled_at)],
        ['Độ sâu (cm)', (r) => r.depth_to_cm == null ? '—' : `${num(r.depth_from_cm, 1)} – ${num(r.depth_to_cm, 1)}`],
        ['Người lấy', (r) => text(r.collected_by_name)], ['Số kết quả', (r) => r.result_count], ['Trạng thái', (r) => status(r.status)],
      ],
    },
    methods: {
      title: 'Phương pháp', path: '/lab-methods', id: 'method_id', domain: 'laboratory', search: true,
      filters: [pick('parameter_code', 'Chỉ tiêu', 'parameters')],
      fields: [
        field('method_code', 'Mã phương pháp', 'text', required), pick('parameter_code', 'Chỉ tiêu', 'parameters', required),
        field('name', 'Tên', 'text', required), field('unit', 'Đơn vị', 'text', required), field('standard_ref', 'Tiêu chuẩn'),
        field('instrument', 'Thiết bị'), field('detection_limit', 'Giới hạn phát hiện', 'number'),
        field('quantification_limit', 'Giới hạn định lượng', 'number'),
      ],
      columns: [
        ['Mã', (r) => `<strong>${esc(r.method_code)}</strong>`], ['Chỉ tiêu', (r) => esc(r.parameter_code)],
        ['Tên', (r) => esc(r.name)], ['Tiêu chuẩn', (r) => text(r.standard_ref)], ['Đơn vị', (r) => esc(r.unit)],
      ],
    },
  };

  const SCREENS = {
    experiments: {
      title: 'Thí nghiệm & nghiệm thức', subtitle: 'Thí nghiệm, liều biochar, gán nghiệm thức cho ô và giai đoạn sinh trưởng.',
      tabs: ['experiments', 'treatments', 'applications', 'plotStages', 'stages', 'plots', 'sites', 'seasons', 'crops'],
    },
    biochar: {
      title: 'Biochar & làm giàu', subtitle: 'Truy xuất từ phụ phẩm cây vải đến lô biochar, làm giàu và chỉ tiêu an toàn.',
      tabs: ['feedstock', 'biochars', 'enrichments', 'enrichmentResults'],
    },
    lab: {
      title: 'Dữ liệu phòng thí nghiệm', subtitle: 'Mẫu đất, kết quả phân tích, duyệt DRAFT → VERIFIED → APPROVED và đối chiếu cảm biến.',
      tabs: ['results', 'samples', 'methods', 'import', 'compare'],
    },
  };
  const SPECIAL_TABS = { import: 'Nhập Excel', compare: 'Đối chiếu cảm biến' };

  function rowButton(action, id, label, extraClass = '') {
    return `<button type="button" class="btn btn-sm ${extraClass}" data-action="${action}" data-id="${esc(id)}">${label}</button>`;
  }

  function reviewActions(resultStatus) {
    const actions = [];
    if (resultStatus === 'DRAFT' && can('laboratory.write')) actions.push(['verify', 'Xác nhận']);
    if (resultStatus === 'VERIFIED' && can('laboratory.approve')) actions.push(['approve', 'Duyệt']);
    if ((resultStatus === 'DRAFT' || resultStatus === 'VERIFIED') && can('laboratory.approve')) actions.push(['reject', 'Từ chối']);
    return actions;
  }

  // ---------- chuyển đổi giá trị biểu mẫu ----------
  function localDateTime(value) {
    if (!value) return '';
    const date = new Date(value);
    return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  }

  function initialValue(item, row) {
    const value = row?.[item.name];
    if (value == null) return '';
    return item.type === 'datetime' ? localDateTime(value) : String(value);
  }

  function control(item, row, filterValue) {
    const value = filterValue ?? initialValue(item, row);
    const attributes = `name="${item.name}" ${item.required && filterValue === undefined ? 'required' : ''}`;
    if (item.type === 'select' || item.type === 'bool') {
      const options = item.lookup ? lookups[item.lookup] || [] : item.options;
      const known = options.some(([key]) => String(key) === value);
      // giữ nguyên giá trị đang lưu kể cả khi danh sách chọn không chứa nó
      const all = value && !known ? [[value, value], ...options] : options;
      return `<select ${attributes}><option value="">${filterValue === undefined ? '— chọn —' : 'Tất cả'}</option>
        ${all.map(([key, label]) => `<option value="${esc(key)}" ${String(key) === value ? 'selected' : ''}>${esc(label)}</option>`).join('')}</select>`;
    }
    if (item.type === 'textarea') return `<textarea ${attributes} rows="2">${esc(value)}</textarea>`;
    const type = { number: 'number', date: 'date', datetime: 'datetime-local' }[item.type] || 'text';
    return `<input type="${type}" ${attributes} value="${esc(value)}" ${type === 'number' ? 'step="any"' : ''}>`;
  }

  function formField(item, row) {
    if (item.type === 'metals') {
      const current = row?.[item.name] || {};
      const names = [...new Set([...METALS, ...Object.keys(current)])];
      return `<fieldset class="tv3-wide tv3-metals"><legend class="field-label">${esc(item.label)}</legend>
        ${names.map((metal) => `<label class="field"><span class="field-label">${esc(metal)}</span>
          <input type="number" step="any" min="0" name="metal:${esc(metal)}" value="${esc(current[metal] ?? '')}"></label>`).join('')}</fieldset>`;
    }
    return `<label class="field ${item.type === 'textarea' ? 'tv3-wide' : ''}">
      <span class="field-label">${esc(item.label)}${item.required ? ' *' : ''}</span>${control(item, row)}</label>`;
  }

  /** Tạo mới: bỏ ô trống. Sửa: chỉ gửi ô đã thay đổi, ô bị xóa trắng gửi null. */
  function readBody(entity, form, row) {
    const input = new FormData(form);
    const body = {};
    for (const item of entity.fields) {
      if (item.type === 'metals') {
        const metals = {};
        for (const [key, raw] of input.entries()) {
          if (key.startsWith('metal:') && raw !== '') metals[key.slice(6)] = Number(raw);
        }
        const empty = Object.keys(metals).length === 0;
        const before = row?.[item.name] || {};
        const same = Object.keys({ ...before, ...metals }).every((key) => before[key] != null && metals[key] != null && Number(before[key]) === metals[key]);
        if (row ? !same : !empty) body[item.name] = empty ? null : metals;
        continue;
      }
      const raw = String(input.get(item.name) ?? '').trim();
      if (row ? raw === initialValue(item, row) : raw === '') continue;
      if (raw === '') body[item.name] = null;
      else if (item.type === 'number') body[item.name] = Number(raw);
      else if (item.type === 'bool') body[item.name] = raw === 'true';
      else if (item.type === 'datetime') body[item.name] = new Date(raw).toISOString();
      else body[item.name] = raw;
    }
    return body;
  }

  // ---------- dựng màn hình ----------
  function tabBar(screen, active) {
    return `<nav class="tabs">${screen.tabs.filter(tabAllowed).map((key) => `
      <button type="button" class="tab ${key === active ? 'active' : ''}" data-action="tv3-tab" data-id="${key}">
      ${esc(SPECIAL_TABS[key] || ENTITIES[key].title)}</button>`).join('')}</nav>`;
  }

  function tabAllowed(key) {
    return SPECIAL_TABS[key] ? true : can(`${ENTITIES[key].domain}.read`);
  }

  function activeTab(pageId) {
    const allowed = SCREENS[pageId].tabs.filter(tabAllowed);
    return allowed.includes(view.tab[pageId]) ? view.tab[pageId] : allowed[0];
  }

  function queryString(key) {
    const query = new URLSearchParams({ page: String(view.pageNo[key] || 1), limit: String(PAGE_SIZE) });
    Object.entries(view.filters[key] || {}).forEach(([name, value]) => value && query.set(name, value));
    return query.toString();
  }

  function filterForm(key, filters, search) {
    if (!filters.length && !search) return '';
    const values = view.filters[key] || {};
    return `<form class="toolbar" data-tv3="filter" data-key="${key}">
      ${search ? `<label class="field grow"><span class="field-label">Tìm kiếm</span><input type="text" name="q" maxlength="100" value="${esc(values.q || '')}"></label>` : ''}
      ${filters.map((item) => `<label class="field"><span class="field-label">${esc(item.label)}</span>${control(item, null, values[item.name] || '')}</label>`).join('')}
      <button class="btn btn-primary" type="submit">Lọc</button></form>`;
  }

  function pager(key, meta) {
    const pages = Math.max(1, Math.ceil(meta.total / meta.limit));
    return `<div class="pager"><span class="muted small">${meta.total} dòng · trang ${meta.page}/${pages}</span>
      <span class="row-actions">
        <button type="button" class="btn btn-sm" data-action="tv3-page" data-id="${meta.page - 1}" data-key="${key}" ${meta.page <= 1 ? 'disabled' : ''}>← Trước</button>
        <button type="button" class="btn btn-sm" data-action="tv3-page" data-id="${meta.page + 1}" data-key="${key}" ${meta.page >= pages ? 'disabled' : ''}>Sau →</button>
      </span></div>`;
  }

  function table(headers, rows, empty = 'Chưa có dữ liệu') {
    return `<div class="table-wrap"><table class="table"><thead><tr>${headers.map((h) => `<th>${h}</th>`).join('')}</tr></thead>
      <tbody>${rows.join('') || `<tr><td class="empty" colspan="${headers.length}">${empty}</td></tr>`}</tbody></table></div>`;
  }

  async function entityTab(key) {
    const entity = ENTITIES[key];
    const writable = can(`${entity.domain}.write`);
    const needed = [...entity.filters, ...(writable ? entity.fields : [])].map((item) => item.lookup).filter(Boolean);
    const [{ data: rows, meta }] = await Promise.all([api(`${entity.path}?${queryString(key)}`), loadLookups(needed)]);
    rowsById = new Map(rows.map((row) => [row[entity.id], row]));
    const editing = view.editing?.key === key ? view.editing.row : null;

    const selectable = entity.selectable && (writable || can('laboratory.approve'));
    const bodyRows = rows.map((row) => {
      const id = row[entity.id];
      const canEdit = writable && (!entity.editable || entity.editable(row));
      const actions = [
        entity.readActions?.(row) || '',
        entity.extraActions && (writable || entity.selectable) ? entity.extraActions(row) : '',
        canEdit ? rowButton('tv3-edit', id, 'Sửa') + rowButton('tv3-delete', id, 'Xóa', 'btn-danger') : '',
      ].join('');
      return `<tr>${selectable ? `<td><input type="checkbox" class="tv3-select" value="${esc(id)}" aria-label="Chọn dòng"></td>` : ''}
        ${entity.columns.map(([, render]) => `<td>${render(row)}</td>`).join('')}
        <td><div class="row-actions">${actions}</div></td></tr>`;
    });
    const headers = [
      ...(selectable ? ['<input type="checkbox" data-action="tv3-select-all" aria-label="Chọn tất cả">'] : []),
      ...entity.columns.map(([title]) => title), '',
    ];

    const bulk = selectable ? `<div class="row-actions tv3-bulk"><span class="muted small">Với các dòng đã chọn:</span>
      ${can('laboratory.write') ? '<button type="button" class="btn btn-sm" data-action="tv3-bulk-verify">Xác nhận</button>' : ''}
      ${can('laboratory.approve') ? `<button type="button" class="btn btn-sm" data-action="tv3-bulk-approve">Duyệt</button>
        <button type="button" class="btn btn-sm btn-danger" data-action="tv3-bulk-reject">Từ chối</button>` : ''}</div>` : '';

    const form = writable ? `<details class="card" id="tv3-form-card" ${editing ? 'open' : ''}>
      <summary><strong>${editing ? 'Sửa' : 'Thêm'}: ${esc(entity.noun || entity.title)}</strong></summary>
      <form class="tv3-form" data-tv3="save" data-key="${key}">
        ${entity.fields.map((item) => formField(item, editing)).join('')}
        <div class="tv3-wide row-actions"><button class="btn btn-primary" type="submit">${editing ? 'Lưu thay đổi' : 'Thêm mới'}</button>
          ${editing ? '<button type="button" class="btn" data-action="tv3-cancel">Hủy sửa</button>' : ''}</div>
      </form></details>` : '';

    return `${form}${key === 'biochars' && view.trace ? traceCard(view.trace) : ''}
      <article class="card">${filterForm(key, entity.filters, entity.search)}${bulk}${table(headers, bodyRows)}${pager(key, meta)}</article>`;
  }

  function traceCard(trace) {
    const feedstock = trace.feedstock_batch;
    const batch = trace.biochar_batch;
    return `<article class="card"><div class="card-head"><h2>Truy xuất lô ${esc(batch.batch_code)}</h2>
      <button type="button" class="btn btn-sm" data-action="tv3-trace-close">Đóng</button></div>
      <dl class="kv">
        <dt>Phụ phẩm</dt><dd>${esc(feedstock.batch_code)} · ${esc(feedstock.feedstock_type)} · ${num(feedstock.mass_kg, 2)} kg · thu gom ${dayText(feedstock.collected_on)} · ${text(feedstock.source_location)}</dd>
        <dt>Biochar</dt><dd>${dayText(batch.produced_on)} · hiệu suất ${num(batch.yield_pct, 2)}% · ${status(batch.safety_status)}</dd>
        <dt>Làm giàu</dt><dd>${trace.enrichment_batches.map((n) => `${esc(n.batch_code)} (${esc(n.enrichment_type)})`).join(', ') || '—'}</dd>
      </dl>
      ${table(['Thí nghiệm', 'Nghiệm thức', 'Ô', 'Ngày bón', 'Liều (kg/m²)'], trace.usages.map((u) => `<tr>
        <td>${esc(u.experiment_code)}</td><td>${esc(u.treatment_code)}</td><td>${text(u.plot_code)}</td><td>${dayText(u.applied_on)}</td><td>${num(u.dose_kg_m2)}</td></tr>`), 'Lô chưa được dùng trong nghiệm thức nào')}
    </article>`;
  }

  function importTab() {
    return `<article class="card"><h2 class="card-head">Nhập kết quả từ Excel</h2>
      <p class="muted">Tải file mẫu, điền trang <code>KetQua</code>, bấm "Kiểm tra" trước. Chỉ cần một dòng lỗi thì không dòng nào được ghi.</p>
      <p><button type="button" class="btn" data-action="tv3-template">Tải file Excel mẫu</button></p>
      ${can('laboratory.write') ? `<form class="toolbar" data-tv3="import">
        <label class="field grow"><span class="field-label">File .xlsx (tối đa 5 MB, 2000 dòng)</span><input type="file" name="file" accept=".xlsx" required></label>
        <button class="btn" type="submit" value="check">Kiểm tra</button>
        <button class="btn btn-primary" type="submit" value="import">Nhập vào hệ thống</button>
      </form>` : '<p class="muted">Bạn không có quyền nhập kết quả.</p>'}
      <div id="tv3-import-report"></div></article>`;
  }

  function importReport(report) {
    const summary = report.errors.length
      ? `<div class="alert alert-error">Có ${report.error_rows}/${report.total_rows} dòng lỗi. Chưa ghi dòng nào.</div>`
      : `<div class="alert alert-ok">${report.dry_run
        ? `Cả ${report.total_rows} dòng hợp lệ. Bấm "Nhập vào hệ thống" để ghi.`
        : `Đã nhập ${report.inserted} kết quả ở trạng thái DRAFT.`}</div>`;
    return summary + (report.errors.length ? table(['Dòng Excel', 'Cột', 'Lỗi'], report.errors.map((e) => `<tr>
      <td>${e.row}</td><td><code>${esc(e.field)}</code></td><td>${esc(e.message)}</td></tr>`)) : '');
  }

  async function compareTab() {
    const filters = [pick('plot_id', 'Ô', 'plots'), choose('parameter_code', 'Chỉ tiêu', ['PH', 'EC', 'MOISTURE', 'N', 'P', 'K'])];
    const [{ data: rows, meta }] = await Promise.all([api(`/sensor-vs-lab?${queryString('compare')}`), loadLookups(['plots'])]);
    return `<article class="card"><p class="muted">Giá trị cảm biến là trung bình các lần đo hợp lệ trong ±2 giờ quanh thời điểm lấy mẫu; chỉ so với kết quả đã VERIFIED hoặc APPROVED.</p>
      ${filterForm('compare', filters, false)}
      ${table(['Mẫu', 'Ô', 'Lấy lúc', 'Chỉ tiêu', 'Phòng TN', 'Cảm biến', 'Số lần đo', 'Chênh lệch', 'Chênh lệch %'], rows.map((r) => `<tr>
        <td><strong>${esc(r.sample_code)}</strong></td><td>${esc(r.plot_code)}</td><td>${dateText(r.sampled_at)}</td><td>${esc(r.parameter_code)}</td>
        <td>${num(r.lab_value)}</td><td>${num(r.sensor_value)}</td><td>${r.n_readings ?? 0}</td><td>${num(r.diff)}</td>
        <td>${r.diff_pct == null ? '—' : `${num(r.diff_pct, 2)}%`}</td></tr>`))}
      ${pager('compare', meta)}</article>`;
  }

  async function render(pageId) {
    const screen = SCREENS[pageId];
    const tab = activeTab(pageId);
    let content;
    if (tab === 'import') content = importTab();
    else if (tab === 'compare') content = await compareTab();
    else content = await entityTab(tab);
    return `<div id="notice" hidden></div>
      <div class="page-header"><div><h1>${esc(screen.title)}</h1><p class="muted">${esc(screen.subtitle)}</p></div></div>
      ${tabBar(screen, tab)}${content}`;
  }

  // ---------- sự kiện ----------
  function showError(error) {
    const details = (error.details || []).map((d) => `${d.field}: ${d.message}`).join('; ');
    notice(details && details !== error.message ? `${error.message} (${details})` : error.message);
    document.querySelector('#notice')?.scrollIntoView({ block: 'nearest' });
  }

  async function reload(message) {
    await navigate(appState.page);
    if (message) notice(message, 'success');
  }

  async function review(ids, action) {
    await api(`/lab-results/bulk/${action}`, { method: 'POST', body: { ids } });
    await reload(`Đã cập nhật ${ids.length} kết quả.`);
  }

  async function click(button) {
    const action = button.dataset.action;
    const id = button.dataset.id;
    const tab = activeTab(appState.page);
    const entity = ENTITIES[tab];
    try {
      if (action === 'tv3-tab') {
        view.tab[appState.page] = id;
        view.editing = null;
        view.trace = null;
        await reload();
      } else if (action === 'tv3-page') {
        view.pageNo[button.dataset.key] = Number(id);
        await reload();
      } else if (action === 'tv3-edit') {
        view.editing = { key: tab, row: rowsById.get(id) };
        await reload();
        document.querySelector('#tv3-form-card')?.scrollIntoView({ block: 'start' });
      } else if (action === 'tv3-cancel') {
        view.editing = null;
        await reload();
      } else if (action === 'tv3-delete') {
        if (!window.confirm('Xóa bản ghi này? Thao tác không thể hoàn tác.')) return;
        await api(`${entity.path}/${encodeURIComponent(id)}`, { method: 'DELETE' });
        view.editing = null;
        await reload('Đã xóa.');
      } else if (action === 'tv3-end') {
        if (!window.confirm('Kết thúc hiệu lực của lần gán này ngay bây giờ?')) return;
        await api(`/treatment-applications/${encodeURIComponent(id)}`, { method: 'PATCH', body: { effective_to: new Date().toISOString() } });
        await reload('Đã kết thúc lần gán.');
      } else if (action === 'tv3-safety-pass' || action === 'tv3-safety-fail') {
        const value = action === 'tv3-safety-pass' ? 'PASSED' : 'FAILED';
        if (!window.confirm(`Kết luận lô này là ${value}?`)) return;
        await api(`/biochar/batches/${encodeURIComponent(id)}`, { method: 'PATCH', body: { safety_status: value } });
        await reload('Đã cập nhật kết luận an toàn.');
      } else if (action === 'tv3-trace') {
        view.trace = (await api(`/biochar/batches/${encodeURIComponent(id)}/trace`)).data;
        await reload();
      } else if (action === 'tv3-trace-close') {
        view.trace = null;
        await reload();
      } else if (action === 'tv3-select-all') {
        document.querySelectorAll('.tv3-select').forEach((box) => { box.checked = button.checked; });
      } else if (action.startsWith('tv3-review-')) {
        await review([id], action.slice('tv3-review-'.length));
      } else if (action.startsWith('tv3-bulk-')) {
        const ids = [...document.querySelectorAll('.tv3-select:checked')].map((box) => box.value);
        if (!ids.length) notice('Hãy chọn ít nhất một kết quả.');
        else await review(ids, action.slice('tv3-bulk-'.length));
      } else if (action === 'tv3-template') {
        const { data: blob } = await api('/lab-results/import-template', { raw: true });
        const link = document.createElement('a');
        link.href = URL.createObjectURL(blob);
        link.download = 'mau-ket-qua-phong-thi-nghiem.xlsx';
        link.click();
        URL.revokeObjectURL(link.href);
      }
    } catch (error) {
      showError(error);
    }
  }

  async function submit(form, submitter) {
    const key = form.dataset.key;
    try {
      if (form.dataset.tv3 === 'filter') {
        view.filters[key] = Object.fromEntries(new FormData(form).entries());
        view.pageNo[key] = 1;
        await reload();
      } else if (form.dataset.tv3 === 'save') {
        const entity = ENTITIES[key];
        const row = view.editing?.key === key ? view.editing.row : null;
        const body = readBody(entity, form, row);
        if (row && !Object.keys(body).length) {
          notice('Chưa có thay đổi nào.');
          return;
        }
        await api(row ? `${entity.path}/${encodeURIComponent(row[entity.id])}` : entity.path,
          { method: row ? 'PATCH' : 'POST', body });
        view.editing = null;
        await reload(row ? 'Đã lưu thay đổi.' : 'Đã thêm mới.');
      } else if (form.dataset.tv3 === 'import') {
        const body = new FormData(form);
        body.set('dry_run', String(submitter?.value !== 'import'));
        const target = document.querySelector('#tv3-import-report');
        target.innerHTML = '<div class="spinner-inline"><span class="spinner" aria-label="Đang xử lý"></span></div>';
        notice('');
        try {
          const { data } = await api('/lab-results/import', { method: 'POST', body });
          target.innerHTML = importReport(data);
        } catch (error) {
          target.innerHTML = '';
          throw error;
        }
      }
    } catch (error) {
      showError(error);
    }
  }

  return { render, click, submit, reset: () => { view = initialView(); } };
}
