package vn.aiot.biochar.common.crud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Khai báo một tài nguyên CRUD. Mọi tên bảng, cột và biểu thức SQL ở đây đều là hằng số trong code,
 * không lấy từ dữ liệu người dùng; giá trị người dùng luôn đi qua tham số {@code ?}.
 */
public final class Resource {

    private final String label;
    private final String table;
    private final String idColumn;
    private final Map<String, Field> fields = new LinkedHashMap<>();
    private final Map<String, Filter> filters = new LinkedHashMap<>();
    private final List<String> searchColumns = new ArrayList<>();
    private String select;
    private String orderBy;
    private Consumer<Change> rule = change -> { };
    private Consumer<Change> deleteRule = change -> { };

    private Resource(String label, String table, String idColumn) {
        this.label = label;
        this.table = table;
        this.idColumn = idColumn;
        this.select = "SELECT t.* FROM " + table + " t";
        this.orderBy = "t." + idColumn;
    }

    public static Resource of(String label, String table, String idColumn) {
        return new Resource(label, table, idColumn);
    }

    public Resource fields(Field... items) {
        for (Field field : items) {
            fields.put(field.name(), field);
        }
        return this;
    }

    /** Câu SELECT dùng cho đọc; bảng chính phải đặt bí danh {@code t}. */
    public Resource select(String sql) {
        this.select = sql;
        return this;
    }

    public Resource orderBy(String clause) {
        this.orderBy = clause;
        return this;
    }

    public Resource search(String... columns) {
        searchColumns.addAll(List.of(columns));
        return this;
    }

    /** Bộ lọc theo tham số truy vấn; {@code condition} chứa đúng một dấu {@code ?}. */
    public Resource filter(Field parameter, String condition) {
        filters.put(parameter.name(), new Filter(parameter, condition));
        return this;
    }

    /** Lọc bằng đúng giá trị của cột trong bảng chính. */
    public Resource filter(Field parameter) {
        return filter(parameter, "t." + parameter.name() + " = ?");
    }

    /** Quy tắc nghiệp vụ, chạy trong transaction trước khi INSERT hoặc UPDATE. */
    public Resource rule(Consumer<Change> value) {
        this.rule = value;
        return this;
    }

    public Resource deleteRule(Consumer<Change> value) {
        this.deleteRule = value;
        return this;
    }

    String label() {
        return label;
    }

    String table() {
        return table;
    }

    String idColumn() {
        return idColumn;
    }

    Map<String, Field> fieldMap() {
        return fields;
    }

    Map<String, Filter> filters() {
        return filters;
    }

    List<String> searchColumns() {
        return searchColumns;
    }

    String selectSql() {
        return select;
    }

    String orderClause() {
        return orderBy;
    }

    Consumer<Change> ruleCheck() {
        return rule;
    }

    Consumer<Change> deleteCheck() {
        return deleteRule;
    }

    record Filter(Field parameter, String condition) {
    }
}
