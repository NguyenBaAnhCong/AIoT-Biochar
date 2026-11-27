package vn.aiot.biochar.modules.dataset;

import java.util.Map;
import java.util.UUID;

public interface IDatasetService {
    Object datasets();
    Map<String, Object> dataset(UUID id);
    UUID createDataset(DatasetService.CreateDatasetRequest request, UUID actorId);
    Object versions(UUID datasetId);
    UUID createVersion(UUID datasetId, DatasetService.CreateVersionRequest request, UUID actorId);
    Map<String, Object> version(UUID datasetId, UUID versionId);
    Map<String, Object> build(UUID datasetId, UUID versionId, UUID actorId);
    Map<String, Object> freeze(UUID datasetId, UUID versionId, UUID actorId);
    Map<String, Object> records(UUID datasetId, UUID versionId, String split, UUID labelId, int page, int limit);
    Map<String, Object> labels(UUID versionId);
    void assignLabel(UUID datasetId, UUID versionId, UUID recordId, UUID labelId, String reason, UUID actorId);
    DatasetService.ExportData exportCsv(UUID datasetId, UUID versionId);
}
