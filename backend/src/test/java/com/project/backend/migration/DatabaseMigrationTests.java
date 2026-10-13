package com.project.backend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "migration.integration", matches = "true")
class DatabaseMigrationTests {
    // Fixed disposable container, never accepts a production URL or credentials.
    private static final String URL = "jdbc:mysql://127.0.0.1:3307/backend_test";
    private static final String USER = "backend_test";
    private static final String PASSWORD = "test-password";

    @Test
    void verifiesFreshInstallLegacyAdoptionAndUpgradeFailures(@TempDir Path directory) throws Exception {
        byte[] initial;
        try (var stream = getClass().getResourceAsStream("/db/migration/V001__initial_schema.sql")) {
            initial = stream.readAllBytes();
        }
        // Isolate V001 fixtures so future production V002+ SQL cannot collide with test upgrades.
        Files.write(directory.resolve("V001__initial_schema.sql"), initial);
        String location = "filesystem:" + directory;
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var sql = connection.createStatement()) {
            DatabaseMigration.migrate(URL, USER, PASSWORD, location);
            sql.executeUpdate("INSERT INTO app_users(email,password,role) VALUES ('migration@example.com','synthetic-hash','USER')");
            DatabaseMigration.migrate(URL, USER, PASSWORD, location);
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM app_users")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }

            // Reproduce the existing RDS initialization marker without changing V001.
            sql.execute("DROP TABLE flyway_schema_history");
            sql.execute("CREATE TABLE schema_migrations(version VARCHAR(50) PRIMARY KEY,checksum CHAR(64) NOT NULL)");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(initial));
            sql.executeUpdate("INSERT INTO schema_migrations VALUES ('V001','" + hash + "')");
            DatabaseMigration.migrate(URL, USER, PASSWORD, location);
            try (var rows = sql.executeQuery("SELECT type FROM flyway_schema_history WHERE version='1'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("BASELINE");
            }
            sql.executeUpdate("UPDATE schema_migrations SET checksum=REPEAT('0',64)");
            assertThatThrownBy(() -> DatabaseMigration.migrate(URL, USER, PASSWORD, location))
                    .hasMessageContaining("checksum");
            sql.executeUpdate("UPDATE schema_migrations SET checksum='" + hash + "'");

            var upgrade = directory.resolve("V002__nullable_column.sql");
            Files.writeString(upgrade, "ALTER TABLE app_users ADD COLUMN migration_fixture VARCHAR(20) NULL;");
            DatabaseMigration.migrate(URL, USER, PASSWORD, location);
            try (var rows = sql.executeQuery("SELECT email,migration_fixture FROM app_users")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("migration@example.com");
                assertThat(rows.getString(2)).isNull();
            }
            DatabaseMigration.migrate(URL, USER, PASSWORD, location);
            Files.writeString(upgrade, "ALTER TABLE app_users ADD COLUMN edited_applied_sql INT;");
            assertThatThrownBy(() -> DatabaseMigration.migrate(URL, USER, PASSWORD, location))
                    .hasMessageContaining("checksum");
            Files.writeString(upgrade, "ALTER TABLE app_users ADD COLUMN migration_fixture VARCHAR(20) NULL;");
            Files.writeString(directory.resolve("V003__partial_failure.sql"),
                    "ALTER TABLE app_users ADD COLUMN partial_fixture INT NULL;\nTHIS IS NOT SQL;");
            assertThatThrownBy(() -> DatabaseMigration.migrate(URL, USER, PASSWORD, location)).isInstanceOf(Exception.class);
            assertThatThrownBy(() -> DatabaseMigration.migrate(URL, USER, PASSWORD, location))
                    .hasMessageContaining("failed migration");
            // MySQL DDL is not rolled back: retry must stop, not silently repair.
            try (var rows = sql.executeQuery("SELECT partial_fixture FROM app_users")) {
                assertThat(rows.next()).isTrue();
            }
            assertThatThrownBy(() -> Flyway.configure().dataSource(URL, USER, PASSWORD).load().clean())
                    .hasMessageContaining("cleanDisabled");
        } finally {
            try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
                 var sql = connection.createStatement()) {
                sql.execute("SET FOREIGN_KEY_CHECKS=0");
                for (String table : new String[]{"chat_messages", "chat_conversations", "app_users", "schema_migrations", "flyway_schema_history"}) {
                    sql.execute("DROP TABLE IF EXISTS " + table);
                }
                sql.execute("SET FOREIGN_KEY_CHECKS=1");
            }
        }
    }
}
