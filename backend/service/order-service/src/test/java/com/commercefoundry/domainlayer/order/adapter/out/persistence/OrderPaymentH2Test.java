package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.mapper.OrderPaymentMapperImpl;
import com.commercefoundry.domainlayer.order.adapter.in.internal.presenter.OrderPaymentResultInternalPresenter;
import com.commercefoundry.domainlayer.order.application.service.OrderPaymentResultInternalFacade;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentResultProcessor;
import com.commercefoundry.domainlayer.order.support.OrderPaymentPersistenceContract;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.global.messaging.JpaProcessedEventStore;
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

/** 결제 반영 기록 · inbox 영속성 계약(#308 PR-B1)을 H2(PostgreSQL 모드)로 돌린다. 제약 이름 판정 · 엔진 의존 동작은 {@code OrderPaymentPostgresTest} 가 본다. */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:order-payment;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS orders",
    "spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
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
public class OrderPaymentH2Test extends OrderPaymentPersistenceContract {

    @Override
    protected boolean reportsConstraintNames() {
        return false;
    }

    @TestConfiguration
    public static class Config {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(3, 3);
        }
    }
}
