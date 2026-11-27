# TV5 — Sức khỏe đất, cảnh báo và báo cáo

TV5 chạy trong cùng ứng dụng Java với backend của nhóm. Sau khi đăng nhập, mở **Dữ liệu & AI → Sức khỏe đất – SQI** (04) hoặc **Vận hành → Cảnh báo & quyết định** (12) và **Vận hành → Báo cáo** (13). Các trang dùng quyền `sqi.read`, `alert.read` và `reporting.read` tương ứng. Các thao tác ghi yêu cầu quyền `.write` của nghiệp vụ. Gán nhãn SQI yêu cầu thêm `dataset.write`.

## Cấu trúc theo mẫu UAV

| Phần | Vị trí và trách nhiệm |
| --- | --- |
| Backend Java | `backend/src/main/java/vn/aiot/biochar/modules/tv5/`. Các thư mục `rest`, `service` và `dao` tách nhận HTTP, nghiệp vụ và PostgreSQL. Mỗi tầng có interface và `impl`. DTO, entity, cấu hình và job đặt riêng. |
| Frontend JavaScript | `backend/src/main/resources/static/assets/js/tv5/`. `core` khởi tạo và điều hướng, `api` gọi backend, `components` chứa thành phần dùng chung, `pages` xử lý tương tác, `views` và `theme` dựng giao diện. |

API nghiệp vụ có tiền tố `/api/v1/tv5`. TV5 dùng xác thực JWT, pool kết nối và trình chạy migration của dự án nhóm. Schema, dữ liệu demo và hai SQL migration TV5 được giữ nguyên từ repo trước. Database mới khởi tạo bằng Compose nạp demo một lần. Database đang dùng giữ dữ liệu trong volume của nó.

## Chạy và kiểm tra

Chạy dự án theo [README](../README.md). Với Maven, mở `http://localhost:8080/`. Với Compose, mở `http://localhost:8081/`. Mở trang SQI để xem biểu đồ và cấu hình phiên bản, trang Cảnh báo để xem dữ liệu và luật, trang Báo cáo để tạo và tải PDF hoặc XLSX.

Tệp báo cáo lưu trong `.tv5-reports/` khi chạy local và volume `tv5_reports` khi chạy Compose. Đổi vị trí bằng `TV5_REPORT_DIR`. Lịch sử trong database và tệp tải xuống là hai phần riêng, nên để tải lại báo cáo đã xuất ở hệ cũ cần mang theo các tệp tương ứng.

Job chạy định kỳ khi bật `TV5_JOBS_ENABLED=true`. Dùng `TV5_DATASET_ID` trỏ đến version dataset trạng thái `BUILT` để job tự tính SQI và gán nhãn. Có thể đặt `TV5_ALERT_MINUTES`, `TV5_SQI_MINUTES` và `TV5_USERNAME`. Gửi Telegram cần cấu hình cả `TV5_TELEGRAM_TOKEN` và `TV5_TELEGRAM_CHAT` trong môi trường chạy.
