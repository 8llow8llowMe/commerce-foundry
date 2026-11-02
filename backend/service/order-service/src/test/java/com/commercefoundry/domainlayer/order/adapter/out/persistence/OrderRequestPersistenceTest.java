package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.mapper.OrderRequestMapperImpl;
import com.commercefoundry.domainlayer.order.support.OrderCreatePersistenceContract;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderProcessor;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.global.properties.OrderProperties;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.config.SnowflakeConfigurer;
import com.commercefoundry.persistence.config.SnowflakePropertiesConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=orders",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "snowflake.datacenter-id=1", "snowflake.worker-id=2"})
@Import({QuerydslConfigurer.class, OrderServiceFeaturesConfig.class, SnowflakePropertiesConfig.class, SnowflakeConfigurer.class, OrderRepositoryAdapter.class,
    OrderRequestRepositoryAdapter.class, OrderMapperImpl.class, OrderRequestMapperImpl.class, OrderProcessor.class,
    OrderRequestPersistenceTest.Properties.class})
class OrderRequestPersistenceTest extends OrderCreatePersistenceContract {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");
    @Override protected boolean postgres() { return true; }

    /** 주문 처리기의 수량 창(#436)이 쓰는 설정 — 기본값으로 묶는다. */
    @TestConfiguration
    @EnableConfigurationProperties(OrderProperties.class)
    static class Properties {
    }
}
