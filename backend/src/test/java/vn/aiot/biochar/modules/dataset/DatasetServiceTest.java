package vn.aiot.biochar.modules.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.aiot.biochar.common.exception.AppException;

class DatasetServiceTest {

    private final IDatasetService service = new DatasetService(null, null);

    @Test
    void rejectsSplitRatiosThatDoNotSumToOne() {
        OffsetDateTime from = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2026-02-01T00:00:00Z");
        var request = new DatasetService.CreateVersionRequest(from, to, "RAW", "BY_TIME",
                Map.of("TRAIN", .7, "VAL", .1, "TEST", .1), 42, null);
        AppException error = assertThrows(AppException.class, () -> service.createVersion(UUID.randomUUID(), request, UUID.randomUUID()));
        assertEquals(400, error.status());
    }

    @Test
    void rejectsUnsupportedSplitStrategy() {
        OffsetDateTime from = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2026-02-01T00:00:00Z");
        var request = new DatasetService.CreateVersionRequest(from, to, "RAW", "INVALID", null, null, null);
        AppException error = assertThrows(AppException.class, () -> service.createVersion(UUID.randomUUID(), request, UUID.randomUUID()));
        assertEquals(400, error.status());
    }
}
