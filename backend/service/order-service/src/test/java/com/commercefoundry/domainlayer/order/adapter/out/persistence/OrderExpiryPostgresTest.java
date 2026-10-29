package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.adapter.out.event.OrderEventAdapter;
import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderExpiryProcessor;
import com.commercefoundry.domainlayer.order.support.OrderExpiryPersistenceContract;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.global.messaging.JpaOutboxStore;
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

/**
 * 미결제 만료 영속성 계약을 PostgreSQL 17 로 돌린다 — 운영 DDL 스냅숏({@code ddl/order-schema.sql})을 넣고 {@code ddl-auto=validate} 로 엔티티와 맞는지도 본다.
 * 키셋 쿼리의 파라미터 타입 추론 · 동시 트랜잭션의 버전 충돌 · {@code timestamptz} 처럼 H2 가 대신 보지 못하는 동작이 여기서 드러난다. Docker 가 없으면 로컬에서는 건너뛴다.
 */
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
    OrderExpiryProcessor.class,
    OrderEventAdapter.class,
    JpaOutboxStore.class,
    OrderExpiryH2Test.Config.class
})
class OrderExpiryPostgresTest extends OrderExpiryPersistenceContract {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");
}
