package vn.aiot.biochar.modules.ref;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.aiot.biochar.common.db.UserTransactionService;
import vn.aiot.biochar.common.exception.AppException;

@Service
public class ReferenceParameterService {

    public static final List<String> CATEGORIES =
            List.of("SOIL_CHEM", "SOIL_PHYS", "SOIL_BIO", "BIOCHAR", "PLANT", "ENV");
    private static final String COLUMNS =
            "parameter_code, name_vi, unit, category, physical_min, physical_max, description";

    private final JdbcTemplate jdbcTemplate;
    private final UserTransactionService transactions;

    public ReferenceParameterService(JdbcTemplate jdbcTemplate, UserTransactionService transactions) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
    }

    public List<ParameterView> list(String category) {
        String sql = "SELECT " + COLUMNS + " FROM ref.parameters"
                + (category == null ? "" : " WHERE category = ?")
                + " ORDER BY category, parameter_code";
        return category == null
                ? jdbcTemplate.query(sql, this::mapParameter)
                : jdbcTemplate.query(sql, this::mapParameter, category);
    }

    public ParameterView get(String code) {
        List<ParameterView> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM ref.parameters WHERE parameter_code = ?",
                this::mapParameter, code);
        if (rows.isEmpty()) {
            throw AppException.notFound("Không tìm thấy chỉ tiêu");
        }
        return rows.getFirst();
    }

    public ParameterView create(
            java.util.UUID actorId, String code, String name, String unit, String category,
            BigDecimal physicalMin, BigDecimal physicalMax, String description) {
        validateRange(physicalMin, physicalMax);
        try {
            ParameterView created = transactions.execute(actorId, "Thêm chỉ tiêu", () -> {
                List<ParameterView> rows = jdbcTemplate.query("""
                        INSERT INTO ref.parameters
                            (parameter_code, name_vi, unit, category, physical_min, physical_max, description)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        RETURNING parameter_code, name_vi, unit, category, physical_min, physical_max, description
                        """, this::mapParameter, code, name, unit, category,
                        physicalMin, physicalMax, description);
                return rows.getFirst();
            });
            return created;
        } catch (DuplicateKeyException exception) {
            throw new AppException(409, "CONFLICT", "Mã chỉ tiêu đã tồn tại",
                    List.of(new vn.aiot.biochar.common.api.ApiErrorResponse.ErrorDetail(
                            "parameter_code", "Đã tồn tại")));
        }
    }

    public ParameterView update(java.util.UUID actorId, String code, ParameterPatch patch) {
        if (patch.fields().isEmpty()) {
            throw AppException.badRequest("Không có trường nào để cập nhật");
        }
        return transactions.execute(actorId, "Sửa chỉ tiêu", () -> {
            List<ParameterView> currentRows = jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM ref.parameters WHERE parameter_code = ? FOR UPDATE",
                    this::mapParameter, code);
            if (currentRows.isEmpty()) {
                throw AppException.notFound("Không tìm thấy chỉ tiêu");
            }
            ParameterView current = currentRows.getFirst();
            BigDecimal min = patch.fields().contains("physical_min") ? patch.physicalMin() : current.physicalMin();
            BigDecimal max = patch.fields().contains("physical_max") ? patch.physicalMax() : current.physicalMax();
            validateRange(min, max);

            Map<String, Object> values = new LinkedHashMap<>();
            if (patch.fields().contains("name_vi")) {
                values.put("name_vi", patch.nameVi());
            }
            if (patch.fields().contains("unit")) {
                values.put("unit", patch.unit());
            }
            if (patch.fields().contains("category")) {
                values.put("category", patch.category());
            }
            if (patch.fields().contains("physical_min")) {
                values.put("physical_min", patch.physicalMin());
            }
            if (patch.fields().contains("physical_max")) {
                values.put("physical_max", patch.physicalMax());
            }
            if (patch.fields().contains("description")) {
                values.put("description", patch.description());
            }

            List<String> assignments = new ArrayList<>();
            List<Object> parameters = new ArrayList<>();
            values.forEach((column, value) -> {
                assignments.add(column + " = ?");
                parameters.add(value);
            });
            parameters.add(code);
            return jdbcTemplate.query("""
                    UPDATE ref.parameters SET
                    """ + String.join(", ", assignments) + " WHERE parameter_code = ? RETURNING " + COLUMNS,
                    this::mapParameter, parameters.toArray()).getFirst();
        });
    }

    private ParameterView mapParameter(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ParameterView(
                rs.getString("parameter_code"),
                rs.getString("name_vi"),
                rs.getString("unit"),
                rs.getString("category"),
                rs.getBigDecimal("physical_min"),
                rs.getBigDecimal("physical_max"),
                rs.getString("description"));
    }

    private void validateRange(BigDecimal min, BigDecimal max) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new AppException(400, "VALIDATION_ERROR", "Khoảng giá trị không hợp lệ",
                    List.of(new vn.aiot.biochar.common.api.ApiErrorResponse.ErrorDetail(
                            "physical_min", "physical_min phải ≤ physical_max")));
        }
    }

    public record ParameterView(
            @JsonProperty("parameter_code") String parameterCode,
            @JsonProperty("name_vi") String nameVi,
            String unit,
            String category,
            @JsonProperty("physical_min") BigDecimal physicalMin,
            @JsonProperty("physical_max") BigDecimal physicalMax,
            String description) {
    }

    public static class ParameterPatch {
        private final List<String> fields = new ArrayList<>();
        private String nameVi;
        private String unit;
        private String category;
        private BigDecimal physicalMin;
        private BigDecimal physicalMax;
        private String description;

        @JsonProperty("name_vi")
        public void setNameVi(String value) {
            fields.add("name_vi");
            nameVi = value;
        }

        @JsonProperty("unit")
        public void setUnit(String value) {
            fields.add("unit");
            unit = value;
        }

        @JsonProperty("category")
        public void setCategory(String value) {
            fields.add("category");
            category = value;
        }

        @JsonProperty("physical_min")
        public void setPhysicalMin(BigDecimal value) {
            fields.add("physical_min");
            physicalMin = value;
        }

        @JsonProperty("physical_max")
        public void setPhysicalMax(BigDecimal value) {
            fields.add("physical_max");
            physicalMax = value;
        }

        @JsonProperty("description")
        public void setDescription(String value) {
            fields.add("description");
            description = value;
        }

        public List<String> fields() {
            return fields;
        }

        public String nameVi() {
            return nameVi;
        }

        public String unit() {
            return unit;
        }

        public String category() {
            return category;
        }

        public BigDecimal physicalMin() {
            return physicalMin;
        }

        public BigDecimal physicalMax() {
            return physicalMax;
        }

        public String description() {
            return description;
        }
    }
}
