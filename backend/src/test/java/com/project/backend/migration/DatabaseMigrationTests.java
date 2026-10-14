package com.project.backend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void resetDisposableDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var sql = connection.createStatement()) {
            sql.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String table : new String[]{"chat_tool_calls", "user_products", "chat_messages", "chat_conversations", "app_users", "schema_migrations", "flyway_schema_history"}) {
                sql.execute("DROP TABLE IF EXISTS " + table);
            }
            sql.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

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
            resetDisposableDatabase();
        }
    }

    @Test
    void profileMigrationPreservesMcpProductsAndConversationHistory(@TempDir Path directory) throws Exception {
        String[] migrations = {"V001__initial_schema.sql", "V002__warranty_result.sql",
                "V003__mcp_context_and_products.sql", "V004__four_category_scope.sql", "V005__chat_tool_calls.sql"};
        for (String name : migrations) {
            try (var stream = getClass().getResourceAsStream("/db/migration/" + name)) {
                Files.write(directory.resolve(name), stream.readAllBytes());
            }
        }
        String location = "filesystem:" + directory;
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var sql = connection.createStatement()) {
            DatabaseMigration.migrate(URL, USER, PASSWORD, location);
            sql.executeUpdate("INSERT INTO app_users(id,email,password,role) VALUES (1,'product-migration@example.com','hash','USER')");
            sql.executeUpdate("INSERT INTO chat_conversations(id,user_id,title,category,created_at,updated_at) VALUES (1,1,'legacy','WASHER',NOW(),NOW())");
            sql.executeUpdate("INSERT INTO chat_messages(conversation_id,role,content,sources,created_at) VALUES (1,'assistant','legacy reply','[]',NOW())");
            sql.executeUpdate("INSERT INTO user_products(user_id,category,display_name,warranty_input,delivery_sido,delivery_sigungu) VALUES (1,'WASHER','legacy product','{}','서울','강남구')");
            try (var stream = getClass().getResourceAsStream("/db/migration/V006__product_profile.sql")) {
                Files.write(directory.resolve("V006__product_profile.sql"), stream.readAllBytes());
            }
            DatabaseMigration.migrate(URL, USER, PASSWORD, location);
            DatabaseMigration.migrate(URL, USER, PASSWORD, location);
            try (var rows = sql.executeQuery("SELECT category,content,sources FROM chat_conversations JOIN chat_messages ON chat_conversations.id=conversation_id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("WASHER");
                assertThat(rows.getString(2)).isEqualTo("legacy reply");
                assertThat(rows.getString(3)).isEqualTo("[]");
            }
            try (var rows = sql.executeQuery("SELECT display_name,warranty_input,delivery_sido,delivery_sigungu,postcode,address,address_detail,created_at,updated_at FROM user_products")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("legacy product");
                assertThat(rows.getString(2)).isEqualTo("{}");
                assertThat(rows.getString(3)).isEqualTo("서울");
                assertThat(rows.getString(4)).isEqualTo("강남구");
                for (int column = 5; column <= 9; column++) assertThat(rows.getObject(column)).isNull();
            }
            resetDisposableDatabase();
            DatabaseMigration.migrate(URL, USER, PASSWORD, "classpath:db/migration");
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM user_products")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        } finally {
            resetDisposableDatabase();
        }
    }
}
