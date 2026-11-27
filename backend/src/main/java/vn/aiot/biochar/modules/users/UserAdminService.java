package vn.aiot.biochar.modules.users;

import java.security.SecureRandom;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import vn.aiot.biochar.common.db.UserTransactionService;
import vn.aiot.biochar.common.exception.AppException;
import vn.aiot.biochar.modules.auth.UserContextService;

@Service
public class UserAdminService {

    private static final String USER_SELECT = """
            SELECT u.user_id, u.username, u.email, u.full_name, u.is_active, u.organization_id,
                   o.name AS organization_name, u.created_at,
                   COALESCE(array_agg(DISTINCT r.role_code)
                       FILTER (WHERE r.role_code IS NOT NULL), ARRAY[]::text[]) AS roles,
                   (SELECT max(a.occurred_at) FROM audit.access_logs a
                     WHERE a.user_id = u.user_id AND a.action = 'LOGIN') AS last_login_at
              FROM iam.users u
              LEFT JOIN iam.organizations o ON o.organization_id = u.organization_id
              LEFT JOIN iam.user_roles ur ON ur.user_id = u.user_id
              LEFT JOIN iam.roles r ON r.role_id = ur.role_id
            """;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final UserTransactionService transactions;
    private final PasswordEncoder passwordEncoder;
    private final UserContextService userContextService;

    public UserAdminService(
            JdbcTemplate jdbcTemplate,
            UserTransactionService transactions,
            PasswordEncoder passwordEncoder,
            UserContextService userContextService) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
        this.passwordEncoder = passwordEncoder;
        this.userContextService = userContextService;
    }

    public UserPage list(String query, Boolean active, String role, int page, int limit) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            where.append("""
                     AND (u.username ILIKE ? ESCAPE '\\'
                          OR u.email ILIKE ? ESCAPE '\\'
                          OR u.full_name ILIKE ? ESCAPE '\\')
                    """);
            String pattern = "%" + escapeLike(query.trim()) + "%";
            parameters.addAll(List.of(pattern, pattern, pattern));
        }
        if (active != null) {
            where.append(" AND u.is_active = ?");
            parameters.add(active);
        }
        if (role != null && !role.isBlank()) {
            where.append("""
                     AND EXISTS (SELECT 1 FROM iam.user_roles x
                                  JOIN iam.roles y ON y.role_id = x.role_id
                                 WHERE x.user_id = u.user_id AND y.role_code = ?)
                    """);
            parameters.add(role);
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam.users u" + where, Long.class, parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(limit);
        pageParameters.add((page - 1) * limit);
        List<UserView> rows = jdbcTemplate.query(
                USER_SELECT + where
                        + " GROUP BY u.user_id, o.name ORDER BY u.created_at DESC, u.username LIMIT ? OFFSET ?",
                (rs, row) -> userView(rs, false), pageParameters.toArray());
        return new UserPage(rows, page, limit, total == null ? 0 : total);
    }

    public UserView get(UUID id) {
        List<UserView> users = jdbcTemplate.query(
                USER_SELECT + " WHERE u.user_id = ? GROUP BY u.user_id, o.name",
                (rs, row) -> userView(rs, true), id);
        if (users.isEmpty()) {
            throw AppException.notFound("Không tìm thấy người dùng");
        }
        return users.getFirst();
    }

    public UserView create(
            UUID actorId, String username, String email, String fullName, String password,
            List<String> roleCodes, UUID organizationId) {
        UUID userId;
        try {
            userId = transactions.execute(actorId, "Tạo người dùng", () -> {
                List<UUID> roleIds = roleIdsByCodes(roleCodes);
                UUID createdId = jdbcTemplate.queryForObject("""
                        INSERT INTO iam.users (username, email, full_name, password_hash, organization_id)
                        VALUES (?, ?, ?, ?, ?)
                        RETURNING user_id
                        """, UUID.class, username, email, fullName, passwordEncoder.encode(password), organizationId);
                setRoles(createdId, roleIds, actorId);
                return createdId;
            });
        } catch (DuplicateKeyException exception) {
            throw uniqueConflict(exception);
        }
        return get(userId);
    }

    public UserView update(UUID actorId, UUID id, UpdateUserRequest request) {
        try {
            transactions.execute(actorId, "Cập nhật người dùng", () -> {
                lockUser(id);
                if (request.roleCodes() != null) {
                    if (actorId.equals(id)) {
                        throw new AppException(400, "SELF_LOCKOUT", "Không thể tự thay đổi vai trò của chính mình");
                    }
                    List<UUID> roleIds = roleIdsByCodes(request.roleCodes());
                    boolean currentlyAdmin = hasAdminRole(id);
                    boolean retainsAdmin = request.roleCodes().contains("ADMIN");
                    if (currentlyAdmin && !retainsAdmin && otherActiveAdmins(id) == 0) {
                        throw lastAdmin();
                    }
                    setRoles(id, roleIds, actorId);
                }

                List<String> assignments = new ArrayList<>();
                List<Object> values = new ArrayList<>();
                if (request.fullName() != null) {
                    assignments.add("full_name = ?");
                    values.add(request.fullName());
                }
                if (request.email() != null) {
                    assignments.add("email = ?");
                    values.add(request.email());
                }
                if (request.organizationIdPresent()) {
                    assignments.add("organization_id = ?");
                    values.add(request.organizationId());
                }
                if (!assignments.isEmpty()) {
                    values.add(id);
                    jdbcTemplate.update("UPDATE iam.users SET " + String.join(", ", assignments)
                            + " WHERE user_id = ?", values.toArray());
                }
                return null;
            });
        } catch (DuplicateKeyException exception) {
            throw uniqueConflict(exception);
        }
        userContextService.invalidate(id);
        return get(id);
    }

    public UserView setActive(UUID actorId, UUID id, boolean active) {
        transactions.execute(actorId, active ? "Mở khóa tài khoản" : "Khóa tài khoản", () -> {
            lockUser(id);
            if (!active) {
                if (actorId.equals(id)) {
                    throw new AppException(400, "SELF_LOCKOUT", "Không thể tự khóa tài khoản của chính mình");
                }
                if (hasAdminRole(id) && otherActiveAdmins(id) == 0) {
                    throw lastAdmin();
                }
            }
            jdbcTemplate.update("UPDATE iam.users SET is_active = ? WHERE user_id = ?", active, id);
            return null;
        });
        userContextService.invalidate(id);
        return get(id);
    }

    public ResetPasswordResult resetPassword(UUID actorId, UUID id, String newPassword) {
        String password = newPassword == null ? temporaryPassword() : newPassword;
        transactions.execute(actorId, "Quản trị viên đặt lại mật khẩu", () -> {
            int updated = jdbcTemplate.update(
                    "UPDATE iam.users SET password_hash = ? WHERE user_id = ?",
                    passwordEncoder.encode(password), id);
            if (updated == 0) {
                throw AppException.notFound("Không tìm thấy người dùng");
            }
            return null;
        });
        userContextService.invalidate(id);
        if (newPassword != null) {
            return new ResetPasswordResult(true, null, null);
        }
        return new ResetPasswordResult(
                false, password, "Hãy gửi mật khẩu này cho người dùng. Hệ thống sẽ không hiển thị lại.");
    }

    private UserView userView(java.sql.ResultSet rs, boolean includePermissions) throws SQLException {
        List<String> roles = strings(rs.getArray("roles"));
        List<String> permissions = List.of();
        if (includePermissions) {
            permissions = jdbcTemplate.query("""
                    SELECT DISTINCT p.permission_code
                      FROM iam.user_roles ur
                      JOIN iam.role_permissions rp ON rp.role_id = ur.role_id
                      JOIN iam.permissions p ON p.permission_id = rp.permission_id
                     WHERE ur.user_id = ?
                     ORDER BY p.permission_code
                    """, (permissionRs, row) -> permissionRs.getString(1),
                    rs.getObject("user_id", UUID.class));
        }
        return new UserView(
                rs.getObject("user_id", UUID.class),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("full_name"),
                rs.getBoolean("is_active"),
                rs.getObject("organization_id", UUID.class),
                rs.getString("organization_name"),
                rs.getTimestamp("created_at"),
                roles,
                rs.getTimestamp("last_login_at"),
                permissions);
    }

    private List<UUID> roleIdsByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        List<RoleId> found = jdbcTemplate.execute(
                (org.springframework.jdbc.core.ConnectionCallback<List<RoleId>>) connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT role_id, role_code FROM iam.roles WHERE role_code = ANY (?)")) {
                        statement.setArray(1, connection.createArrayOf("text", codes.toArray()));
                        try (var result = statement.executeQuery()) {
                            List<RoleId> roleIds = new ArrayList<>();
                            while (result.next()) {
                                roleIds.add(new RoleId(
                                        result.getObject("role_id", UUID.class), result.getString("role_code")));
                            }
                            return roleIds;
                        }
                    }
                });
        List<String> missing = codes.stream().filter(code -> found.stream().noneMatch(r -> r.code().equals(code))).toList();
        if (!missing.isEmpty()) {
            throw new AppException(400, "VALIDATION_ERROR", "Vai trò không tồn tại",
                    List.of(new vn.aiot.biochar.common.api.ApiErrorResponse.ErrorDetail(
                            "role_codes", "Không có: " + String.join(", ", missing))));
        }
        return found.stream().map(RoleId::id).toList();
    }

    private void setRoles(UUID userId, List<UUID> roleIds, UUID grantedBy) {
        jdbcTemplate.update("DELETE FROM iam.user_roles WHERE user_id = ?", userId);
        for (UUID roleId : roleIds) {
            jdbcTemplate.update("""
                    INSERT INTO iam.user_roles (user_id, role_id, granted_by)
                    VALUES (?, ?, ?)
                    """, userId, roleId, grantedBy);
        }
    }

    private void lockUser(UUID id) {
        List<UUID> users = jdbcTemplate.query(
                "SELECT user_id FROM iam.users WHERE user_id = ? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), id);
        if (users.isEmpty()) {
            throw AppException.notFound("Không tìm thấy người dùng");
        }
        jdbcTemplate.query(
                "SELECT role_id FROM iam.roles WHERE role_code = 'ADMIN' FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class));
    }

    private boolean hasAdminRole(UUID userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM iam.user_roles ur
                  JOIN iam.roles r ON r.role_id = ur.role_id
                 WHERE ur.user_id = ? AND r.role_code = 'ADMIN'
                """, Integer.class, userId);
        return count != null && count > 0;
    }

    private int otherActiveAdmins(UUID excludedId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT u.user_id)::int
                  FROM iam.users u
                  JOIN iam.user_roles ur ON ur.user_id = u.user_id
                  JOIN iam.roles r ON r.role_id = ur.role_id
                 WHERE r.role_code = 'ADMIN' AND u.is_active AND u.user_id <> ?
                """, Integer.class, excludedId);
        return count == null ? 0 : count;
    }

    private AppException lastAdmin() {
        return new AppException(409, "LAST_ADMIN", "Phải còn ít nhất một quản trị viên (ADMIN) đang hoạt động");
    }

    private AppException uniqueConflict(DuplicateKeyException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        String field = message != null && message.contains("email") ? "email" : "username";
        String label = field.equals("email") ? "Email đã được sử dụng" : "Tên đăng nhập đã tồn tại";
        return new AppException(409, "CONFLICT", label,
                List.of(new vn.aiot.biochar.common.api.ApiErrorResponse.ErrorDetail(field, "Đã tồn tại")));
    }

    private String temporaryPassword() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return "T" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + RANDOM.nextInt(10);
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(String::valueOf).toList();
    }

    public record UserPage(List<UserView> data, int page, int limit, long total) {
    }

    public record UserView(
            UUID id, String username, String email,
            @JsonProperty("full_name") String fullName,
            @JsonProperty("is_active") boolean isActive,
            @JsonProperty("organization_id") UUID organizationId,
            @JsonProperty("organization_name") String organizationName,
            @JsonProperty("created_at") Timestamp createdAt,
            List<String> roles,
            @JsonProperty("last_login_at") Timestamp lastLoginAt,
            List<String> permissions) {
    }

    public record ResetPasswordResult(
            boolean reset,
            @JsonProperty("temporary_password") String temporaryPassword,
            String warning) {
    }

    public static class UpdateUserRequest {
        private String fullName;
        private String email;
        private UUID organizationId;
        private boolean organizationIdPresent;
        private List<String> roleCodes;

        @JsonProperty("full_name")
        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        @JsonProperty("email")
        public void setEmail(String email) {
            this.email = email;
        }

        @JsonProperty("organization_id")
        public void setOrganizationId(String organizationId) {
            this.organizationIdPresent = true;
            this.organizationId = organizationId == null ? null : UUID.fromString(organizationId);
        }

        @JsonProperty("role_codes")
        public void setRoleCodes(List<String> roleCodes) {
            this.roleCodes = roleCodes;
        }

        public String fullName() {
            return fullName;
        }

        public String email() {
            return email;
        }

        public UUID organizationId() {
            return organizationId;
        }

        public boolean organizationIdPresent() {
            return organizationIdPresent;
        }

        public List<String> roleCodes() {
            return roleCodes;
        }
    }

    private record RoleId(UUID id, String code) {
    }
}
