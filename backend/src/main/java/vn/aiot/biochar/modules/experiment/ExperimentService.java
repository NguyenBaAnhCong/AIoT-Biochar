package vn.aiot.biochar.modules.experiment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.aiot.biochar.common.crud.Change;
import vn.aiot.biochar.common.crud.Field;
import vn.aiot.biochar.common.crud.Resource;
import vn.aiot.biochar.common.crud.SqlRows;
import vn.aiot.biochar.common.exception.AppException;

/** Thí nghiệm, nghiệm thức, gán nghiệm thức cho ô và giai đoạn sinh trưởng (schema experiment). */
@Service
public class ExperimentService {

    private static final String[] STATUSES = {"PLANNED", "RUNNING", "COMPLETED", "CANCELLED"};
    private static final Map<String, List<String>> TRANSITIONS = Map.of(
            "PLANNED", List.of("RUNNING", "CANCELLED"),
            "RUNNING", List.of("COMPLETED", "CANCELLED"),
            "COMPLETED", List.of(),
            "CANCELLED", List.of());

    private final SqlRows rows;
    private final Map<String, Resource> resources;

    public ExperimentService(SqlRows rows) {
        this.rows = rows;
        this.resources = Map.of(
                "experiments", experiments(),
                "treatments", treatments(),
                "treatment-applications", applications(),
                "growth-stages", growthStages(),
                "plot-growth-stages", plotGrowthStages());
    }

    public Resource resource(String name) {
        return resources.get(name);
    }

    private Resource experiments() {
        return Resource.of("thí nghiệm", "experiment.experiments", "experiment_id")
                .fields(
                        Field.uuid("site_id").required().references("farm.sites", "site_id"),
                        Field.code("experiment_code").required(),
                        Field.text("title", 255).required(),
                        Field.choice("design_type", "CRD", "RCBD", "SPLIT_PLOT", "FACTORIAL", "OBSERVATIONAL")
                                .notNull(),
                        Field.integer("replicates", 1, 1000).notNull(),
                        Field.date("started_on").required(),
                        Field.date("ended_on"),
                        Field.uuid("principal_investigator").references("iam.users", "user_id"),
                        Field.choice("status", STATUSES).notNull(),
                        Field.text("objective", 4000))
                .select("""
                        SELECT t.*, s.site_code, u.full_name AS principal_investigator_name,
                               (SELECT count(*) FROM experiment.treatments x
                                 WHERE x.experiment_id = t.experiment_id) AS treatment_count
                          FROM experiment.experiments t
                          JOIN farm.sites s ON s.site_id = t.site_id
                          LEFT JOIN iam.users u ON u.user_id = t.principal_investigator""")
                .filter(Field.uuid("site_id"))
                .filter(Field.choice("status", STATUSES))
                .search("t.experiment_code", "t.title")
                .orderBy("t.started_on DESC, t.experiment_code")
                .rule(this::checkExperiment);
    }

    private Resource treatments() {
        return Resource.of("nghiệm thức", "experiment.treatments", "treatment_id")
                .fields(
                        Field.uuid("experiment_id").required()
                                .references("experiment.experiments", "experiment_id"),
                        Field.code("treatment_code").required(),
                        Field.text("name", 255).required(),
                        Field.decimal("biochar_dose_kg_m2", 0, 99_999).notNull(),
                        Field.uuid("biochar_batch_id").references("biochar.biochar_batches", "biochar_batch_id"),
                        Field.uuid("enrichment_batch_id")
                                .references("biochar.enrichment_batches", "enrichment_batch_id"),
                        Field.text("microbial_formula", 255),
                        Field.text("fertilizer_desc", 255),
                        Field.bool("control_flag").notNull(),
                        Field.text("description", 2000))
                .select("""
                        SELECT t.*, e.experiment_code, e.site_id, b.batch_code AS biochar_batch_code,
                               b.safety_status AS biochar_safety_status, n.batch_code AS enrichment_batch_code
                          FROM experiment.treatments t
                          JOIN experiment.experiments e ON e.experiment_id = t.experiment_id
                          LEFT JOIN biochar.biochar_batches b ON b.biochar_batch_id = t.biochar_batch_id
                          LEFT JOIN biochar.enrichment_batches n
                                 ON n.enrichment_batch_id = t.enrichment_batch_id""")
                .filter(Field.uuid("experiment_id"))
                .search("t.treatment_code", "t.name")
                .orderBy("e.experiment_code, t.treatment_code")
                .rule(this::checkTreatment);
    }

    private Resource applications() {
        return Resource.of("lần gán nghiệm thức", "experiment.treatment_applications", "application_id")
                .fields(
                        Field.uuid("treatment_id").required()
                                .references("experiment.treatments", "treatment_id"),
                        Field.uuid("plot_id").required().references("farm.plots", "plot_id"),
                        Field.uuid("crop_season_id").references("farm.crop_seasons", "crop_season_id"),
                        Field.integer("replicate_no", 1, 1000).notNull(),
                        Field.date("applied_on").required(),
                        Field.timestamp("effective_from").notNull(),
                        Field.timestamp("effective_to"),
                        Field.decimal("actual_dose_kg_m2", 0, 99_999),
                        Field.text("notes", 2000))
                .select("""
                        SELECT t.*, p.plot_code, tr.treatment_code, tr.experiment_id, e.experiment_code,
                               s.season_code, u.full_name AS applied_by_name,
                               COALESCE(t.actual_dose_kg_m2, tr.biochar_dose_kg_m2) AS effective_dose_kg_m2,
                               (t.effective_from <= now()
                                AND (t.effective_to IS NULL OR t.effective_to > now())) AS is_active
                          FROM experiment.treatment_applications t
                          JOIN experiment.treatments tr ON tr.treatment_id = t.treatment_id
                          JOIN experiment.experiments e ON e.experiment_id = tr.experiment_id
                          JOIN farm.plots p ON p.plot_id = t.plot_id
                          LEFT JOIN farm.crop_seasons s ON s.crop_season_id = t.crop_season_id
                          LEFT JOIN iam.users u ON u.user_id = t.applied_by""")
                .filter(Field.uuid("plot_id"))
                .filter(Field.uuid("treatment_id"))
                .filter(Field.uuid("experiment_id"), "tr.experiment_id = ?")
                .orderBy("t.effective_from DESC, p.plot_code")
                .rule(this::checkApplication);
    }

    private Resource growthStages() {
        return Resource.of("giai đoạn sinh trưởng", "experiment.growth_stages", "growth_stage_id")
                .fields(
                        Field.uuid("crop_id").required().references("farm.crops", "crop_id"),
                        Field.code("stage_code").required(),
                        Field.text("name_vi", 128).required(),
                        Field.integer("stage_order", 1, 1000).required(),
                        Field.integer("typical_days_from_planting", 0, 100_000))
                .select("""
                        SELECT t.*, c.crop_code, c.name_vi AS crop_name
                          FROM experiment.growth_stages t
                          JOIN farm.crops c ON c.crop_id = t.crop_id""")
                .filter(Field.uuid("crop_id"))
                .orderBy("c.crop_code, t.stage_order");
    }

    private Resource plotGrowthStages() {
        return Resource.of("ghi nhận giai đoạn", "experiment.plot_growth_stages", "plot_growth_stage_id")
                .fields(
                        Field.uuid("crop_season_id").required()
                                .references("farm.crop_seasons", "crop_season_id"),
                        Field.uuid("growth_stage_id").required()
                                .references("experiment.growth_stages", "growth_stage_id"),
                        Field.timestamp("started_at").required(),
                        Field.timestamp("ended_at"))
                .select("""
                        SELECT t.*, s.season_code, s.plot_id, p.plot_code, g.stage_code,
                               g.name_vi AS stage_name, g.stage_order, u.full_name AS observed_by_name
                          FROM experiment.plot_growth_stages t
                          JOIN farm.crop_seasons s ON s.crop_season_id = t.crop_season_id
                          JOIN farm.plots p ON p.plot_id = s.plot_id
                          JOIN experiment.growth_stages g ON g.growth_stage_id = t.growth_stage_id
                          LEFT JOIN iam.users u ON u.user_id = t.observed_by""")
                .filter(Field.uuid("crop_season_id"))
                .filter(Field.uuid("plot_id"), "s.plot_id = ?")
                .orderBy("p.plot_code, t.started_at DESC")
                .rule(this::checkPlotGrowthStage);
    }

    private void checkExperiment(Change change) {
        LocalDate started = change.date("started_on");
        LocalDate ended = change.date("ended_on");
        if (ended != null && ended.isBefore(started)) {
            throw Change.invalid("ended_on", "Ngày kết thúc không được trước ngày bắt đầu");
        }
        String status = change.text("status");
        if ("COMPLETED".equals(status) && ended == null) {
            throw Change.invalid("ended_on", "Thí nghiệm COMPLETED phải có ngày kết thúc");
        }
        if (!change.creating() && change.changed("status")) {
            String previous = (String) change.before("status");
            if (!TRANSITIONS.get(previous).contains(status)) {
                throw new AppException(409, "INVALID_TRANSITION",
                        "Không thể chuyển trạng thái từ " + previous + " sang " + status);
            }
        }
        if (change.creating()) {
            if (change.get("principal_investigator") == null) {
                change.set("principal_investigator", change.actor().id());
            }
        } else if (change.changed("site_id") && rows.count("""
                SELECT count(*) FROM experiment.treatment_applications a
                  JOIN experiment.treatments t ON t.treatment_id = a.treatment_id
                 WHERE t.experiment_id = ?
                """, change.id()) > 0) {
            throw AppException.conflict("Không thể đổi địa điểm khi thí nghiệm đã gán nghiệm thức cho ô");
        }
    }

    private void checkTreatment(Change change) {
        boolean control = Boolean.TRUE.equals(change.get("control_flag"));
        BigDecimal dose = change.number("biochar_dose_kg_m2");
        UUID biochar = change.uuid("biochar_batch_id");
        UUID enrichment = change.uuid("enrichment_batch_id");
        if (control && (dose != null && dose.signum() != 0 || biochar != null || enrichment != null)) {
            throw Change.invalid("control_flag", "Nghiệm thức đối chứng phải có liều 0 và không gắn lô biochar");
        }
        if (enrichment != null) {
            UUID source = (UUID) rows.first(
                    "SELECT biochar_batch_id FROM biochar.enrichment_batches WHERE enrichment_batch_id = ?",
                    enrichment).get("biochar_batch_id");
            if (biochar == null) {
                biochar = source;
                change.set("biochar_batch_id", source);
            } else if (!biochar.equals(source)) {
                throw Change.invalid("enrichment_batch_id", "Lô làm giàu không được tạo từ lô biochar đã chọn");
            }
        }
        boolean applied = !change.creating() && rows.count(
                "SELECT count(*) FROM experiment.treatment_applications WHERE treatment_id = ?", change.id()) > 0;
        if (applied && change.changed("experiment_id")) {
            throw AppException.conflict("Không thể chuyển nghiệm thức đã gán cho ô sang thí nghiệm khác");
        }
        if (biochar != null && !Objects.equals(biochar, change.before("biochar_batch_id"))) {
            String safety = (String) rows.first(
                    "SELECT safety_status FROM biochar.biochar_batches WHERE biochar_batch_id = ?",
                    biochar).get("safety_status");
            if ("FAILED".equals(safety)) {
                throw Change.invalid("biochar_batch_id", "Lô biochar không đạt an toàn, không được sử dụng");
            }
            if (applied && !"PASSED".equals(safety)) {
                throw Change.invalid("biochar_batch_id",
                        "Nghiệm thức đã bón ngoài đồng chỉ được dùng lô đã đạt an toàn (PASSED)");
            }
        }
    }

    private void checkApplication(Change change) {
        LocalDate appliedOn = change.date("applied_on");
        if (change.creating() && change.get("effective_from") == null) {
            change.set("effective_from", appliedOn.atStartOfDay(Field.ZONE).toInstant());
        }
        Instant from = change.time("effective_from");
        Instant to = change.time("effective_to");
        if (to != null && !to.isAfter(from)) {
            throw Change.invalid("effective_to", "Thời điểm hết hiệu lực phải sau thời điểm bắt đầu");
        }
        UUID plotId = change.uuid("plot_id");
        Map<String, Object> plot = rows.first(
                "SELECT site_id, status FROM farm.plots WHERE plot_id = ? FOR UPDATE", plotId);
        Map<String, Object> treatment = rows.first("""
                SELECT t.control_flag, e.site_id, e.status, b.safety_status
                  FROM experiment.treatments t
                  JOIN experiment.experiments e ON e.experiment_id = t.experiment_id
                  LEFT JOIN biochar.biochar_batches b ON b.biochar_batch_id = t.biochar_batch_id
                 WHERE t.treatment_id = ?
                """, change.uuid("treatment_id"));

        if (!plot.get("site_id").equals(treatment.get("site_id"))) {
            throw Change.invalid("plot_id", "Ô không thuộc địa điểm của thí nghiệm");
        }
        BigDecimal actualDose = change.number("actual_dose_kg_m2");
        if (Boolean.TRUE.equals(treatment.get("control_flag")) && actualDose != null && actualDose.signum() != 0) {
            throw Change.invalid("actual_dose_kg_m2", "Ô đối chứng không được bón biochar");
        }
        // chỉ kiểm tra khi gán mới hoặc đổi ô / nghiệm thức, để vẫn kết thúc được bản ghi cũ
        if (change.creating() || change.changed("plot_id") || change.changed("treatment_id")) {
            String experimentStatus = (String) treatment.get("status");
            if ("COMPLETED".equals(experimentStatus) || "CANCELLED".equals(experimentStatus)) {
                throw AppException.conflict("Thí nghiệm đã kết thúc hoặc đã hủy, không thể gán thêm");
            }
            String plotStatus = (String) plot.get("status");
            if ("INACTIVE".equals(plotStatus) || "RETIRED".equals(plotStatus)) {
                throw Change.invalid("plot_id", "Ô đang ngưng sử dụng");
            }
            Object safety = treatment.get("safety_status");
            if (safety != null && !"PASSED".equals(safety)) {
                throw new AppException(409, "BIOCHAR_NOT_SAFE",
                        "Lô biochar của nghiệm thức chưa đạt an toàn (PASSED), không được bón ra ô");
            }
        }
        UUID seasonId = change.uuid("crop_season_id");
        if (seasonId != null && rows.count(
                "SELECT count(*) FROM farm.crop_seasons WHERE crop_season_id = ? AND plot_id = ?",
                seasonId, plotId) == 0) {
            throw Change.invalid("crop_season_id", "Mùa vụ không thuộc ô đã chọn");
        }
        if (rows.count("""
                SELECT count(*) FROM experiment.treatment_applications
                 WHERE plot_id = ? AND application_id IS DISTINCT FROM ?::uuid
                   AND tstzrange(effective_from, effective_to) && tstzrange(?::timestamptz, ?::timestamptz)
                """, plotId, change.id(), from, to) > 0) {
            throw new AppException(409, "OVERLAP",
                    "Ô đã có nghiệm thức hiệu lực trong khoảng thời gian này. Hãy kết thúc lần gán cũ trước");
        }
        if (change.creating()) {
            change.set("applied_by", change.actor().id());
        }
    }

    private void checkPlotGrowthStage(Change change) {
        Instant started = change.time("started_at");
        Instant ended = change.time("ended_at");
        if (ended != null && !ended.isAfter(started)) {
            throw Change.invalid("ended_at", "Thời điểm kết thúc phải sau thời điểm bắt đầu");
        }
        Map<String, Object> season = rows.first(
                "SELECT crop_id, planted_on, harvested_on FROM farm.crop_seasons WHERE crop_season_id = ? FOR UPDATE",
                change.uuid("crop_season_id"));
        if (rows.count("SELECT count(*) FROM experiment.growth_stages WHERE growth_stage_id = ? AND crop_id = ?",
                change.uuid("growth_stage_id"), season.get("crop_id")) == 0) {
            throw Change.invalid("growth_stage_id", "Giai đoạn không thuộc cây trồng của mùa vụ");
        }
        LocalDate startDate = started.atZone(Field.ZONE).toLocalDate();
        LocalDate harvested = (LocalDate) season.get("harvested_on");
        if (startDate.isBefore((LocalDate) season.get("planted_on"))
                || harvested != null && startDate.isAfter(harvested)) {
            throw Change.invalid("started_at", "Thời điểm bắt đầu nằm ngoài mùa vụ");
        }
        if (rows.count("""
                SELECT count(*) FROM experiment.plot_growth_stages
                 WHERE crop_season_id = ? AND plot_growth_stage_id IS DISTINCT FROM ?::uuid
                   AND tstzrange(started_at, ended_at) && tstzrange(?::timestamptz, ?::timestamptz)
                """, change.uuid("crop_season_id"), change.id(), started, ended) > 0) {
            throw new AppException(409, "OVERLAP", "Giai đoạn chồng lấn thời gian với giai đoạn khác của mùa vụ");
        }
        if (change.creating()) {
            change.set("observed_by", change.actor().id());
        }
    }
}
