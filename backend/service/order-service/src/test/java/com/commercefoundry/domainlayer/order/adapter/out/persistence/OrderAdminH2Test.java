package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderAdminProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderFulfillmentProcessor;
import com.commercefoundry.domainlayer.order.support.OrderAdminPersistenceContract;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 어드민 주문 관리 영속성 계약을 H2(PostgreSQL 모드)로 돌린다 — Docker 없이 로컬에서 도는 쪽이다. 엔진 의존 동작은 {@code OrderAdminPostgresTest} 가 본다. */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS orders",
    "spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    OrderServiceFeaturesConfig.class,
    OrderRepositoryAdapter.class,
    OrderMapperImpl.class,
    OrderAdminProcessor.class,
    OrderFulfillmentProcessor.class,
    OrderAdminH2Test.Config.class
})
public class OrderAdminH2Test extends OrderAdminPersistenceContract {

    @TestConfiguration
    public static class Config {

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(2, 3);
        }

        /** 어드민 수동 배송 완료 · 출고 · 배송 완료 반영의 시각 (#18). */
        @Bean
        Clock orderClock() {
            return Clock.systemUTC();
        }
    }
}
