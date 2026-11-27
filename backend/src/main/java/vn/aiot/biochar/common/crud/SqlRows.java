package vn.aiot.biochar.common.crud;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chạy truy vấn và trả mỗi dòng thành Map theo tên cột, với kiểu dữ liệu ổn định:
 * DATE → LocalDate, TIMESTAMPTZ → Instant, JSONB → đối tượng JSON, SMALLINT → Integer.
 */
@Component
public class SqlRows {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public SqlRows(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    public List<Map<String, Object>> query(String sql, Object... parameters) {
        return jdbcTemplate.query(sql, (rs, row) -> map(rs), bind(parameters));
    }

    /** Dòng đầu tiên, hoặc null nếu không có. */
    public Map<String, Object> first(String sql, Object... parameters) {
        List<Map<String, Object>> rows = query(sql, parameters);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public long count(String sql, Object... parameters) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, bind(parameters));
        return value == null ? 0 : value;
    }

    public int update(String sql, Object... parameters) {
        return jdbcTemplate.update(sql, bind(parameters));
    }

    public String toJson(Object value) {
        return jsonMapper.writeValueAsString(value);
    }

    /** Driver PostgreSQL không nhận trực tiếp Instant nên đổi sang OffsetDateTime. */
    private Object[] bind(Object[] parameters) {
        Object[] bound = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            Object value = parameters[index];
            bound[index] = value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value;
        }
        return bound;
    }

    private Map<String, Object> map(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int column = 1; column <= meta.getColumnCount(); column++) {
            row.put(meta.getColumnLabel(column), value(rs, column, meta.getColumnTypeName(column)));
        }
        return row;
    }

    private Object value(ResultSet rs, int column, String typeName) throws SQLException {
        if ("jsonb".equals(typeName) || "json".equals(typeName)) {
            String json = rs.getString(column);
            return json == null ? null : jsonMapper.readValue(json, Object.class);
        }
        if ("date".equals(typeName)) {
            return rs.getObject(column, LocalDate.class);
        }
        Object value = rs.getObject(column);
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Short number) {
            return number.intValue();
        }
        if (value instanceof Array array) {
            return new ArrayList<>(Arrays.asList((Object[]) array.getArray()));
        }
        return value;
    }
}
