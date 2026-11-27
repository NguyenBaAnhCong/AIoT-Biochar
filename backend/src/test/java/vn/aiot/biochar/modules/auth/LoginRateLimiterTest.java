package vn.aiot.biochar.modules.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import vn.aiot.biochar.common.exception.AppException;

class LoginRateLimiterTest {

    @Test
    void rejectsAttemptsAfterConfiguredIpLimit() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, 900);
        limiter.check("192.0.2.10");
        limiter.check("192.0.2.10");

        AppException exception = assertThrows(
                AppException.class, () -> limiter.check("192.0.2.10"));

        assertEquals(429, exception.status());
        assertEquals("TOO_MANY_REQUESTS", exception.code());
    }

    @Test
    void tracksLimitsSeparatelyForEachIp() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 900);
        limiter.check("192.0.2.10");
        limiter.check("192.0.2.11");
    }
}
