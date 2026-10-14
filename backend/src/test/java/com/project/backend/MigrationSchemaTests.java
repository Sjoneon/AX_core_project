package com.project.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Production runs with ddl-auto=validate on ops/rds/sql V001..V005 plus backend V006. This applies the same SQL to
 * the disposable MySQL 8.4 database (gradlew mysqlSchemaTest) and starts the application with validate.
 */
@Tag("mysql-schema")
class MigrationSchemaTests {
    static final Path SQL=Path.of("..","ops","rds","sql");
    static final List<String> MIGRATIONS=List.of("V001__initial_schema.sql","V002__warranty_result.sql",
            "V003__mcp_context_and_products.sql","V004__four_category_scope.sql","V005__chat_tool_calls.sql");
    static final List<String> TABLES=List.of("chat_tool_calls","chat_messages","user_products","chat_conversations","app_users");

    @Test void migrationsSatisfyHibernateValidate() throws Exception {
        String url=System.getProperty("test.datasource.url","");
        assumeTrue(url.startsWith("jdbc:mysql:"),"MySQL 전용 검사입니다.");
        String user=System.getProperty("test.datasource.username"),password=System.getProperty("test.datasource.password");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){
            dropAll(statement);
            for(var file:MIGRATIONS)for(var sql:statements(Files.readString(SQL.resolve(file))))statement.execute(sql);
            for(var sql:statements(Files.readString(Path.of("src/main/resources/db/migration/V006__product_profile.sql"))))statement.execute(sql);
        }
        try(var context=new SpringApplicationBuilder(BackendApplication.class).properties(
                "spring.datasource.url="+url,"spring.datasource.username="+user,"spring.datasource.password="+password,
                "spring.jpa.hibernate.ddl-auto=validate","server.port=0","homecare.agent.enabled=false",
                "spring.ai.openai.api-key=test-key","spring.ai.openai.chat.model=gpt-4o-mini","homecare.knowledge.vector.enabled=false",
                "jwt.secret=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=").run()){
            assertThat(context.isRunning()).isTrue();
        }finally{
            try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){dropAll(statement);}
        }
    }
    /** Statements of one migration file; comment lines are removed and the preflight SELECTs are kept. */
    static List<String> statements(String sql){
        var body=new StringBuilder();
        for(var line:sql.lines().toList())if(!line.strip().startsWith("--"))body.append(line).append('\n');
        return Arrays.stream(body.toString().split(";")).map(String::strip).filter(s->!s.isEmpty()).toList();
    }
    static void dropAll(Statement statement) throws SQLException {
        statement.execute("SET FOREIGN_KEY_CHECKS = 0");
        for(var table:TABLES)statement.execute("DROP TABLE IF EXISTS "+table);
        statement.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
}
