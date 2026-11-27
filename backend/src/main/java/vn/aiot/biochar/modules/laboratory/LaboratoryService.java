package vn.aiot.biochar.modules.laboratory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.aiot.biochar.common.api.ApiErrorResponse.ErrorDetail;
import vn.aiot.biochar.common.api.ApiResponse;
import vn.aiot.biochar.common.crud.Change;
import vn.aiot.biochar.common.crud.CrudService;
import vn.aiot.biochar.common.crud.Field;
import vn.aiot.biochar.common.crud.Resource;
import vn.aiot.biochar.common.crud.SqlRows;
import vn.aiot.biochar.common.db.UserTransactionService;
import vn.aiot.biochar.common.exception.AppException;
import vn.aiot.biochar.modules.auth.AppUser;

/** Mẫu đất, phương pháp, kết quả phân tích và quy trình duyệt (schema laboratory). */
@Service
public class LaboratoryService {

    private static final String[] SAMPLE_TYPES = {"SOIL", "SOIL_SOLUTION", "PLANT", "BIOCHAR"};
    private static final String[] SAMPLE_STATUSES = {"COLLECTED", "RECEIVED", "ANALYZING", "COMPLETED", "REJECTED"};
    private static final String[] RESULT_STATUSES = {"DRAFT", "VERIFIED", "APPROVED", "REJECTED"};
    public static final int MAX_BULK = 200;

    private final SqlRows rows;
    private final CrudService crud;
    private final UserTransactionService transactions;
    private final Map<String, Resource> resources;

    public LaboratoryService(SqlRows rows, CrudService crud, UserTransactionService transactions) {
        this.rows = rows;
        this.crud = crud;
        this.transactions = transactions;
        this.resources = Map.of(
                "lab-methods", methods(),
                "samples", samples(),
                "lab-results", results());
    }

    public Resource resource(String name) {
        return resources.get(name);
    }

    /** Chuyển trạng thái một hoặc nhiều kết quả; một dòng lỗi thì không dòng nào được đổi. */
    public List<Map<String, Object>> transition(List<UUID> ids, ReviewAction action, AppUser actor) {
        List<UUID> distinct = ids.stream().distinct().sorted().toList();
        return transactions.execute(actor.id(), action.label + " kết quả phòng thí nghiệm", () -> {
            for (UUID id : distinct) {
                Map<String, Object> current = rows.first(
                        "SELECT result_status, analyst_id FROM laboratory.lab_results"
                                + " WHERE lab_result_id = ? FOR UPDATE", id);
                if (current == null) {
                    throw AppException.notFound("Không tìm thấy kết quả " + id);
                }
                String status = (String) current.get("result_status");
                if (!action.from.contains(status)) {
                    throw new AppException(409, "INVALID_TRANSITION",
                            "Không thể " + action.label.toLowerCase() + " kết quả đang ở trạng thái " + status,
                            List.of(new ErrorDetail("lab_result_id", id.toString())));
                }
                if (action == ReviewAction.APPROVE && actor.id().equals(current.get("analyst_id"))) {
                    throw new AppException(409, "FOUR_EYES",
                            "Người nhập kết quả không được tự duyệt (nguyên tắc 4 mắt)",
                            List.of(new ErrorDetail("lab_result_id", id.toString())));
                }
                rows.update("""
                        UPDATE laboratory.lab_results
                           SET result_status = ?, approved_by = ?::uuid
                         WHERE lab_result_id = ?
                        """, action.to, action == ReviewAction.APPROVE ? actor.id() : null, id);
            }
            return distinct.stream().map(id -> crud.get(resources.get("lab-results"), id)).toList();
        });
    }

    /** Đối chiếu cảm biến (trung bình ±2 giờ) với kết quả đã xác nhận, từ view laboratory.v_sensor_vs_lab. */
    public ApiResponse<List<Map<String, Object>>> sensorVsLab(Map<String, String> query) {
        Resource view = Resource.of("dòng đối chiếu", "laboratory.v_sensor_vs_lab", "sample_id")
                .select("""
                        SELECT t.*, p.plot_code
                          FROM laboratory.v_sensor_vs_lab t
                          JOIN farm.plots p ON p.plot_id = t.plot_id""")
                .filter(Field.uuid("plot_id"))
                .filter(Field.code("parameter_code").upperCase())
                .orderBy("t.sampled_at DESC, p.plot_code, t.parameter_code");
        return crud.list(view, query);
    }

    /**
     * Kiểm tra một kết quả phân tích; dùng chung cho nhập tay và nhập Excel.
     *
     * @param sample    dòng soil_samples (status, sampled_at)
     * @param parameter dòng ref.parameters (unit, physical_min, physical_max)
     * @param method    dòng lab_methods (parameter_code), có thể null
     */
    List<ErrorDetail> checkResultValues(
            Map<String, Object> sample, Map<String, Object> parameter, Map<String, Object> method,
            String parameterCode, BigDecimal value, String unit, Instant analyzedAt) {
        List<ErrorDetail> errors = new ArrayList<>();
        if ("REJECTED".equals(sample.get("status"))) {
            errors.add(new ErrorDetail("sample_id", "Mẫu đã bị loại, không nhận kết quả"));
        }
        if (unit != null && !unit.equals(parameter.get("unit"))) {
            errors.add(new ErrorDetail("unit",
                    "Đơn vị của " + parameterCode + " phải là " + parameter.get("unit")));
        }
        BigDecimal min = (BigDecimal) parameter.get("physical_min");
        BigDecimal max = (BigDecimal) parameter.get("physical_max");
        if (value != null && (min != null && value.compareTo(min) < 0 || max != null && value.compareTo(max) > 0)) {
            errors.add(new ErrorDetail("value", "Ngoài giới hạn vật lý "
                    + (min == null ? "" : min.toPlainString()) + " – " + (max == null ? "" : max.toPlainString())));
        }
        if (method != null && !parameterCode.equals(method.get("parameter_code"))) {
            errors.add(new ErrorDetail("method_id",
                    "Phương pháp này dùng cho chỉ tiêu " + method.get("parameter_code")));
        }
        if (analyzedAt != null) {
            if (analyzedAt.isBefore((Instant) sample.get("sampled_at"))) {
                errors.add(new ErrorDetail("analyzed_at", "Không được trước thời điểm lấy mẫu"));
            } else if (analyzedAt.isAfter(Instant.now().plus(5, ChronoUnit.MINUTES))) {
                errors.add(new ErrorDetail("analyzed_at", "Không được ở tương lai"));
            }
        }
        return errors;
    }

    private Resource methods() {
        return Resource.of("phương pháp phân tích", "laboratory.lab_methods", "method_id")
                .fields(
                        Field.code("method_code").required(),
                        Field.code("parameter_code").upperCase().required()
                                .references("ref.parameters", "parameter_code"),
                        Field.text("name", 255).required(),
                        Field.text("standard_ref", 128),
                        Field.text("instrument", 128),
                        Field.decimal("detection_limit", 0, 1e12),
                        Field.decimal("quantification_limit", 0, 1e12),
                        Field.text("unit", 32).required())
                .filter(Field.code("parameter_code").upperCase())
                .search("t.method_code", "t.name")
                .orderBy("t.parameter_code, t.method_code")
                .rule(change -> {
                    if (!change.creating() && change.changed("parameter_code") && rows.count(
                            "SELECT count(*) FROM laboratory.lab_results WHERE method_id = ?", change.id()) > 0) {
                        throw AppException.conflict("Phương pháp đã có kết quả, không thể đổi chỉ tiêu");
                    }
                });
    }

    private Resource samples() {
        return Resource.of("mẫu", "laboratory.soil_samples", "sample_id")
                .fields(
                        Field.code("sample_code").required(),
                        Field.uuid("plot_id").required().references("farm.plots", "plot_id"),
                        Field.choice("sample_type", SAMPLE_TYPES).notNull(),
                        Field.timestamp("sampled_at").required(),
                        Field.decimal("depth_from_cm", 0, 9999),
                        Field.decimal("depth_to_cm", 0, 9999),
                        Field.decimal("latitude", -90, 90),
                        Field.decimal("longitude", -180, 180),
                        Field.uuid("near_node_id").references("iot.sensor_nodes", "node_id"),
                        Field.integer("composite_of", 1, 1000),
                        Field.timestamp("received_at"),
                        Field.choice("status", SAMPLE_STATUSES).notNull(),
                        Field.text("notes", 2000))
                .select("""
                        SELECT t.*, p.plot_code, u.full_name AS collected_by_name,
                               (SELECT count(*) FROM laboratory.lab_results r
                                 WHERE r.sample_id = t.sample_id) AS result_count
                          FROM laboratory.soil_samples t
                          JOIN farm.plots p ON p.plot_id = t.plot_id
                          LEFT JOIN iam.users u ON u.user_id = t.collected_by""")
                .filter(Field.uuid("plot_id"))
                .filter(Field.choice("status", SAMPLE_STATUSES))
                .filter(Field.timestamp("from"), "t.sampled_at >= ?")
                .filter(Field.timestamp("to"), "t.sampled_at < ?")
                .search("t.sample_code", "p.plot_code")
                .orderBy("t.sampled_at DESC, t.sample_code")
                .rule(this::checkSample)
                .deleteRule(change -> {
                    // khóa ngoại là ON DELETE CASCADE nên phải chặn ở đây để không mất kết quả
                    if (rows.count("SELECT count(*) FROM laboratory.lab_results WHERE sample_id = ?",
                            change.id()) > 0) {
                        throw AppException.conflict("Mẫu đã có kết quả phân tích, không được xóa");
                    }
                });
    }

    private Resource results() {
        return Resource.of("kết quả phân tích", "laboratory.lab_results", "lab_result_id")
                .fields(
                        Field.uuid("sample_id").required().references("laboratory.soil_samples", "sample_id"),
                        Field.code("parameter_code").upperCase().required()
                                .references("ref.parameters", "parameter_code"),
                        Field.decimal("value", -1e15, 1e15).required(),
                        Field.text("unit", 32).notNull(),
                        Field.uuid("method_id").references("laboratory.lab_methods", "method_id"),
                        Field.integer("replicate_no", 1, 100).notNull(),
                        Field.bool("below_lod").notNull(),
                        Field.timestamp("analyzed_at"))
                .select("""
                        SELECT t.*, s.sample_code, s.plot_id, s.sampled_at, p.plot_code,
                               m.method_code, a.full_name AS analyst_name, v.full_name AS approved_by_name
                          FROM laboratory.lab_results t
                          JOIN laboratory.soil_samples s ON s.sample_id = t.sample_id
                          JOIN farm.plots p ON p.plot_id = s.plot_id
                          LEFT JOIN laboratory.lab_methods m ON m.method_id = t.method_id
                          LEFT JOIN iam.users a ON a.user_id = t.analyst_id
                          LEFT JOIN iam.users v ON v.user_id = t.approved_by""")
                .filter(Field.uuid("sample_id"))
                .filter(Field.uuid("plot_id"), "s.plot_id = ?")
                .filter(Field.code("parameter_code").upperCase())
                .filter(Field.choice("result_status", RESULT_STATUSES))
                .search("s.sample_code", "p.plot_code")
                .orderBy("s.sampled_at DESC, s.sample_code, t.parameter_code, t.replicate_no")
                .rule(this::checkResult)
                .deleteRule(change -> requireEditable((String) change.before("result_status")));
    }

    private void checkSample(Change change) {
        Instant sampled = change.time("sampled_at");
        if (change.changed("sampled_at") && sampled.isAfter(Instant.now().plus(5, ChronoUnit.MINUTES))) {
            throw Change.invalid("sampled_at", "Thời điểm lấy mẫu không được ở tương lai");
        }
        Instant received = change.time("received_at");
        if (received != null && received.isBefore(sampled)) {
            throw Change.invalid("received_at", "Thời điểm nhận mẫu không được trước thời điểm lấy mẫu");
        }
        BigDecimal from = change.number("depth_from_cm");
        BigDecimal to = change.number("depth_to_cm");
        if (from != null && to != null && to.compareTo(from) <= 0) {
            throw Change.invalid("depth_to_cm", "Độ sâu dưới phải lớn hơn độ sâu trên");
        }
        if (change.creating()) {
            change.set("collected_by", change.actor().id());
            return;
        }
        boolean hasReviewed = rows.count("""
                SELECT count(*) FROM laboratory.lab_results
                 WHERE sample_id = ? AND result_status IN ('VERIFIED','APPROVED')
                """, change.id()) > 0;
        if (hasReviewed && (change.changed("plot_id") || change.changed("sampled_at")
                || change.changed("sample_type")
                || change.changed("status") && "REJECTED".equals(change.get("status")))) {
            throw AppException.conflict(
                    "Mẫu đã có kết quả được xác nhận, không được đổi ô, thời điểm, loại hoặc loại bỏ mẫu");
        }
    }

    private void checkResult(Change change) {
        if (!change.creating()) {
            requireEditable((String) change.before("result_status"));
        }
        String parameterCode = change.text("parameter_code");
        Map<String, Object> parameter = rows.first(
                "SELECT unit, physical_min, physical_max FROM ref.parameters WHERE parameter_code = ?",
                parameterCode);
        // khóa mẫu để không bị loại bỏ trong lúc đang ghi kết quả
        Map<String, Object> sample = rows.first(
                "SELECT status, sampled_at FROM laboratory.soil_samples WHERE sample_id = ? FOR UPDATE",
                change.uuid("sample_id"));
        UUID methodId = change.uuid("method_id");
        Map<String, Object> method = methodId == null ? null : rows.first(
                "SELECT parameter_code FROM laboratory.lab_methods WHERE method_id = ?", methodId);
        if (change.creating() && change.get("unit") == null) {
            change.set("unit", parameter.get("unit"));
        }
        List<ErrorDetail> errors = checkResultValues(sample, parameter, method, parameterCode,
                change.number("value"), change.text("unit"), change.time("analyzed_at"));
        if (!errors.isEmpty()) {
            throw new AppException(400, "VALIDATION_ERROR", errors.getFirst().message(), errors);
        }
        // người ghi số liệu sau cùng là người phân tích; sửa xong phải duyệt lại từ đầu
        change.set("analyst_id", change.actor().id());
        change.set("result_status", "DRAFT");
        change.set("approved_by", null);
    }

    private void requireEditable(String status) {
        if (!"DRAFT".equals(status) && !"REJECTED".equals(status)) {
            throw new AppException(409, "RESULT_LOCKED",
                    "APPROVED".equals(status)
                            ? "Kết quả đã duyệt, không được sửa hoặc xóa"
                            : "Kết quả đã xác nhận. Cần từ chối trước khi sửa hoặc xóa");
        }
    }

    public enum ReviewAction {
        VERIFY("Xác nhận", "VERIFIED", List.of("DRAFT")),
        APPROVE("Duyệt", "APPROVED", List.of("VERIFIED")),
        REJECT("Từ chối", "REJECTED", List.of("DRAFT", "VERIFIED"));

        private final String label;
        private final String to;
        private final List<String> from;

        ReviewAction(String label, String to, List<String> from) {
            this.label = label;
            this.to = to;
            this.from = from;
        }
    }
}
