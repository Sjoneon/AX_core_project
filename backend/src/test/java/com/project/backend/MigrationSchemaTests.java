package com.project.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.sql.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import com.project.backend.migration.DatabaseMigration;
import com.project.backend.product.UserProductRepository;

/** Applies the same packaged Flyway migrations as deployment and validates the real MySQL schema. */
@Tag("mysql-schema")
class MigrationSchemaTests {
    static final List<String> TABLES=List.of("chat_tool_calls","chat_messages","user_products","chat_conversations","app_users","schema_migrations","flyway_schema_history");

    @Test void migrationsSatisfyHibernateValidateAndReadLegacyProducts() throws Exception {
        String url=System.getProperty("test.datasource.url","");
        assumeTrue(url.startsWith("jdbc:mysql:"),"MySQL 전용 검사입니다.");
        String user=System.getProperty("test.datasource.username"),password=System.getProperty("test.datasource.password");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){
            dropAll(statement);
        }
        DatabaseMigration.migrate(url,user,password,"classpath:db/migration");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){
            statement.executeUpdate("INSERT INTO app_users(id,email,password,role) VALUES (1,'legacy@example.com','hash','USER')");
            statement.executeUpdate("INSERT INTO user_products(id,user_id,category,name,model,purchase_date,postcode,address,created_at,updated_at) "
                    + "VALUES (1,1,'WASHER','기존 세탁기','TR15WV5','2025-01-02','12345','기존 주소',NOW(),NOW())");
        }
        try(var context=new SpringApplicationBuilder(BackendApplication.class).run(
                "--spring.datasource.url="+url,"--spring.datasource.username="+user,"--spring.datasource.password="+password,
                "--spring.jpa.hibernate.ddl-auto=validate","--server.port=0","--homecare.agent.enabled=false",
                "--spring.ai.openai.api-key=test-key","--spring.ai.openai.chat.model=gpt-4o-mini","--homecare.knowledge.vector.enabled=false",
                "--jwt.secret=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")){
            assertThat(context.isRunning()).isTrue();
            var product=context.getBean(UserProductRepository.class).findById(1L).orElseThrow();
            assertThat(product.getDisplayName()).isEqualTo("기존 세탁기");
            assertThat(product.warranty().modelName()).isEqualTo("TR15WV5");
            assertThat(product.warranty().purchaseDate()).isEqualTo(LocalDate.of(2025,1,2));
            assertThat(product.response().address()).isEqualTo("기존 주소");
            try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){
                statement.executeUpdate("""
                        UPDATE user_products SET model='OLD-RELEASE-EDIT',purchase_date=NULL,
                          warranty_input='{"category":"WASHER","modelName":"TR15WV5","purchaseDate":"2025-01-02","deliveryDate":"2025-01-03","proofAvailable":true}'
                        WHERE id=1
                        """);
            }
            var edited=context.getBean(UserProductRepository.class).findById(1L).orElseThrow().warranty();
            assertThat(edited.modelName()).isEqualTo("OLD-RELEASE-EDIT");
            assertThat(edited.purchaseDate()).isNull();
            assertThat(edited.deliveryDate()).isNull();
            assertThat(edited.proofAvailable()).isNull();
        }finally{
            try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){dropAll(statement);}
        }
    }
    static void dropAll(Statement statement) throws SQLException {
        statement.execute("SET FOREIGN_KEY_CHECKS = 0");
        for(var table:TABLES)statement.execute("DROP TABLE IF EXISTS "+table);
        statement.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
}
