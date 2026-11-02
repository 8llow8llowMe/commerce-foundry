package com.commercefoundry.domainlayer.aiusage.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Executes the deployable delta against an independent synthetic legacy PostgreSQL 17 table. */
@Testcontainers
@EnabledWithDockerOrOnCi
class AiUsageProviderMigrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test void additiveDeltaPreservesLegacyNullAndAllowsRecordedProviders() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create schema operations");
        jdbc.execute("create table operations.ai_usage_call (id bigint primary key, model varchar(64))");
        jdbc.update("insert into operations.ai_usage_call (id, model) values (1, 'test-small')");
        try (var connection = dataSource.getConnection()) {
            new ResourceDatabasePopulator(new ClassPathResource("ddl/ai-usage-provider.sql")).populate(connection);
        }

        assertThat(jdbc.queryForObject("select provider from operations.ai_usage_call where id=1", String.class)).isNull();
        var column = jdbc.queryForMap("select is_nullable, character_maximum_length, column_default from information_schema.columns "
            + "where table_schema='operations' and table_name='ai_usage_call' and column_name='provider'");
        assertThat(column.get("is_nullable")).isEqualTo("YES");
        assertThat(((Number)column.get("character_maximum_length")).intValue()).isEqualTo(32);
        assertThat(column.get("column_default")).isNull();
        String comment = jdbc.queryForObject("select col_description('operations.ai_usage_call'::regclass, attnum) from pg_attribute "
            + "where attrelid='operations.ai_usage_call'::regclass and attname='provider'", String.class);
        assertThat(comment).contains("anthropic/openai");
        jdbc.update("insert into operations.ai_usage_call (id, model, provider) values (2, 'test-small', 'openai')");
        assertThat(jdbc.queryForObject("select provider from operations.ai_usage_call where id=2", String.class)).isEqualTo("openai");
        assertThat(jdbc.queryForObject("select provider from operations.ai_usage_call where id=1", String.class)).isNull();
    }
}
