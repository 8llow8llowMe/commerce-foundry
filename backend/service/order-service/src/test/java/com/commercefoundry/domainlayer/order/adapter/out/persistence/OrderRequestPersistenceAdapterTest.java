package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.order.application.mapper.OrderRequestMapperImpl;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRequestRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 주문 요청 기록 어댑터를 실제 PostgreSQL 에서 본다. 테스트 트랜잭션을 끈다({@code NOT_SUPPORTED}) — 운영처럼 리포지토리 호출 하나가 한 트랜잭션이어야
 * "중복 넣기가 유니크 키에 걸려 그 트랜잭션이 끝나도 다음 호출(새 트랜잭션)의 조회는 된다" 를 볼 수 있다. 한 트랜잭션 안이면 PostgreSQL 이 이어지는 조회까지 거절한다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=orders",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, OrderServiceFeaturesConfig.class, OrderRequestRepositoryAdapter.class, OrderRequestMapperImpl.class})
class OrderRequestPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/order-schema.sql");

    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");

    @Autowired
    private OrderRequestRepositoryPort orderRequestRepositoryPort;

    private static OrderRequest start(long id, long memberId, UUID key, long orderId) {
        return OrderRequest.start(id, memberId, key, "h".repeat(64), orderId, NOW, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("같은 회원 · 같은 키의 두 번째 넣기는 빈 결과이고, 이어지는 조회(새 트랜잭션)는 처음 기록을 읽는다")
    void duplicateInsertThenRead() {
        UUID key = UUID.randomUUID();
        OrderRequest first = orderRequestRepositoryPort.insertIfAbsent(start(1001L, 7L, key, 5001L)).orElseThrow();

        assertThat(first.version()).isZero();
        assertThat(orderRequestRepositoryPort.insertIfAbsent(start(1002L, 7L, key, 5002L))).isEmpty();
        assertThat(orderRequestRepositoryPort.findByMemberIdAndIdempotencyKey(7L, key)).get().satisfies(read -> {
            assertThat(read.orderId()).isEqualTo(5001L);
            assertThat(read.idempotencyKey()).isEqualTo(key);
        });
        assertThat(orderRequestRepositoryPort.insertIfAbsent(start(1003L, 8L, key, 5003L))).as("다른 회원은 같은 키를 쓸 수 있다").isPresent();
    }

    @Test
    @DisplayName("상태 · lease 갱신은 버전 비교다 — 낡은 버전으로 저장하면 빈 결과다")
    void versionCheck() {
        UUID key = UUID.randomUUID();
        OrderRequest inserted = orderRequestRepositoryPort.insertIfAbsent(start(2001L, 7L, key, 6001L)).orElseThrow();

        OrderRequest ended = orderRequestRepositoryPort.saveIfUnchanged(inserted.endLease(NOW)).orElseThrow();
        assertThat(ended.version()).isEqualTo(1L);
        assertThat(ended.leaseExpiresAt()).isEqualTo(NOW);

        assertThat(orderRequestRepositoryPort.saveIfUnchanged(inserted.complete())).isEmpty();
        assertThat(orderRequestRepositoryPort.findByMemberIdAndIdempotencyKey(7L, key).orElseThrow().status()).isEqualTo(OrderRequest.Status.PROCESSING);
    }
}
