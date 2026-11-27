# TV4 – Chất lượng dữ liệu và Dataset

TV4 tích hợp trực tiếp với PostgreSQL và API Java hiện có. Backend theo lớp Controller → Service → DAO; giao diện là JavaScript thuần trong SPA chung, dùng chung đăng nhập, quyền, session và style.

## Database

- Dùng schema `data_quality` hiện có: `quality_rules`, `quality_checks`, `data_issues`, `data_audit`.
- Dùng schema `dataset` hiện có: `datasets`, `dataset_versions`, `dataset_records`, `labels`, cùng `build_version`, `freeze_version` và `v_version_readiness`.
- `database/migrations/20_tv4_quality_dataset.sql` thêm kiểm tra slot cảm biến thiếu, ghi nhiệt độ đất vào audit correction và buộc dataset version phải ở trạng thái BUILT, đủ điều kiện theo hàm gốc, không có leakage trước khi freeze.
- Không sửa database init hoặc tạo lại business logic build dataset.

## API

Prefix là `/api/v1/tv4` và yêu cầu JWT cùng authority `data_quality.read/write` hoặc `dataset.read/write`.

- `/quality/rules`: xem, tạo, cập nhật, bật/tắt rule.
- `/quality/checks` và `/quality/checks/run`: lịch sử và chạy QC.
- `/quality/issues`: filter loại/trạng thái/sensor/plot/thời gian, phân trang; `confirm`, `ignore`, `correct` ghi audit.
- `/quality/statistics`, `/quality/audit`: KPI theo dữ liệu database, diễn biến ngày và lineage.
- `/datasets`: quản lý dataset và version, readiness, split counts, leakage, labels, observations, gán nhãn chuyên gia, build/freeze và export CSV.

## Job QC

Job Spring Scheduler mặc định tắt. Bật bằng `TV4_QC_ENABLED=true`; `TV4_QC_INTERVAL_MS` mặc định 900000 ms và `TV4_QC_LOOKBACK_MINUTES` mặc định 30 phút. Mỗi lượt chạy xử lý cửa sổ gần hiện tại, tiếp nối từ lần QC trước để phát hiện cả khoảng cảm biến mất kết nối. Job chạy `data_quality.run_sensor_checks()` hiện hữu và hàm bổ sung kiểm tra missing.

## Chạy

```powershell
docker compose up -d db
mvn -f backend/pom.xml spring-boot:run
```

Trong `.env`, bật `TV4_QC_ENABLED=true` nếu muốn chạy QC tự động. Thủ công có thể chạy QC từ màn hình 08.

## KPI

Màn hình 08 đọc trực tiếp số đọc, trạng thái QC, issue duplicate/missing và số quan sát hợp lệ của dataset version mới nhất. Các tỷ lệ được tính từ dữ liệu DB; dữ liệu thiếu hoặc chưa build sẽ hiện 0/chưa đạt, không được giả lập.
