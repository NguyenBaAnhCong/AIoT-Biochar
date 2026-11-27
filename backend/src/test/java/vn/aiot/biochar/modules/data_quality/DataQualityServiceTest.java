package vn.aiot.biochar.modules.data_quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.aiot.biochar.common.exception.AppException;

class DataQualityServiceTest {

    private final IDataQualityService service = new DataQualityService(null, null);

    @Test
    void rejectsInvalidRuleWithoutWritingToDatabase() {
        var request = new DataQualityService.RuleRequest("PH", "Khoảng pH", "SENSOR_READING", "RANGE", "PH",
                new BigDecimal("8"), new BigDecimal("3"), null, null, "ERROR", null);
        AppException error = assertThrows(AppException.class, () -> service.createRule(request));
        assertEquals(400, error.status());
    }

    @Test
    void requiresOrderedQcWindow() {
        OffsetDateTime now = OffsetDateTime.now();
        AppException error = assertThrows(AppException.class, () -> service.runChecks(now, now.minusMinutes(1), UUID.randomUUID()));
        assertEquals("VALIDATION_ERROR", error.code());
    }
}
