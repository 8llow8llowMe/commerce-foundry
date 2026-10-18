package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationItemEntity;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.mapper.StockReservationMapperImpl;
import com.commercefoundry.domainlayer.product.application.port.out.StockReservationRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.StockReleaseReason;
import com.commercefoundry.domainlayer.product.domain.model.StockReservation;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationItem;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 재고 선점 헤더 · 항목의 저장 · merge(버전 검사) · 유니크 키 · 만료 대상 조회를 실제 PostgreSQL 에 질의해 본다 ({@code coding-conventions.md} §9-5).
 * 스키마는 DDL 스냅숏 + {@code ddl-auto=validate} 다. Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 *
 * <p>동시 선점 · 해제(초과 판매 · 교착 · 이중 복원 없음)는 실제 Facade 트랜잭션 경계로 {@code StockReservationConcurrencyTest} 가 본다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=product",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, StockReservationRepositoryAdapter.class, StockReservationMapperImpl.class})
class StockReservationPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    private static final long ORDER_ID = 8001L;
    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private StockReservationRepositoryPort stockReservationRepositoryPort;

    @Test
    @DisplayName("저장: 헤더(버전 0)와 항목이 INSERT 되고, 항목은 SKU 오름차순으로 다시 읽힌다")
    void createAndRead() {
        stockReservationRepositoryPort.create(reserved(7001L, ORDER_ID, NOW.plusSeconds(1800)));
        entityManager.clear();

        StockReservation found = stockReservationRepositoryPort.findById(7001L).orElseThrow();
        assertThat(found.status()).isEqualTo(StockReservationStatus.RESERVED);
        assertThat(found.version()).isZero();
        assertThat(found.expiresAt()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(found.items()).extracting(StockReservationItem::skuId).containsExactly(1001L, 1002L);
        assertThat(found.items()).extracting(StockReservationItem::deducted).containsExactly(true, false);

        StockReservationEntity header = entityManager.find(StockReservationEntity.class, 7001L);
        assertThat(header.getActiveOrderId()).isEqualTo(ORDER_ID);
        assertThat(header.getCreatedAt()).isNotNull();
        assertThat(entityManager.find(StockReservationItemEntity.class, 70011L).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("갱신: merge 가 상태 · 활성 주문 아이디를 바꾸고 버전을 올린다. created_at 은 그대로다")
    void updateMerges() {
        stockReservationRepositoryPort.create(reserved(7001L, ORDER_ID, NOW.plusSeconds(1800)));
        entityManager.clear();
        Instant createdAt = entityManager.find(StockReservationEntity.class, 7001L).getCreatedAt();
        entityManager.clear();

        StockReservation released = stockReservationRepositoryPort.findById(7001L).orElseThrow()
            .release(NOW, StockReleaseReason.RELEASED_BY_CALLER).reservation();
        stockReservationRepositoryPort.update(released);
        entityManager.clear();

        StockReservationEntity saved = entityManager.find(StockReservationEntity.class, 7001L);
        assertThat(saved.getStatus()).isEqualTo(StockReservationStatus.RELEASED);
        assertThat(saved.getReleaseReason()).isEqualTo(StockReleaseReason.RELEASED_BY_CALLER);
        assertThat(saved.getActiveOrderId()).isNull();
        assertThat(saved.getVersion()).isEqualTo(1L);
        assertThat(saved.getCreatedAt()).isEqualTo(createdAt);
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("갱신: 읽은 뒤 다른 쪽이 먼저 바꿨으면(낡은 버전) STOCK_RESERVATION_CONFLICT 다")
    void staleVersionFails() {
        stockReservationRepositoryPort.create(reserved(7001L, ORDER_ID, NOW.plusSeconds(1800)));
        entityManager.clear();
        StockReservation stale = stockReservationRepositoryPort.findById(7001L).orElseThrow();
        stockReservationRepositoryPort.update(stale.confirm(NOW).reservation());
        entityManager.clear();

        assertThatThrownBy(() -> stockReservationRepositoryPort.update(stale.release(NOW, StockReleaseReason.EXPIRED).reservation()))
            .isInstanceOf(ProductException.class)
            .extracting(exception -> ((ProductException) exception).getErrorCode())
            .isEqualTo(ProductErrorCode.STOCK_RESERVATION_CONFLICT);
    }

    @Test
    @DisplayName("활성 선점: 같은 주문의 두 번째 활성 선점은 유니크 키 위반으로 STOCK_RESERVATION_CONFLICT 다")
    void activeOrderIdIsUnique() {
        stockReservationRepositoryPort.create(reserved(7001L, ORDER_ID, NOW.plusSeconds(1800)));

        assertThatThrownBy(() -> stockReservationRepositoryPort.create(reserved(7002L, ORDER_ID, NOW.plusSeconds(1800))))
            .isInstanceOf(ProductException.class)
            .extracting(exception -> ((ProductException) exception).getErrorCode())
            .isEqualTo(ProductErrorCode.STOCK_RESERVATION_CONFLICT);
    }

    @Test
    @DisplayName("활성 선점: 해제된 주문은 다시 선점할 수 있고, 활성 조회는 새 선점만 찾는다")
    void releasedOrderCanReserveAgain() {
        stockReservationRepositoryPort.create(reserved(7001L, ORDER_ID, NOW.plusSeconds(1800)));
        entityManager.clear();
        stockReservationRepositoryPort.update(stockReservationRepositoryPort.findById(7001L).orElseThrow()
            .release(NOW, StockReleaseReason.EXPIRED).reservation());
        entityManager.clear();

        stockReservationRepositoryPort.create(reserved(7002L, ORDER_ID, NOW.plusSeconds(3600)));
        entityManager.clear();

        assertThat(stockReservationRepositoryPort.findActiveByOrderId(ORDER_ID)).get().extracting(StockReservation::id).isEqualTo(7002L);
        assertThat(stockReservationRepositoryPort.findActiveByOrderId(9999L)).isEmpty();
    }

    @Test
    @DisplayName("만료 대상: RESERVED 이고 만료 시각이 now 이전 · 같은 것만, 만료 시각 → 아이디 순으로 limit 건까지 읽는다")
    void findExpired() {
        stockReservationRepositoryPort.create(reserved(7001L, 1L, NOW.minusSeconds(10)));
        stockReservationRepositoryPort.create(reserved(7002L, 2L, NOW.minusSeconds(30)));
        stockReservationRepositoryPort.create(reserved(7003L, 3L, NOW));
        stockReservationRepositoryPort.create(reserved(7004L, 4L, NOW.plusMillis(1)));
        stockReservationRepositoryPort.create(reserved(7000L, 5L, NOW.minusSeconds(10)));
        stockReservationRepositoryPort.create(reserved(7005L, 6L, NOW.minus(1, ChronoUnit.HOURS)));
        stockReservationRepositoryPort.create(reserved(7006L, 7L, NOW.minus(2, ChronoUnit.HOURS)));
        entityManager.clear();
        settle(7005L, true);
        settle(7006L, false);

        assertThat(stockReservationRepositoryPort.findExpiredReservationIds(NOW, 10)).containsExactly(7002L, 7000L, 7001L, 7003L);
        assertThat(stockReservationRepositoryPort.findExpiredReservationIds(NOW, 2)).containsExactly(7002L, 7000L);
    }

    @Test
    @DisplayName("없는 선점은 빈 값이다")
    void notFound() {
        assertThat(stockReservationRepositoryPort.findById(1L)).isEmpty();
    }

    /** 확정 또는 해제해 만료 대상에서 뺀다. */
    private void settle(long reservationId, boolean confirm) {
        StockReservation reservation = stockReservationRepositoryPort.findById(reservationId).orElseThrow();
        stockReservationRepositoryPort.update(confirm
            ? reservation.confirm(NOW).reservation()
            : reservation.release(NOW, StockReleaseReason.RELEASED_BY_CALLER).reservation());
        entityManager.clear();
    }

    /** 항목 아이디는 선점 아이디마다 겹치지 않게 선점 아이디 × 10 + 1 · + 2 다. */
    private static StockReservation reserved(long reservationId, long orderId, Instant expiresAt) {
        long itemBase = reservationId * 10L;
        return StockReservation.reserve(reservationId, orderId, List.of(
            new StockReservationItem(itemBase + 1L, 1001L, 2, true),
            new StockReservationItem(itemBase + 2L, 1002L, 1, false)
        ), expiresAt);
    }
}
