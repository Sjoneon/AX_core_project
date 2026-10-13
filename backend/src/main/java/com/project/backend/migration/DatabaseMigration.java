package com.project.backend.migration;

import org.flywaydb.core.Flyway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** A separate entry point: never starts Spring, HTTP or OpenAI. */
public final class DatabaseMigration {
    private DatabaseMigration() {
    }

    public static void main(String[] args) {
        try {
            migrate(required("MIGRATION_JDBC_URL"), required("MIGRATION_USERNAME"),
                    required("MIGRATION_PASSWORD"), "classpath:db/migration");
        } catch (Exception error) {
            // SQL exceptions can contain SQL literals, JDBC URLs and credentials.
            System.err.println("DB_MIGRATION_FAILED: " + error.getClass().getSimpleName()
                    + "; inspect migration history before retrying. No automatic repair or DB rollback.");
            System.exit(1);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }

    public static void migrate(String url, String user, String password, String location) throws Exception {
        Flyway flyway = Flyway.configure().dataSource(url, user, password).locations(location)
                .cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false)
                .validateMigrationNaming(true).validateOnMigrate(true).load();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            Set<String> tables = tables(connection);
            if (tables.contains("schema_migrations")) {
                // Also protects V001 after Flyway baseline, which itself skips V001 validation.
                verifyLegacyV001(connection);
                if (!tables.contains("flyway_schema_history")) {
                    if (!tables.equals(Set.of("app_users", "chat_conversations", "chat_messages", "schema_migrations"))) {
                        throw new IllegalStateException("Unknown schema; baseline refused");
                    }
                    Flyway.configure().configuration(flyway.getConfiguration()).baselineVersion("1")
                            .baselineDescription("Verified existing V001").load().baseline();
                    System.out.println("Registered existing V001; application tables and data preserved.");
                }
            }
            var result = flyway.migrate();
            if ("homecare_migrator".equals(user)) {
                if (!"project_db".equals(connection.getCatalog())) {
                    throw new IllegalStateException("Unexpected application database");
                }
                // Keep metadata inaccessible to the app; cover newly added application tables.
                for (String table : tables(connection)) {
                    if (!Set.of("schema_migrations", "flyway_schema_history").contains(table)) {
                        String quoted = "`" + table.replace("`", "``") + "`";
                        try (var statement = connection.createStatement()) {
                            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON project_db." + quoted
                                    + " TO 'homecare_app'@'10.20.1.0/24'");
                        }
                    }
                }
            }
            System.out.println("DB_MIGRATION_OK: " + result.migrationsExecuted + " new migration(s) applied.");
        }
    }

    private static Set<String> tables(Connection connection) throws Exception {
        Set<String> tables = new HashSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW FULL TABLES WHERE Table_type = 'BASE TABLE'")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }

    private static void verifyLegacyV001(Connection connection) throws Exception {
        String checksum;
        try (var stream = DatabaseMigration.class.getResourceAsStream("/db/migration/V001__initial_schema.sql")) {
            if (stream == null) {
                throw new IllegalStateException("Missing initial SQL");
            }
            // The original initializer hashed UTF-8 text.
            byte[] bytes = new String(stream.readAllBytes(), StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
            checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT version, checksum FROM schema_migrations")) {
            if (!rows.next() || !"V001".equals(rows.getString(1)) || !checksum.equals(rows.getString(2)) || rows.next()) {
                throw new IllegalStateException("Legacy V001 checksum or history mismatch");
            }
        }
    }
}
