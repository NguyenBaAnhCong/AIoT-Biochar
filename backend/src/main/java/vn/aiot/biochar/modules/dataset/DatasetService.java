package vn.aiot.biochar.modules.dataset;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.aiot.biochar.common.db.UserTransactionService;
import vn.aiot.biochar.common.exception.AppException;

@Service
public class DatasetService implements IDatasetService {

    private static final List<String> PURPOSES = List.of("REFERENCE", "TRAINING", "EVALUATION", "FIELD_TEST");
    private static final List<String> AGGREGATIONS = List.of("RAW", "HOURLY", "DAILY");
    private static final List<String> SPLIT_STRATEGIES = List.of("RANDOM", "BY_PLOT", "BY_TIME", "BY_BLOCK");
    private static final List<String> SPLITS = List.of("TRAIN", "VAL", "TEST");

    private final DatasetDao dao;
    private final UserTransactionService userTransactions;

    public DatasetService(DatasetDao dao, UserTransactionService userTransactions) {
        this.dao = dao;
        this.userTransactions = userTransactions;
    }

    @Override
    public Object datasets() {
        return dao.datasets();
    }

    @Override
    public Map<String, Object> dataset(UUID id) {
        return Map.of("dataset", dao.dataset(id), "versions", dao.versions(id));
    }

    @Override
    public UUID createDataset(CreateDatasetRequest request, UUID actorId) {
        if (request == null || request.datasetCode() == null || !request.datasetCode().matches("[A-Za-z0-9_-]{1,32}")) {
            throw AppException.badRequest("Mã dataset chỉ gồm chữ, số, dấu gạch ngang hoặc gạch dưới; tối đa 32 ký tự.");
        }
        if (request.name() == null || request.name().isBlank() || request.name().length() > 255) {
            throw AppException.badRequest("Tên dataset bắt buộc và tối đa 255 ký tự.");
        }
        if (!PURPOSES.contains(request.purpose())) throw AppException.badRequest("Mục đích dataset không hợp lệ.");
        int minimum = request.minValidObservations() == null ? 10000 : request.minValidObservations();
        if (minimum < 1 || minimum > 100000000) throw AppException.badRequest("Số quan sát hợp lệ tối thiểu không hợp lệ.");
        CreateDatasetRequest normalized = new CreateDatasetRequest(request.datasetCode(), request.name(), request.purpose(),
                request.siteId(), request.experimentId(), minimum, request.description());
        return userTransactions.execute(actorId, "Tạo dataset", () -> dao.createDataset(normalized, actorId));
    }

    @Override
    public Object versions(UUID datasetId) {
        return dao.versions(datasetId);
    }

    @Override
    public UUID createVersion(UUID datasetId, CreateVersionRequest request, UUID actorId) {
        if (request == null || request.timeFrom() == null || request.timeTo() == null || !request.timeFrom().isBefore(request.timeTo())) {
            throw AppException.badRequest("Khoảng thời gian dataset version chưa hợp lệ.");
        }
        if (!AGGREGATIONS.contains(request.aggregation()) || !SPLIT_STRATEGIES.contains(request.splitStrategy())) {
            throw AppException.badRequest("Kiểu tổng hợp hoặc chiến lược chia tập không hợp lệ.");
        }
        Map<String, Double> ratio = request.splitRatio() == null
                ? Map.of("TRAIN", 0.7, "VAL", 0.15, "TEST", 0.15) : request.splitRatio();
        double train = ratio.getOrDefault("TRAIN", 0.0);
        double validation = ratio.getOrDefault("VAL", 0.0);
        double test = ratio.getOrDefault("TEST", 0.0);
        if (ratio.size() != 3 || ratio.keySet().stream().anyMatch(key -> !SPLITS.contains(key))
                || !Double.isFinite(train) || !Double.isFinite(validation) || !Double.isFinite(test)
                || train < 0 || validation < 0 || test < 0 || Math.abs(train + validation + test - 1.0) > 0.001) {
            throw AppException.badRequest("Tỷ lệ TRAIN, VAL, TEST phải không âm và tổng bằng 1.");
        }
        CreateVersionRequest normalized = new CreateVersionRequest(request.timeFrom(), request.timeTo(),
                request.aggregation(), request.splitStrategy(), ratio,
                request.randomSeed() == null ? 42 : request.randomSeed(), request.notes());
        return userTransactions.execute(actorId, "Tạo dataset version", () -> dao.createVersion(datasetId, normalized, actorId));
    }

    @Override
    public Map<String, Object> version(UUID datasetId, UUID versionId) {
        Map<String, Object> value = dao.version(datasetId, versionId);
        value.put("label_distribution", dao.labelDistribution(versionId));
        return value;
    }

    @Override
    public Map<String, Object> build(UUID datasetId, UUID versionId, UUID actorId) {
        return userTransactions.execute(actorId, "Build dataset version", () -> {
            int built = dao.build(datasetId, versionId, actorId);
            return Map.of("built_records", built, "version", dao.version(datasetId, versionId));
        });
    }

    @Override
    public Map<String, Object> freeze(UUID datasetId, UUID versionId, UUID actorId) {
        return userTransactions.execute(actorId, "Freeze dataset version", () -> {
            String checksum = dao.freeze(datasetId, versionId, actorId);
            return Map.of("checksum", checksum, "version", dao.version(datasetId, versionId));
        });
    }

    @Override
    public Map<String, Object> records(UUID datasetId, UUID versionId, String split, UUID labelId, int page, int limit) {
        validatePaging(page, limit);
        if (split != null && !SPLITS.contains(split)) throw AppException.badRequest("Split không hợp lệ.");
        dao.version(datasetId, versionId);
        return dao.records(versionId, split, labelId, page, limit);
    }

    @Override
    public Map<String, Object> labels(UUID versionId) {
        return Map.of("labels", dao.labels(), "distribution", dao.labelDistribution(versionId));
    }

    @Override
    public void assignLabel(UUID datasetId, UUID versionId, UUID recordId, UUID labelId, String reason, UUID actorId) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) throw AppException.badRequest("Cần ghi lý do gán nhãn thủ công.");
        dao.version(datasetId, versionId);
        userTransactions.execute(actorId, reason, () -> {
            dao.assignLabel(versionId, recordId, labelId, actorId, reason.trim());
            return null;
        });
    }

    @Override
    public ExportData exportCsv(UUID datasetId, UUID versionId) {
        dao.version(datasetId, versionId);
        List<Map<String, Object>> rows = dao.exportRows(versionId);
        return new ExportData("dataset-version-" + versionId + ".csv", rows);
    }

    private void validatePaging(int page, int limit) {
        if (page < 1 || limit < 1 || limit > 200) throw AppException.badRequest("page phải ≥ 1 và limit trong khoảng 1–200.");
    }

    public record CreateDatasetRequest(String datasetCode, String name, String purpose, UUID siteId,
                                       UUID experimentId, Integer minValidObservations, String description) { }

    public record CreateVersionRequest(OffsetDateTime timeFrom, OffsetDateTime timeTo, String aggregation,
                                       String splitStrategy, Map<String, Double> splitRatio,
                                       Integer randomSeed, String notes) { }

    public record ExportData(String fileName, List<Map<String, Object>> rows) { }
}
