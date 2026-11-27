# API của TV3 – khu đất, thí nghiệm, biochar, phòng thí nghiệm

Địa chỉ gốc: `/api/v1`, gửi `Authorization: Bearer <accessToken>`. Định dạng phản hồi và lỗi giống
[API_TV1.md](API_TV1.md). JSON dùng **snake_case** giống tên cột.

## Quy ước chung cho mọi tài nguyên CRUD

| Phương thức | Đường dẫn | Quyền | Mô tả |
|---|---|---|---|
| GET | `/<tài-nguyên>?page=1&limit=20` | `<domain>.read` | Danh sách, `limit` tối đa 200, có `meta` |
| GET | `/<tài-nguyên>/{id}` | `<domain>.read` | Một bản ghi |
| POST | `/<tài-nguyên>` | `<domain>.write` | Tạo, trả `201` |
| PATCH | `/<tài-nguyên>/{id}` | `<domain>.write` | Chỉ gửi trường cần đổi; gửi `null` để xóa trống |
| DELETE | `/<tài-nguyên>/{id}` | `<domain>.write` | Trả `204`; bản ghi đang được tham chiếu trả `409` |

Trường không được phép ghi (kể cả `result_status`, `analyst_id`, `approved_by`, `applied_by`, cột tự tính)
bị từ chối với `400`. Ngày: `YYYY-MM-DD`. Thời điểm: ISO 8601; nếu thiếu múi giờ thì hiểu là giờ Việt Nam.
Mọi thao tác ghi đặt `app.user_id` để trigger audit ghi đúng người thực hiện.

## Tài nguyên

`*` là bắt buộc khi tạo.

| Đường dẫn | Domain | Trường ghi được | Lọc / tìm (`q`) |
|---|---|---|---|
| `/sites` | farm | `site_code*`, `name*`, `organization_id`, `province`, `district`, `latitude`, `longitude`, `elevation_m`, `climate_zone` | `q` |
| `/plots` | farm | `site_id*`, `plot_code*`, `area_m2*`, `soil_type*`, `soil_texture`, `latitude`, `longitude`, `boundary_geojson`, `block_no`, `status` | `site_id`, `status`, `q` |
| `/crops` | farm | `crop_code*`, `name_vi*`, `scientific_name`, `variety` | `q` |
| `/crop-seasons` | farm | `plot_id*`, `crop_id*`, `season_code*`, `planted_on*`, `harvested_on`, `planting_density`, `notes` | `plot_id`, `crop_id`, `q` |
| `/experiments` | experiment | `site_id*`, `experiment_code*`, `title*`, `started_on*`, `design_type`, `replicates`, `ended_on`, `principal_investigator`, `status`, `objective` | `site_id`, `status`, `q` |
| `/treatments` | experiment | `experiment_id*`, `treatment_code*`, `name*`, `biochar_dose_kg_m2`, `biochar_batch_id`, `enrichment_batch_id`, `control_flag`, `microbial_formula`, `fertilizer_desc`, `description` | `experiment_id`, `q` |
| `/treatment-applications` | experiment | `treatment_id*`, `plot_id*`, `applied_on*`, `crop_season_id`, `replicate_no`, `effective_from`, `effective_to`, `actual_dose_kg_m2`, `notes` | `experiment_id`, `treatment_id`, `plot_id` |
| `/growth-stages` | experiment | `crop_id*`, `stage_code*`, `name_vi*`, `stage_order*`, `typical_days_from_planting` | `crop_id` |
| `/plot-growth-stages` | experiment | `crop_season_id*`, `growth_stage_id*`, `started_at*`, `ended_at` | `crop_season_id`, `plot_id` |
| `/biochar/feedstock-batches` | biochar | `batch_code*`, `collected_on*`, `mass_kg*`, `feedstock_type`, `source_location`, `moisture_pct`, `pretreatment`, `notes` | `feedstock_type`, `q` |
| `/biochar/batches` | biochar | `batch_code*`, `feedstock_batch_id*`, `produced_on*`, thông số nhiệt phân, chỉ tiêu chất lượng, `heavy_metals_mg_kg`, `safety_status`, `notes` | `feedstock_batch_id`, `safety_status`, `q` |
| `/biochar/enrichment-batches` | biochar | `batch_code*`, `biochar_batch_id*`, `enrichment_type*`, `started_on*`, `finished_on`, `microbial_formula`, `nutrient_formula`, `ratio_desc`, `notes` | `biochar_batch_id`, `q` |
| `/biochar/enrichment-results` | biochar | `enrichment_batch_id*`, `parameter_code*`, `value*`, `unit*`, `measured_on*`, `method`, `pass_flag` | `enrichment_batch_id` |
| `/lab-methods` | laboratory | `method_code*`, `parameter_code*`, `name*`, `unit*`, `standard_ref`, `instrument`, `detection_limit`, `quantification_limit` | `parameter_code`, `q` |
| `/samples` | laboratory | `sample_code*`, `plot_id*`, `sampled_at*`, `sample_type`, `depth_from_cm`, `depth_to_cm`, `latitude`, `longitude`, `near_node_id`, `composite_of`, `received_at`, `status`, `notes` | `plot_id`, `status`, `from`, `to`, `q` |
| `/lab-results` | laboratory | `sample_id*`, `parameter_code*`, `value*`, `unit`, `method_id`, `replicate_no`, `below_lod`, `analyzed_at` | `sample_id`, `plot_id`, `parameter_code`, `result_status`, `q` |

## API riêng

| Phương thức | Đường dẫn | Quyền | Mô tả |
|---|---|---|---|
| GET | `/biochar/batches/{id}/trace` | `biochar.read` | Phụ phẩm đầu vào, lô làm giàu, nghiệm thức và ô đã dùng |
| POST | `/lab-results/{id}/verify` | `laboratory.write` | DRAFT → VERIFIED |
| POST | `/lab-results/{id}/approve` | `laboratory.approve` | VERIFIED → APPROVED |
| POST | `/lab-results/{id}/reject` | `laboratory.approve` | DRAFT hoặc VERIFIED → REJECTED |
| POST | `/lab-results/bulk/{verify\|approve\|reject}` | như trên | `{ "ids": [...] }` (tối đa 200); một dòng lỗi thì không dòng nào đổi |
| GET | `/lab-results/import-template` | `laboratory.read` | Tải file Excel mẫu (trang hướng dẫn lấy mã từ database) |
| POST | `/lab-results/import` | `laboratory.write` | `multipart/form-data`: `file` (.xlsx ≤ 5 MB, ≤ 2000 dòng), `dry_run` |
| GET | `/sensor-vs-lab?plot_id=&parameter_code=` | `laboratory.read` | Từ `laboratory.v_sensor_vs_lab` |

Kết quả nhập Excel trả `200` kèm báo cáo
`{ total_rows, valid_rows, error_rows, inserted, dry_run, errors: [{ row, field, message }] }`.
Có bất kỳ lỗi dòng nào thì `inserted = 0`. File hỏng, thiếu cột bắt buộc hoặc không có dữ liệu trả `400`;
file quá 5 MB trả `413`. File mẫu tĩnh: [mau-ket-qua-phong-thi-nghiem.xlsx](mau-ket-qua-phong-thi-nghiem.xlsx).

## Quy tắc nghiệp vụ

**Khu đất và thí nghiệm**
- Mùa vụ trên cùng một ô không chồng lấn; giai đoạn sinh trưởng phải đúng cây trồng, bắt đầu trong mùa vụ và không chồng lấn.
- Thí nghiệm chỉ chuyển `PLANNED → RUNNING → COMPLETED`, hoặc sang `CANCELLED` từ `PLANNED`/`RUNNING`; `COMPLETED` phải có `ended_on`.
- Nghiệm thức đối chứng: liều 0, không gắn lô biochar. Lô làm giàu phải được tạo từ đúng lô biochar đã chọn.
- Gán nghiệm thức: ô phải cùng địa điểm với thí nghiệm; một ô chỉ có một nghiệm thức hiệu lực tại một thời điểm (`409 OVERLAP`);
  lô biochar phải `PASSED` (`409 BIOCHAR_NOT_SAFE`). Bỏ trống `effective_from` thì lấy 00:00 ngày bón.

**Biochar**
- Tổng khối lượng đưa vào nhiệt phân không vượt khối lượng lô phụ phẩm; khối lượng ra không lớn hơn khối lượng vào.
- Ngày sản xuất không trước ngày thu gom; ngày làm giàu không trước ngày sản xuất.
- `PASSED` chỉ được kết luận khi đã có `heavy_metals_mg_kg`. Sửa số liệu kim loại nặng mà không gửi kèm kết luận thì trạng thái về `PENDING`.
- Lô `FAILED` không được làm giàu hay dùng cho nghiệm thức mới. Hệ thống **không tự áp ngưỡng** kim loại nặng: người đánh giá kết luận.

**Phòng thí nghiệm**
- Đơn vị phải trùng đơn vị chuẩn trong `ref.parameters`; giá trị phải nằm trong giới hạn vật lý; phương pháp phải đúng chỉ tiêu.
- Người nhập/sửa số liệu sau cùng (`analyst_id`) không được tự duyệt (`409 FOUR_EYES`).
- `VERIFIED` và `APPROVED` không sửa, xóa được. `VERIFIED` có thể bị từ chối rồi sửa lại (về `DRAFT`); `APPROVED` là trạng thái cuối.
- Mẫu đã có kết quả thì không xóa được; mẫu có kết quả đã xác nhận thì không đổi ô, thời điểm, loại hoặc loại bỏ.
