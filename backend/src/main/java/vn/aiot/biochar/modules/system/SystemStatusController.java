package vn.aiot.biochar.modules.system;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.aiot.biochar.common.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private static final String SCHEMAS = """
            ('ref','iam','farm','experiment','biochar','iot','calibration','laboratory',
             'data_quality','dataset','sqi','ml','ota','alert','reporting','audit')
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final long startedAt = System.nanoTime();

    public SystemStatusController(JdbcTemplate jdbcTemplate, Environment environment) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('iam.read')")
    public ApiResponse<SystemStatus> status() {
        Map<String, Object> database = jdbcTemplate.queryForMap("""
                SELECT current_database() AS name, version() AS version,
                       pg_database_size(current_database())::bigint AS size_bytes,
                       now() AS server_time,
                       (SELECT count(*)::int FROM information_schema.schemata
                         WHERE schema_name IN
                """ + SCHEMAS + ") AS domain_schemas");
        Map<String, Object> counts = jdbcTemplate.queryForMap("""
                SELECT (SELECT count(*)::int FROM iam.users) AS users,
                       (SELECT count(*)::int FROM iam.users WHERE is_active) AS active_users,
                       (SELECT count(*)::int FROM iam.api_keys
                         WHERE revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())) AS active_api_keys,
                       (SELECT count(*)::int FROM audit.audit_logs
                         WHERE occurred_at > now() - interval '24 hours') AS changes_24h,
                       (SELECT count(*)::int FROM audit.access_logs
                         WHERE action = 'LOGIN_FAILED'
                           AND occurred_at > now() - interval '24 hours') AS failed_logins_24h
                """);
        List<MigrationView> migrations = migrationTableExists()
                ? jdbcTemplate.query("""
                        SELECT filename, applied_at
                          FROM public.schema_migrations
                         ORDER BY filename
                        """, (rs, row) -> new MigrationView(rs.getString("filename"), rs.getTimestamp("applied_at")))
                : List.of();
        String[] profiles = environment.getActiveProfiles();
        String profile = profiles.length == 0 ? "development" : String.join(",", profiles);
        ServerStatus server = new ServerStatus(
                System.getProperty("java.version"),
                (System.nanoTime() - startedAt) / 1_000_000_000,
                profile);
        return ApiResponse.of(new SystemStatus(server, database, counts, migrations));
    }

    private boolean migrationTableExists() {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.schema_migrations') IS NOT NULL", Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public record ServerStatus(
            @JsonProperty("java_version") String javaVersion,
            @JsonProperty("uptime_seconds") long uptimeSeconds,
            String environment) {
    }

    public record MigrationView(String filename, @JsonProperty("applied_at") Timestamp appliedAt) {
    }

    public record SystemStatus(
            ServerStatus server,
            Map<String, Object> database,
            Map<String, Object> counts,
            List<MigrationView> migrations) {
    }
}
