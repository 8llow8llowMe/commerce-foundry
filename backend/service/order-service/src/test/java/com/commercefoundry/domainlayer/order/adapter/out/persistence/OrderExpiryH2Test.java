package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.adapter.out.event.OrderEventAdapter;
import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderExpiryProcessor;
import com.commercefoundry.domainlayer.order.support.OrderExpiryPersistenceContract;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.global.messaging.JpaOutboxStore;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.outbox.OutboxStore;
import com.commercefoundry.messaging.publish.EventPublisher;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미결제 만료 영속성 계약을 H2(PostgreSQL 모드)로 돌린다 — Docker 없이 로컬에서 도는 쪽이다. 엔진 의존 동작은 {@code OrderExpiryPostgresTest} 가 본다.
 * 스케줄러는 import 하지 않는다 — {@code @EnableScheduling}(Features 설정)이 켜져 있어도 스윕이 돌지 않는다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:expiry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS orders",
    "spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    OrderServiceFeaturesConfig.class,
    OrderRepositoryAdapter.class,
    OrderMapperImpl.class,
    OrderExpiryProcessor.class,
    OrderEventAdapter.class,
    JpaOutboxStore.class,
    OrderExpiryH2Test.Config.class
})
public class OrderExpiryH2Test extends OrderExpiryPersistenceContract {

    /** 시계 · 아이디 · 발행기 — 메시징 설정기를 띄우지 않고 outbox 로만 쓰는 발행기를 직접 만든다. */
    @TestConfiguration
    public static class Config {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(2, 2);
        }

        @Bean
        EventPublisher eventPublisher(OutboxStore store, Clock clock) {
            return new EventPublisher(() -> store, new EventEnvelopeCodec(65536), "order", clock);
        }
    }
}
