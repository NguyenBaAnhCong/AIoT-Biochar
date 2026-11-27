package vn.aiot.biochar.modules.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.aiot.biochar.common.exception.AppException;

@Service
public class AuditService {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public AuditService(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    public Page<AuditLogSummary> auditLogs(
            String schema, String table, String operation, UUID actorId, String record,
            OffsetDateTime from, OffsetDateTime to, int page, int limit) {
        List<Object> parameters = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        addFilter(where, parameters, "a.schema_name = ?", schema);
        addFilter(where, parameters, "a.table_name = ?", table);
        addFilter(where, parameters, "a.operation = ?", operation);
        addFilter(where, parameters, "a.actor_id = ?", actorId);
        addFilter(where, parameters, "a.occurred_at >= ?", from == null ? null : Timestamp.from(from.toInstant()));
        addFilter(where, parameters, "a.occurred_at < ?", to == null ? null : Timestamp.from(to.toInstant()));
        if (record != null && !record.isBlank()) {
            where.append(" AND a.record_pk::text ILIKE ? ESCAPE '\\'");
            parameters.add("%" + escapeLike(record.trim()) + "%");
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit.audit_logs a" + where, Long.class, parameters.toArray());
        List<Object> queryParameters = new ArrayList<>(parameters);
        queryParameters.add(limit);
        queryParameters.add((page - 1) * limit);
        List<AuditLogSummary> rows = jdbcTemplate.query("""
                SELECT a.audit_log_id, a.occurred_at, a.actor_id, a.actor_type, u.username AS actor_username,
                       a.schema_name, a.table_name, a.operation, a.record_pk::text AS record_pk,
                       a.changed_fields, a.client_ip, a.application
                  FROM audit.audit_logs a
                  LEFT JOIN iam.users u ON u.user_id = a.actor_id
                """ + where + " ORDER BY a.occurred_at DESC, a.audit_log_id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new AuditLogSummary(
                        rs.getLong("audit_log_id"),
                        rs.getTimestamp("occurred_at"),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_type"),
                        rs.getString("actor_username"),
                        rs.getString("schema_name"),
                        rs.getString("table_name"),
                        rs.getString("operation"),
                        parseJson(rs.getString("record_pk")),
                        strings(rs.getArray("changed_fields")),
                        rs.getString("client_ip"),
                        rs.getString("application")), queryParameters.toArray());
        return new Page<>(rows, page, limit, total == null ? 0 : total);
    }

    public AuditLogDetail auditLog(long id) {
        List<AuditLogDetail> rows = jdbcTemplate.query("""
                SELECT a.audit_log_id, a.occurred_at, a.actor_id, a.actor_type, u.username AS actor_username,
                       a.db_user, a.schema_name, a.table_name, a.operation, a.record_pk::text AS record_pk,
                       a.old_data::text AS old_data, a.new_data::text AS new_data,
                       a.changed_fields, a.client_ip, a.application
                  FROM audit.audit_logs a
                  LEFT JOIN iam.users u ON u.user_id = a.actor_id
                 WHERE a.audit_log_id = ?
                """, (rs, row) -> new AuditLogDetail(
                rs.getLong("audit_log_id"),
                rs.getTimestamp("occurred_at"),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_type"),
                rs.getString("actor_username"),
                rs.getString("db_user"),
                rs.getString("schema_name"),
                rs.getString("table_name"),
                rs.getString("operation"),
                parseJson(rs.getString("record_pk")),
                parseJson(rs.getString("old_data")),
                parseJson(rs.getString("new_data")),
                strings(rs.getArray("changed_fields")),
                rs.getString("client_ip"),
                rs.getString("application")), id);
        if (rows.isEmpty()) {
            throw AppException.notFound("Không tìm thấy bản ghi audit");
        }
        return rows.getFirst();
    }

    public Page<AccessLogView> accessLogs(
            String action, UUID userId, UUID apiKeyId, Integer status, String method,
            OffsetDateTime from, OffsetDateTime to, int page, int limit) {
        List<Object> parameters = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        addFilter(where, parameters, "l.action = ?", action);
        addFilter(where, parameters, "l.user_id = ?", userId);
        addFilter(where, parameters, "l.api_key_id = ?", apiKeyId);
        addFilter(where, parameters, "l.status_code = ?", status);
        addFilter(where, parameters, "l.http_method = ?", method);
        addFilter(where, parameters, "l.occurred_at >= ?", from == null ? null : Timestamp.from(from.toInstant()));
        addFilter(where, parameters, "l.occurred_at < ?", to == null ? null : Timestamp.from(to.toInstant()));

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit.access_logs l" + where, Long.class, parameters.toArray());
        List<Object> queryParameters = new ArrayList<>(parameters);
        queryParameters.add(limit);
        queryParameters.add((page - 1) * limit);
        List<AccessLogView> rows = jdbcTemplate.query("""
                SELECT l.access_log_id, l.occurred_at, l.user_id, u.username,
                       l.api_key_id, k.key_prefix AS api_key_prefix, l.action, l.resource,
                       l.http_method, l.status_code, l.ip_address, l.user_agent
                  FROM audit.access_logs l
                  LEFT JOIN iam.users u ON u.user_id = l.user_id
                  LEFT JOIN iam.api_keys k ON k.api_key_id = l.api_key_id
                """ + where + " ORDER BY l.occurred_at DESC, l.access_log_id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new AccessLogView(
                        rs.getLong("access_log_id"),
                        rs.getTimestamp("occurred_at"),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("username"),
                        rs.getObject("api_key_id", UUID.class),
                        rs.getString("api_key_prefix"),
                        rs.getString("action"),
                        rs.getString("resource"),
                        rs.getString("http_method"),
                        rs.getObject("status_code", Integer.class),
                        rs.getString("ip_address"),
                        rs.getString("user_agent")), queryParameters.toArray());
        return new Page<>(rows, page, limit, total == null ? 0 : total);
    }

    public AuditSummary summary() {
        Map<String, Integer> operations = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT operation, count(*)::int AS count
                  FROM audit.audit_logs
                 WHERE occurred_at > now() - interval '24 hours'
                 GROUP BY operation
                 ORDER BY operation
                """, (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> operations.put(rs.getString("operation"), rs.getInt("count")));
        List<AccessCounts> access = jdbcTemplate.query("""
                SELECT count(*) FILTER (WHERE action = 'LOGIN')::int AS logins,
                       count(*) FILTER (WHERE action = 'LOGIN_FAILED')::int AS failed_logins,
                       count(*) FILTER (WHERE status_code IN (401, 403) AND action = 'API_CALL')::int AS denied_calls
                  FROM audit.access_logs
                 WHERE occurred_at > now() - interval '24 hours'
                """, (rs, row) -> new AccessCounts(
                rs.getInt("logins"), rs.getInt("failed_logins"), rs.getInt("denied_calls")));
        AccessCounts counts = access.getFirst();
        return new AuditSummary(24, operations, counts.logins(), counts.failedLogins(), counts.deniedCalls());
    }

    private void addFilter(StringBuilder where, List<Object> parameters, String expression, Object value) {
        if (value != null && !(value instanceof String text && text.isBlank())) {
            where.append(" AND ").append(expression);
            parameters.add(value);
        }
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private JsonNode parseJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            return jsonMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Không thể đọc dữ liệu JSON trong audit log", exception);
        }
    }

    private List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        return java.util.Arrays.stream(values).map(String::valueOf).toList();
    }

    public record Page<T>(List<T> rows, int page, int limit, long total) {
    }

    public record AuditLogSummary(
            @JsonProperty("id") long id,
            @JsonProperty("occurred_at") Timestamp occurredAt,
            @JsonProperty("actor_id") UUID actorId,
            @JsonProperty("actor_type") String actorType,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("schema_name") String schemaName,
            @JsonProperty("table_name") String tableName,
            String operation,
            @JsonProperty("record_pk") JsonNode recordPk,
            @JsonProperty("changed_fields") List<String> changedFields,
            @JsonProperty("client_ip") String clientIp,
            String application) {
    }

    public record AuditLogDetail(
            @JsonProperty("id") long id,
            @JsonProperty("occurred_at") Timestamp occurredAt,
            @JsonProperty("actor_id") UUID actorId,
            @JsonProperty("actor_type") String actorType,
            @JsonProperty("actor_username") String actorUsername,
            @JsonProperty("db_user") String dbUser,
            @JsonProperty("schema_name") String schemaName,
            @JsonProperty("table_name") String tableName,
            String operation,
            @JsonProperty("record_pk") JsonNode recordPk,
            @JsonProperty("old_data") JsonNode oldData,
            @JsonProperty("new_data") JsonNode newData,
            @JsonProperty("changed_fields") List<String> changedFields,
            @JsonProperty("client_ip") String clientIp,
            String application) {
    }

    public record AccessLogView(
            @JsonProperty("id") long id,
            @JsonProperty("occurred_at") Timestamp occurredAt,
            @JsonProperty("user_id") UUID userId,
            String username,
            @JsonProperty("api_key_id") UUID apiKeyId,
            @JsonProperty("api_key_prefix") String apiKeyPrefix,
            String action,
            String resource,
            @JsonProperty("http_method") String httpMethod,
            @JsonProperty("status_code") Integer statusCode,
            @JsonProperty("ip_address") String ipAddress,
            @JsonProperty("user_agent") String userAgent) {
    }

    public record AuditSummary(
            @JsonProperty("window_hours") int windowHours,
            @JsonProperty("changes_by_operation") Map<String, Integer> changesByOperation,
            int logins,
            @JsonProperty("failed_logins") int failedLogins,
            @JsonProperty("denied_calls") int deniedCalls) {
    }

    private record AccessCounts(int logins, int failedLogins, int deniedCalls) {
    }
}
