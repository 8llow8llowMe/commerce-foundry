package com.commercefoundry.domainlayer.sizechartgeneration.application.service;

import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the same registration, nanos replay and concurrent winner assertions against PostgreSQL timestamp(6). */
@Testcontainers
@EnabledWithDockerOrOnCi
class SizeChartGenerationRegistrationPostgresTest extends SizeChartGenerationRegistrationH2Test {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
        .withInitScript("ddl/operations-schema.sql");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
