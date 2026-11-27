package vn.aiot.biochar.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

@Component
@ConditionalOnProperty(name = "app.migrations.enabled", havingValue = "true")
public class DatabaseMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);
    private static final long MIGRATION_LOCK_ID = 727001;

    private final DataSource dataSource;
    private final Path migrationDirectory;

    public DatabaseMigrationRunner(
            DataSource dataSource,
            org.springframework.core.env.Environment environment) {
        this.dataSource = dataSource;
        this.migrationDirectory = Path.of(environment.getRequiredProperty("app.migrations.location"))
                .toAbsolutePath().normalize();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!Files.isDirectory(migrationDirectory)) {
            throw new IllegalStateException("Không tìm thấy thư mục migration: " + migrationDirectory);
        }
        List<Path> migrationFiles;
        try (var files = Files.list(migrationDirectory)) {
            migrationFiles = files
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
        }

        try (Connection connection = dataSource.getConnection()) {
            lock(connection);
            try {
                createMigrationTable(connection);
                for (Path file : migrationFiles) {
                    if (!isApplied(connection, file.getFileName().toString())) {
                        apply(connection, file);
                    }
                }
            } finally {
                unlock(connection);
            }
        }
    }

    private void lock(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + MIGRATION_LOCK_ID + ")");
        }
    }

    private void createMigrationTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS public.schema_migrations (
                        filename text PRIMARY KEY,
                        applied_at timestamptz NOT NULL DEFAULT now()
                    )
                    """);
        }
    }

    private boolean isApplied(Connection connection, String filename) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM public.schema_migrations WHERE filename = ?")) {
            statement.setString(1, filename);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void apply(Connection connection, Path file) throws SQLException, IOException {
        String sql = Files.readString(file, StandardCharsets.UTF_8);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            try (var mark = connection.prepareStatement(
                    "INSERT INTO public.schema_migrations (filename) VALUES (?)")) {
                mark.setString(1, file.getFileName().toString());
                mark.executeUpdate();
            }
            connection.commit();
            log.info("Đã áp dụng migration {}", file.getFileName());
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw new IllegalStateException(
                    "Migration " + file.getFileName() + " thất bại: " + exception.getMessage(), exception);
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void unlock(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_unlock(" + MIGRATION_LOCK_ID + ")");
        } catch (SQLException exception) {
            log.warn("Không thể nhả khóa migration; khóa sẽ được nhả khi đóng kết nối", exception);
        }
    }
}
