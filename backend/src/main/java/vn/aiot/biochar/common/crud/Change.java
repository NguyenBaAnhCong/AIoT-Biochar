package vn.aiot.biochar.common.crud;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.aiot.biochar.common.api.ApiErrorResponse.ErrorDetail;
import vn.aiot.biochar.common.exception.AppException;
import vn.aiot.biochar.modules.auth.AppUser;

/** Một thao tác ghi đang được kiểm tra: bản ghi hiện tại (nếu có) và các cột sắp ghi. */
public final class Change {

    private final UUID id;
    private final Map<String, Object> current;
    private final Map<String, Object> values;
    private final AppUser actor;

    Change(UUID id, Map<String, Object> current, Map<String, Object> values, AppUser actor) {
        this.id = id;
        this.current = current;
        this.values = values;
        this.actor = actor;
    }

    public boolean creating() {
        return current == null;
    }

    /** Khóa chính của bản ghi đang sửa hoặc xóa; null khi tạo mới. */
    public UUID id() {
        return id;
    }

    public AppUser actor() {
        return actor;
    }

    /** Giá trị sau khi áp thay đổi. */
    public Object get(String column) {
        if (values.containsKey(column)) {
            return values.get(column);
        }
        return current == null ? null : current.get(column);
    }

    public Object before(String column) {
        return current == null ? null : current.get(column);
    }

    public boolean sent(String column) {
        return values.containsKey(column);
    }

    /** True khi tạo mới có gửi cột, hoặc khi sửa làm giá trị cột đổi khác. */
    public boolean changed(String column) {
        if (!values.containsKey(column)) {
            return false;
        }
        if (current == null) {
            return values.get(column) != null;
        }
        Object left = current.get(column);
        Object right = values.get(column);
        if (left instanceof BigDecimal a && right instanceof BigDecimal b) {
            return a.compareTo(b) != 0;
        }
        return !Objects.equals(left, right);
    }

    /** Máy chủ tự gán cột (người dùng không gửi được các cột không khai báo trong Resource). */
    public void set(String column, Object value) {
        values.put(column, value);
    }

    public UUID uuid(String column) {
        return (UUID) get(column);
    }

    public String text(String column) {
        return (String) get(column);
    }

    public LocalDate date(String column) {
        return (LocalDate) get(column);
    }

    public Instant time(String column) {
        return (Instant) get(column);
    }

    public BigDecimal number(String column) {
        Object value = get(column);
        if (value == null || value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        return new BigDecimal(value.toString());
    }

    public static AppException invalid(String field, String message) {
        return new AppException(400, "VALIDATION_ERROR", message, List.of(new ErrorDetail(field, message)));
    }
}
