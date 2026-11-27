package vn.aiot.biochar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import jakarta.servlet.Filter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.aiot.biochar.modules.auth.JwtService;

/**
 * Test tích hợp TV3. Cần PostgreSQL đã nạp database/init và dữ liệu demo; chạy khi đặt biến
 * TV3_DB_TEST=true. Mỗi test nằm trong một transaction và được rollback nên không để lại dữ liệu.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "TV3_DB_TEST", matches = "true")
class Tv3IntegrationTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper json;

    private MockMvc mvc;
    private String admin;
    private String labTech;
    private String researcher;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        admin = token("admin");
        labTech = token("labtech01");
        researcher = token("researcher01");
    }

    @Test
    void readsDemoDataWithContext() throws Exception {
        JsonNode plots = ok(admin, "/plots");
        assertTrue(plots.get("meta").get("total").asInt() >= 3);
        assertTrue(plots.get("data").get(0).hasNonNull("current_treatment_code"));

        JsonNode batch = ok(admin, "/biochar/batches?q=BC-2026-01").get("data").get(0);
        assertTrue(batch.get("produced_on").asString().matches("\\d{4}-\\d{2}-\\d{2}"));
        assertTrue(batch.get("heavy_metals_mg_kg").get("Pb").isNumber());
        JsonNode trace = ok(admin, "/biochar/batches/" + batch.get("biochar_batch_id").asString() + "/trace");
        assertTrue(trace.get("data").get("feedstock_batch").hasNonNull("batch_code"));

        assertTrue(ok(admin, "/sensor-vs-lab?parameter_code=ph").get("meta").get("total").asInt() > 0);
        for (String path : new String[] {"/sites", "/crops", "/crop-seasons", "/experiments", "/treatments",
                "/treatment-applications", "/growth-stages", "/plot-growth-stages", "/samples",
                "/lab-results", "/lab-methods", "/biochar/feedstock-batches", "/biochar/enrichment-batches",
                "/biochar/enrichment-results"}) {
            ok(admin, path);
        }
    }

    @Test
    void enforcesAuthenticationPermissionsAndInputRules() throws Exception {
        assertEquals(401, send(null, HttpMethod.GET, "/plots", null).getStatus());
        assertEquals(403, send(researcher, HttpMethod.POST, "/sites",
                Map.of("site_code", "T3IT", "name", "x")).getStatus());

        JsonNode error = fail(400, admin, HttpMethod.POST, "/plots",
                Map.of("plot_code", "A 1", "area_m2", -1, "hacker", 1));
        String details = error.get("details").toString();
        for (String field : new String[] {"plot_code", "area_m2", "hacker", "site_id", "soil_type"}) {
            assertTrue(details.contains("\"" + field + "\""), field);
        }
        fail(400, admin, HttpMethod.POST, "/plots", Map.of(
                "site_id", UUID.randomUUID(), "plot_code", "T3IT", "area_m2", 10, "soil_type", "Feralit"));
        fail(400, admin, HttpMethod.GET, "/plots?limit=9999", null);
        fail(404, admin, HttpMethod.GET, "/plots/" + UUID.randomUUID(), null);
    }

    @Test
    void protectsFarmAndExperimentRules() throws Exception {
        String site = create(admin, "/sites", Map.of("site_code", "T3IT-S", "name", "Khu test"), "site_id");
        String plot = create(admin, "/plots", Map.of("site_id", site, "plot_code", "P1", "area_m2", 20,
                "soil_type", "Feralit"), "plot_id");
        String crop = create(admin, "/crops", Map.of("crop_code", "T3IT-C", "name_vi", "Cây test"), "crop_id");
        String season = create(admin, "/crop-seasons", Map.of("plot_id", plot, "crop_id", crop,
                "season_code", "V1", "planted_on", "2026-01-10", "harvested_on", "2026-05-01"), "crop_season_id");
        fail(409, admin, HttpMethod.POST, "/crop-seasons", Map.of("plot_id", plot, "crop_id", crop,
                "season_code", "V2", "planted_on", "2026-04-01"));

        String stage = create(admin, "/growth-stages", Map.of("crop_id", crop, "stage_code", "VEG",
                "name_vi", "Sinh trưởng", "stage_order", 1), "growth_stage_id");
        fail(400, admin, HttpMethod.POST, "/plot-growth-stages", Map.of("crop_season_id", season,
                "growth_stage_id", stage, "started_at", "2025-12-01T00:00:00+07:00"));
        create(admin, "/plot-growth-stages", Map.of("crop_season_id", season, "growth_stage_id", stage,
                "started_at", "2026-02-01T00:00:00+07:00"), "plot_growth_stage_id");

        String experiment = create(researcher, "/experiments", Map.of("site_id", site,
                "experiment_code", "T3IT-E", "title", "Thí nghiệm test", "started_on", "2026-01-01"),
                "experiment_id");
        fail(409, admin, HttpMethod.PATCH, "/experiments/" + experiment,
                Map.of("status", "COMPLETED", "ended_on", "2026-06-01"));
        fail(400, admin, HttpMethod.POST, "/treatments", Map.of("experiment_id", experiment,
                "treatment_code", "T3IT-CT", "name", "ĐC", "control_flag", true, "biochar_dose_kg_m2", 1));
        String control = create(admin, "/treatments", Map.of("experiment_id", experiment,
                "treatment_code", "T3IT-CT", "name", "ĐC", "control_flag", true), "treatment_id");

        String otherPlot = jdbc.queryForObject(
                "SELECT plot_id::text FROM farm.plots WHERE site_id <> ?::uuid LIMIT 1", String.class, site);
        fail(400, admin, HttpMethod.POST, "/treatment-applications",
                Map.of("treatment_id", control, "plot_id", otherPlot, "applied_on", "2026-01-15"));
        String application = create(admin, "/treatment-applications", Map.of("treatment_id", control,
                "plot_id", plot, "crop_season_id", season, "applied_on", "2026-01-15"), "application_id");
        fail(409, admin, HttpMethod.POST, "/treatment-applications",
                Map.of("treatment_id", control, "plot_id", plot, "applied_on", "2026-02-15"));
        // kết thúc lần gán cũ rồi mới gán được lần mới
        assertEquals(200, send(admin, HttpMethod.PATCH, "/treatment-applications/" + application,
                Map.of("effective_to", "2026-02-15T00:00:00+07:00")).getStatus());
        create(admin, "/treatment-applications",
                Map.of("treatment_id", control, "plot_id", plot, "applied_on", "2026-02-15"), "application_id");

        String pendingBatch = newBiocharBatch("T3IT-P");
        String treated = create(admin, "/treatments", Map.of("experiment_id", experiment,
                "treatment_code", "T3IT-B", "name", "Biochar", "biochar_dose_kg_m2", 1.5,
                "biochar_batch_id", pendingBatch), "treatment_id");
        String plot2 = create(admin, "/plots", Map.of("site_id", site, "plot_code", "P2", "area_m2", 20,
                "soil_type", "Feralit"), "plot_id");
        JsonNode unsafe = fail(409, admin, HttpMethod.POST, "/treatment-applications",
                Map.of("treatment_id", treated, "plot_id", plot2, "applied_on", "2026-02-15"));
        assertEquals("BIOCHAR_NOT_SAFE", unsafe.get("code").asString());
    }

    @Test
    void protectsBiocharMassDateAndSafetyRules() throws Exception {
        String feedstock = create(admin, "/biochar/feedstock-batches", Map.of("batch_code", "T3IT-FS",
                "collected_on", "2026-01-05", "mass_kg", 100), "feedstock_batch_id");
        Map<String, Object> base = Map.of("batch_code", "T3IT-BC", "feedstock_batch_id", feedstock,
                "produced_on", "2026-01-10", "input_mass_kg", 60, "output_mass_kg", 18);
        fail(400, admin, HttpMethod.POST, "/biochar/batches", with(base, "produced_on", "2026-01-01"));
        fail(400, admin, HttpMethod.POST, "/biochar/batches", with(base, "output_mass_kg", 70));
        fail(400, admin, HttpMethod.POST, "/biochar/batches", with(base, "safety_status", "PASSED"));
        fail(400, admin, HttpMethod.POST, "/biochar/batches",
                with(base, "heavy_metals_mg_kg", Map.of("Pb", -1)));
        JsonNode created = data(send(admin, HttpMethod.POST, "/biochar/batches", base), 201);
        assertEquals(30.0, created.get("yield_pct").asDouble(), 0.001);
        String batch = created.get("biochar_batch_id").asString();

        fail(400, admin, HttpMethod.POST, "/biochar/batches",
                with(with(base, "batch_code", "T3IT-BC2"), "input_mass_kg", 41));
        fail(400, admin, HttpMethod.PATCH, "/biochar/feedstock-batches/" + feedstock, Map.of("mass_kg", 50));

        String path = "/biochar/batches/" + batch;
        JsonNode passed = data(send(admin, HttpMethod.PATCH, path,
                Map.of("heavy_metals_mg_kg", Map.of("Pb", 2.1), "safety_status", "PASSED")), 200);
        assertEquals("PASSED", passed.get("safety_status").asString());
        JsonNode same = data(send(admin, HttpMethod.PATCH, path,
                Map.of("heavy_metals_mg_kg", Map.of("Pb", 2.1))), 200);
        assertEquals("PASSED", same.get("safety_status").asString());
        JsonNode changed = data(send(admin, HttpMethod.PATCH, path,
                Map.of("heavy_metals_mg_kg", Map.of("Pb", 9))), 200);
        assertEquals("PENDING", changed.get("safety_status").asString());

        data(send(admin, HttpMethod.PATCH, path, Map.of("safety_status", "FAILED")), 200);
        fail(400, admin, HttpMethod.POST, "/biochar/enrichment-batches", Map.of("batch_code", "T3IT-EN",
                "biochar_batch_id", batch, "enrichment_type", "MICROBIAL", "started_on", "2026-01-12"));
    }

    @Test
    void runsLabReviewWorkflowWithFourEyes() throws Exception {
        String sample = newSample("T3IT-M1");
        Map<String, Object> base = Map.of("sample_id", sample, "parameter_code", "PH", "value", 5.8);
        fail(400, labTech, HttpMethod.POST, "/lab-results", with(base, "unit", "mg/kg"));
        fail(400, labTech, HttpMethod.POST, "/lab-results", with(base, "value", 20));
        fail(400, labTech, HttpMethod.POST, "/lab-results", with(base, "result_status", "APPROVED"));
        fail(400, labTech, HttpMethod.POST, "/lab-results", with(base, "analyst_id", UUID.randomUUID()));
        assertEquals(403, send(researcher, HttpMethod.POST, "/lab-results", base).getStatus());

        JsonNode created = data(send(labTech, HttpMethod.POST, "/lab-results", base), 201);
        assertEquals("DRAFT", created.get("result_status").asString());
        assertEquals("pH", created.get("unit").asString());
        String id = created.get("lab_result_id").asString();
        String path = "/lab-results/" + id;

        assertEquals("INVALID_TRANSITION",
                fail(409, admin, HttpMethod.POST, path + "/approve", null).get("code").asString());
        assertEquals("VERIFIED",
                data(send(labTech, HttpMethod.POST, path + "/verify", null), 200).get("result_status").asString());
        assertEquals("RESULT_LOCKED",
                fail(409, labTech, HttpMethod.PATCH, path, Map.of("value", 6)).get("code").asString());
        assertEquals("FOUR_EYES",
                fail(409, labTech, HttpMethod.POST, path + "/approve", null).get("code").asString());
        JsonNode approved = data(send(admin, HttpMethod.POST, path + "/approve", null), 200);
        assertEquals("APPROVED", approved.get("result_status").asString());
        assertTrue(approved.hasNonNull("approved_by_name"));
        fail(409, admin, HttpMethod.DELETE, path, null);
        fail(409, admin, HttpMethod.POST, path + "/reject", null);
        fail(409, admin, HttpMethod.DELETE, "/samples/" + sample, null);
        fail(409, admin, HttpMethod.PATCH, "/samples/" + sample, Map.of("status", "REJECTED"));

        // sửa kết quả bị từ chối đưa nó về DRAFT và người sửa trở thành người phân tích
        String second = create(labTech, "/lab-results", with(base, "replicate_no", 2), "lab_result_id");
        data(send(admin, HttpMethod.POST, "/lab-results/bulk/reject", Map.of("ids", new String[] {second})), 200);
        JsonNode edited = data(send(admin, HttpMethod.PATCH, "/lab-results/" + second, Map.of("value", 6)), 200);
        assertEquals("DRAFT", edited.get("result_status").asString());
        data(send(admin, HttpMethod.POST, "/lab-results/" + second + "/verify", null), 200);
        assertEquals("FOUR_EYES", fail(409, admin, HttpMethod.POST, "/lab-results/" + second + "/approve", null)
                .get("code").asString());

        Integer audited = jdbc.queryForObject("""
                SELECT count(*) FROM audit.audit_logs
                 WHERE table_name = 'lab_results' AND record_pk->>'lab_result_id' = ? AND actor_id IS NOT NULL
                """, Integer.class, id);
        assertTrue(audited != null && audited >= 3);
    }

    @Test
    void importsExcelOnlyWhenEveryRowIsValid() throws Exception {
        newSample("T3IT-M2");
        MockHttpServletResponse template = send(labTech, HttpMethod.GET, "/lab-results/import-template", null);
        assertEquals(200, template.getStatus());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(template.getContentAsByteArray()))) {
            assertEquals("sample_code", workbook.getSheet("KetQua").getRow(0).getCell(0).getStringCellValue());
        }

        byte[] mixed = fill(template.getContentAsByteArray(), new Object[][] {
                {"T3IT-M2", "ph", "5,9"},
                {"T3IT-M2", "PH", 6.1},
                {"KHONG-CO", "PH", 6},
                {"T3IT-M2", "EC", "abc"},
                {"T3IT-M2", "N", null}});
        JsonNode report = upload(mixed, false);
        assertEquals(5, report.get("total_rows").asInt());
        assertEquals(1, report.get("valid_rows").asInt());
        assertEquals(0, report.get("inserted").asInt());
        String errors = report.get("errors").toString();
        for (String expected : new String[] {"\"row\":3", "\"row\":4", "\"row\":5", "\"row\":6"}) {
            assertTrue(errors.contains(expected), expected + " in " + errors);
        }
        assertFalse(errors.contains("\"row\":2,"), errors);
        assertEquals(0, countResults("T3IT-M2"));

        byte[] valid = fill(template.getContentAsByteArray(), new Object[][] {
                {"T3IT-M2", "PH", "5,9"}, {"T3IT-M2", "EC", 0.42}});
        assertEquals(0, upload(valid, true).get("inserted").asInt());
        assertEquals(0, countResults("T3IT-M2"));
        assertEquals(2, upload(valid, false).get("inserted").asInt());
        assertEquals(2, countResults("T3IT-M2"));
        assertEquals(2, upload(valid, false).get("error_rows").asInt());

        var bad = new MockMultipartFile("file", "a.xlsx", "application/octet-stream", "not excel".getBytes());
        assertEquals(400, mvc.perform(multipart("/api/v1/lab-results/import").file(bad)
                .header("Authorization", "Bearer " + labTech)).andReturn().getResponse().getStatus());
    }

    private int countResults(String sampleCode) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM laboratory.lab_results r
                  JOIN laboratory.soil_samples s ON s.sample_id = r.sample_id WHERE s.sample_code = ?
                """, Integer.class, sampleCode);
        return count == null ? 0 : count;
    }

    private byte[] fill(byte[] template, Object[][] data) throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(template));
                var output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheet("KetQua");
            for (int index = 0; index < data.length; index++) {
                Row row = sheet.createRow(index + 1);
                for (int column = 0; column < data[index].length; column++) {
                    Object value = data[index][column];
                    if (value instanceof Number number) {
                        row.createCell(column).setCellValue(number.doubleValue());
                    } else if (value != null) {
                        row.createCell(column).setCellValue(value.toString());
                    }
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private JsonNode upload(byte[] content, boolean dryRun) throws Exception {
        var file = new MockMultipartFile("file", "ket-qua.xlsx", "application/octet-stream", content);
        MockHttpServletResponse response = mvc.perform(multipart("/api/v1/lab-results/import")
                        .file(file)
                        .param("dry_run", String.valueOf(dryRun))
                        .header("Authorization", "Bearer " + labTech))
                .andReturn().getResponse();
        return data(response, 200);
    }

    private String newSample(String code) throws Exception {
        String plot = jdbc.queryForObject("SELECT plot_id::text FROM farm.plots LIMIT 1", String.class);
        return create(labTech, "/samples", Map.of("sample_code", code, "plot_id", plot,
                "sampled_at", "2026-03-01T08:00:00+07:00"), "sample_id");
    }

    private String newBiocharBatch(String code) throws Exception {
        String feedstock = create(admin, "/biochar/feedstock-batches", Map.of("batch_code", code + "-FS",
                "collected_on", "2026-01-05", "mass_kg", 100), "feedstock_batch_id");
        return create(admin, "/biochar/batches", Map.of("batch_code", code, "feedstock_batch_id", feedstock,
                "produced_on", "2026-01-10"), "biochar_batch_id");
    }

    private String token(String username) {
        return jwtService.createAccessToken(jdbc.queryForObject(
                "SELECT user_id FROM iam.users WHERE username = ?", UUID.class, username));
    }

    private Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        var copy = new java.util.LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    private MockHttpServletResponse send(String token, HttpMethod method, String path, Object body)
            throws Exception {
        var builder = request(method, java.net.URI.create("/api/v1" + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MockHttpServletResponse response = mvc.perform(builder).andReturn().getResponse();
        response.setCharacterEncoding("UTF-8");
        return response;
    }

    private JsonNode data(MockHttpServletResponse response, int status) throws Exception {
        response.setCharacterEncoding("UTF-8");
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return json.readTree(response.getContentAsString()).get("data");
    }

    private JsonNode ok(String token, String path) throws Exception {
        MockHttpServletResponse response = send(token, HttpMethod.GET, path, null);
        assertEquals(200, response.getStatus(), path + " " + response.getContentAsString());
        return json.readTree(response.getContentAsString());
    }

    private JsonNode fail(int status, String token, HttpMethod method, String path, Object body) throws Exception {
        MockHttpServletResponse response = send(token, method, path, body);
        assertEquals(status, response.getStatus(), method + " " + path + " " + response.getContentAsString());
        return json.readTree(response.getContentAsString()).get("error");
    }

    private String create(String token, String path, Object body, String idColumn) throws Exception {
        return data(send(token, HttpMethod.POST, path, body), 201).get(idColumn).asString();
    }
}
