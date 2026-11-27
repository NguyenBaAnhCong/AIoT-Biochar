package vn.aiot.biochar.modules.auth;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class UserContextService {

    private static final long CACHE_TTL_MILLIS = 15_000;
    private static final String USER_CONTEXT_SQL = """
            SELECT u.user_id, u.username, u.email, u.full_name, u.is_active, u.organization_id,
                   COALESCE(array_agg(DISTINCT r.role_code)
                       FILTER (WHERE r.role_code IS NOT NULL), ARRAY[]::text[]) AS roles,
                   COALESCE(array_agg(DISTINCT p.permission_code)
                       FILTER (WHERE p.permission_code IS NOT NULL), ARRAY[]::text[]) AS permissions
              FROM iam.users u
              LEFT JOIN iam.user_roles ur ON ur.user_id = u.user_id
              LEFT JOIN iam.roles r ON r.role_id = ur.role_id
              LEFT JOIN iam.role_permissions rp ON rp.role_id = r.role_id
              LEFT JOIN iam.permissions p ON p.permission_id = rp.permission_id
             WHERE u.user_id = ?
             GROUP BY u.user_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Map<UUID, CachedContext> cache = new ConcurrentHashMap<>();

    public UserContextService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AppUser getRequired(UUID userId) {
        CachedContext cached = cache.get(userId);
        if (cached != null && Instant.now().toEpochMilli() - cached.cachedAt() < CACHE_TTL_MILLIS) {
            return cached.user();
        }

        List<AppUser> users = jdbcTemplate.query(USER_CONTEXT_SQL, (resultSet, rowNumber) ->
                new AppUser(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("username"),
                        resultSet.getString("email"),
                        resultSet.getString("full_name"),
                        resultSet.getBoolean("is_active"),
                        resultSet.getObject("organization_id", UUID.class),
                        strings(resultSet.getArray("roles")),
                        strings(resultSet.getArray("permissions"))),
                userId);

        if (users.isEmpty()) {
            cache.remove(userId);
            return null;
        }
        AppUser user = users.getFirst();
        cache.put(userId, new CachedContext(user, Instant.now().toEpochMilli()));
        return user;
    }

    public void invalidate(UUID userId) {
        if (userId == null) {
            cache.clear();
        } else {
            cache.remove(userId);
        }
    }

    private List<String> strings(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        Object[] values = (Object[]) sqlArray.getArray();
        return Arrays.stream(values).map(String::valueOf).toList();
    }

    private record CachedContext(AppUser user, long cachedAt) {
    }
}
