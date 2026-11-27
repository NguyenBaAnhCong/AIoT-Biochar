package vn.aiot.biochar.modules.ref;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;
import vn.aiot.biochar.common.api.ApiResponse;
import vn.aiot.biochar.common.exception.AppException;
import vn.aiot.biochar.modules.auth.AppUser;

@RestController
@RequestMapping("/api/v1/ref/parameters")
public class ReferenceParameterController {

    private final ReferenceParameterService service;

    public ReferenceParameterController(ReferenceParameterService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ReferenceParameterService.ParameterView>> list(
            @RequestParam(required = false) String category) {
        if (category != null && !ReferenceParameterService.CATEGORIES.contains(category)) {
            throw AppException.badRequest("category không hợp lệ");
        }
        return ApiResponse.of(service.list(category));
    }

    @GetMapping("/{code}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ReferenceParameterService.ParameterView> get(@PathVariable String code) {
        return ApiResponse.of(service.get(normalizeCode(code)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('iam.admin')")
    public ResponseEntity<ApiResponse<ReferenceParameterService.ParameterView>> create(
            @AuthenticationPrincipal AppUser actor,
            @Valid @RequestBody CreateParameterRequest request) {
        String code = normalizeCode(request.parameterCode());
        String category = request.category().trim().toUpperCase(Locale.ROOT);
        validateCategory(category);
        validateDescription(request.description());
        var result = service.create(
                actor.id(), code, request.nameVi().trim(), request.unit().trim(), category,
                request.physicalMin(), request.physicalMax(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @PatchMapping("/{code}")
    @PreAuthorize("hasAuthority('iam.admin')")
    public ApiResponse<ReferenceParameterService.ParameterView> update(
            @AuthenticationPrincipal AppUser actor,
            @PathVariable String code,
            @RequestBody ReferenceParameterService.ParameterPatch patch) {
        if (patch.fields().isEmpty()) {
            throw AppException.badRequest("Không có trường nào để cập nhật");
        }
        if (patch.fields().contains("name_vi")
                && (patch.nameVi() == null || patch.nameVi().isBlank() || patch.nameVi().length() > 128)) {
            throw AppException.badRequest("name_vi phải có từ 1 đến 128 ký tự");
        }
        if (patch.fields().contains("unit")
                && (patch.unit() == null || patch.unit().isBlank() || patch.unit().length() > 32)) {
            throw AppException.badRequest("unit phải có từ 1 đến 32 ký tự");
        }
        if (patch.fields().contains("category")) {
            if (patch.category() == null) {
                throw AppException.badRequest("category không được để trống");
            }
            patch.setCategory(patch.category().trim().toUpperCase(Locale.ROOT));
            validateCategory(patch.category());
        }
        if (patch.fields().contains("description")) {
            validateDescription(patch.description());
        }
        return ApiResponse.of(service.update(actor.id(), normalizeCode(code), patch));
    }

    private String normalizeCode(String code) {
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z][A-Z0-9_]{0,31}$")) {
            throw AppException.badRequest("parameter_code không hợp lệ");
        }
        return normalized;
    }

    private void validateCategory(String category) {
        if (!ReferenceParameterService.CATEGORIES.contains(category)) {
            throw AppException.badRequest("category không hợp lệ");
        }
    }

    private void validateDescription(String description) {
        if (description != null && description.length() > 1000) {
            throw AppException.badRequest("description tối đa 1000 ký tự");
        }
    }

    public record CreateParameterRequest(
            @JsonProperty("parameter_code") @NotBlank @Size(max = 32) String parameterCode,
            @JsonProperty("name_vi") @NotBlank @Size(max = 128) String nameVi,
            @NotBlank @Size(max = 32) String unit,
            @NotBlank String category,
            @JsonProperty("physical_min") BigDecimal physicalMin,
            @JsonProperty("physical_max") BigDecimal physicalMax,
            @Size(max = 1000) String description) {
    }
}
