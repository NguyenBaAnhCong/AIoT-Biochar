package vn.aiot.biochar.modules.users;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.aiot.biochar.common.api.ApiResponse;
import vn.aiot.biochar.modules.auth.AppUser;

@RestController
public class RolesAdminController {

    private final RolesAdminService service;

    public RolesAdminController(RolesAdminService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/roles")
    @PreAuthorize("hasAuthority('iam.read')")
    public ApiResponse<List<RolesAdminService.RoleSummary>> roles() {
        return ApiResponse.of(service.listRoles());
    }

    @GetMapping("/api/v1/roles/{id}")
    @PreAuthorize("hasAuthority('iam.read')")
    public ApiResponse<RolesAdminService.RoleDetail> role(@PathVariable UUID id) {
        return ApiResponse.of(service.getRole(id));
    }

    @PutMapping("/api/v1/roles/{id}/permissions")
    @PreAuthorize("hasAuthority('iam.admin')")
    public ApiResponse<RolesAdminService.RoleDetail> setPermissions(
            @AuthenticationPrincipal AppUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody SetPermissionsRequest request) {
        return ApiResponse.of(service.setRolePermissions(
                actor.id(), id, request.permissionCodes().stream().distinct().toList()));
    }

    @GetMapping("/api/v1/permissions")
    @PreAuthorize("hasAuthority('iam.read')")
    public ApiResponse<List<RolesAdminService.PermissionView>> permissions() {
        return ApiResponse.of(service.listPermissions());
    }

    @GetMapping("/api/v1/organizations")
    @PreAuthorize("hasAuthority('iam.read')")
    public ApiResponse<List<RolesAdminService.OrganizationView>> organizations() {
        return ApiResponse.of(service.listOrganizations());
    }

    @PostMapping("/api/v1/organizations")
    @PreAuthorize("hasAuthority('iam.write')")
    public ResponseEntity<ApiResponse<RolesAdminService.OrganizationView>> createOrganization(
            @AuthenticationPrincipal AppUser actor,
            @Valid @RequestBody CreateOrganizationRequest request) {
        String orgType = request.orgType() == null ? "RESEARCH" : request.orgType();
        service.validateOrganizationType(orgType);
        var result = service.createOrganization(
                actor.id(), request.code().trim().toUpperCase(Locale.ROOT),
                request.name().trim(), orgType);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @PatchMapping("/api/v1/organizations/{id}")
    @PreAuthorize("hasAuthority('iam.write')")
    public ApiResponse<RolesAdminService.OrganizationView> updateOrganization(
            @AuthenticationPrincipal AppUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateOrganizationRequest request) {
        if (request.name() == null && request.orgType() == null) {
            throw vn.aiot.biochar.common.exception.AppException.badRequest(
                    "Không có trường nào để cập nhật");
        }
        if (request.name() != null && request.name().isBlank()) {
            throw vn.aiot.biochar.common.exception.AppException.badRequest("name không được để trống");
        }
        if (request.orgType() != null) {
            service.validateOrganizationType(request.orgType());
        }
        return ApiResponse.of(service.updateOrganization(
                actor.id(), id,
                request.name() == null ? null : request.name().trim(),
                request.orgType()));
    }

    public record SetPermissionsRequest(
            @JsonProperty("permission_codes")
            @NotEmpty @Size(max = 100)
            List<@NotBlank @Pattern(regexp = "^[a-z_]+\\.[a-z_]+$") String> permissionCodes) {
    }

    public record CreateOrganizationRequest(
            @NotBlank @Size(min = 2, max = 32) @Pattern(regexp = "^[A-Za-z0-9_-]+$")
            String code,
            @NotBlank @Size(max = 255)
            String name,
            @JsonProperty("org_type") String orgType) {
    }

    public record UpdateOrganizationRequest(
            @Size(max = 255) String name,
            @JsonProperty("org_type") String orgType) {
    }
}
