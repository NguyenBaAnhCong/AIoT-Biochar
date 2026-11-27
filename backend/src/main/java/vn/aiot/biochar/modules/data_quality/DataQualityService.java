package vn.aiot.biochar.modules.data_quality;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.aiot.biochar.common.db.UserTransactionService;
import vn.aiot.biochar.common.exception.AppException;

@Service
public class DataQualityService implements IDataQualityService {

    private static final List<String> TARGETS = List.of("SENSOR_READING", "LAB_RESULT", "DATASET_RECORD");
    private static final List<String> TYPES = List.of("RANGE", "RATE_OF_CHANGE", "FLATLINE", "TIMESTAMP", "DUPLICATE", "MISSING", "CROSS_CHECK");
    private static final List<String> SEVERITIES = List.of("WARN", "ERROR");
    private static final List<String> ISSUE_TYPES = List.of("OUT_OF_RANGE", "SPIKE", "FLATLINE", "MISSING", "DUPLICATE", "INVALID_TIMESTAMP", "SENSOR_FAULT", "LAB_MISMATCH", "OTHER");
    private static final List<String> ISSUE_STATUSES = List.of("OPEN", "CONFIRMED", "CORRECTED", "IGNORED");

    private final DataQualityDao dao;
    private final UserTransactionService userTransactions;

    public DataQualityService(DataQualityDao dao, UserTransactionService userTransactions) {
        this.dao = dao;
        this.userTransactions = userTransactions;
    }

    @Override
    public Map<String, Object> rules() {
        return Map.of("rules", dao.rules(), "rule_types", TYPES, "issue_types", ISSUE_TYPES, "statuses", ISSUE_STATUSES);
    }

    @Override
    public UUID createRule(RuleRequest request) {
        validateRule(request);
        return dao.createRule(request.ruleCode().trim().toUpperCase(), request.name().trim(), request.target().trim().toUpperCase(),
                request.ruleType().trim().toUpperCase(), blankToNull(request.parameterCode()), request.minValue(), request.maxValue(),
                request.maxDelta(), request.windowMinutes(), request.severity(), request.description());
    }

    @Override
    public void updateRule(UUID id, RuleRequest request) {
        validateRule(request);
        dao.updateRule(id, request.name().trim(), request.target().trim().toUpperCase(), request.ruleType().trim().toUpperCase(),
                blankToNull(request.parameterCode()), request.minValue(), request.maxValue(), request.maxDelta(),
                request.windowMinutes(), request.severity(), request.description());
    }

    @Override
    public void setRuleActive(UUID id, boolean active) {
        dao.setRuleActive(id, active);
    }

    @Override
    public Map<String, Object> issues(String type, String status, UUID sensorId, UUID plotId,
                                      OffsetDateTime from, OffsetDateTime to, int page, int limit) {
        validatePaging(page, limit);
        if (type != null && !ISSUE_TYPES.contains(type)) throw AppException.badRequest("Loại issue không hợp lệ.");
        if (status != null && !ISSUE_STATUSES.contains(status)) throw AppException.badRequest("Trạng thái issue không hợp lệ.");
        validateRange(from, to);
        return dao.issues(type, status, sensorId, plotId, from, to, page, limit);
    }

    @Override
    public Map<String, Object> checks(int page, int limit) {
        validatePaging(page, limit);
        return dao.checks(page, limit);
    }

    @Override
    public Map<String, Object> statistics(OffsetDateTime from, OffsetDateTime to) {
        validateRange(from, to);
        return Map.of(
                "summary", dao.statistics(from, to),
                "by_day", dao.dailyStatistics(from, to));
    }

    @Override
    public Map<String, Object> audit(OffsetDateTime from, OffsetDateTime to, int page, int limit) {
        validatePaging(page, limit);
        validateRange(from, to);
        return dao.audit(from, to, page, limit);
    }

    @Override
    public UUID runChecks(OffsetDateTime from, OffsetDateTime to, UUID actorId) {
        validateRange(from, to);
        if (from == null || to == null) throw AppException.badRequest("Cần chọn thời điểm bắt đầu và kết thúc QC.");
        if (from.isAfter(OffsetDateTime.now().plusMinutes(5))) throw AppException.badRequest("Thời điểm bắt đầu không được ở tương lai.");
        return userTransactions.execute(actorId, null, () -> {
            UUID checkId = dao.runChecks(from, to, actorId);
            dao.runMissingChecks(checkId);
            return checkId;
        });
    }

    @Override
    public int runScheduledChecks(UUID actorId, long lookbackMinutes) {
        if (lookbackMinutes < 1 || lookbackMinutes > 10080) throw AppException.badRequest("Cửa sổ QC tự động phải từ 1 đến 10080 phút.");
        OffsetDateTime to = OffsetDateTime.now().minusMinutes(5);
        OffsetDateTime from = to.minusMinutes(lookbackMinutes);
        OffsetDateTime lastCheck = dao.latestCheckEnd();
        if (lastCheck != null && lastCheck.isAfter(from)) from = lastCheck;
        if (!from.isBefore(to)) return 0;
        runChecks(from, to, actorId);
        return 1;
    }

    @Override
    public void confirm(long issueId, String reason, UUID actorId) {
        requireReason(reason, "Cần ghi lý do xác nhận issue.");
        userTransactions.execute(actorId, reason, () -> {
            dao.resolve(issueId, "CONFIRMED", reason.trim(), actorId, null, null);
            return null;
        });
    }

    @Override
    public void ignore(long issueId, String reason, UUID actorId) {
        requireReason(reason, "Cần ghi lý do bỏ qua issue.");
        userTransactions.execute(actorId, reason, () -> {
            dao.resolve(issueId, "IGNORED", reason.trim(), actorId, null, null);
            return null;
        });
    }

    @Override
    public void correct(long issueId, String parameterCode, BigDecimal value, String reason, UUID actorId) {
        requireReason(reason, "Cần ghi lý do chỉnh sửa dữ liệu.");
        if (value == null || parameterCode == null || parameterCode.isBlank()) {
            throw AppException.badRequest("Cần chọn chỉ tiêu và giá trị mới.");
        }
        userTransactions.execute(actorId, reason, () -> {
            dao.resolve(issueId, "CORRECTED", reason.trim(), actorId, parameterCode.trim().toUpperCase(), value);
            return null;
        });
    }

    private void validateRule(RuleRequest request) {
        if (request == null || request.ruleCode() == null || request.ruleCode().isBlank() || request.ruleCode().length() > 32) {
            throw AppException.badRequest("Mã luật QC bắt buộc và tối đa 32 ký tự.");
        }
        if (request.name() == null || request.name().isBlank() || request.name().length() > 255) {
            throw AppException.badRequest("Tên luật QC bắt buộc và tối đa 255 ký tự.");
        }
        if (!TARGETS.contains(request.target()) || !TYPES.contains(request.ruleType()) || !SEVERITIES.contains(request.severity())) {
            throw AppException.badRequest("Target, loại luật hoặc mức độ không hợp lệ.");
        }
        if ("RANGE".equals(request.ruleType()) && (request.parameterCode() == null || request.minValue() == null || request.maxValue() == null || request.minValue().compareTo(request.maxValue()) > 0)) {
            throw AppException.badRequest("Luật RANGE cần chỉ tiêu và khoảng min/max hợp lệ.");
        }
        if ("RATE_OF_CHANGE".equals(request.ruleType()) && (request.parameterCode() == null || request.maxDelta() == null || request.maxDelta().signum() < 0)) {
            throw AppException.badRequest("Luật RATE_OF_CHANGE cần chỉ tiêu và max_delta không âm.");
        }
        if (request.windowMinutes() != null && request.windowMinutes() < 0) throw AppException.badRequest("window_minutes không được âm.");
    }

    private void validatePaging(int page, int limit) {
        if (page < 1 || limit < 1 || limit > 200) throw AppException.badRequest("page phải ≥ 1 và limit trong khoảng 1–200.");
    }

    private void validateRange(OffsetDateTime from, OffsetDateTime to) {
        if (from != null && to != null && !from.isBefore(to)) throw AppException.badRequest("from phải trước to.");
    }

    private void requireReason(String reason, String message) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) throw AppException.badRequest(message);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    public record RuleRequest(String ruleCode, String name, String target, String ruleType, String parameterCode,
                              BigDecimal minValue, BigDecimal maxValue, BigDecimal maxDelta,
                              Integer windowMinutes, String severity, String description) {
    }
}
