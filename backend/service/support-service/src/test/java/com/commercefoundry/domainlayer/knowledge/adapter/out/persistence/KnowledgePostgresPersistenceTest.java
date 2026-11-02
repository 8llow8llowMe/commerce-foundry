package com.commercefoundry.domainlayer.knowledge.adapter.out.persistence;

import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeException;
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
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=support",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"
})
class KnowledgePostgresPersistenceTest extends KnowledgePersistenceContract {

    @Override
    Class<? extends RuntimeException> uniqueViolationType() {
        return KnowledgeException.class;
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/support-schema.sql");
}
