package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.support.ClaimCancellationPersistenceContract;
import com.commercefoundry.domainlayer.order.support.OrderReturnTestConfig;
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

/** 커밋된 스냅숏({@code ddl/order-schema.sql})을 적용한 PostgreSQL 에서 엔티티 검증({@code validate}) 뒤 같은 계약을 돌린다. */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"
})
@Import(OrderReturnTestConfig.class)
class ClaimCancellationPostgresTest extends ClaimCancellationPersistenceContract {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");
}
