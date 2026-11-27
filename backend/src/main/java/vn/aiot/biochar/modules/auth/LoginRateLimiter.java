package vn.aiot.biochar.modules.auth;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.aiot.biochar.common.exception.AppException;

@Component
public class LoginRateLimiter {

    private final int limit;
    private final long windowMillis;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LoginRateLimiter(
            @Value("${app.security.login-limit}") int limit,
            @Value("${app.security.login-window-seconds}") long windowSeconds) {
        if (limit < 1 || windowSeconds < 1) {
            throw new IllegalStateException("Giới hạn đăng nhập không hợp lệ");
        }
        this.limit = limit;
        this.windowMillis = windowSeconds * 1000;
    }

    public void check(String clientIp) {
        long now = Instant.now().toEpochMilli();
        Bucket bucket = buckets.computeIfAbsent(clientIp, ignored -> new Bucket(now));
        synchronized (bucket) {
            if (now - bucket.windowStartedAt >= windowMillis) {
                bucket.windowStartedAt = now;
                bucket.attempts = 0;
            }
            if (bucket.attempts >= limit) {
                throw new AppException(
                        429, "TOO_MANY_REQUESTS",
                        "Đăng nhập sai quá nhiều lần, hãy thử lại sau ít phút");
            }
            bucket.attempts++;
        }
    }

    private static final class Bucket {
        private long windowStartedAt;
        private int attempts;

        private Bucket(long windowStartedAt) {
            this.windowStartedAt = windowStartedAt;
        }
    }
}
