package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderAutoConfirmationProcessor;
import com.commercefoundry.domainlayer.order.support.OrderAutoConfirmationPersistenceContract;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 자동 구매 확정의 키셋 · 원자성 · 동시성 공통 계약. */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"
})
@Import({
    OrderServiceFeaturesConfig.class,
    OrderRepositoryAdapter.class,
    OrderMapperImpl.class,
    OrderAutoConfirmationProcessor.class,
    OrderAutoConfirmationH2Test.Config.class
})
class OrderAutoConfirmationPostgresTest extends OrderAutoConfirmationPersistenceContract {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");
}
