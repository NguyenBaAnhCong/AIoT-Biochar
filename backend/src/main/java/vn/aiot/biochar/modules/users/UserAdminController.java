package vn.aiot.biochar.modules.users;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.annotation.JsonProperty;
import vn.aiot.biochar.common.api.ApiResponse;
import vn.aiot.biochar.common.exception.AppException;
import vn.aiot.biochar.modules.auth.AppUser;

@RestController
@RequestMapping("/api/v1/users")
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('iam.read')")
    public ApiResponse<List<UserAdminService.UserView>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        validatePaging(page, limit);
        if (q != null && q.length() > 100) {
            throw AppException.badRequest("q tối đa 100 ký tự");
        }
        if (role != null && !role.matches("^[A-Z_]{2,32}$")) {
            throw AppException.badRequest("role không hợp lệ");
        }
        UserAdminService.UserPage result = service.list(q, active, role, page, limit);
        return ApiResponse.page(result.data(), new ApiResponse.PageMeta(page, limit, result.total()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('iam.read')")
    public ApiResponse<UserAdminService.UserView> get(@PathVariable UUID id) {
        return ApiResponse.of(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('iam.write')")
    public ResponseEntity<ApiResponse<UserAdminService.UserView>> create(
            @AuthenticationPrincipal AppUser actor,
            @Valid @RequestBody CreateUserRequest request) {
        UserAdminService.UserView user = service.create(
                actor.id(),
                request.username().trim().toLowerCase(Locale.ROOT),
                request.email().trim().toLowerCase(Locale.ROOT),
                request.fullName().trim(),
                request.password(),
                request.roleCodes() == null ? List.of() : request.roleCodes().stream().distinct().toList(),
                request.organizationId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(user));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('iam.write')")
    public ApiResponse<UserAdminService.UserView> update(
            @AuthenticationPrincipal AppUser actor,
            @PathVariable UUID id,
            @RequestBody UserAdminService.UpdateUserRequest request) {
        validateUpdate(request);
        if (request.email() != null) {
            request.setEmail(request.email().trim().toLowerCase(Locale.ROOT));
        }
        if (request.fullName() != null) {
            request.setFullName(request.fullName().trim());
            if (request.fullName().isEmpty()) {
                throw AppException.badRequest("full_name không được để trống");
            }
        }
        if (request.roleCodes() != null) {
            request.setRoleCodes(request.roleCodes().stream().distinct().toList());
        }
        return ApiResponse.of(service.update(actor.id(), id, request));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('iam.write')")
    public ApiResponse<UserAdminService.UserView> deactivate(
            @AuthenticationPrincipal AppUser actor, @PathVariable UUID id) {
        return ApiResponse.of(service.setActive(actor.id(), id, false));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('iam.write')")
    public ApiResponse<UserAdminService.UserView> activate(
            @AuthenticationPrincipal AppUser actor, @PathVariable UUID id) {
        return ApiResponse.of(service.setActive(actor.id(), id, true));
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('iam.write')")
    public ApiResponse<UserAdminService.ResetPasswordResult> resetPassword(
            @AuthenticationPrincipal AppUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) ResetPasswordRequest request) {
        return ApiResponse.of(service.resetPassword(
                actor.id(), id, request == null ? null : request.newPassword()));
    }

    private void validatePaging(int page, int limit) {
        if (page < 1 || limit < 1 || limit > 100) {
            throw AppException.badRequest("page phải ≥ 1 và limit phải từ 1 đến 100");
        }
    }

    private void validateUpdate(UserAdminService.UpdateUserRequest request) {
        if (request.fullName() == null && request.email() == null
                && !request.organizationIdPresent() && request.roleCodes() == null) {
            throw AppException.badRequest("Không có trường nào để cập nhật");
        }
        if (request.email() != null) {
            String email = request.email().trim();
            if (email.length() > 255 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                throw AppException.badRequest("Email không hợp lệ");
            }
        }
        if (request.fullName() != null && request.fullName().trim().length() > 255) {
            throw AppException.badRequest("full_name tối đa 255 ký tự");
        }
        if (request.roleCodes() != null && (request.roleCodes().size() > 10 || request.roleCodes().stream()
                .anyMatch(code -> code == null || !code.matches("^[A-Z_]{2,32}$")))) {
            throw AppException.badRequest("role_codes không hợp lệ");
        }
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "^[a-zA-Z0-9_.-]+$")
            String username,
            @NotBlank @Email @Size(max = 255)
            String email,
            @JsonProperty("full_name") @NotBlank @Size(max = 255)
            String fullName,
            @NotBlank @Size(min = 8, max = 72)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9]).+$", message = "Cần có chữ cái và chữ số")
            String password,
            @JsonProperty("role_codes") @Size(max = 10)
            List<@NotBlank @Pattern(regexp = "^[A-Z_]{2,32}$") String> roleCodes,
            @JsonProperty("organization_id") UUID organizationId) {
    }

    public record ResetPasswordRequest(
            @JsonProperty("new_password") @Size(min = 8, max = 72)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9]).+$", message = "Cần có chữ cái và chữ số")
            String newPassword) {
    }
}
