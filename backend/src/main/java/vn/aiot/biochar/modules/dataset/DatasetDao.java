package vn.aiot.biochar.modules.dataset;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DatasetDao {
    List<Map<String, Object>> datasets();
    Map<String, Object> dataset(UUID id);
    List<Map<String, Object>> versions(UUID datasetId);
    UUID createDataset(DatasetService.CreateDatasetRequest request, UUID actorId);
    UUID createVersion(UUID datasetId, DatasetService.CreateVersionRequest request, UUID actorId);
    Map<String, Object> version(UUID datasetId, UUID versionId);
    int build(UUID datasetId, UUID versionId, UUID actorId);
    String freeze(UUID datasetId, UUID versionId, UUID actorId);
    List<Map<String, Object>> labels();
    List<Map<String, Object>> labelDistribution(UUID versionId);
    Map<String, Object> records(UUID versionId, String split, UUID labelId, int page, int limit);
    void assignLabel(UUID versionId, UUID recordId, UUID labelId, UUID actorId, String reason);
    List<Map<String, Object>> exportRows(UUID versionId);
}
