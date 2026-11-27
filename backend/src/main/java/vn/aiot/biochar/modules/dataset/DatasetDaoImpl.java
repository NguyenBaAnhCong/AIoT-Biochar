package vn.aiot.biochar.modules.dataset;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.aiot.biochar.common.exception.AppException;

@Repository
public class DatasetDaoImpl implements DatasetDao {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    public DatasetDaoImpl(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions, JsonMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public List<Map<String, Object>> datasets() {
        return jdbc.queryForList("""
                SELECT d.dataset_id,d.dataset_code,d.name,d.purpose,d.site_id,s.site_code,d.experiment_id,
                       d.min_valid_observations,d.description,d.created_at,
                       latest.dataset_version_id,latest.version_no,latest.status,
                       COALESCE(readiness.records,0) AS record_count,COALESCE(readiness.valid_records,0) AS valid_count,
                       COALESCE(readiness.train,0) AS train_count,COALESCE(readiness.val,0) AS validation_count,
                       COALESCE(readiness.test,0) AS test_count,COALESCE(readiness.leaking_groups,0) AS leaking_groups
                FROM dataset.datasets d
                LEFT JOIN farm.sites s ON s.site_id=d.site_id
                LEFT JOIN LATERAL (
                    SELECT v.dataset_version_id,v.version_no,v.status FROM dataset.dataset_versions v
                    WHERE v.dataset_id=d.dataset_id ORDER BY v.version_no DESC LIMIT 1
                ) latest ON true
                LEFT JOIN dataset.v_version_readiness readiness USING (dataset_version_id)
                ORDER BY d.created_at DESC,d.dataset_code
                """, Map.of());
    }

    @Override
    public Map<String, Object> dataset(UUID id) {
        List<Map<String, Object>> result = jdbc.queryForList("""
                SELECT d.dataset_id,d.dataset_code,d.name,d.purpose,d.site_id,s.site_code,d.experiment_id,
                       e.experiment_code,d.owner_id,d.min_valid_observations,d.description,d.created_at
                FROM dataset.datasets d
                LEFT JOIN farm.sites s ON s.site_id=d.site_id
                LEFT JOIN experiment.experiments e ON e.experiment_id=d.experiment_id
                WHERE d.dataset_id=:id
                """, Map.of("id", id));
        if (result.isEmpty()) throw AppException.notFound("Không tìm thấy dataset.");
        return result.getFirst();
    }

    @Override
    public List<Map<String, Object>> versions(UUID datasetId) {
        if (jdbc.queryForObject("SELECT count(*) FROM dataset.datasets WHERE dataset_id=:id", Map.of("id", datasetId), Long.class) == 0) {
            throw AppException.notFound("Không tìm thấy dataset.");
        }
        return jdbc.queryForList("""
                SELECT v.dataset_version_id,v.dataset_id,v.version_no,v.status,v.time_from,v.time_to,
                       v.aggregation,v.split_strategy,v.split_ratio,v.random_seed,v.record_count,v.valid_count,
                       v.labeled_count,v.checksum,v.created_by,v.created_at,v.frozen_at,
                       r.records,r.valid_records,r.labeled_records,r.train,r.val,r.test,
                       r.min_valid_observations,r.meets_min_observations,r.leaking_groups
                FROM dataset.dataset_versions v
                LEFT JOIN dataset.v_version_readiness r USING (dataset_version_id)
                WHERE v.dataset_id=:id ORDER BY v.version_no DESC
                """, Map.of("id", datasetId));
    }

    @Override
    public UUID createDataset(DatasetService.CreateDatasetRequest request, UUID actorId) {
        return transactions.execute(status -> {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO dataset.datasets
                        (dataset_code,name,purpose,site_id,experiment_id,owner_id,min_valid_observations,description)
                    VALUES (:code,:name,:purpose,:site,:experiment,:owner,:minimum,:description)
                    RETURNING dataset_id
                    """, new MapSqlParameterSource().addValue("code", request.datasetCode().trim().toUpperCase())
                    .addValue("name", request.name().trim()).addValue("purpose", request.purpose())
                    .addValue("site", request.siteId()).addValue("experiment", request.experimentId())
                    .addValue("owner", actorId).addValue("minimum", request.minValidObservations())
                    .addValue("description", request.description()), UUID.class);
            dataAudit(id, "dataset.datasets", id.toString(), "CREATE", "Tạo dataset", null,
                    "{\"dataset_id\":\"" + id + "\"}", actorId);
            return id;
        });
    }

    @Override
    public UUID createVersion(UUID datasetId, DatasetService.CreateVersionRequest request, UUID actorId) {
        return transactions.execute(status -> {
            List<Map<String, Object>> found = jdbc.queryForList(
                    "SELECT dataset_id FROM dataset.datasets WHERE dataset_id=:id FOR UPDATE", Map.of("id", datasetId));
            if (found.isEmpty()) throw AppException.notFound("Không tìm thấy dataset.");
            Integer versionNo = jdbc.queryForObject(
                    "SELECT COALESCE(max(version_no),0)+1 FROM dataset.dataset_versions WHERE dataset_id=:id",
                    Map.of("id", datasetId), Integer.class);
            String ratio;
            try { ratio = json.writeValueAsString(request.splitRatio()); }
            catch (tools.jackson.core.JacksonException error) { throw new IllegalArgumentException("Tỷ lệ chia tập không hợp lệ", error); }
            UUID versionId = jdbc.queryForObject("""
                    INSERT INTO dataset.dataset_versions
                        (dataset_id,version_no,time_from,time_to,aggregation,split_strategy,split_ratio,random_seed,created_by,notes)
                    VALUES (:dataset,:version,:from,:to,:aggregation,:strategy,CAST(:ratio AS jsonb),:seed,:actor,:notes)
                    RETURNING dataset_version_id
                    """, new MapSqlParameterSource().addValue("dataset", datasetId).addValue("version", versionNo)
                    .addValue("from", Timestamp.from(request.timeFrom().toInstant()))
                    .addValue("to", Timestamp.from(request.timeTo().toInstant())).addValue("aggregation", request.aggregation())
                    .addValue("strategy", request.splitStrategy()).addValue("ratio", ratio).addValue("seed", request.randomSeed())
                    .addValue("actor", actorId).addValue("notes", request.notes()), UUID.class);
            dataAudit(datasetId, "dataset.dataset_versions", versionId.toString(), "CREATE", "Tạo dataset version " + versionNo,
                    null, "{\"status\":\"DRAFT\",\"version_no\":" + versionNo + "}", actorId);
            return versionId;
        });
    }

    @Override
    public Map<String, Object> version(UUID datasetId, UUID versionId) {
        List<Map<String, Object>> result = jdbc.queryForList("""
                SELECT d.dataset_id,d.dataset_code,d.name AS dataset_name,d.min_valid_observations,
                       v.dataset_version_id,v.version_no,v.status,v.time_from,v.time_to,v.aggregation,
                       v.split_strategy,v.split_ratio,v.random_seed,v.record_count,v.valid_count,v.labeled_count,
                       v.checksum,v.created_by,v.created_at,v.frozen_at,v.notes,
                       r.records,r.valid_records,r.labeled_records,r.train,r.val,r.test,r.meets_min_observations,r.leaking_groups
                FROM dataset.dataset_versions v JOIN dataset.datasets d USING (dataset_id)
                LEFT JOIN dataset.v_version_readiness r USING (dataset_version_id)
                WHERE d.dataset_id=:dataset AND v.dataset_version_id=:version
                """, Map.of("dataset", datasetId, "version", versionId));
        if (result.isEmpty()) throw AppException.notFound("Không tìm thấy dataset version.");
        return result.getFirst();
    }

    @Override
    public int build(UUID datasetId, UUID versionId, UUID actorId) {
        return transactions.execute(status -> {
            Map<String, Object> version = version(datasetId, versionId);
            if (!List.of("DRAFT", "BUILT").contains(version.get("status"))) throw AppException.conflict("Chỉ version DRAFT hoặc BUILT mới được build.");
            Integer count = jdbc.getJdbcTemplate().queryForObject("SELECT dataset.build_version(?)", Integer.class, versionId);
            jdbc.update("""
                    INSERT INTO data_quality.data_audit
                        (dataset_id,source_table,record_id,action,reason,old_value,new_value,actor_id,actor_type)
                    VALUES (:dataset,'dataset.dataset_versions',:record,'UPDATE','Build dataset version',
                            CAST(:old AS jsonb),CAST(:new AS jsonb),:actor,'USER')
                    """, new MapSqlParameterSource().addValue("actor", actorId).addValue("dataset", datasetId)
                    .addValue("record", versionId.toString()).addValue("old", "{\"status\":\"" + version.get("status") + "\"}")
                    .addValue("new", "{\"status\":\"BUILT\",\"record_count\":" + (count == null ? 0 : count) + "}"));
            return count == null ? 0 : count;
        });
    }

    @Override
    public String freeze(UUID datasetId, UUID versionId, UUID actorId) {
        return transactions.execute(status -> {
            Map<String, Object> version = version(datasetId, versionId);
            if (!"BUILT".equals(version.get("status"))) throw AppException.conflict("Chỉ version đã build mới được đóng băng.");
            Number leaking = (Number) version.get("leaking_groups");
            if (leaking != null && leaking.longValue() > 0) throw AppException.conflict("Dataset có nhóm bị leakage; chưa thể đóng băng.");
            if (!Boolean.TRUE.equals(version.get("meets_min_observations"))) {
                throw AppException.conflict("Dataset version chưa đủ số quan sát hợp lệ để đóng băng.");
            }
            String checksum = jdbc.getJdbcTemplate().queryForObject("SELECT dataset.freeze_version(?)", String.class, versionId);
            dataAudit(datasetId, "dataset.dataset_versions", versionId.toString(), "UPDATE", "Đóng băng dataset version",
                    "{\"status\":\"BUILT\"}", "{\"status\":\"FROZEN\",\"checksum\":\"" + checksum + "\"}", actorId);
            return checksum;
        });
    }

    @Override
    public List<Map<String, Object>> labels() {
        return jdbc.queryForList("SELECT label_id,label_set,label_code,name_vi,class_order,description FROM dataset.labels ORDER BY label_set,class_order", Map.of());
    }

    @Override
    public List<Map<String, Object>> labelDistribution(UUID versionId) {
        return jdbc.queryForList("""
                SELECT l.label_id,l.label_set,l.label_code,l.name_vi,l.class_order,
                       count(r.record_id) AS count,
                       CASE WHEN sum(count(r.record_id)) OVER ()>0
                            THEN round(count(r.record_id)*100.0/sum(count(r.record_id)) OVER (),2) ELSE 0 END AS percentage
                FROM dataset.labels l
                LEFT JOIN dataset.dataset_records r ON r.label_id=l.label_id AND r.dataset_version_id=:version
                GROUP BY l.label_id,l.label_set,l.label_code,l.name_vi,l.class_order
                ORDER BY l.label_set,l.class_order
                """, Map.of("version", versionId));
    }

    @Override
    public Map<String, Object> records(UUID versionId, String split, UUID labelId, int page, int limit) {
        StringBuilder where = new StringBuilder(" WHERE r.dataset_version_id=:version");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("version", versionId);
        if (split != null) { where.append(" AND r.split=:split"); params.addValue("split", split); }
        if (labelId != null) { where.append(" AND r.label_id=:label"); params.addValue("label", labelId); }
        Long total = jdbc.queryForObject("SELECT count(*) FROM dataset.dataset_records r" + where, params, Long.class);
        MapSqlParameterSource pageParams = new MapSqlParameterSource(params.getValues())
                .addValue("limit", limit).addValue("offset", (long) (page - 1) * limit);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.record_id,r.observed_at,r.site_id,r.plot_id,p.plot_code,r.treatment_id,t.treatment_code,
                       r.ph,r.ec,r.soil_moisture,r.soil_temperature,r.n,r.p,r.k,r.sensor_payload,r.lab_payload,
                       r.label_id,l.label_code,l.name_vi AS label_name,r.label_source,r.split,r.group_key,
                       r.valid_flag,r.invalid_reason
                FROM dataset.dataset_records r
                LEFT JOIN farm.plots p USING (plot_id)
                LEFT JOIN experiment.treatments t USING (treatment_id)
                LEFT JOIN dataset.labels l USING (label_id)
                """ + where + " ORDER BY r.observed_at,r.record_id LIMIT :limit OFFSET :offset", pageParams);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", rows);
        result.put("meta", Map.of("page", page, "limit", limit, "total", total == null ? 0 : total));
        return result;
    }

    @Override
    public void assignLabel(UUID versionId, UUID recordId, UUID labelId, UUID actorId, String reason) {
        transactions.execute(status -> {
            Map<String, Object> version = versionById(versionId);
            if (!"BUILT".equals(version.get("status"))) throw AppException.conflict("Chỉ version BUILT mới được gán nhãn thủ công.");
            List<Map<String, Object>> old = jdbc.queryForList("""
                    SELECT jsonb_build_object('label_id',label_id,'label_source',label_source)::text AS value
                    FROM dataset.dataset_records WHERE dataset_version_id=:version AND record_id=:record FOR UPDATE
                    """, Map.of("version", versionId, "record", recordId));
            if (old.isEmpty()) throw AppException.notFound("Không tìm thấy observation trong version.");
            int updated = jdbc.update("""
                    UPDATE dataset.dataset_records SET label_id=:label,label_source='EXPERT'
                    WHERE dataset_version_id=:version AND record_id=:record
                    """, new MapSqlParameterSource().addValue("label", labelId)
                    .addValue("version", versionId).addValue("record", recordId));
            if (updated == 0) throw AppException.notFound("Không tìm thấy observation trong version.");
            String oldJson = String.valueOf(old.getFirst().get("value"));
            dataAudit((UUID) version.get("dataset_id"), "dataset.dataset_records", recordId.toString(), "UPDATE", reason,
                    oldJson, "{\"label_id\":\"" + labelId + "\",\"label_source\":\"EXPERT\"}", actorId);
            jdbc.update("UPDATE dataset.dataset_versions SET labeled_count=(SELECT count(*) FROM dataset.dataset_records WHERE dataset_version_id=:version AND label_id IS NOT NULL) WHERE dataset_version_id=:version",
                    Map.of("version", versionId));
            return null;
        });
    }

    @Override
    public List<Map<String, Object>> exportRows(UUID versionId) {
        Map<String, Object> version = versionById(versionId);
        if (!"FROZEN".equals(version.get("status"))) throw AppException.conflict("Chỉ dataset version FROZEN mới được export.");
        return jdbc.queryForList("""
                SELECT r.record_id,r.observed_at,r.site_id,p.plot_code,r.treatment_id,t.treatment_code,
                       r.soil_type,r.biochar_dose_kg_m2,r.ph,r.ec,r.soil_moisture,r.soil_temperature,r.n,r.p,r.k,
                       r.sensor_payload::text AS sensor_payload,r.lab_payload::text AS lab_payload,
                       l.label_code,r.label_source,r.split,r.group_key,r.valid_flag
                FROM dataset.dataset_records r
                LEFT JOIN farm.plots p USING (plot_id)
                LEFT JOIN experiment.treatments t USING (treatment_id)
                LEFT JOIN dataset.labels l USING (label_id)
                WHERE r.dataset_version_id=:version AND r.valid_flag=true
                ORDER BY r.observed_at,r.record_id
                """, Map.of("version", versionId));
    }

    private Map<String, Object> versionById(UUID versionId) {
        List<Map<String, Object>> values = jdbc.queryForList("""
                SELECT dataset_id,status FROM dataset.dataset_versions WHERE dataset_version_id=:id
                """, Map.of("id", versionId));
        if (values.isEmpty()) throw AppException.notFound("Không tìm thấy dataset version.");
        return values.getFirst();
    }

    private void dataAudit(UUID datasetId, String sourceTable, String recordId, String action, String reason,
                           String oldValue, String newValue, UUID actorId) {
        jdbc.update("""
                INSERT INTO data_quality.data_audit
                    (dataset_id,source_table,record_id,action,reason,old_value,new_value,actor_id,actor_type)
                VALUES (:dataset,:source,:record,:action,:reason,
                        CAST(:old AS jsonb),CAST(:new AS jsonb),:actor,'USER')
                """, new MapSqlParameterSource().addValue("dataset", datasetId).addValue("source", sourceTable).addValue("record", recordId)
                .addValue("action", action).addValue("reason", reason).addValue("old", oldValue)
                .addValue("new", newValue).addValue("actor", actorId));
    }
}
