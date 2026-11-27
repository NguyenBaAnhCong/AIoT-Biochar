package vn.aiot.biochar.modules.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);
    private static final long ERROR_SAMPLE_MILLIS = 60_000;

    private final JdbcTemplate jdbcTemplate;
    private final long apiKeySampleMillis;
    private final ConcurrentMap<String, Long> lastApiKeyLog = new ConcurrentHashMap<>();

    public AccessLogFilter(
            JdbcTemplate jdbcTemplate,
            @Value("${app.audit.api-key-sample-seconds:300}") long apiKeySampleSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.apiKeySampleMillis = Math.max(1, apiKeySampleSeconds) * 1_000;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            writeLogIfNeeded(request, response);
        }
    }

    private void writeLogIfNeeded(HttpServletRequest request, HttpServletResponse response) {
        String path = request.getRequestURI();
        if (path.equals("/api/v1/auth/login")) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return;
        }

        Object principal = authentication.getPrincipal();
        boolean userRequest = principal instanceof AppUser;
        boolean apiKeyRequest = principal instanceof ApiKeyPrincipal;
        if (!userRequest && !apiKeyRequest) {
            return;
        }

        int status = response.getStatus();
        String method = request.getMethod();
        boolean isWrite = !method.equals("GET") && !method.equals("HEAD") && !method.equals("OPTIONS");
        if (!isWrite && status < 400) {
            return;
        }

        java.util.UUID userId = userRequest ? ((AppUser) principal).id() : null;
        ApiKeyPrincipal apiKey = apiKeyRequest ? (ApiKeyPrincipal) principal : null;
        if (apiKey != null && !shouldSampleApiKey(apiKey.id(), status, Instant.now().toEpochMilli())) {
            return;
        }

        String resource = path.length() > 300 ? path.substring(0, 300) : path;
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > 500) {
            userAgent = userAgent.substring(0, 500);
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO audit.access_logs
                        (user_id, api_key_id, action, resource, http_method, status_code, ip_address, user_agent)
                    VALUES (?, ?, 'API_CALL', ?, ?, ?, ?::inet, ?)
                    """, userId, apiKey == null ? null : apiKey.id(), resource, method,
                    status, request.getRemoteAddr(), userAgent);
        } catch (RuntimeException exception) {
            log.warn("Không ghi được access_logs cho {} {}", method, resource, exception);
        }
    }

    private boolean shouldSampleApiKey(java.util.UUID keyId, int status, long now) {
        String sampleKey = keyId + "|" + (status >= 400 ? status : "ok");
        long interval = status >= 400 ? ERROR_SAMPLE_MILLIS : apiKeySampleMillis;
        boolean[] allowed = {false};
        lastApiKeyLog.compute(sampleKey, (key, previous) -> {
            if (previous == null || now - previous >= interval) {
                allowed[0] = true;
                return now;
            }
            return previous;
        });
        return allowed[0];
    }
}
