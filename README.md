# Edge-AIoT Biochar

Project migration workspace. The original `aiot-main` project remains unchanged.

## Current implementation

- Backend: Java 21, Maven, Spring Boot 4.1.1, JDBC and PostgreSQL.
- Frontend: static HTML, CSS and vanilla JavaScript served by Spring Boot.
- Database: PostgreSQL 16; the existing initialization SQL and migrations are retained.
- TV1 backend: authentication, API keys, users, roles, permissions, organizations, audit,
  reference parameters, dashboard summary and system status.
- TV5: soil health (SQI), alerts and decisions, and PDF/XLSX reporting. See the [TV5 integration guide](docs/TV5.md).
- TV4: data quality, issue/audit workflows, dataset versioning, labeling and CSV export. See the [TV4 integration guide](docs/TV4.md).
- Demo seed and pending SQL migrations run automatically in the isolated Compose database.

The database schema and TV1 API documents copied from the original project are kept under
`database/` and `docs/reference/`. No React or Node.js backend code is used by this Java
application.

## Run locally

1. Copy `.env.example` to `.env`, replace `JWT_SECRET` with a random value of at least
   32 characters, and keep `.env` local. Spring Boot loads this file when started from the
   project root.
2. Start PostgreSQL:

   ```powershell
   docker compose up -d db
   ```

3. From the project root, run the backend:

   ```powershell
   mvn -f backend/pom.xml spring-boot:run
   ```

4. When running with Maven, open `http://localhost:8080/`. The public health endpoint is
   `http://localhost:8080/api/v1/health`. When running the full Docker Compose stack instead,
   open `http://localhost:8081/` and use `http://localhost:8081/api/v1/health`.
   The Maven run configuration uses the project root as its working directory so it loads
   `.env`, and sets a PostgreSQL-compatible Vietnam timezone for the local JVM.
   With the example development seed, sign in as `admin` using the value of
   `DEV_DEFAULT_PASSWORD` from `.env` (default: `Biochar@2026`).

When running from the project root, the Java backend loads database settings from `.env`.
`SPRING_DATASOURCE_URL`, `DB_USER`, and `DB_PASSWORD` can override the corresponding
`POSTGRES_*` settings. The example database port is 5433 from the host and 5432 inside
Docker Compose. Demo users receive the configured development password only when
`SEED_DEV_PASSWORDS=true`; do not enable this in production.

## Database safety

The Compose stack uses its own `aiot_biochar_java_pgdata` volume and host port 5433 to avoid
colliding with the original project. Initialization and seed SQL are mounted read-only.
Pending migrations are applied by the Java backend when `APP_MIGRATIONS_ENABLED=true`.
Applied SQL files must not be edited; schema changes belong in a new migration.

## Current scope

The Java application includes TV1, TV4 and TV5. Their pages share the sign-in session and
PostgreSQL connection.
