package vn.aiot.biochar.modules.data_quality;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import vn.aiot.biochar.common.exception.AppException;

@Repository
public class DataQualityDaoImpl implements DataQualityDao {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    public DataQualityDaoImpl(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions, JsonMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public List<Map<String, Object>> rules() {
        return jdbc.queryForList("""
                SELECT rule_id, rule_code, name, target, rule_type, parameter_code, min_value,
                       max_value, max_delta, window_minutes, severity, is_active, description
                FROM data_quality.quality_rules ORDER BY rule_code
                """, Map.of());
    }

    @Override
    public UUID createRule(String code, String name, String target, String type, String parameter,
                           BigDecimal min, BigDecimal max, BigDecimal maxDelta, Integer window,
                           String severity, String description) {
        return jdbc.queryForObject("""
                INSERT INTO data_quality.quality_rules
                    (rule_code,name,target,rule_type,parameter_code,min_value,max_value,max_delta,window_minutes,severity,description)
                VALUES (:code,:name,:target,:type,:parameter,:min,:max,:delta,:window,:severity,:description)
                RETURNING rule_id
                """, new MapSqlParameterSource().addValue("code", code).addValue("name", name)
                .addValue("target", target).addValue("type", type).addValue("parameter", parameter)
                .addValue("min", min).addValue("max", max).addValue("delta", maxDelta)
                .addValue("window", window).addValue("severity", severity).addValue("description", description), UUID.class);
    }

    @Override
    public void updateRule(UUID id, String name, String target, String type, String parameter,
                           BigDecimal min, BigDecimal max, BigDecimal maxDelta, Integer window,
                           String severity, String description) {
        int updated = jdbc.update("""
                UPDATE data_quality.quality_rules
                SET name=:name,target=:target,rule_type=:type,parameter_code=:parameter,
                    min_value=:min,max_value=:max,max_delta=:delta,window_minutes=:window,
                    severity=:severity,description=:description
                WHERE rule_id=:id
                """, new MapSqlParameterSource().addValue("id", id).addValue("name", name)
                .addValue("target", target).addValue("type", type).addValue("parameter", parameter)
                .addValue("min", min).addValue("max", max).addValue("delta", maxDelta)
                .addValue("window", window).addValue("severity", severity).addValue("description", description));
        if (updated == 0) throw AppException.notFound("Không tìm thấy luật QC.");
    }

    @Override
    public void setRuleActive(UUID id, boolean active) {
        if (jdbc.update("UPDATE data_quality.quality_rules SET is_active=:active WHERE rule_id=:id",
                Map.of("active", active, "id", id)) == 0) {
            throw AppException.notFound("Không tìm thấy luật QC.");
        }
    }

    @Override
    public Map<String, Object> issue(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT issue_id, check_id, issue_type, source_table, source_record_id, sensor_id, plot_id,
                       observed_at, parameter_code, observed_value, status, details
                FROM data_quality.data_issues WHERE issue_id=:id
                """, Map.of("id", id));
        if (rows.isEmpty()) throw AppException.notFound("Không tìm thấy issue dữ liệu.");
        return rows.getFirst();
    }

    @Override
    public Map<String, Object> issues(String type, String status, UUID sensorId, UUID plotId,
                                      OffsetDateTime from, OffsetDateTime to, int page, int limit) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        add(where, params, "i.issue_type", "type", type);
        add(where, params, "i.status", "status", status);
        add(where, params, "i.sensor_id", "sensorId", sensorId);
        add(where, params, "i.plot_id", "plotId", plotId);
        add(where, params, "i.observed_at >=", "from", from == null ? null : Timestamp.from(from.toInstant()));
        add(where, params, "i.observed_at <", "to", to == null ? null : Timestamp.from(to.toInstant()));
        Long total = jdbc.queryForObject("SELECT count(*) FROM data_quality.data_issues i" + where,
                params, Long.class);
        MapSqlParameterSource pageParams = new MapSqlParameterSource(params.getValues())
                .addValue("limit", limit).addValue("offset", (long) (page - 1) * limit);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.issue_id,i.check_id,i.issue_type,i.source_table,i.source_record_id,i.sensor_id,
                       i.plot_id,p.plot_code,s.sensor_code,n.node_code,i.observed_at,i.parameter_code,
                       i.observed_value,i.details::text AS details,i.severity,i.status,i.detected_at,i.resolved_by,
                       i.resolved_at,sr.ph,sr.ec,sr.soil_moisture,sr.soil_temperature,sr.n,sr.p,sr.k
                FROM data_quality.data_issues i
                LEFT JOIN farm.plots p ON p.plot_id=i.plot_id
                LEFT JOIN iot.sensors s ON s.sensor_id=i.sensor_id
                LEFT JOIN iot.sensor_nodes n ON n.node_id=s.node_id
                LEFT JOIN iot.sensor_readings sr ON sr.reading_id =
                    CASE WHEN i.source_record_id ~ '^[0-9]+$' THEN i.source_record_id::bigint END
                    AND sr.measured_at=i.observed_at
                """ + where + " ORDER BY i.observed_at DESC NULLS LAST,i.issue_id DESC LIMIT :limit OFFSET :offset",
                pageParams);
        return page(rows, page, limit, total == null ? 0 : total);
    }

    @Override
    public Map<String, Object> checks(int page, int limit) {
        return pageFor("""
                SELECT check_id,target,scope_from,scope_to,started_at,finished_at,records_checked,
                       records_valid,records_suspect,records_invalid,pass_rate_pct,triggered_by,status
                FROM data_quality.quality_checks ORDER BY started_at DESC
                """, page, limit);
    }

    @Override
    public Map<String, Object> statistics(OffsetDateTime from, OffsetDateTime to) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", from == null ? null : Timestamp.from(from.toInstant()))
                .addValue("to", to == null ? null : Timestamp.from(to.toInstant()));
        return jdbc.queryForMap("""
                WITH source_bounds AS (
                    SELECT min(measured_at) AS first_reading,max(measured_at)+interval '1 microsecond' AS last_reading
                    FROM iot.sensor_readings
                ), bounds AS (
                    SELECT COALESCE(:from, source_bounds.first_reading, now()-interval '45 days') AS date_from,
                           COALESCE(:to, source_bounds.last_reading, now()) AS date_to FROM source_bounds
                ), readings AS (
                    SELECT count(*) AS total,
                           count(*) FILTER (WHERE quality_status IN ('VALID','CORRECTED')) AS valid,
                           count(*) FILTER (WHERE quality_status='SUSPECT') AS suspect,
                           count(*) FILTER (WHERE quality_status='INVALID') AS invalid
                    FROM iot.sensor_readings sr CROSS JOIN bounds b
                    WHERE sr.measured_at >= b.date_from AND sr.measured_at < b.date_to
                ), issues AS (
                    SELECT count(*) FILTER (WHERE issue_type='DUPLICATE') AS duplicates,
                           count(*) FILTER (WHERE issue_type='MISSING' AND status IN ('OPEN','CONFIRMED')) AS missing,
                           count(*) FILTER (WHERE issue_type NOT IN ('DUPLICATE','MISSING')) AS other_issues
                    FROM data_quality.data_issues i CROSS JOIN bounds b
                    WHERE i.observed_at >= b.date_from AND i.observed_at < b.date_to
                ), latest_dataset AS (
                    SELECT valid_records FROM dataset.v_version_readiness v
                    JOIN dataset.dataset_versions dv USING (dataset_version_id)
                    ORDER BY dv.created_at DESC LIMIT 1
                )
                SELECT b.date_from,b.date_to,r.total AS total_observations,r.valid AS valid_observations,
                       r.suspect AS suspect_observations,r.invalid AS invalid_observations,
                       i.duplicates AS duplicate_issues,i.missing AS missing_issues,i.other_issues,
                       CASE WHEN r.total+i.duplicates>0 THEN round((r.invalid+i.duplicates)*100.0/(r.total+i.duplicates),2) ELSE 0 END AS error_duplicate_pct,
                       CASE WHEN r.total+i.missing>0 THEN round(i.missing*100.0/(r.total+i.missing),2) ELSE 0 END AS missing_after_processing_pct,
                       COALESCE((SELECT valid_records FROM latest_dataset),0) AS dataset_valid_observations,
                       (CASE WHEN r.total+i.duplicates>0 THEN (r.invalid+i.duplicates)*100.0/(r.total+i.duplicates) ELSE 0 END) <= 5 AS error_duplicate_ok,
                       (CASE WHEN r.total+i.missing>0 THEN i.missing*100.0/(r.total+i.missing) ELSE 0 END) <= 5 AS missing_ok,
                       COALESCE((SELECT valid_records FROM latest_dataset),0) >= 10000 AS observations_ok
                FROM bounds b CROSS JOIN readings r CROSS JOIN issues i
                """, params);
    }

    @Override
    public List<Map<String, Object>> dailyStatistics(OffsetDateTime from, OffsetDateTime to) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", from == null ? null : Timestamp.from(from.toInstant()))
                .addValue("to", to == null ? null : Timestamp.from(to.toInstant()));
        return jdbc.queryForList("""
                WITH source_bounds AS (
                    SELECT min(measured_at) AS first_reading,max(measured_at)+interval '1 microsecond' AS last_reading
                    FROM iot.sensor_readings
                ), bounds AS (
                    SELECT COALESCE(:from,source_bounds.first_reading,now()-interval '45 days') AS date_from,
                           COALESCE(:to,source_bounds.last_reading,now()) AS date_to FROM source_bounds
                ), reading_days AS (
                    SELECT date_trunc('day',sr.measured_at) AS day,count(*) AS observations,
                           count(*) FILTER (WHERE sr.quality_status IN ('VALID','CORRECTED')) AS valid,
                           count(*) FILTER (WHERE sr.quality_status='INVALID') AS invalid
                    FROM iot.sensor_readings sr CROSS JOIN bounds b
                    WHERE sr.measured_at >= b.date_from AND sr.measured_at < b.date_to
                    GROUP BY 1
                ), issue_days AS (
                    SELECT date_trunc('day',i.observed_at) AS day,
                           count(*) FILTER (WHERE i.issue_type='MISSING' AND i.status IN ('OPEN','CONFIRMED')) AS missing,
                           count(*) FILTER (WHERE i.issue_type='DUPLICATE') AS duplicates,
                           count(*) FILTER (WHERE i.issue_type NOT IN ('MISSING','DUPLICATE')) AS issues
                    FROM data_quality.data_issues i CROSS JOIN bounds b
                    WHERE i.observed_at >= b.date_from AND i.observed_at < b.date_to
                    GROUP BY 1
                )
                SELECT COALESCE(r.day,i.day) AS day,COALESCE(r.observations,0) AS observations,COALESCE(r.valid,0) AS valid,COALESCE(r.invalid,0) AS invalid,COALESCE(i.missing,0) AS missing,
                       COALESCE(i.duplicates,0) AS duplicates,COALESCE(i.issues,0) AS issues
                FROM reading_days r FULL JOIN issue_days i USING (day)
                ORDER BY COALESCE(r.day,i.day)
                """, params);
    }

    @Override
    public Map<String, Object> audit(OffsetDateTime from, OffsetDateTime to, int page, int limit) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        add(where, params, "a.occurred_at >=", "from", from == null ? null : Timestamp.from(from.toInstant()));
        add(where, params, "a.occurred_at <", "to", to == null ? null : Timestamp.from(to.toInstant()));
        Long total = jdbc.queryForObject("SELECT count(*) FROM data_quality.data_audit a" + where, params, Long.class);
        MapSqlParameterSource pageParams = new MapSqlParameterSource(params.getValues())
                .addValue("limit", limit).addValue("offset", (long) (page - 1) * limit);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.audit_id,a.dataset_id,a.source_table,a.record_id,a.action,a.reason,
                       a.old_value::text AS old_value,a.new_value::text AS new_value,
                       a.actor_id,a.actor_type,u.username AS actor_username,a.occurred_at
                FROM data_quality.data_audit a LEFT JOIN iam.users u ON u.user_id=a.actor_id
                """ + where + " ORDER BY a.occurred_at DESC,a.audit_id DESC LIMIT :limit OFFSET :offset", pageParams);
        return page(rows, page, limit, total == null ? 0 : total);
    }

    @Override
    public UUID runChecks(OffsetDateTime from, OffsetDateTime to, UUID actorId) {
        return transactions.execute(status -> jdbc.getJdbcTemplate().queryForObject(
                "SELECT data_quality.run_sensor_checks(?, ?, INTERVAL '7 days', ?)", UUID.class,
                Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()), actorId));
    }

    @Override
    public int runMissingChecks(UUID checkId) {
        return jdbc.getJdbcTemplate().queryForObject(
                "SELECT data_quality.run_missing_sensor_checks(?)", Integer.class, checkId);
    }

    @Override
    public OffsetDateTime latestCheckEnd() {
        List<OffsetDateTime> values = jdbc.getJdbcTemplate().query(
                "SELECT max(scope_to) FROM data_quality.quality_checks WHERE status='DONE'",
                (result, index) -> result.getObject(1, OffsetDateTime.class));
        return values.isEmpty() ? null : values.getFirst();
    }

    @Override
    public void resolve(long issueId, String status, String reason, UUID actorId,
                        String parameterCode, BigDecimal correctedValue) {
        Map<String, Object> issue = issue(issueId);
        String oldStatus = String.valueOf(issue.get("status"));
        if (!List.of("OPEN", "CONFIRMED").contains(oldStatus)) {
            throw AppException.conflict("Issue đã được xử lý và không thể chuyển trạng thái thêm.");
        }
        if ("CORRECTED".equals(status)) {
            String sourceId = String.valueOf(issue.get("source_record_id"));
            if (!sourceId.matches("^[0-9]+$")) throw AppException.conflict("Issue này không gắn với bản ghi cảm biến để sửa.");
            String column = switch (parameterCode) {
                case "PH" -> "ph";
                case "EC" -> "ec";
                case "MOISTURE" -> "soil_moisture";
                case "SOIL_TEMP" -> "soil_temperature";
                case "N" -> "n";
                case "P" -> "p";
                case "K" -> "k";
                default -> throw AppException.badRequest("Chỉ tiêu cảm biến không được hỗ trợ.");
            };
            MapSqlParameterSource correction = new MapSqlParameterSource().addValue("value", correctedValue)
                    .addValue("id", Long.parseLong(sourceId)).addValue("observedAt", issue.get("observed_at"));
            String oldReading = jdbc.queryForObject("""
                    SELECT to_jsonb(sr)::text FROM iot.sensor_readings sr
                    WHERE reading_id=:id AND measured_at=:observedAt FOR UPDATE
                    """, correction, String.class);
            String updateSql = "UPDATE iot.sensor_readings SET " + column
                    + "=:value,quality_status='CORRECTED' WHERE reading_id=:id AND measured_at=:observedAt";
            int changed = jdbc.update(updateSql, correction);
            if (changed == 0) throw AppException.notFound("Không tìm thấy bản ghi cảm biến để sửa.");
            jdbc.update("""
                    UPDATE data_quality.data_issues SET status='CORRECTED',resolved_by=:actor,resolved_at=now()
                    WHERE issue_id=:id
                    """, new MapSqlParameterSource().addValue("actor", actorId).addValue("id", issueId));
            String newReading = jdbc.queryForObject("""
                    SELECT to_jsonb(sr)::text FROM iot.sensor_readings sr
                    WHERE reading_id=:id AND measured_at=:observedAt
                    """, correction, String.class);
            try {
                auditIssue(issueId, oldStatus, "CORRECTED", reason, actorId,
                        Map.of("reading", json.readValue(oldReading, Object.class)),
                        Map.of("reading", json.readValue(newReading, Object.class)));
            } catch (JacksonException exception) {
                throw new IllegalStateException("Không thể tạo audit cho dữ liệu cảm biến.", exception);
            }
            return;
        }
        jdbc.update("""
                UPDATE data_quality.data_issues SET status=:status,resolved_by=:actor,resolved_at=now()
                WHERE issue_id=:id
                """, new MapSqlParameterSource().addValue("status", status)
                .addValue("actor", actorId).addValue("id", issueId));
        auditIssue(issueId, oldStatus, status, reason, actorId, Map.of("status", oldStatus), Map.of("status", status));
    }

    private void auditIssue(long issueId, String oldStatus, String newStatus, String reason, UUID actorId,
                            Map<String, Object> oldValue, Map<String, Object> newValue) {
        String oldJson;
        String newJson;
        try {
            oldJson = json.writeValueAsString(oldValue);
            newJson = json.writeValueAsString(newValue);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Không thể tạo audit cho issue.", exception);
        }
        jdbc.update("""
                INSERT INTO data_quality.data_audit
                    (source_table,record_id,action,reason,old_value,new_value,actor_id,actor_type)
                VALUES ('data_quality.data_issues',:record,:action,:reason,CAST(:old AS jsonb),CAST(:new AS jsonb),:actor,'USER')
                """, new MapSqlParameterSource().addValue("record", Long.toString(issueId))
                .addValue("action", "IGNORED".equals(newStatus) ? "REJECT" : "UPDATE")
                .addValue("reason", reason).addValue("old", oldJson).addValue("new", newJson)
                .addValue("actor", actorId));
    }

    private Map<String, Object> pageFor(String query, int page, int limit) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + query + ") records", Map.of(), Long.class);
        List<Map<String, Object>> rows = jdbc.queryForList(query + " LIMIT :limit OFFSET :offset",
                Map.of("limit", limit, "offset", (long) (page - 1) * limit));
        return page(rows, page, limit, total == null ? 0 : total);
    }

    private void add(StringBuilder where, MapSqlParameterSource params, String expression, String name, Object value) {
        if (value != null && (!(value instanceof String string) || !string.isBlank())) {
            where.append(" AND ").append(expression).append(" :").append(name);
            params.addValue(name, value);
        }
    }

    private Map<String, Object> page(List<Map<String, Object>> rows, int page, int limit, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", rows);
        result.put("meta", Map.of("page", page, "limit", limit, "total", total));
        return result;
    }
}
