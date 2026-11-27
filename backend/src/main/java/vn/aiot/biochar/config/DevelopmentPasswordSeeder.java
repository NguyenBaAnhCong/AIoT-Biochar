package vn.aiot.biochar.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import vn.aiot.biochar.common.db.UserTransactionService;

@Component
public class DevelopmentPasswordSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentPasswordSeeder.class);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final String defaultPassword;
    private final boolean enabled;
    private final Environment environment;
    private final UserTransactionService transactions;

    public DevelopmentPasswordSeeder(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            UserTransactionService transactions,
            Environment environment,
            @Value("${app.dev-seed.enabled:false}") boolean enabled,
            @Value("${app.dev-seed.default-password:Biochar@2026}") String defaultPassword) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.transactions = transactions;
        this.environment = environment;
        this.enabled = enabled;
        this.defaultPassword = defaultPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || isProduction()) {
            return;
        }
        int updated = transactions.execute(null, "Đặt mật khẩu demo trong môi trường phát triển", () ->
                jdbcTemplate.update(
                        "UPDATE iam.users SET password_hash = ? WHERE password_hash IS NULL",
                        passwordEncoder.encode(defaultPassword)));
        if (updated > 0) {
            log.info("Đã đặt mật khẩu phát triển cho {} tài khoản chưa có mật khẩu", updated);
        }
    }

    private boolean isProduction() {
        return java.util.Arrays.stream(environment.getActiveProfiles())
                        .anyMatch(profile -> profile.equalsIgnoreCase("prod")
                                || profile.equalsIgnoreCase("production"))
                || "production".equalsIgnoreCase(environment.getProperty("NODE_ENV"));
    }
}
