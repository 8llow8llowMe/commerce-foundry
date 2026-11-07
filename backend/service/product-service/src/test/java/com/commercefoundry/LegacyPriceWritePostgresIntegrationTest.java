package com.commercefoundry;

import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class LegacyPriceWritePostgresIntegrationTest extends LegacyPriceWriteIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");
}
