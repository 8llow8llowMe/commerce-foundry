package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockRestorationEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockRestorationItemEntity;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.mapper.StockReservationMapperImpl;
import com.commercefoundry.domainlayer.product.application.mapper.StockRestorationMapperImpl;
import com.commercefoundry.domainlayer.product.application.port.out.StockReservationRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRestorationRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuRestoredQuantityQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.StockReservation;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationItem;
import com.commercefoundry.domainlayer.product.domain.model.StockRestoration;
import com.commercefoundry.domainlayer.product.domain.model.StockRestorationItem;
import com.commercefoundry.domainlayer.product.domain.model.StockRestorationReason;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
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
 * 결제 후 재고 복원 기록의 저장(Persistable INSERT) · 클레임 유니크 키 · 선점별 복원 수량 합, 그리고 선점 헤더 잠금 조회를 실제 PostgreSQL 에 질의해 본다
 * ({@code coding-conventions.md} §9-5). 스키마는 DDL 스냅숏 + {@code ddl-auto=validate} 다. Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 *
 * <p>잠금이 실제로 줄을 세우는지(같은 선점의 동시 복원)는 실제 Facade 트랜잭션 경계로 {@code StockRestorationConcurrencyTest} 가 본다.
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
@Import({
    QuerydslConfigurer.class, ProductServiceFeaturesConfig.class,
    StockRestorationRepositoryAdapter.class, StockRestorationMapperImpl.class,
    StockReservationRepositoryAdapter.class, StockReservationMapperImpl.class
})
class StockRestorationPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    private static final long RESERVATION_ID = 7001L;
    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private StockRestorationRepositoryPort stockRestorationRepositoryPort;

    @Autowired
    private StockReservationRepositoryPort stockReservationRepositoryPort;

    @Test
    @DisplayName("저장 · 조회: 헤더와 항목이 INSERT(Persistable) 되고, 클레임으로 다시 읽으면 항목이 SKU 오름차순이다")
    void createAndFindByClaimId() {
        stockRestorationRepositoryPort.create(restoration(9001L, 6001L, RESERVATION_ID, new int[] {2, 1}));
        entityManager.clear();

        StockRestoration found = stockRestorationRepositoryPort.findByClaimId(6001L).orElseThrow();
        assertThat(found.id()).isEqualTo(9001L);
        assertThat(found.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(found.orderId()).isEqualTo(8001L);
        assertThat(found.reason()).isEqualTo(StockRestorationReason.CANCELLATION);
        assertThat(found.items()).extracting(StockRestorationItem::skuId).containsExactly(1001L, 1002L);
        assertThat(found.items()).extracting(StockRestorationItem::restored).containsExactly(true, false);
        assertThat(entityManager.find(StockRestorationEntity.class, 9001L).getCreatedAt()).isNotNull();
        assertThat(entityManager.find(StockRestorationItemEntity.class, 90011L).getCreatedAt()).isNotNull();
        assertThat(stockRestorationRepositoryPort.findByClaimId(9999L)).isEmpty();
    }

    @Test
    @DisplayName("클레임 유니크 키: 같은 클레임의 두 번째 복원은 STOCK_RESTORATION_CONFLICT 다")
    void claimIdIsUnique() {
        stockRestorationRepositoryPort.create(restoration(9001L, 6001L, RESERVATION_ID, new int[] {1, 1}));

        assertThatThrownBy(() -> stockRestorationRepositoryPort.create(restoration(9002L, 6001L, RESERVATION_ID, new int[] {1, 1})))
            .isInstanceOf(ProductException.class)
            .extracting(exception -> ((ProductException) exception).getErrorCode())
            .isEqualTo(ProductErrorCode.STOCK_RESTORATION_CONFLICT);
    }

    @Test
    @DisplayName("복원 수량 합: 선점 하나의 복원 기록들을 SKU 별 group by 한 번으로 더한다 — 다른 선점의 복원은 빠지고, restored=false 도 더한다")
    void sumRestoredQuantities() {
        stockRestorationRepositoryPort.create(restoration(9001L, 6001L, RESERVATION_ID, new int[] {2, 1}));
        stockRestorationRepositoryPort.create(restoration(9002L, 6002L, RESERVATION_ID, new int[] {1, 3}));
        stockRestorationRepositoryPort.create(restoration(9003L, 6003L, 7002L, new int[] {5, 5}));
        entityManager.clear();

        assertThat(stockRestorationRepositoryPort.sumRestoredQuantitiesByReservationId(RESERVATION_ID))
            .containsExactlyInAnyOrder(new SkuRestoredQuantityQueryResult(1001L, 3L), new SkuRestoredQuantityQueryResult(1002L, 4L));
        assertThat(stockRestorationRepositoryPort.sumRestoredQuantitiesByReservationId(9999L)).isEmpty();
    }

    @Test
    @DisplayName("선점 잠금 조회: 비관적 쓰기 잠금 JPQL 이 PostgreSQL 에서 돌고, 헤더와 항목을 함께 읽는다. 버전은 그대로다")
    void findReservationForUpdate() {
        StockReservation reservation = StockReservation.reserve(RESERVATION_ID, 8001L, List.of(
            new StockReservationItem(70011L, 1001L, 2, true), new StockReservationItem(70012L, 1002L, 1, false)
        ), NOW.plusSeconds(1800));
        stockReservationRepositoryPort.createHeader(reservation.id(), reservation.orderId(), reservation.expiresAt());
        stockReservationRepositoryPort.createItems(reservation);
        entityManager.clear();

        StockReservation locked = stockReservationRepositoryPort.findByIdForUpdate(RESERVATION_ID).orElseThrow();
        assertThat(locked.items()).extracting(StockReservationItem::skuId).containsExactly(1001L, 1002L);
        assertThat(locked.version()).isZero();
        assertThat(stockReservationRepositoryPort.findByIdForUpdate(9999L)).isEmpty();
    }

    /** 항목 아이디는 복원 아이디 × 10 + 1 · + 2 다. SKU 1001 은 재고를 되돌렸고(restored), 1002 는 무제한이라 기록만 남는다. */
    private static StockRestoration restoration(long restorationId, long claimId, long reservationId, int[] quantities) {
        return new StockRestoration(restorationId, claimId, reservationId, 8001L, StockRestorationReason.CANCELLATION, List.of(
            new StockRestorationItem(restorationId * 10L + 1L, 1001L, quantities[0], true),
            new StockRestorationItem(restorationId * 10L + 2L, 1002L, quantities[1], false)
        ));
    }
}
