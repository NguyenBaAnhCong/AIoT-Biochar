package vn.aiot.biochar.modules.biochar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.aiot.biochar.common.crud.Change;
import vn.aiot.biochar.common.crud.CrudService;
import vn.aiot.biochar.common.crud.Field;
import vn.aiot.biochar.common.crud.Resource;
import vn.aiot.biochar.common.crud.SqlRows;
import vn.aiot.biochar.common.exception.AppException;

/** Lô phụ phẩm cây vải → lô biochar (nhiệt phân, chất lượng, an toàn) → lô làm giàu (schema biochar). */
@Service
public class BiocharService {

    private static final String[] FEEDSTOCK_TYPES = {
            "LITCHI_PRUNING", "LITCHI_BRANCH", "LITCHI_LEAF", "LITCHI_PEEL", "LITCHI_SEED", "MIXED", "OTHER"};
    private static final String[] SAFETY = {"PENDING", "PASSED", "FAILED"};

    private final SqlRows rows;
    private final CrudService crud;
    private final Map<String, Resource> resources;

    public BiocharService(SqlRows rows, CrudService crud) {
        this.rows = rows;
        this.crud = crud;
        this.resources = Map.of(
                "feedstock-batches", feedstockBatches(),
                "batches", biocharBatches(),
                "enrichment-batches", enrichmentBatches(),
                "enrichment-results", enrichmentResults());
    }

    public Resource resource(String name) {
        return resources.get(name);
    }

    /** Truy xuất nguồn gốc một lô biochar: phụ phẩm đầu vào, các lô làm giàu và nơi đã sử dụng. */
    public Map<String, Object> trace(UUID biocharBatchId) {
        Map<String, Object> batch = crud.get(resources.get("batches"), biocharBatchId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("biochar_batch", batch);
        result.put("feedstock_batch",
                crud.get(resources.get("feedstock-batches"), (UUID) batch.get("feedstock_batch_id")));
        result.put("enrichment_batches", rows.query("""
                SELECT * FROM biochar.enrichment_batches WHERE biochar_batch_id = ? ORDER BY started_on, batch_code
                """, biocharBatchId));
        result.put("usages", rows.query("""
                SELECT e.experiment_code, t.treatment_code, p.plot_code, a.applied_on,
                       COALESCE(a.actual_dose_kg_m2, t.biochar_dose_kg_m2) AS dose_kg_m2
                  FROM experiment.treatments t
                  JOIN experiment.experiments e ON e.experiment_id = t.experiment_id
                  LEFT JOIN experiment.treatment_applications a ON a.treatment_id = t.treatment_id
                  LEFT JOIN farm.plots p ON p.plot_id = a.plot_id
                 WHERE t.biochar_batch_id = ?
                 ORDER BY t.treatment_code, p.plot_code, a.applied_on
                """, biocharBatchId));
        return result;
    }

    private Resource feedstockBatches() {
        return Resource.of("lô phụ phẩm", "biochar.feedstock_batches", "feedstock_batch_id")
                .fields(
                        Field.code("batch_code").required(),
                        Field.choice("feedstock_type", FEEDSTOCK_TYPES).notNull(),
                        Field.text("source_location", 255),
                        Field.date("collected_on").required(),
                        Field.decimal("mass_kg", 0, 99_999_999).positive().required(),
                        Field.decimal("moisture_pct", 0, 100),
                        Field.text("pretreatment", 255),
                        Field.text("notes", 2000))
                .select("""
                        SELECT t.*, COALESCE((SELECT sum(b.input_mass_kg) FROM biochar.biochar_batches b
                                               WHERE b.feedstock_batch_id = t.feedstock_batch_id), 0)
                               AS used_mass_kg
                          FROM biochar.feedstock_batches t""")
                .filter(Field.choice("feedstock_type", FEEDSTOCK_TYPES))
                .search("t.batch_code", "t.source_location")
                .orderBy("t.collected_on DESC, t.batch_code")
                .rule(this::checkFeedstock);
    }

    private Resource biocharBatches() {
        return Resource.of("lô biochar", "biochar.biochar_batches", "biochar_batch_id")
                .fields(
                        Field.code("batch_code").required(),
                        Field.uuid("feedstock_batch_id").required()
                                .references("biochar.feedstock_batches", "feedstock_batch_id"),
                        Field.date("produced_on").required(),
                        Field.text("reactor_type", 64),
                        Field.decimal("pyrolysis_temp_c", 200, 1000),
                        Field.decimal("residence_time_min", 0, 999_999).positive(),
                        Field.decimal("heating_rate_c_min", 0, 9999),
                        Field.decimal("input_mass_kg", 0, 99_999_999).positive(),
                        Field.decimal("output_mass_kg", 0, 99_999_999),
                        Field.decimal("ph", 0, 14),
                        Field.decimal("ec_ds_m", 0, 99_999),
                        Field.decimal("carbon_pct", 0, 100),
                        Field.decimal("ash_pct", 0, 100),
                        Field.decimal("surface_area_m2_g", 0, 999_999),
                        Field.decimal("cec_cmol_kg", 0, 999_999),
                        Field.json("heavy_metals_mg_kg"),
                        Field.choice("safety_status", SAFETY).notNull(),
                        Field.text("notes", 2000))
                .select("""
                        SELECT t.*, f.batch_code AS feedstock_batch_code, f.feedstock_type
                          FROM biochar.biochar_batches t
                          JOIN biochar.feedstock_batches f ON f.feedstock_batch_id = t.feedstock_batch_id""")
                .filter(Field.uuid("feedstock_batch_id"))
                .filter(Field.choice("safety_status", SAFETY))
                .search("t.batch_code", "f.batch_code")
                .orderBy("t.produced_on DESC, t.batch_code")
                .rule(this::checkBiocharBatch);
    }

    private Resource enrichmentBatches() {
        return Resource.of("lô làm giàu", "biochar.enrichment_batches", "enrichment_batch_id")
                .fields(
                        Field.code("batch_code").required(),
                        Field.uuid("biochar_batch_id").required()
                                .references("biochar.biochar_batches", "biochar_batch_id"),
                        Field.choice("enrichment_type", "MICROBIAL", "NUTRIENT", "COMPOST", "MIXED").required(),
                        Field.text("microbial_formula", 255),
                        Field.text("nutrient_formula", 255),
                        Field.text("ratio_desc", 255),
                        Field.date("started_on").required(),
                        Field.date("finished_on"),
                        Field.text("notes", 2000))
                .select("""
                        SELECT t.*, b.batch_code AS biochar_batch_code, b.safety_status AS biochar_safety_status
                          FROM biochar.enrichment_batches t
                          JOIN biochar.biochar_batches b ON b.biochar_batch_id = t.biochar_batch_id""")
                .filter(Field.uuid("biochar_batch_id"))
                .search("t.batch_code", "b.batch_code")
                .orderBy("t.started_on DESC, t.batch_code")
                .rule(this::checkEnrichmentBatch);
    }

    private Resource enrichmentResults() {
        return Resource.of("kết quả làm giàu", "biochar.enrichment_results", "enrichment_result_id")
                .fields(
                        Field.uuid("enrichment_batch_id").required()
                                .references("biochar.enrichment_batches", "enrichment_batch_id"),
                        Field.code("parameter_code").upperCase().required(),
                        Field.decimal("value", -1e15, 1e15).required(),
                        Field.text("unit", 32).required(),
                        Field.date("measured_on").required(),
                        Field.text("method", 255),
                        Field.bool("pass_flag"))
                .select("""
                        SELECT t.*, n.batch_code AS enrichment_batch_code
                          FROM biochar.enrichment_results t
                          JOIN biochar.enrichment_batches n ON n.enrichment_batch_id = t.enrichment_batch_id""")
                .filter(Field.uuid("enrichment_batch_id"))
                .orderBy("t.measured_on DESC, n.batch_code, t.parameter_code")
                .rule(this::checkEnrichmentResult);
    }

    private void checkFeedstock(Change change) {
        requireNotFuture(change, "collected_on");
        if (change.creating()) {
            return;
        }
        Map<String, Object> usage = rows.first("""
                SELECT COALESCE(sum(input_mass_kg), 0) AS used, min(produced_on) AS first_produced
                  FROM biochar.biochar_batches WHERE feedstock_batch_id = ?
                """, change.id());
        if (change.number("mass_kg").compareTo((BigDecimal) usage.get("used")) < 0) {
            throw Change.invalid("mass_kg",
                    "Khối lượng nhỏ hơn phần đã đưa vào nhiệt phân (" + usage.get("used") + " kg)");
        }
        LocalDate firstProduced = (LocalDate) usage.get("first_produced");
        if (firstProduced != null && change.date("collected_on").isAfter(firstProduced)) {
            throw Change.invalid("collected_on", "Ngày thu gom không được sau ngày sản xuất biochar từ lô này");
        }
    }

    private void checkBiocharBatch(Change change) {
        requireNotFuture(change, "produced_on");
        // khóa lô phụ phẩm để tổng khối lượng sử dụng không bị vượt khi tạo đồng thời
        Map<String, Object> feedstock = rows.first("""
                SELECT collected_on, mass_kg FROM biochar.feedstock_batches
                 WHERE feedstock_batch_id = ? FOR UPDATE
                """, change.uuid("feedstock_batch_id"));
        if (change.date("produced_on").isBefore((LocalDate) feedstock.get("collected_on"))) {
            throw Change.invalid("produced_on", "Ngày sản xuất không được trước ngày thu gom phụ phẩm");
        }
        BigDecimal input = change.number("input_mass_kg");
        BigDecimal output = change.number("output_mass_kg");
        if (input != null && output != null && output.compareTo(input) > 0) {
            throw Change.invalid("output_mass_kg", "Khối lượng ra không được lớn hơn khối lượng vào");
        }
        if (input != null) {
            BigDecimal used = (BigDecimal) rows.first("""
                    SELECT COALESCE(sum(input_mass_kg), 0) AS used FROM biochar.biochar_batches
                     WHERE feedstock_batch_id = ? AND biochar_batch_id IS DISTINCT FROM ?::uuid
                    """, change.uuid("feedstock_batch_id"), change.id()).get("used");
            BigDecimal remaining = ((BigDecimal) feedstock.get("mass_kg")).subtract(used);
            if (input.compareTo(remaining) > 0) {
                throw Change.invalid("input_mass_kg", "Lô phụ phẩm chỉ còn " + remaining + " kg");
            }
        }
        checkHeavyMetals(change);
    }

    private void checkHeavyMetals(Change change) {
        if (change.sent("heavy_metals_mg_kg") && change.get("heavy_metals_mg_kg") instanceof Map<?, ?> metals) {
            for (Map.Entry<?, ?> entry : metals.entrySet()) {
                if (!String.valueOf(entry.getKey()).matches("^[A-Za-z0-9_]{1,16}$")
                        || !(entry.getValue() instanceof Number number) || number.doubleValue() < 0) {
                    throw Change.invalid("heavy_metals_mg_kg",
                            "Mỗi chỉ tiêu phải có dạng {\"Pb\": 2.1} với giá trị là số không âm");
                }
            }
            if (metals.isEmpty()) {
                change.set("heavy_metals_mg_kg", null);
            }
        }
        // số liệu an toàn thay đổi thì kết luận cũ không còn giá trị
        if (!change.creating() && !change.sent("safety_status")
                && change.sent("heavy_metals_mg_kg") && !sameMetals(change)) {
            change.set("safety_status", "PENDING");
        }
        if ("PASSED".equals(change.get("safety_status")) && change.get("heavy_metals_mg_kg") == null) {
            throw Change.invalid("heavy_metals_mg_kg", "Phải có kết quả kim loại nặng trước khi kết luận PASSED");
        }
    }

    private boolean sameMetals(Change change) {
        Object before = change.before("heavy_metals_mg_kg");
        Object after = change.get("heavy_metals_mg_kg");
        if (before instanceof Map<?, ?> left && after instanceof Map<?, ?> right) {
            return left.keySet().equals(right.keySet()) && left.entrySet().stream().allMatch(entry ->
                    new BigDecimal(String.valueOf(entry.getValue()))
                            .compareTo(new BigDecimal(String.valueOf(right.get(entry.getKey())))) == 0);
        }
        return before == null && after == null;
    }

    private void checkEnrichmentBatch(Change change) {
        LocalDate started = change.date("started_on");
        LocalDate finished = change.date("finished_on");
        requireNotFuture(change, "started_on");
        if (finished != null && finished.isBefore(started)) {
            throw Change.invalid("finished_on", "Ngày kết thúc ủ không được trước ngày bắt đầu");
        }
        Map<String, Object> source = rows.first(
                "SELECT produced_on, safety_status FROM biochar.biochar_batches WHERE biochar_batch_id = ?",
                change.uuid("biochar_batch_id"));
        if (started.isBefore((LocalDate) source.get("produced_on"))) {
            throw Change.invalid("started_on", "Không thể làm giàu trước ngày sản xuất lô biochar");
        }
        if ((change.creating() || change.changed("biochar_batch_id"))
                && "FAILED".equals(source.get("safety_status"))) {
            throw Change.invalid("biochar_batch_id", "Lô biochar không đạt an toàn, không được làm giàu");
        }
        if (!change.creating() && change.changed("biochar_batch_id") && rows.count(
                "SELECT count(*) FROM experiment.treatments WHERE enrichment_batch_id = ?", change.id()) > 0) {
            throw AppException.conflict("Lô làm giàu đã dùng trong nghiệm thức, không thể đổi lô biochar nguồn");
        }
    }

    private void checkEnrichmentResult(Change change) {
        requireNotFuture(change, "measured_on");
        LocalDate started = (LocalDate) rows.first(
                "SELECT started_on FROM biochar.enrichment_batches WHERE enrichment_batch_id = ?",
                change.uuid("enrichment_batch_id")).get("started_on");
        if (change.date("measured_on").isBefore(started)) {
            throw Change.invalid("measured_on", "Ngày đo không được trước ngày bắt đầu làm giàu");
        }
    }

    private void requireNotFuture(Change change, String field) {
        if (change.changed(field) && change.date(field).isAfter(LocalDate.now(Field.ZONE))) {
            throw Change.invalid(field, "Không được là ngày trong tương lai");
        }
    }
}
