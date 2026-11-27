package vn.aiot.biochar.modules.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.aiot.biochar.common.exception.AppException;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtService(
            @Value("${app.security.jwt-secret}") String secret,
            @Value("${app.security.access-ttl-seconds}") long accessTtlSeconds,
            @Value("${app.security.refresh-ttl-seconds}") long refreshTtlSeconds) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET phải có ít nhất 32 byte");
        }
        if (accessTtlSeconds < 1 || refreshTtlSeconds < accessTtlSeconds) {
            throw new IllegalStateException("Thời hạn JWT không hợp lệ");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public String createAccessToken(UUID userId) {
        return createToken(userId, "access", accessTtlSeconds);
    }

    public String createRefreshToken(UUID userId) {
        return createToken(userId, "refresh", refreshTtlSeconds);
    }

    public UUID verifyAccessToken(String token) {
        return verifyToken(token, "access");
    }

    public UUID verifyRefreshToken(String token) {
        return verifyToken(token, "refresh");
    }

    public long accessTtlSeconds() {
        return accessTtlSeconds;
    }

    private String createToken(UUID userId, String type, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("typ", type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(signingKey)
                .compact();
    }

    private UUID verifyToken(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!expectedType.equals(claims.get("typ", String.class))) {
                throw AppException.unauthorized("Token không hợp lệ");
            }
            return UUID.fromString(claims.getSubject());
        } catch (ExpiredJwtException exception) {
            throw AppException.unauthorized("Phiên đăng nhập đã hết hạn");
        } catch (JwtException | IllegalArgumentException exception) {
            throw AppException.unauthorized("Token không hợp lệ");
        }
    }
}
