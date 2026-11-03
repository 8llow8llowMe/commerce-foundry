package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.mapper.OrderPaymentMapperImpl;
import com.commercefoundry.domainlayer.order.adapter.in.internal.presenter.OrderPaymentResultInternalPresenter;
import com.commercefoundry.domainlayer.order.application.service.OrderPaymentResultInternalFacade;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentResultProcessor;
import com.commercefoundry.domainlayer.order.support.OrderPaymentPersistenceContract;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.global.messaging.JpaProcessedEventStore;
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
 * 결제 반영 기록 · inbox 영속성 계약(#308 PR-B1)을 PostgreSQL 17 로 돌린다 — 운영 DDL 스냅숏을 넣고 {@code ddl-auto=validate} 로 엔티티(uuid · timestamptz 포함)와
 * 맞는지, 유니크 위반의 사유 판정(제약 이름)까지 본다. Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"
})
@Import({
    OrderServiceFeaturesConfig.class,
    OrderRepositoryAdapter.class,
    OrderMapperImpl.class,
    OrderPaymentRepositoryAdapter.class,
    OrderPaymentMapperImpl.class,
    OrderPaymentResultInternalFacade.class,
    OrderPaymentResultProcessor.class,
    OrderPaymentResultInternalPresenter.class,
    JpaProcessedEventStore.class,
    OrderPaymentH2Test.Config.class
})
class OrderPaymentPostgresTest extends OrderPaymentPersistenceContract {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");

    @Override
    protected boolean reportsConstraintNames() {
        return true;
    }
}
