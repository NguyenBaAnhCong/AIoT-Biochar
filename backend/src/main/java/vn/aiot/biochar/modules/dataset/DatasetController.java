package vn.aiot.biochar.modules.dataset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vn.aiot.biochar.common.api.ApiResponse;
import vn.aiot.biochar.common.exception.AppException;
import vn.aiot.biochar.modules.auth.AppUser;

@RestController
@RequestMapping("/api/v1/tv4/datasets")
public class DatasetController {

    private static final List<String> CSV_COLUMNS = List.of(
            "record_id", "observed_at", "site_id", "plot_code", "treatment_id", "treatment_code",
            "soil_type", "biochar_dose_kg_m2", "ph", "ec", "soil_moisture", "soil_temperature",
            "n", "p", "k", "sensor_payload", "lab_payload", "label_code", "label_source", "split", "group_key", "valid_flag");

    private final IDatasetService service;

    public DatasetController(IDatasetService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('dataset.read')")
    public ApiResponse<Object> datasets() {
        return ApiResponse.of(service.datasets());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('dataset.write')")
    public ApiResponse<Map<String, UUID>> create(@AuthenticationPrincipal AppUser user,
                                                   @RequestBody DatasetService.CreateDatasetRequest request) {
        return ApiResponse.of(Map.of("dataset_id", service.createDataset(request, user.id())));
    }

    @GetMapping("/{datasetId}")
    @PreAuthorize("hasAuthority('dataset.read')")
    public ApiResponse<Map<String, Object>> dataset(@PathVariable UUID datasetId) {
        return ApiResponse.of(service.dataset(datasetId));
    }

    @GetMapping("/{datasetId}/versions")
    @PreAuthorize("hasAuthority('dataset.read')")
    public ApiResponse<Object> versions(@PathVariable UUID datasetId) {
        return ApiResponse.of(service.versions(datasetId));
    }

    @PostMapping("/{datasetId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('dataset.write')")
    public ApiResponse<Map<String, UUID>> createVersion(@AuthenticationPrincipal AppUser user,
            @PathVariable UUID datasetId, @RequestBody DatasetService.CreateVersionRequest request) {
        return ApiResponse.of(Map.of("dataset_version_id", service.createVersion(datasetId, request, user.id())));
    }

    @GetMapping("/{datasetId}/versions/{versionId}")
    @PreAuthorize("hasAuthority('dataset.read')")
    public ApiResponse<Map<String, Object>> version(@PathVariable UUID datasetId, @PathVariable UUID versionId) {
        return ApiResponse.of(service.version(datasetId, versionId));
    }

    @PostMapping("/{datasetId}/versions/{versionId}/build")
    @PreAuthorize("hasAuthority('dataset.write')")
    public ApiResponse<Map<String, Object>> build(@AuthenticationPrincipal AppUser user,
            @PathVariable UUID datasetId, @PathVariable UUID versionId) {
        return ApiResponse.of(service.build(datasetId, versionId, user.id()));
    }

    @PostMapping("/{datasetId}/versions/{versionId}/freeze")
    @PreAuthorize("hasAuthority('dataset.write')")
    public ApiResponse<Map<String, Object>> freeze(@AuthenticationPrincipal AppUser user,
            @PathVariable UUID datasetId, @PathVariable UUID versionId) {
        return ApiResponse.of(service.freeze(datasetId, versionId, user.id()));
    }

    @GetMapping("/{datasetId}/versions/{versionId}/records")
    @PreAuthorize("hasAuthority('dataset.read')")
    public ApiResponse<?> records(@PathVariable UUID datasetId, @PathVariable UUID versionId,
                                  @RequestParam(required = false) String split,
                                  @RequestParam(required = false, name = "label_id") UUID labelId,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int limit) {
        return paged(service.records(datasetId, versionId, split, labelId, page, limit));
    }

    @GetMapping("/labels")
    @PreAuthorize("hasAuthority('dataset.read')")
    public ApiResponse<Map<String, Object>> labels(@RequestParam(name = "version_id") UUID versionId) {
        return ApiResponse.of(service.labels(versionId));
    }

    @PatchMapping("/{datasetId}/versions/{versionId}/records/{recordId}/label")
    @PreAuthorize("hasAuthority('dataset.write')")
    public ApiResponse<Map<String, Boolean>> assignLabel(@AuthenticationPrincipal AppUser user,
            @PathVariable UUID datasetId, @PathVariable UUID versionId, @PathVariable UUID recordId,
            @RequestBody LabelRequest request) {
        service.assignLabel(datasetId, versionId, recordId, request.labelId(), request.reason(), user.id());
        return ApiResponse.of(Map.of("updated", true));
    }

    @GetMapping("/{datasetId}/versions/{versionId}/export.csv")
    @PreAuthorize("hasAuthority('dataset.read')")
    public ResponseEntity<StreamingResponseBody> exportCsv(@PathVariable UUID datasetId, @PathVariable UUID versionId) {
        DatasetService.ExportData export = service.exportCsv(datasetId, versionId);
        StreamingResponseBody body = output -> {
            output.write(String.join(",", CSV_COLUMNS).getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            for (Map<String, Object> row : export.rows()) {
                for (int index = 0; index < CSV_COLUMNS.size(); index++) {
                    if (index > 0) output.write(',');
                    Object value = row.get(CSV_COLUMNS.get(index));
                    output.write(csv(value == null ? "" : String.valueOf(value)).getBytes(StandardCharsets.UTF_8));
                }
                output.write('\n');
            }
        };
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.fileName() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private ApiResponse<?> paged(Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Number> meta = (Map<String, Number>) result.get("meta");
        return ApiResponse.page(rows, new ApiResponse.PageMeta(meta.get("page").intValue(),
                meta.get("limit").intValue(), meta.get("total").longValue()));
    }

    private String csv(String value) {
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    public record LabelRequest(UUID labelId, String reason) { }
}
