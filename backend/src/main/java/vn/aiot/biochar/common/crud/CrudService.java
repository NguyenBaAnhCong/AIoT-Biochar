package vn.aiot.biochar.common.crud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.aiot.biochar.common.api.ApiErrorResponse.ErrorDetail;
import vn.aiot.biochar.common.api.ApiResponse;
import vn.aiot.biochar.common.db.UserTransactionService;
import vn.aiot.biochar.common.exception.AppException;
import vn.aiot.biochar.modules.auth.AppUser;

/** Danh sách, xem, tạo, sửa, xóa cho các {@link Resource}. Mọi thao tác ghi đặt app.user_id cho audit. */
@Service
public class CrudService {

    public static final int MAX_LIMIT = 200;

    private final SqlRows rows;
    private final UserTransactionService transactions;

    public CrudService(SqlRows rows, UserTransactionService transactions) {
        this.rows = rows;
        this.transactions = transactions;
    }

    public ApiResponse<List<Map<String, Object>>> list(Resource resource, Map<String, String> query) {
        int page = pageNumber(query.get("page"), "page", 1, 1_000_000);
        int limit = pageNumber(query.get("limit"), "limit", 20, MAX_LIMIT);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();

        resource.filters().forEach((name, filter) -> {
            String raw = query.get(name);
            if (raw == null || raw.isBlank()) {
                return;
            }
            try {
                parameters.add(filter.parameter().parse(raw));
            } catch (IllegalArgumentException exception) {
                throw Change.invalid(name, exception.getMessage());
            }
            where.append(" AND ").append(filter.condition());
        });

        String search = query.get("q");
        if (search != null && !search.isBlank() && !resource.searchColumns().isEmpty()) {
            if (search.length() > 100) {
                throw Change.invalid("q", "Tối đa 100 ký tự");
            }
            String pattern = "%" + search.trim()
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            List<String> conditions = new ArrayList<>();
            for (String column : resource.searchColumns()) {
                conditions.add(column + " ILIKE ?");
                parameters.add(pattern);
            }
            where.append(" AND (").append(String.join(" OR ", conditions)).append(")");
        }

        String filtered = resource.selectSql() + where;
        long total = rows.count("SELECT count(*) FROM (" + filtered + ") counted", parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(limit);
        pageParameters.add((long) (page - 1) * limit);
        List<Map<String, Object>> data = rows.query(
                filtered + " ORDER BY " + resource.orderClause() + " LIMIT ? OFFSET ?", pageParameters.toArray());
        return ApiResponse.page(data, new ApiResponse.PageMeta(page, limit, total));
    }

    public Map<String, Object> get(Resource resource, UUID id) {
        Map<String, Object> row = rows.first(
                resource.selectSql() + " WHERE t." + resource.idColumn() + " = ?", id);
        if (row == null) {
            throw notFound(resource);
        }
        return row;
    }

    public Map<String, Object> create(Resource resource, Map<String, Object> body, AppUser actor) {
        Map<String, Object> values = parseBody(resource, body, true);
        return transactions.execute(actor.id(), "Thêm " + resource.label(), () -> {
            checkReferences(resource, values);
            resource.ruleCheck().accept(new Change(null, null, values, actor));
            List<String> columns = new ArrayList<>();
            List<String> marks = new ArrayList<>();
            List<Object> parameters = new ArrayList<>();
            values.forEach((column, value) -> {
                if (value != null) {
                    columns.add(column);
                    marks.add(placeholder(resource, column));
                    parameters.add(sqlValue(resource, column, value));
                }
            });
            Map<String, Object> inserted = rows.first(
                    "INSERT INTO " + resource.table() + " (" + String.join(", ", columns) + ") VALUES ("
                            + String.join(", ", marks) + ") RETURNING " + resource.idColumn() + " AS id",
                    parameters.toArray());
            return get(resource, (UUID) inserted.get("id"));
        });
    }

    public Map<String, Object> update(Resource resource, UUID id, Map<String, Object> body, AppUser actor) {
        Map<String, Object> values = parseBody(resource, body, false);
        if (values.isEmpty()) {
            throw AppException.badRequest("Không có trường nào để cập nhật");
        }
        return transactions.execute(actor.id(), "Sửa " + resource.label(), () -> {
            Map<String, Object> current = lock(resource, id);
            checkReferences(resource, values);
            resource.ruleCheck().accept(new Change(id, current, values, actor));
            List<String> assignments = new ArrayList<>();
            List<Object> parameters = new ArrayList<>();
            values.forEach((column, value) -> {
                assignments.add(column + " = " + placeholder(resource, column));
                parameters.add(sqlValue(resource, column, value));
            });
            parameters.add(id);
            rows.update("UPDATE " + resource.table() + " SET " + String.join(", ", assignments)
                    + " WHERE " + resource.idColumn() + " = ?", parameters.toArray());
            return get(resource, id);
        });
    }

    public void delete(Resource resource, UUID id, AppUser actor) {
        transactions.execute(actor.id(), "Xóa " + resource.label(), () -> {
            Map<String, Object> current = lock(resource, id);
            resource.deleteCheck().accept(new Change(id, current, new LinkedHashMap<>(), actor));
            rows.update("DELETE FROM " + resource.table() + " WHERE " + resource.idColumn() + " = ?", id);
            return null;
        });
    }

    private Map<String, Object> lock(Resource resource, UUID id) {
        Map<String, Object> row = rows.first(
                "SELECT * FROM " + resource.table() + " WHERE " + resource.idColumn() + " = ? FOR UPDATE", id);
        if (row == null) {
            throw notFound(resource);
        }
        return row;
    }

    private AppException notFound(Resource resource) {
        return AppException.notFound("Không tìm thấy " + resource.label());
    }

    private Map<String, Object> parseBody(Resource resource, Map<String, Object> body, boolean creating) {
        List<ErrorDetail> errors = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        body.forEach((name, raw) -> {
            Field field = resource.fieldMap().get(name);
            if (field == null) {
                errors.add(new ErrorDetail(name, "Trường không được phép ghi"));
                return;
            }
            try {
                values.put(name, field.parse(raw));
            } catch (IllegalArgumentException exception) {
                errors.add(new ErrorDetail(name, exception.getMessage()));
            }
        });
        if (creating) {
            resource.fieldMap().values().stream()
                    .filter(field -> field.isRequired() && !body.containsKey(field.name()))
                    .forEach(field -> errors.add(new ErrorDetail(field.name(), "Bắt buộc nhập")));
        }
        if (!errors.isEmpty()) {
            throw new AppException(400, "VALIDATION_ERROR", "Dữ liệu không hợp lệ", errors);
        }
        return values;
    }

    private void checkReferences(Resource resource, Map<String, Object> values) {
        List<ErrorDetail> errors = new ArrayList<>();
        values.forEach((name, value) -> {
            Field field = resource.fieldMap().get(name);
            if (value != null && field != null && field.refTable() != null
                    && rows.count("SELECT count(*) FROM " + field.refTable()
                            + " WHERE " + field.refColumn() + " = ?", value) == 0) {
                errors.add(new ErrorDetail(name, "Không tồn tại trong hệ thống"));
            }
        });
        if (!errors.isEmpty()) {
            throw new AppException(400, "VALIDATION_ERROR", "Dữ liệu tham chiếu không hợp lệ", errors);
        }
    }

    private String placeholder(Resource resource, String column) {
        Field field = resource.fieldMap().get(column);
        return field != null && field.type() == Field.Type.JSON ? "?::jsonb" : "?";
    }

    private Object sqlValue(Resource resource, String column, Object value) {
        Field field = resource.fieldMap().get(column);
        return value != null && field != null && field.type() == Field.Type.JSON ? rows.toJson(value) : value;
    }

    private int pageNumber(String raw, String name, int fallback, int maximum) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value >= 1 && value <= maximum) {
                return value;
            }
        } catch (NumberFormatException exception) {
            // rơi xuống báo lỗi bên dưới
        }
        throw Change.invalid(name, name + " phải từ 1 đến " + maximum);
    }
}
