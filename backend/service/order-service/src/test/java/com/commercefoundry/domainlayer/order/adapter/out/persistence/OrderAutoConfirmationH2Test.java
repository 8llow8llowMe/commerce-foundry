package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderAutoConfirmationProcessor;
import com.commercefoundry.domainlayer.order.support.OrderAutoConfirmationPersistenceContract;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 자동 구매 확정의 키셋 · 원자성 · 동시성 공통 계약. */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:auto-confirm;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS orders",
    "spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    OrderServiceFeaturesConfig.class,
    OrderRepositoryAdapter.class,
    OrderMapperImpl.class,
    OrderAutoConfirmationProcessor.class,
    OrderAutoConfirmationH2Test.Config.class
})
public class OrderAutoConfirmationH2Test extends OrderAutoConfirmationPersistenceContract {

    @org.springframework.boot.test.context.TestConfiguration
    public static class Config {
        @org.springframework.context.annotation.Bean
        java.time.Clock clock() { return java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC); }
        @org.springframework.context.annotation.Bean
        com.commercefoundry.persistence.util.SnowflakeIdGenerator snowflakeIdGenerator() {
            return new com.commercefoundry.persistence.util.SnowflakeIdGenerator(2, 2);
        }
    }
}
