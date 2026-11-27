package vn.aiot.biochar.modules.laboratory;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import vn.aiot.biochar.common.api.ApiErrorResponse.ErrorDetail;
import vn.aiot.biochar.common.crud.Field;
import vn.aiot.biochar.common.crud.SqlRows;
import vn.aiot.biochar.common.db.UserTransactionService;
import vn.aiot.biochar.common.exception.AppException;
import vn.aiot.biochar.modules.auth.AppUser;

/** Nhập kết quả phòng thí nghiệm từ file Excel theo mẫu. Có một dòng lỗi thì không ghi dòng nào. */
@Service
public class LabImportService {

    static final String DATA_SHEET = "KetQua";
    static final List<String> COLUMNS = List.of(
            "sample_code", "parameter_code", "value", "unit",
            "method_code", "replicate_no", "below_lod", "analyzed_at");
    private static final List<String> REQUIRED_COLUMNS = List.of("sample_code", "parameter_code", "value");
    private static final int MAX_ROWS = 2000;

    private static final Field VALUE = Field.decimal("value", -1e15, 1e15).required();
    private static final Field REPLICATE = Field.integer("replicate_no", 1, 100);
    private static final DateTimeFormatter VIETNAMESE_DATE_TIME =
            DateTimeFormatter.ofPattern("d/M/uuuu H:mm").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter VIETNAMESE_DATE =
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private final SqlRows rows;
    private final UserTransactionService transactions;
    private final LaboratoryService laboratory;

    public LabImportService(SqlRows rows, UserTransactionService transactions, LaboratoryService laboratory) {
        this.rows = rows;
        this.transactions = transactions;
        this.laboratory = laboratory;
    }

    /** File mẫu: trang nhập liệu trống kèm trang hướng dẫn liệt kê mã chỉ tiêu, phương pháp và mẫu. */
    public byte[] template() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            header.setFont(bold);

            Sheet data = workbook.createSheet(DATA_SHEET);
            writeRow(data, 0, header, COLUMNS.toArray());
            for (int column = 0; column < COLUMNS.size(); column++) {
                data.setColumnWidth(column, 20 * 256);
            }
            data.createFreezePane(0, 1);

            Sheet guide = workbook.createSheet("HuongDan");
            int line = 0;
            writeRow(guide, line++, header, "Cột", "Bắt buộc", "Ý nghĩa");
            for (String[] item : new String[][] {
                    {"sample_code", "Có", "Mã mẫu đã khai báo trong hệ thống"},
                    {"parameter_code", "Có", "Mã chỉ tiêu (bảng bên dưới)"},
                    {"value", "Có", "Giá trị đo, dạng số"},
                    {"unit", "Không", "Bỏ trống thì lấy đơn vị chuẩn; nếu nhập phải trùng đơn vị chuẩn"},
                    {"method_code", "Không", "Mã phương pháp, phải đúng chỉ tiêu"},
                    {"replicate_no", "Không", "Lần lặp, mặc định 1"},
                    {"below_lod", "Không", "true nếu dưới giới hạn phát hiện"},
                    {"analyzed_at", "Không", "Ô kiểu ngày, hoặc 2026-03-15T08:30, hoặc 15/03/2026 08:30"}}) {
                writeRow(guide, line++, null, (Object[]) item);
            }
            line++;
            writeRow(guide, line++, header, "parameter_code", "Tên", "Đơn vị");
            for (Map<String, Object> row : rows.query(
                    "SELECT parameter_code, name_vi, unit FROM ref.parameters ORDER BY parameter_code")) {
                writeRow(guide, line++, null, row.get("parameter_code"), row.get("name_vi"), row.get("unit"));
            }
            line++;
            writeRow(guide, line++, header, "method_code", "Chỉ tiêu", "Tên phương pháp");
            for (Map<String, Object> row : rows.query(
                    "SELECT method_code, parameter_code, name FROM laboratory.lab_methods ORDER BY method_code")) {
                writeRow(guide, line++, null, row.get("method_code"), row.get("parameter_code"), row.get("name"));
            }
            line++;
            writeRow(guide, line++, header, "sample_code (200 mẫu mới nhất)", "Ô", "Thời điểm lấy mẫu");
            for (Map<String, Object> row : rows.query("""
                    SELECT s.sample_code, p.plot_code,
                           to_char(s.sampled_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'DD/MM/YYYY HH24:MI') AS sampled
                      FROM laboratory.soil_samples s
                      JOIN farm.plots p ON p.plot_id = s.plot_id
                     WHERE s.status <> 'REJECTED'
                     ORDER BY s.sampled_at DESC LIMIT 200
                    """)) {
                writeRow(guide, line++, null, row.get("sample_code"), row.get("plot_code"), row.get("sampled"));
            }
            guide.setColumnWidth(0, 32 * 256);
            guide.setColumnWidth(1, 28 * 256);
            guide.setColumnWidth(2, 60 * 256);

            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public ImportReport importWorkbook(InputStream input, boolean dryRun, AppUser actor) {
        List<ParsedRow> parsed = read(input);
        return transactions.execute(actor.id(), "Nhập Excel kết quả phòng thí nghiệm", () -> {
            List<RowError> errors = new ArrayList<>();
            List<Object[]> inserts = new ArrayList<>();
            Map<String, Map<String, Object>> samples = new HashMap<>();
            Map<String, Map<String, Object>> parameters = new HashMap<>();
            Map<String, Map<String, Object>> methods = new HashMap<>();
            rows.query("SELECT parameter_code, unit, physical_min, physical_max FROM ref.parameters")
                    .forEach(row -> parameters.put((String) row.get("parameter_code"), row));
            rows.query("SELECT method_id, method_code, parameter_code FROM laboratory.lab_methods")
                    .forEach(row -> methods.put((String) row.get("method_code"), row));
            // nạp và khóa mẫu theo thứ tự mã để hai lần nhập đồng thời không khóa chéo nhau
            parsed.stream().map(row -> row.values.get("sample_code")).filter(code -> code != null)
                    .map(Object::toString).distinct().sorted().forEach(code -> {
                        Map<String, Object> sample = rows.first("""
                                SELECT sample_id, status, sampled_at FROM laboratory.soil_samples
                                 WHERE sample_code = ? FOR UPDATE
                                """, code);
                        if (sample != null) {
                            samples.put(code, sample);
                        }
                    });

            Set<String> keysInFile = new HashSet<>();
            int validRows = 0;
            for (ParsedRow row : parsed) {
                List<ErrorDetail> problems = new ArrayList<>(row.problems);
                String sampleCode = (String) row.values.get("sample_code");
                String parameterCode = (String) row.values.get("parameter_code");
                String methodCode = (String) row.values.get("method_code");
                Map<String, Object> sample = sampleCode == null ? null : samples.get(sampleCode);
                Map<String, Object> parameter = parameterCode == null ? null : parameters.get(parameterCode);
                Map<String, Object> method = methodCode == null ? null : methods.get(methodCode);
                if (sampleCode != null && sample == null) {
                    problems.add(new ErrorDetail("sample_code", "Không có mẫu " + sampleCode));
                }
                if (parameterCode != null && parameter == null) {
                    problems.add(new ErrorDetail("parameter_code", "Không có chỉ tiêu " + parameterCode));
                }
                if (methodCode != null && method == null) {
                    problems.add(new ErrorDetail("method_code", "Không có phương pháp " + methodCode));
                }
                BigDecimal value = (BigDecimal) row.values.get("value");
                String unit = (String) row.values.get("unit");
                Instant analyzedAt = (Instant) row.values.get("analyzed_at");
                int replicate = row.values.get("replicate_no") == null
                        ? 1 : (Integer) row.values.get("replicate_no");
                if (sample != null && parameter != null) {
                    laboratory.checkResultValues(
                                    sample, parameter, method, parameterCode, value, unit, analyzedAt)
                            .forEach(error -> problems.add(new ErrorDetail(
                                    excelColumn(error.field()), error.message())));
                    String key = sampleCode + "|" + parameterCode + "|" + replicate;
                    if (!keysInFile.add(key)) {
                        problems.add(new ErrorDetail("replicate_no", "Trùng với một dòng khác trong file"));
                    } else if (rows.count("""
                            SELECT count(*) FROM laboratory.lab_results
                             WHERE sample_id = ? AND parameter_code = ? AND replicate_no = ?
                            """, sample.get("sample_id"), parameterCode, replicate) > 0) {
                        problems.add(new ErrorDetail("replicate_no",
                                "Đã có kết quả cho mẫu, chỉ tiêu và lần lặp này"));
                    }
                }
                if (problems.isEmpty()) {
                    validRows++;
                    inserts.add(new Object[] {
                            sample.get("sample_id"), parameterCode, value,
                            unit == null ? parameter.get("unit") : unit,
                            method == null ? null : method.get("method_id"), replicate,
                            Boolean.TRUE.equals(row.values.get("below_lod")), analyzedAt, actor.id()});
                } else {
                    problems.forEach(problem ->
                            errors.add(new RowError(row.number, problem.field(), problem.message())));
                }
            }

            int inserted = 0;
            if (!dryRun && errors.isEmpty()) {
                for (Object[] values : inserts) {
                    inserted += rows.update("""
                            INSERT INTO laboratory.lab_results
                                (sample_id, parameter_code, value, unit, method_id, replicate_no,
                                 below_lod, analyzed_at, analyst_id, result_status)
                            VALUES (?, ?, ?, ?, ?::uuid, ?, ?, ?::timestamptz, ?, 'DRAFT')
                            """, values);
                }
            }
            return new ImportReport(parsed.size(), validRows, parsed.size() - validRows, inserted, dryRun, errors);
        });
    }

    private String excelColumn(String field) {
        return switch (field) {
            case "sample_id" -> "sample_code";
            case "method_id" -> "method_code";
            default -> field;
        };
    }

    private List<ParsedRow> read(InputStream input) {
        try (Workbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheet(DATA_SHEET);
            if (sheet == null) {
                sheet = workbook.getSheetAt(0);
            }
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Map<String, Integer> positions = readHeader(sheet.getRow(0), formatter);
            List<ParsedRow> result = new ArrayList<>();
            for (Row row : sheet) {
                if (row.getRowNum() == 0 || isEmpty(row, positions, formatter)) {
                    continue;
                }
                if (result.size() >= MAX_ROWS) {
                    throw AppException.badRequest("File tối đa " + MAX_ROWS + " dòng dữ liệu");
                }
                result.add(parseRow(row, positions, formatter));
            }
            if (result.isEmpty()) {
                throw AppException.badRequest("File không có dòng dữ liệu nào");
            }
            return result;
        } catch (AppException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw AppException.badRequest("Không đọc được file. Hãy dùng file .xlsx theo mẫu");
        }
    }

    private Map<String, Integer> readHeader(Row header, DataFormatter formatter) {
        Map<String, Integer> positions = new HashMap<>();
        if (header != null) {
            for (Cell cell : header) {
                String name = formatter.formatCellValue(cell).trim().toLowerCase(Locale.ROOT);
                if (COLUMNS.contains(name)) {
                    positions.put(name, cell.getColumnIndex());
                }
            }
        }
        List<String> missing = REQUIRED_COLUMNS.stream().filter(name -> !positions.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            throw AppException.badRequest("Dòng tiêu đề thiếu cột: " + String.join(", ", missing));
        }
        return positions;
    }

    private boolean isEmpty(Row row, Map<String, Integer> positions, DataFormatter formatter) {
        return positions.values().stream()
                .allMatch(column -> formatter.formatCellValue(row.getCell(column)).isBlank());
    }

    private ParsedRow parseRow(Row row, Map<String, Integer> positions, DataFormatter formatter) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<ErrorDetail> problems = new ArrayList<>();
        for (String column : COLUMNS) {
            Cell cell = positions.containsKey(column) ? row.getCell(positions.get(column)) : null;
            try {
                values.put(column, parseCell(column, cell, formatter));
            } catch (IllegalArgumentException exception) {
                problems.add(new ErrorDetail(column, exception.getMessage()));
            }
        }
        for (String column : REQUIRED_COLUMNS) {
            if (values.containsKey(column) && values.get(column) == null) {
                problems.add(new ErrorDetail(column, "Bắt buộc nhập"));
            }
        }
        return new ParsedRow(row.getRowNum() + 1, values, problems);
    }

    private Object parseCell(String column, Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return null;
        }
        // ô công thức: dùng giá trị Excel đã tính sẵn, không tính lại trên máy chủ
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.BLANK) {
            return null;
        }
        if (type == CellType.ERROR) {
            throw new IllegalArgumentException("Ô đang báo lỗi công thức");
        }
        String text = switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            default -> formatter.formatCellValue(cell).trim();
        };
        if (text.isEmpty()) {
            return null;
        }
        return switch (column) {
            case "parameter_code" -> text.toUpperCase(Locale.ROOT);
            case "value" -> VALUE.parse(decimalText(text));
            case "replicate_no" -> REPLICATE.parse(text);
            case "below_lod" -> parseFlag(text);
            case "analyzed_at" -> type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().atZone(Field.ZONE).toInstant()
                    : parseDateTime(text);
            default -> text;
        };
    }

    /** Chấp nhận dấu phẩy thập phân kiểu Việt Nam (5,6) khi không có dấu chấm. */
    static String decimalText(String text) {
        return text.matches("^-?\\d+,\\d+$") ? text.replace(',', '.') : text;
    }

    static Boolean parseFlag(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true", "1", "x", "có", "yes" -> true;
            case "false", "0", "không", "no" -> false;
            default -> throw new IllegalArgumentException("Chỉ nhận true hoặc false");
        };
    }

    static Instant parseDateTime(String text) {
        try {
            if (text.contains("/")) {
                LocalDateTime local = text.contains(":")
                        ? LocalDateTime.parse(text, VIETNAMESE_DATE_TIME)
                        : LocalDate.parse(text, VIETNAMESE_DATE).atStartOfDay();
                return local.atZone(Field.ZONE).toInstant();
            }
            if (text.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                return LocalDate.parse(text).atStartOfDay(Field.ZONE).toInstant();
            }
            return (Instant) Field.timestamp("analyzed_at").parse(text.replace(' ', 'T'));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Thời điểm không hợp lệ, ví dụ 15/03/2026 08:30");
        }
    }

    private void writeRow(Sheet sheet, int index, CellStyle style, Object... values) {
        Row row = sheet.createRow(index);
        for (int column = 0; column < values.length; column++) {
            Cell cell = row.createCell(column);
            cell.setCellValue(values[column] == null ? "" : values[column].toString());
            if (style != null) {
                cell.setCellStyle(style);
            }
        }
    }

    private record ParsedRow(int number, Map<String, Object> values, List<ErrorDetail> problems) {
    }

    public record RowError(int row, String field, String message) {
    }

    public record ImportReport(
            @JsonProperty("total_rows") int totalRows,
            @JsonProperty("valid_rows") int validRows,
            @JsonProperty("error_rows") int errorRows,
            int inserted,
            @JsonProperty("dry_run") boolean dryRun,
            List<RowError> errors) {
    }
}
