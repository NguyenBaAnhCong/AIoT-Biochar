package vn.aiot.biochar.modules.laboratory;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.aiot.biochar.common.api.ApiResponse;
import vn.aiot.biochar.common.crud.CrudService;
import vn.aiot.biochar.common.exception.AppException;
import vn.aiot.biochar.modules.auth.AppUser;

@RestController
@RequestMapping("/api/v1")
public class LaboratoryController {

    private static final String LIST = "/{resource:lab-methods|samples|lab-results}";
    private static final String ITEM = LIST + "/{id}";
    /** Xác nhận cần quyền nhập liệu; duyệt và từ chối cần quyền duyệt. */
    private static final String REVIEW_PERMISSION =
            "hasAuthority(#action == 'verify' ? 'laboratory.write' : 'laboratory.approve')";
    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final LaboratoryService service;
    private final LabImportService importService;
    private final CrudService crud;

    public LaboratoryController(LaboratoryService service, LabImportService importService, CrudService crud) {
        this.service = service;
        this.importService = importService;
        this.crud = crud;
    }

    @GetMapping("/sensor-vs-lab")
    @PreAuthorize("hasAuthority('laboratory.read')")
    public ApiResponse<List<Map<String, Object>>> sensorVsLab(@RequestParam Map<String, String> query) {
        return service.sensorVsLab(query);
    }

    @GetMapping("/lab-results/import-template")
    @PreAuthorize("hasAuthority('laboratory.read')")
    public ResponseEntity<byte[]> importTemplate() {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mau-ket-qua-phong-thi-nghiem.xlsx\"")
                .body(importService.template());
    }

    @PostMapping(path = "/lab-results/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('laboratory.write')")
    public ApiResponse<LabImportService.ImportReport> importResults(
            @AuthenticationPrincipal AppUser actor,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "dry_run", defaultValue = "false") boolean dryRun) throws IOException {
        String name = file.getOriginalFilename();
        if (file.isEmpty() || name == null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw AppException.badRequest("Hãy chọn file .xlsx theo mẫu");
        }
        try (var input = file.getInputStream()) {
            return ApiResponse.of(importService.importWorkbook(input, dryRun, actor));
        }
    }

    @PostMapping("/lab-results/{id}/{action:verify|approve|reject}")
    @PreAuthorize(REVIEW_PERMISSION)
    public ApiResponse<Map<String, Object>> review(
            @AuthenticationPrincipal AppUser actor, @PathVariable UUID id, @PathVariable String action) {
        return ApiResponse.of(service.transition(List.of(id), reviewAction(action), actor).getFirst());
    }

    @PostMapping("/lab-results/bulk/{action:verify|approve|reject}")
    @PreAuthorize(REVIEW_PERMISSION)
    public ApiResponse<List<Map<String, Object>>> reviewMany(
            @AuthenticationPrincipal AppUser actor,
            @PathVariable String action,
            @RequestBody BulkRequest request) {
        if (request.ids() == null || request.ids().isEmpty() || request.ids().size() > LaboratoryService.MAX_BULK
                || request.ids().stream().anyMatch(id -> id == null)) {
            throw AppException.badRequest("ids phải có từ 1 đến " + LaboratoryService.MAX_BULK + " kết quả");
        }
        return ApiResponse.of(service.transition(request.ids(), reviewAction(action), actor));
    }

    @GetMapping(LIST)
    @PreAuthorize("hasAuthority('laboratory.read')")
    public ApiResponse<List<Map<String, Object>>> list(
            @PathVariable String resource, @RequestParam Map<String, String> query) {
        return crud.list(service.resource(resource), query);
    }

    @GetMapping(ITEM)
    @PreAuthorize("hasAuthority('laboratory.read')")
    public ApiResponse<Map<String, Object>> get(@PathVariable String resource, @PathVariable UUID id) {
        return ApiResponse.of(crud.get(service.resource(resource), id));
    }

    @PostMapping(LIST)
    @PreAuthorize("hasAuthority('laboratory.write')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @AuthenticationPrincipal AppUser actor,
            @PathVariable String resource,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(crud.create(service.resource(resource), body, actor)));
    }

    @PatchMapping(ITEM)
    @PreAuthorize("hasAuthority('laboratory.write')")
    public ApiResponse<Map<String, Object>> update(
            @AuthenticationPrincipal AppUser actor,
            @PathVariable String resource,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.of(crud.update(service.resource(resource), id, body, actor));
    }

    @DeleteMapping(ITEM)
    @PreAuthorize("hasAuthority('laboratory.write')")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AppUser actor, @PathVariable String resource, @PathVariable UUID id) {
        crud.delete(service.resource(resource), id, actor);
        return ResponseEntity.noContent().build();
    }

    private LaboratoryService.ReviewAction reviewAction(String action) {
        return LaboratoryService.ReviewAction.valueOf(action.toUpperCase(Locale.ROOT));
    }

    public record BulkRequest(List<UUID> ids) {
    }
}
