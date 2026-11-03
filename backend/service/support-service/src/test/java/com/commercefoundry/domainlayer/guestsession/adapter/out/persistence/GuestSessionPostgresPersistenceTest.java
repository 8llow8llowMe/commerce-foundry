package com.commercefoundry.domainlayer.guestsession.adapter.out.persistence;

import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionException;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=support",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"
})
class GuestSessionPostgresPersistenceTest extends GuestSessionPersistenceContract {
    @Override
    Class<? extends RuntimeException> uniqueViolationType() {
        return GuestSessionException.class;
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/support-schema.sql");
}
