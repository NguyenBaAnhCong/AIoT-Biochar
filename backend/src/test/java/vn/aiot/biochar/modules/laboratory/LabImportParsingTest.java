package vn.aiot.biochar.modules.laboratory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import vn.aiot.biochar.common.crud.Field;

class LabImportParsingTest {

    @Test
    void readsVietnameseAndIsoDateTimesInVietnamTime() {
        Instant expected = Instant.parse("2026-03-15T01:30:00Z");
        assertEquals(expected, LabImportService.parseDateTime("15/03/2026 08:30"));
        assertEquals(expected, LabImportService.parseDateTime("2026-03-15 08:30"));
        assertEquals(expected, LabImportService.parseDateTime("2026-03-15T08:30:00+07:00"));
        assertThrows(IllegalArgumentException.class, () -> LabImportService.parseDateTime("31/02/2026"));
        assertThrows(IllegalArgumentException.class, () -> LabImportService.parseDateTime("hôm qua"));
    }

    @Test
    void readsDecimalCommaAndFlags() {
        assertEquals("5.6", LabImportService.decimalText("5,6"));
        assertEquals("1,234.5", LabImportService.decimalText("1,234.5"));
        assertEquals(true, LabImportService.parseFlag("Có"));
        assertEquals(false, LabImportService.parseFlag("0"));
        assertThrows(IllegalArgumentException.class, () -> LabImportService.parseFlag("có lẽ"));
    }

    @Test
    void fieldRejectsValuesOutsideItsRules() {
        Field ph = Field.decimal("ph", 0, 14);
        assertThrows(IllegalArgumentException.class, () -> ph.parse("15"));
        assertThrows(IllegalArgumentException.class, () -> ph.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> Field.code("plot_code").required().parse("  "));
        assertThrows(IllegalArgumentException.class, () -> Field.code("plot_code").parse("A 1"));
        assertThrows(IllegalArgumentException.class, () -> Field.integer("block_no", 1, 10).parse("1.5"));
        assertThrows(IllegalArgumentException.class, () -> Field.date("planted_on").parse("15/03/2026"));
        assertThrows(IllegalArgumentException.class, () -> Field.uuid("plot_id").parse("1-1-1-1-1"));
    }
}
