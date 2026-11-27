package vn.aiot.biochar.common.db;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserTransactionService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public UserTransactionService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public <T> T execute(UUID userId, String reason, Supplier<T> operation) {
        return transactionTemplate.execute(status -> {
            if (userId != null) {
                jdbcTemplate.queryForObject(
                        "SELECT set_config('app.user_id', ?, true)", String.class, userId.toString());
            }
            if (reason != null && !reason.isBlank()) {
                jdbcTemplate.queryForObject(
                        "SELECT set_config('app.change_reason', ?, true)", String.class, reason);
            }
            return operation.get();
        });
    }
}
