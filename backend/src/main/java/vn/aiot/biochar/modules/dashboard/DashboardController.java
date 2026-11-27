package vn.aiot.biochar.modules.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.aiot.biochar.common.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final JdbcTemplate jdbcTemplate;

    public DashboardController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('reporting.read')")
    public ApiResponse<DashboardSummary> summary() {
        List<AcceptanceKpi> kpis = jdbcTemplate.query("""
                SELECT kpi_code, name_vi, unit, comparator, target_value, current_value, passed
                  FROM reporting.v_acceptance_summary
                 ORDER BY kpi_code
                """, (rs, row) -> new AcceptanceKpi(
                rs.getString("kpi_code"),
                rs.getString("name_vi"),
                rs.getString("unit"),
                rs.getString("comparator"),
                rs.getBigDecimal("target_value"),
                rs.getBigDecimal("current_value"),
                rs.getObject("passed", Boolean.class)));
        List<Map<String, Object>> plots = jdbcTemplate.queryForList("""
                SELECT plot_id, plot_code, soil_type, site_code, treatment_code, biochar_dose_kg_m2,
                       node_code, node_status, last_seen_at, gateway_code, gateway_status, last_reading_at,
                       ph, ec, soil_moisture, n, p, k, sqi_value, health_class, sqi_date, open_alerts,
                       gateway_model_version
                  FROM reporting.v_dashboard_plots
                 ORDER BY plot_code
                """);
        Integer openAlerts = jdbcTemplate.queryForObject(
                "SELECT count(*)::int FROM alert.alerts WHERE status <> 'RESOLVED'", Integer.class);
        int passed = (int) kpis.stream().filter(kpi -> Boolean.TRUE.equals(kpi.passed())).count();
        int failed = (int) kpis.stream().filter(kpi -> Boolean.FALSE.equals(kpi.passed())).count();
        Acceptance acceptance = new Acceptance(kpis.size(), passed, failed, kpis.size() - passed - failed, kpis);
        return ApiResponse.of(new DashboardSummary(
                Instant.now(), acceptance, plots, openAlerts == null ? 0 : openAlerts));
    }

    public record AcceptanceKpi(
            @JsonProperty("kpi_code") String kpiCode,
            @JsonProperty("name_vi") String nameVi,
            String unit,
            String comparator,
            @JsonProperty("target_value") java.math.BigDecimal targetValue,
            @JsonProperty("current_value") java.math.BigDecimal currentValue,
            Boolean passed) {
    }

    public record Acceptance(
            int total, int passed, int failed, int pending, List<AcceptanceKpi> kpis) {
    }

    public record DashboardSummary(
            @JsonProperty("generated_at") Instant generatedAt,
            Acceptance acceptance,
            List<Map<String, Object>> plots,
            @JsonProperty("open_alerts") int openAlerts) {
    }
}
