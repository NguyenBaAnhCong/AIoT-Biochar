package vn.aiot.biochar.modules.system;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.aiot.biochar.common.api.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> status = jdbcTemplate.queryForMap("""
                SELECT now() AS server_time,
                       current_database() AS db,
                       (SELECT count(*)::int
                          FROM information_schema.schemata
                          WHERE schema_name IN (
                              'ref', 'iam', 'farm', 'experiment', 'biochar', 'iot', 'calibration', 'laboratory',
                              'data_quality', 'dataset', 'sqi', 'ml', 'ota', 'alert', 'reporting', 'audit'
                          )) AS domain_schemas
                """);
        status.put("status", "ok");
        return ApiResponse.of(status);
    }
}
