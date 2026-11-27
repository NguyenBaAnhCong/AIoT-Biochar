package vn.aiot.biochar.modules.auth;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.aiot.biochar.common.db.UserTransactionService;
import vn.aiot.biochar.common.exception.AppException;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String DUMMY_HASH =
            "$2a$10$2b2qUe5c52Y3bZcjDqWxeO6lZ7E2N7pY2yB6bMuP3xk5LZ0ZsJc1G";

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserContextService userContextService;
    private final UserTransactionService userTransactionService;

    public AuthService(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            UserContextService userContextService,
            UserTransactionService userTransactionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.userContextService = userContextService;
        this.userTransactionService = userTransactionService;
    }

    public LoginResult login(String username, String password, RequestMeta meta) {
        List<LoginRow> users = jdbcTemplate.query("""
                SELECT user_id, username, password_hash, is_active
                  FROM iam.users
                 WHERE lower(username) = lower(?) OR lower(email) = lower(?)
                 LIMIT 1
                """, (rs, rowNum) -> new LoginRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getBoolean("is_active")),
                username, username);
        LoginRow user = users.isEmpty() ? null : users.getFirst();
        String hash = user == null || user.passwordHash() == null ? DUMMY_HASH : user.passwordHash();
        boolean passwordMatches = passwordEncoder.matches(password, hash);

        if (user == null || user.passwordHash() == null || !user.active() || !passwordMatches) {
            writeAccessLog(user == null ? null : user.id(), "LOGIN_FAILED",
                    "username=" + username, meta, 401);
            throw AppException.unauthorized("Sai tên đăng nhập hoặc mật khẩu");
        }

        AppUser context = userContextService.getRequired(user.id());
        if (context == null || !context.active()) {
            writeAccessLog(user.id(), "LOGIN_FAILED", "username=" + username, meta, 401);
            throw AppException.unauthorized("Sai tên đăng nhập hoặc mật khẩu");
        }
        writeAccessLog(user.id(), "LOGIN", "/auth/login", meta, 200);
        return new LoginResult(
                jwtService.createAccessToken(user.id()),
                jwtService.createRefreshToken(user.id()),
                jwtService.accessTtlSeconds(),
                UserView.from(context));
    }

    public RefreshResult refresh(String refreshToken) {
        UUID userId = jwtService.verifyRefreshToken(refreshToken);
        AppUser user = userContextService.getRequired(userId);
        if (user == null || !user.active()) {
            throw AppException.unauthorized("Tài khoản không tồn tại hoặc đã bị khóa");
        }
        return new RefreshResult(jwtService.createAccessToken(userId),
                jwtService.accessTtlSeconds(), UserView.from(user));
    }

    public UserView me(AppUser user) {
        return UserView.from(user);
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        AppUser context = userContextService.getRequired(userId);
        if (context == null || !context.active()) {
            throw AppException.unauthorized("Tài khoản không tồn tại hoặc đã bị khóa");
        }
        String currentHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM iam.users WHERE user_id = ?", String.class, userId);
        if (currentHash == null || !passwordEncoder.matches(currentPassword, currentHash)) {
            throw new AppException(400, "VALIDATION_ERROR", "Mật khẩu hiện tại không đúng",
                    List.of(new vn.aiot.biochar.common.api.ApiErrorResponse.ErrorDetail(
                            "currentPassword", "Không đúng")));
        }
        String newHash = passwordEncoder.encode(newPassword);
        userTransactionService.execute(userId, "Người dùng tự đổi mật khẩu", () -> {
            jdbcTemplate.update("UPDATE iam.users SET password_hash = ? WHERE user_id = ?", newHash, userId);
            return null;
        });
    }

    private void writeAccessLog(UUID userId, String action, String resource, RequestMeta meta, int status) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO audit.access_logs
                        (user_id, action, resource, http_method, status_code, ip_address, user_agent)
                    VALUES (?, ?, ?, ?, ?, ?::inet, ?)
                    """,
                    userId, action, resource == null ? null : resource.substring(0, Math.min(200, resource.length())),
                    "POST", status, meta.ipAddress(), meta.userAgent());
        } catch (RuntimeException exception) {
            log.warn("Không ghi được access_logs cho hành động {}", action, exception);
        }
    }

    public record RequestMeta(String ipAddress, String userAgent) {
    }

    public record LoginResult(
            String accessToken, String refreshToken, long expiresIn, UserView user) {
    }

    public record RefreshResult(String accessToken, long expiresIn, UserView user) {
    }

    public record UserView(
            UUID id, String username, String email, String fullName,
            List<String> roles, List<String> permissions) {

        static UserView from(AppUser user) {
            return new UserView(user.id(), user.username(), user.email(), user.fullName(),
                    user.roles(), user.permissions());
        }
    }

    private record LoginRow(UUID id, String username, String passwordHash, boolean active) {
    }
}
