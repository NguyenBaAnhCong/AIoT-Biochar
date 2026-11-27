package vn.aiot.biochar.modules.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.aiot.biochar.common.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthService authService, LoginRateLimiter loginRateLimiter) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        loginRateLimiter.check(clientIp(servletRequest));
        String userAgent = servletRequest.getHeader("User-Agent");
        AuthService.RequestMeta meta = new AuthService.RequestMeta(
                clientIp(servletRequest), userAgent == null ? null : userAgent.substring(0, Math.min(500, userAgent.length())));
        return ApiResponse.of(authService.login(request.username().trim(), request.password(), meta));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthService.RefreshResult> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.of(authService.refresh(request.refreshToken()));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<AuthService.UserView> me(@AuthenticationPrincipal AppUser user) {
        return ApiResponse.of(authService.me(user));
    }

    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal AppUser user,
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(user.id(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 72)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9]).+$", message = "Cần có chữ cái và chữ số")
            String newPassword) {
    }
}
