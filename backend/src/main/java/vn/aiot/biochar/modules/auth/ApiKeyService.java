package vn.aiot.biochar.modules.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.aiot.biochar.common.db.UserTransactionService;
import vn.aiot.biochar.common.exception.AppException;

@Service
public class ApiKeyService {

    public static final List<String> SCOPES = List.of(
            "iot.ingest", "iot.read", "ml.inference", "ota.update");

    private final JdbcTemplate jdbcTemplate;
    private final UserTransactionService userTransactionService;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(JdbcTemplate jdbcTemplate, UserTransactionService userTransactionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.userTransactionService = userTransactionService;
    }

    public ApiKeyPrincipal authenticate(String rawKey) {
        String hash = hashApiKey(rawKey);
        List<ApiKeyPrincipal> rows = jdbcTemplate.query("""
                SELECT api_key_id, key_prefix, scopes, owner_user_id, expires_at, revoked_at
                  FROM iam.api_keys
                 WHERE key_hash = ?
                """, (rs, rowNumber) -> {
            Array sqlScopes = rs.getArray("scopes");
            Object[] values = (Object[]) sqlScopes.getArray();
            return new ApiKeyRow(
                    rs.getObject("api_key_id", UUID.class),
                    rs.getString("key_prefix"),
                    Arrays.stream(values).map(String::valueOf).toList(),
                    rs.getObject("owner_user_id", UUID.class),
                    rs.getTimestamp("expires_at"),
                    rs.getTimestamp("revoked_at"));
        }, hash).stream().filter(row -> row.revokedAt() == null)
                .filter(row -> row.expiresAt() == null || row.expiresAt().toInstant().isAfter(Instant.now()))
                .map(row -> new ApiKeyPrincipal(row.id(), row.prefix(), row.scopes(), row.ownerUserId()))
                .toList();

        if (rows.isEmpty()) {
            throw AppException.unauthorized("API key không hợp lệ, đã bị thu hồi hoặc đã hết hạn");
        }
        return rows.getFirst();
    }

    public CreatedApiKey create(UUID ownerUserId, String label, List<String> scopes, Integer expiresInDays) {
        if (scopes == null || scopes.isEmpty()) {
            throw AppException.badRequest("Chọn ít nhất một phạm vi");
        }
        List<String> normalizedScopes = scopes.stream().distinct().toList();
        List<String> unknown = normalizedScopes.stream().filter(scope -> !SCOPES.contains(scope)).toList();
        if (!unknown.isEmpty()) {
            throw AppException.badRequest("Phạm vi API key không hợp lệ");
        }

        byte[] prefixBytes = new byte[4];
        byte[] secretBytes = new byte[24];
        secureRandom.nextBytes(prefixBytes);
        secureRandom.nextBytes(secretBytes);
        String prefix = "ak_" + HexFormat.of().formatHex(prefixBytes);
        String key = prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        String hash = hashApiKey(key);
        Timestamp expiresAt = expiresInDays == null
                ? null
                : Timestamp.from(Instant.now().plusSeconds(expiresInDays.longValue() * 86_400));

        ApiKeyRow row = userTransactionService.execute(ownerUserId, "Tạo API key", () ->
                jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<ApiKeyRow>) connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO iam.api_keys
                                (owner_user_id, label, key_prefix, key_hash, scopes, expires_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            RETURNING api_key_id, label, key_prefix, scopes, expires_at, created_at
                            """)) {
                        statement.setObject(1, ownerUserId);
                        statement.setString(2, label);
                        statement.setString(3, prefix);
                        statement.setString(4, hash);
                        statement.setArray(5, connection.createArrayOf("text", normalizedScopes.toArray()));
                        statement.setTimestamp(6, expiresAt);
                        try (var result = statement.executeQuery()) {
                            if (!result.next()) {
                                throw new IllegalStateException("Không nhận được API key vừa tạo");
                            }
                            Array sqlScopes = result.getArray("scopes");
                            Object[] values = (Object[]) sqlScopes.getArray();
                            return new ApiKeyRow(
                                    result.getObject("api_key_id", UUID.class),
                                    result.getString("key_prefix"),
                                    Arrays.stream(values).map(String::valueOf).toList(),
                                    ownerUserId,
                                    result.getTimestamp("expires_at"),
                                    null,
                                    result.getString("label"),
                                    result.getTimestamp("created_at"));
                        }
                    }
                }));

        return new CreatedApiKey(
                row.id(), row.label(), row.prefix(), row.scopes(), row.expiresAt(), row.createdAt(),
                key, "Hãy lưu key này ngay. Hệ thống sẽ không hiển thị lại.");
    }

    public List<ApiKeyView> list() {
        return jdbcTemplate.query("""
                SELECT k.api_key_id, k.label, k.key_prefix, k.scopes, k.expires_at, k.revoked_at, k.created_at,
                       u.username AS owner,
                       (SELECT max(a.occurred_at)
                          FROM audit.access_logs a
                         WHERE a.api_key_id = k.api_key_id) AS last_used_at,
                       CASE WHEN k.revoked_at IS NOT NULL THEN 'REVOKED'
                            WHEN k.expires_at IS NOT NULL AND k.expires_at < now() THEN 'EXPIRED'
                            ELSE 'ACTIVE' END AS status
                  FROM iam.api_keys k
                  LEFT JOIN iam.users u ON u.user_id = k.owner_user_id
                 ORDER BY k.created_at DESC
                """, (rs, rowNumber) -> {
            Array sqlScopes = rs.getArray("scopes");
            Object[] values = (Object[]) sqlScopes.getArray();
            return new ApiKeyView(
                    rs.getObject("api_key_id", UUID.class),
                    rs.getString("label"),
                    rs.getString("key_prefix"),
                    Arrays.stream(values).map(String::valueOf).toList(),
                    rs.getTimestamp("expires_at"),
                    rs.getTimestamp("revoked_at"),
                    rs.getTimestamp("created_at"),
                    rs.getString("owner"),
                    rs.getTimestamp("last_used_at"),
                    rs.getString("status"));
        });
    }

    public RevokedApiKey revoke(UUID actorId, UUID id) {
        List<RevokedApiKey> rows = userTransactionService.execute(actorId, "Thu hồi API key", () ->
                jdbcTemplate.query("""
                        UPDATE iam.api_keys
                           SET revoked_at = now()
                         WHERE api_key_id = ? AND revoked_at IS NULL
                        RETURNING api_key_id, label, revoked_at
                        """, (rs, rowNumber) -> new RevokedApiKey(
                        rs.getObject("api_key_id", UUID.class),
                        rs.getString("label"),
                        rs.getTimestamp("revoked_at")), id));
        if (rows.isEmpty()) {
            throw AppException.notFound("Không tìm thấy key hoặc key đã bị thu hồi");
        }
        return rows.getFirst();
    }

    public static String hashApiKey(String key) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Thiếu thuật toán SHA-256", exception);
        }
    }

    public record ApiKeyView(
            UUID id, String label, String prefix, List<String> scopes,
            Timestamp expires_at, Timestamp revoked_at, Timestamp created_at,
            String owner, Timestamp last_used_at, String status) {
    }

    public record CreatedApiKey(
            UUID id, String label, String prefix, List<String> scopes, Timestamp expires_at,
            Timestamp created_at, String key, String warning) {
    }

    public record RevokedApiKey(UUID id, String label, Timestamp revoked_at) {
    }

    private record ApiKeyRow(
            UUID id, String prefix, List<String> scopes, UUID ownerUserId,
            Timestamp expiresAt, Timestamp revokedAt, String label, Timestamp createdAt) {

        private ApiKeyRow(
                UUID id, String prefix, List<String> scopes, UUID ownerUserId,
                Timestamp expiresAt, Timestamp revokedAt) {
            this(id, prefix, scopes, ownerUserId, expiresAt, revokedAt, null, null);
        }
    }
}
