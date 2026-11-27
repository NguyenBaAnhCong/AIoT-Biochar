package vn.aiot.biochar.modules.users;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.aiot.biochar.common.db.UserTransactionService;
import vn.aiot.biochar.common.exception.AppException;
import vn.aiot.biochar.modules.auth.UserContextService;

@Service
public class RolesAdminService {

    private static final List<String> ADMIN_REQUIRED_PERMISSIONS =
            List.of("iam.admin", "iam.read", "iam.write");
    private static final List<String> ORGANIZATION_TYPES =
            List.of("RESEARCH", "UNIVERSITY", "FARM", "COMPANY", "GOVERNMENT");

    private final JdbcTemplate jdbcTemplate;
    private final UserTransactionService transactions;
    private final UserContextService userContextService;

    public RolesAdminService(
            JdbcTemplate jdbcTemplate,
            UserTransactionService transactions,
            UserContextService userContextService) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
        this.userContextService = userContextService;
    }

    public List<RoleSummary> listRoles() {
        return jdbcTemplate.query("""
                SELECT r.role_id, r.role_code, r.name, r.description,
                       (SELECT count(*)::int FROM iam.role_permissions rp WHERE rp.role_id = r.role_id) AS permission_count,
                       (SELECT count(*)::int FROM iam.user_roles ur
                          JOIN iam.users u ON u.user_id = ur.user_id
                         WHERE ur.role_id = r.role_id AND u.is_active) AS active_user_count
                  FROM iam.roles r
                 ORDER BY r.role_code
                """, (rs, row) -> new RoleSummary(
                rs.getObject("role_id", UUID.class),
                rs.getString("role_code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("permission_count"),
                rs.getInt("active_user_count")));
    }

    public RoleDetail getRole(UUID id) {
        List<RoleBase> roles = jdbcTemplate.query(
                "SELECT role_id, role_code, name, description FROM iam.roles WHERE role_id = ?",
                (rs, row) -> new RoleBase(
                        rs.getObject("role_id", UUID.class),
                        rs.getString("role_code"),
                        rs.getString("name"),
                        rs.getString("description")), id);
        if (roles.isEmpty()) {
            throw AppException.notFound("Không tìm thấy vai trò");
        }
        RoleBase role = roles.getFirst();
        List<PermissionView> permissions = jdbcTemplate.query("""
                SELECT p.permission_code, p.domain, p.action, p.description
                  FROM iam.role_permissions rp
                  JOIN iam.permissions p ON p.permission_id = rp.permission_id
                 WHERE rp.role_id = ?
                 ORDER BY p.domain, p.permission_code
                """, (rs, row) -> new PermissionView(
                rs.getString("permission_code"),
                rs.getString("domain"),
                rs.getString("action"),
                rs.getString("description")), id);
        return new RoleDetail(role.id(), role.roleCode(), role.name(), role.description(), permissions);
    }

    public List<PermissionView> listPermissions() {
        return jdbcTemplate.query("""
                SELECT permission_code, domain, action, description
                  FROM iam.permissions
                 ORDER BY domain, permission_code
                """, (rs, row) -> new PermissionView(
                rs.getString("permission_code"),
                rs.getString("domain"),
                rs.getString("action"),
                rs.getString("description")));
    }

    public RoleDetail setRolePermissions(UUID actorId, UUID roleId, List<String> permissionCodes) {
        transactions.execute(actorId, "Cập nhật quyền của vai trò", () -> {
            jdbcTemplate.query(
                    "SELECT role_id FROM iam.roles ORDER BY role_id FOR UPDATE",
                    (rs, row) -> rs.getObject(1, UUID.class));
            List<String> roles = jdbcTemplate.query(
                    "SELECT role_code FROM iam.roles WHERE role_id = ? FOR UPDATE",
                    (rs, row) -> rs.getString(1), roleId);
            if (roles.isEmpty()) {
                throw AppException.notFound("Không tìm thấy vai trò");
            }

            List<String> uniqueCodes = permissionCodes.stream().distinct().toList();
            if ("ADMIN".equals(roles.getFirst())) {
                List<String> missingRequired = ADMIN_REQUIRED_PERMISSIONS.stream()
                        .filter(code -> !uniqueCodes.contains(code)).toList();
                if (!missingRequired.isEmpty()) {
                    throw new AppException(400, "PROTECTED_ROLE",
                            "Vai trò ADMIN phải giữ các quyền: " + String.join(", ", missingRequired));
                }
            }

            List<PermissionId> found = new ArrayList<>();
            for (String code : uniqueCodes) {
                List<PermissionId> rows = jdbcTemplate.query(
                        "SELECT permission_id, permission_code FROM iam.permissions WHERE permission_code = ?",
                        (rs, row) -> new PermissionId(
                                rs.getObject("permission_id", UUID.class), rs.getString("permission_code")), code);
                found.addAll(rows);
            }
            List<String> missing = uniqueCodes.stream()
                    .filter(code -> found.stream().noneMatch(permission -> permission.code().equals(code))).toList();
            if (!missing.isEmpty()) {
                throw new AppException(400, "VALIDATION_ERROR", "Quyền không tồn tại",
                        List.of(new vn.aiot.biochar.common.api.ApiErrorResponse.ErrorDetail(
                                "permission_codes", "Không có: " + String.join(", ", missing))));
            }

            jdbcTemplate.update("DELETE FROM iam.role_permissions WHERE role_id = ?", roleId);
            for (PermissionId permission : found) {
                jdbcTemplate.update("""
                        INSERT INTO iam.role_permissions (role_id, permission_id)
                        VALUES (?, ?)
                        """, roleId, permission.id());
            }

            Integer stillAdmin = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM iam.user_roles ur
                      JOIN iam.role_permissions rp ON rp.role_id = ur.role_id
                      JOIN iam.permissions p ON p.permission_id = rp.permission_id
                     WHERE ur.user_id = ? AND p.permission_code = 'iam.admin'
                    """, Integer.class, actorId);
            if (stillAdmin == null || stillAdmin == 0) {
                throw new AppException(400, "SELF_LOCKOUT",
                        "Thay đổi này sẽ làm bạn mất quyền iam.admin của chính mình");
            }
            return null;
        });
        userContextService.invalidate(null);
        return getRole(roleId);
    }

    public List<OrganizationView> listOrganizations() {
        return jdbcTemplate.query("""
                SELECT o.organization_id, o.code, o.name, o.org_type, o.created_at,
                       (SELECT count(*)::int FROM iam.users u WHERE u.organization_id = o.organization_id) AS user_count
                  FROM iam.organizations o
                 ORDER BY o.name
                """, (rs, row) -> new OrganizationView(
                rs.getObject("organization_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("org_type"),
                rs.getTimestamp("created_at"),
                rs.getInt("user_count")));
    }

    public OrganizationView createOrganization(UUID actorId, String code, String name, String orgType) {
        try {
            OrganizationBase created = transactions.execute(actorId, "Tạo tổ chức", () -> {
                List<OrganizationBase> rows = jdbcTemplate.query("""
                        INSERT INTO iam.organizations (code, name, org_type)
                        VALUES (?, ?, ?)
                        RETURNING organization_id, code, name, org_type, created_at
                        """, (rs, row) -> new OrganizationBase(
                        rs.getObject("organization_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("org_type"),
                        rs.getTimestamp("created_at")), code, name, orgType);
                return rows.getFirst();
            });
            return new OrganizationView(
                    created.id(), created.code(), created.name(), created.orgType(), created.createdAt(), 0);
        } catch (DuplicateKeyException exception) {
            throw new AppException(409, "CONFLICT", "Mã tổ chức đã tồn tại",
                    List.of(new vn.aiot.biochar.common.api.ApiErrorResponse.ErrorDetail("code", "Đã tồn tại")));
        }
    }

    public OrganizationView updateOrganization(
            UUID actorId, UUID id, String name, String orgType) {
        if (name == null && orgType == null) {
            throw AppException.badRequest("Không có trường nào để cập nhật");
        }
        OrganizationBase updated = transactions.execute(actorId, "Cập nhật tổ chức", () -> {
            List<OrganizationBase> rows = jdbcTemplate.query("""
                    UPDATE iam.organizations
                       SET name = COALESCE(?, name), org_type = COALESCE(?, org_type)
                     WHERE organization_id = ?
                    RETURNING organization_id, code, name, org_type, created_at
                    """, (rs, row) -> new OrganizationBase(
                    rs.getObject("organization_id", UUID.class),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("org_type"),
                    rs.getTimestamp("created_at")), name, orgType, id);
            return rows.isEmpty() ? null : rows.getFirst();
        });
        if (updated == null) {
            throw AppException.notFound("Không tìm thấy tổ chức");
        }
        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam.users WHERE organization_id = ?", Integer.class, id);
        return new OrganizationView(
                updated.id(), updated.code(), updated.name(), updated.orgType(), updated.createdAt(),
                userCount == null ? 0 : userCount);
    }

    public void validateOrganizationType(String orgType) {
        if (!ORGANIZATION_TYPES.contains(orgType)) {
            throw AppException.badRequest("org_type không hợp lệ");
        }
    }

    public record RoleSummary(
            UUID id, @JsonProperty("role_code") String roleCode, String name, String description,
            @JsonProperty("permission_count") int permissionCount,
            @JsonProperty("active_user_count") int activeUserCount) {
    }

    public record RoleDetail(
            UUID id, @JsonProperty("role_code") String roleCode, String name, String description,
            List<PermissionView> permissions) {
    }

    public record PermissionView(
            @JsonProperty("permission_code") String permissionCode,
            String domain, String action, String description) {
    }

    public record OrganizationView(
            UUID id, String code, String name,
            @JsonProperty("org_type") String orgType,
            @JsonProperty("created_at") Timestamp createdAt,
            @JsonProperty("user_count") int userCount) {
    }

    private record RoleBase(UUID id, String roleCode, String name, String description) {
    }

    private record PermissionId(UUID id, String code) {
    }

    private record OrganizationBase(
            UUID id, String code, String name, String orgType, Timestamp createdAt) {
    }
}
