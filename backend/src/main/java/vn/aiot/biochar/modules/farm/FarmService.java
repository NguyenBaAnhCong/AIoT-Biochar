package vn.aiot.biochar.modules.farm;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Service;
import vn.aiot.biochar.common.crud.Change;
import vn.aiot.biochar.common.crud.Field;
import vn.aiot.biochar.common.crud.Resource;
import vn.aiot.biochar.common.crud.SqlRows;
import vn.aiot.biochar.common.exception.AppException;

/** Địa điểm, ô thí nghiệm, cây trồng và mùa vụ (schema farm). */
@Service
public class FarmService {

    private final SqlRows rows;
    private final Map<String, Resource> resources;

    public FarmService(SqlRows rows) {
        this.rows = rows;
        this.resources = Map.of(
                "sites", sites(),
                "plots", plots(),
                "crops", crops(),
                "crop-seasons", cropSeasons());
    }

    public Resource resource(String name) {
        return resources.get(name);
    }

    private Resource sites() {
        return Resource.of("địa điểm", "farm.sites", "site_id")
                .fields(
                        Field.uuid("organization_id").references("iam.organizations", "organization_id"),
                        Field.code("site_code").required(),
                        Field.text("name", 255).required(),
                        Field.text("province", 128),
                        Field.text("district", 128),
                        Field.decimal("latitude", -90, 90),
                        Field.decimal("longitude", -180, 180),
                        Field.decimal("elevation_m", -500, 9000),
                        Field.text("climate_zone", 64))
                .select("""
                        SELECT t.*, o.name AS organization_name,
                               (SELECT count(*) FROM farm.plots p WHERE p.site_id = t.site_id) AS plot_count
                          FROM farm.sites t
                          LEFT JOIN iam.organizations o ON o.organization_id = t.organization_id""")
                .search("t.site_code", "t.name", "t.province")
                .orderBy("t.site_code");
    }

    private Resource plots() {
        return Resource.of("ô thí nghiệm", "farm.plots", "plot_id")
                .fields(
                        Field.uuid("site_id").required().references("farm.sites", "site_id"),
                        Field.code("plot_code").required(),
                        Field.decimal("area_m2", 0, 99_999_999).positive().required(),
                        Field.text("soil_type", 64).required(),
                        Field.text("soil_texture", 64),
                        Field.decimal("latitude", -90, 90),
                        Field.decimal("longitude", -180, 180),
                        Field.json("boundary_geojson"),
                        Field.integer("block_no", 1, 1000),
                        Field.choice("status", "ACTIVE", "FALLOW", "INACTIVE", "RETIRED").notNull())
                .select("""
                        SELECT t.*, s.site_code, s.name AS site_name,
                               cur.treatment_id AS current_treatment_id,
                               cur.treatment_code AS current_treatment_code,
                               cur.dose AS current_biochar_dose_kg_m2
                          FROM farm.plots t
                          JOIN farm.sites s ON s.site_id = t.site_id
                          LEFT JOIN LATERAL (
                              SELECT tr.treatment_id, tr.treatment_code,
                                     COALESCE(a.actual_dose_kg_m2, tr.biochar_dose_kg_m2) AS dose
                                FROM experiment.treatment_applications a
                                JOIN experiment.treatments tr ON tr.treatment_id = a.treatment_id
                               WHERE a.plot_id = t.plot_id AND a.effective_from <= now()
                                 AND (a.effective_to IS NULL OR a.effective_to > now())
                               ORDER BY a.effective_from DESC
                               LIMIT 1) cur ON true""")
                .filter(Field.uuid("site_id"))
                .filter(Field.choice("status", "ACTIVE", "FALLOW", "INACTIVE", "RETIRED"))
                .search("t.plot_code", "t.soil_type")
                .orderBy("s.site_code, t.plot_code")
                .rule(this::checkPlot);
    }

    private Resource crops() {
        return Resource.of("cây trồng", "farm.crops", "crop_id")
                .fields(
                        Field.code("crop_code").required(),
                        Field.text("name_vi", 128).required(),
                        Field.text("scientific_name", 128),
                        Field.text("variety", 128))
                .search("t.crop_code", "t.name_vi")
                .orderBy("t.crop_code");
    }

    private Resource cropSeasons() {
        return Resource.of("mùa vụ", "farm.crop_seasons", "crop_season_id")
                .fields(
                        Field.uuid("plot_id").required().references("farm.plots", "plot_id"),
                        Field.uuid("crop_id").required().references("farm.crops", "crop_id"),
                        Field.code("season_code").required(),
                        Field.date("planted_on").required(),
                        Field.date("harvested_on"),
                        Field.decimal("planting_density", 0, 99_999_999),
                        Field.text("notes", 2000))
                .select("""
                        SELECT t.*, p.plot_code, p.site_id, c.crop_code, c.name_vi AS crop_name
                          FROM farm.crop_seasons t
                          JOIN farm.plots p ON p.plot_id = t.plot_id
                          JOIN farm.crops c ON c.crop_id = t.crop_id""")
                .filter(Field.uuid("plot_id"))
                .filter(Field.uuid("crop_id"))
                .search("t.season_code", "p.plot_code")
                .orderBy("t.planted_on DESC, p.plot_code")
                .rule(this::checkCropSeason);
    }

    private void checkPlot(Change change) {
        if (!change.creating() && change.changed("site_id") && rows.count("""
                SELECT (SELECT count(*) FROM experiment.treatment_applications WHERE plot_id = ?)
                     + (SELECT count(*) FROM farm.crop_seasons WHERE plot_id = ?)
                """, change.id(), change.id()) > 0) {
            throw AppException.conflict("Không thể chuyển địa điểm cho ô đã có mùa vụ hoặc đã bón nghiệm thức");
        }
    }

    private void checkCropSeason(Change change) {
        LocalDate planted = change.date("planted_on");
        LocalDate harvested = change.date("harvested_on");
        if (harvested != null && harvested.isBefore(planted)) {
            throw Change.invalid("harvested_on", "Ngày thu hoạch không được trước ngày trồng");
        }
        // khóa ô để hai yêu cầu đồng thời không cùng tạo mùa vụ chồng lấn
        rows.first("SELECT plot_id FROM farm.plots WHERE plot_id = ? FOR UPDATE", change.uuid("plot_id"));
        if (rows.count("""
                SELECT count(*) FROM farm.crop_seasons
                 WHERE plot_id = ? AND crop_season_id IS DISTINCT FROM ?::uuid
                   AND daterange(planted_on, harvested_on, '[]') && daterange(?::date, ?::date, '[]')
                """, change.uuid("plot_id"), change.id(), planted, harvested) > 0) {
            throw AppException.conflict("Mùa vụ chồng lấn thời gian với mùa vụ khác trên cùng ô");
        }
        if (!change.creating() && (change.changed("crop_id") || change.changed("plot_id")) && rows.count("""
                SELECT (SELECT count(*) FROM experiment.plot_growth_stages WHERE crop_season_id = ?)
                     + (SELECT count(*) FROM experiment.treatment_applications WHERE crop_season_id = ?)
                """, change.id(), change.id()) > 0) {
            throw AppException.conflict("Không thể đổi ô hoặc cây trồng của mùa vụ đã có dữ liệu");
        }
    }
}
