package vn.aiot.biochar.modules.data_quality;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public interface IDataQualityService {
    Map<String, Object> rules();
    UUID createRule(DataQualityService.RuleRequest request);
    void updateRule(UUID id, DataQualityService.RuleRequest request);
    void setRuleActive(UUID id, boolean active);
    Map<String, Object> issues(String type, String status, UUID sensorId, UUID plotId,
                               OffsetDateTime from, OffsetDateTime to, int page, int limit);
    Map<String, Object> checks(int page, int limit);
    Map<String, Object> statistics(OffsetDateTime from, OffsetDateTime to);
    Map<String, Object> audit(OffsetDateTime from, OffsetDateTime to, int page, int limit);
    UUID runChecks(OffsetDateTime from, OffsetDateTime to, UUID actorId);
    int runScheduledChecks(UUID actorId, long lookbackMinutes);
    void confirm(long issueId, String reason, UUID actorId);
    void ignore(long issueId, String reason, UUID actorId);
    void correct(long issueId, String parameterCode, BigDecimal value, String reason, UUID actorId);
}
