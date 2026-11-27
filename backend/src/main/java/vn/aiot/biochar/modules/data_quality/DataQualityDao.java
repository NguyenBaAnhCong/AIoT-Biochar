package vn.aiot.biochar.modules.data_quality;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DataQualityDao {
    List<Map<String, Object>> rules();
    UUID createRule(String code, String name, String target, String type, String parameter,
                    BigDecimal min, BigDecimal max, BigDecimal maxDelta, Integer window,
                    String severity, String description);
    void updateRule(UUID id, String name, String target, String type, String parameter,
                    BigDecimal min, BigDecimal max, BigDecimal maxDelta, Integer window,
                    String severity, String description);
    void setRuleActive(UUID id, boolean active);
    Map<String, Object> issue(long id);
    Map<String, Object> issues(String type, String status, UUID sensorId, UUID plotId,
                               OffsetDateTime from, OffsetDateTime to, int page, int limit);
    Map<String, Object> checks(int page, int limit);
    Map<String, Object> statistics(OffsetDateTime from, OffsetDateTime to);
    List<Map<String, Object>> dailyStatistics(OffsetDateTime from, OffsetDateTime to);
    Map<String, Object> audit(OffsetDateTime from, OffsetDateTime to, int page, int limit);
    UUID runChecks(OffsetDateTime from, OffsetDateTime to, UUID actorId);
    int runMissingChecks(UUID checkId);
    OffsetDateTime latestCheckEnd();
    void resolve(long issueId, String status, String reason, UUID actorId,
                 String parameterCode, BigDecimal correctedValue);
}
