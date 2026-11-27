package vn.aiot.biochar.modules.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.aiot.biochar.common.exception.AppException;

class JwtServiceTest {

    @Test
    void accessAndRefreshTokensArePurposeBound() {
        JwtService service = new JwtService("12345678901234567890123456789012", 60, 120);
        UUID userId = UUID.randomUUID();

        String accessToken = service.createAccessToken(userId);
        String refreshToken = service.createRefreshToken(userId);

        assertEquals(userId, service.verifyAccessToken(accessToken));
        assertEquals(userId, service.verifyRefreshToken(refreshToken));
        assertThrows(AppException.class, () -> service.verifyRefreshToken(accessToken));
        assertThrows(AppException.class, () -> service.verifyAccessToken(refreshToken));
    }

    @Test
    void refusesWeakSigningKeysAndInvalidLifetimeConfiguration() {
        assertThrows(IllegalStateException.class, () -> new JwtService("too-short", 60, 120));
        assertThrows(IllegalStateException.class,
                () -> new JwtService("12345678901234567890123456789012", 120, 60));
    }
}
