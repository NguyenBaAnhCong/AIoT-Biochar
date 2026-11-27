package vn.aiot.biochar.common.crud;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Mô tả một cột được phép ghi qua API: kiểu dữ liệu, giới hạn và cách đọc giá trị từ JSON. */
public final class Field {

    public enum Type { TEXT, CHOICE, UUID, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP, JSON }

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final String name;
    private final Type type;
    private int maxLength = 2000;
    private Pattern pattern;
    private List<String> choices = List.of();
    private BigDecimal min;
    private BigDecimal max;
    private boolean positive;
    private boolean required;
    private boolean notNull;
    private boolean upperCase;
    private String refTable;
    private String refColumn;

    private Field(String name, Type type) {
        this.name = name;
        this.type = type;
    }

    public static Field text(String name, int maxLength) {
        Field field = new Field(name, Type.TEXT);
        field.maxLength = maxLength;
        return field;
    }

    /** Mã định danh: chữ, số, gạch ngang, gạch dưới, dấu chấm. */
    public static Field code(String name) {
        Field field = text(name, 32);
        field.pattern = Pattern.compile("^[A-Za-z0-9_.-]+$");
        return field;
    }

    public static Field choice(String name, String... values) {
        Field field = new Field(name, Type.CHOICE);
        field.choices = List.of(values);
        return field;
    }

    public static Field uuid(String name) {
        return new Field(name, Type.UUID);
    }

    public static Field integer(String name, long min, long max) {
        return new Field(name, Type.INTEGER).range(min, max);
    }

    public static Field decimal(String name, double min, double max) {
        Field field = new Field(name, Type.DECIMAL);
        field.min = BigDecimal.valueOf(min);
        field.max = BigDecimal.valueOf(max);
        return field;
    }

    public static Field bool(String name) {
        return new Field(name, Type.BOOLEAN);
    }

    public static Field date(String name) {
        return new Field(name, Type.DATE);
    }

    public static Field timestamp(String name) {
        return new Field(name, Type.TIMESTAMP);
    }

    public static Field json(String name) {
        return new Field(name, Type.JSON);
    }

    private Field range(long minimum, long maximum) {
        this.min = BigDecimal.valueOf(minimum);
        this.max = BigDecimal.valueOf(maximum);
        return this;
    }

    /** Bắt buộc khi tạo mới và không được xóa trống. */
    public Field required() {
        this.required = true;
        this.notNull = true;
        return this;
    }

    /** Cột NOT NULL đã có giá trị mặc định: được bỏ qua khi tạo nhưng không được gửi null. */
    public Field notNull() {
        this.notNull = true;
        return this;
    }

    public Field positive() {
        this.positive = true;
        return this;
    }

    public Field upperCase() {
        this.upperCase = true;
        return this;
    }

    public Field references(String table, String column) {
        this.refTable = table;
        this.refColumn = column;
        return this;
    }

    public String name() {
        return name;
    }

    public Type type() {
        return type;
    }

    public boolean isRequired() {
        return required;
    }

    public String refTable() {
        return refTable;
    }

    public String refColumn() {
        return refColumn;
    }

    /** Chuyển giá trị JSON sang kiểu Java; ném IllegalArgumentException kèm thông báo cho người dùng. */
    public Object parse(Object raw) {
        if (raw == null || (raw instanceof String string && string.isBlank())) {
            if (notNull) {
                throw new IllegalArgumentException("Không được để trống");
            }
            return null;
        }
        return switch (type) {
            case TEXT -> parseText(raw);
            case CHOICE -> parseChoice(raw);
            case UUID -> parseUuid(raw);
            case INTEGER -> parseInteger(raw);
            case DECIMAL -> parseDecimal(raw);
            case BOOLEAN -> parseBoolean(raw);
            case DATE -> parseDate(raw);
            case TIMESTAMP -> parseTimestamp(raw);
            case JSON -> parseJsonObject(raw);
        };
    }

    private String parseText(Object raw) {
        if (!(raw instanceof String string)) {
            throw new IllegalArgumentException("Phải là chuỗi ký tự");
        }
        String value = string.trim();
        if (upperCase) {
            value = value.toUpperCase(Locale.ROOT);
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("Tối đa " + maxLength + " ký tự");
        }
        if (pattern != null && !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Chỉ dùng chữ không dấu, số, '.', '_' và '-'");
        }
        return value;
    }

    private String parseChoice(Object raw) {
        String value = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (!choices.contains(value)) {
            throw new IllegalArgumentException("Phải là một trong: " + String.join(", ", choices));
        }
        return value;
    }

    private UUID parseUuid(Object raw) {
        String value = String.valueOf(raw).trim();
        if (!value.matches("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")) {
            throw new IllegalArgumentException("UUID không hợp lệ");
        }
        return java.util.UUID.fromString(value);
    }

    private BigDecimal parseNumber(Object raw) {
        if (!(raw instanceof Number) && !(raw instanceof String)) {
            throw new IllegalArgumentException("Phải là số");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.toString().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Phải là số");
        }
        if (positive && value.signum() <= 0) {
            throw new IllegalArgumentException("Phải lớn hơn 0");
        }
        if ((min != null && value.compareTo(min) < 0) || (max != null && value.compareTo(max) > 0)) {
            throw new IllegalArgumentException(
                    "Phải trong khoảng " + min.stripTrailingZeros().toPlainString()
                            + " – " + max.stripTrailingZeros().toPlainString());
        }
        return value;
    }

    private BigDecimal parseDecimal(Object raw) {
        return parseNumber(raw);
    }

    private Integer parseInteger(Object raw) {
        try {
            return parseNumber(raw).intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Phải là số nguyên");
        }
    }

    private Boolean parseBoolean(Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if ("true".equals(raw) || "false".equals(raw)) {
            return Boolean.valueOf((String) raw);
        }
        throw new IllegalArgumentException("Phải là true hoặc false");
    }

    private LocalDate parseDate(Object raw) {
        try {
            LocalDate value = LocalDate.parse(String.valueOf(raw).trim());
            checkYear(value.getYear());
            return value;
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Ngày phải có dạng YYYY-MM-DD");
        }
    }

    private java.time.Instant parseTimestamp(Object raw) {
        String value = String.valueOf(raw).trim();
        try {
            OffsetDateTime parsed = value.matches(".*(Z|[+-]\\d{2}:\\d{2})$")
                    ? OffsetDateTime.parse(value)
                    : LocalDateTime.parse(value).atZone(ZONE).toOffsetDateTime();
            checkYear(parsed.getYear());
            return parsed.toInstant();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Thời điểm phải theo ISO 8601, ví dụ 2026-03-15T08:30:00+07:00");
        }
    }

    private void checkYear(int year) {
        if (year < 1900 || year > 2200) {
            throw new IllegalArgumentException("Năm phải trong khoảng 1900 – 2200");
        }
    }

    private Map<String, Object> parseJsonObject(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Phải là đối tượng JSON");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        map.forEach((key, item) -> value.put(String.valueOf(key), item));
        return value;
    }
}
