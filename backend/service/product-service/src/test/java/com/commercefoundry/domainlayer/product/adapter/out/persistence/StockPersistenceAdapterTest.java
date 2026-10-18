package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockAdjustmentHistoryEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockRepository;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.mapper.StockAdjustmentHistoryMapperImpl;
import com.commercefoundry.domainlayer.product.application.mapper.StockMapperImpl;
import com.commercefoundry.domainlayer.product.application.port.out.StockAdjustmentHistoryRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.StockQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentHistory;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
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
 * 재고 조건부 UPDATE(차감 · 복원 · 어드민 CAS · 관리 여부)와 첫 등록 · 조정 이력을 실제 PostgreSQL 에 질의해 본다 — JPQL 은 컴파일로 검증되지 않는다
 * ({@code coding-conventions.md} §9-5). 스키마는 {@link ProductPersistenceAdapterTest} 와 같이 DDL 스냅숏 + {@code ddl-auto=validate} 다.
 * Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다 ({@link EnabledWithDockerOrOnCi}).
 *
 * <p>동시 차감(초과 판매 없음)은 3단계 재고 선점의 동시성 테스트가 본다. 여기서는 한 문장의 조건 · 경계값을 본다.
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
    StockRepositoryAdapter.class, StockAdjustmentHistoryRepositoryAdapter.class,
    StockMapperImpl.class, StockAdjustmentHistoryMapperImpl.class
})
class StockPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    private static final long MANAGED = 1001L;
    private static final long UNLIMITED = 1002L;
    private static final long NO_ROW = 1003L;
    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockRepositoryPort stockRepositoryPort;

    @Autowired
    private StockAdjustmentHistoryRepositoryPort stockAdjustmentHistoryRepositoryPort;

    @BeforeEach
    void setUp() {
        entityManager.persist(stock(MANAGED, true, 5L));
        entityManager.persist(stock(UNLIMITED, false, 0L));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("차감: 판매 가능 수량만큼은 차감된다 — 경계값 available = quantity 면 0 이 된다")
    void deductExactlyAvailable() {
        assertThat(stockRepositoryPort.deduct(MANAGED, 5L)).isTrue();

        assertThat(quantityOf(MANAGED)).isZero();
    }

    @Test
    @DisplayName("차감: 판매 가능 수량보다 1 많으면 0행이고 수량은 그대로다 — 마이너스 재고가 생기지 않는다")
    void deductMoreThanAvailable() {
        assertThat(stockRepositoryPort.deduct(MANAGED, 6L)).isFalse();

        assertThat(quantityOf(MANAGED)).isEqualTo(5L);
    }

    @Test
    @DisplayName("차감: 무제한(재고 관리 안 함) SKU 는 0행이고 수량을 건드리지 않는다")
    void deductUnlimited() {
        assertThat(stockRepositoryPort.deduct(UNLIMITED, 1L)).isFalse();

        assertThat(stockRepositoryPort.findBySkuId(UNLIMITED)).get().extracting(StockQueryResult::availableQuantity).isEqualTo(0L);
    }

    @Test
    @DisplayName("차감 · 복원 · CAS · 관리 여부: 재고 행이 없는 SKU 는 모두 0행이다")
    void noRow() {
        assertThat(stockRepositoryPort.deduct(NO_ROW, 1L)).isFalse();
        assertThat(stockRepositoryPort.restore(NO_ROW, 1L)).isFalse();
        assertThat(stockRepositoryPort.updateQuantityIfMatches(NO_ROW, 0L, 3L)).isFalse();
        assertThat(stockRepositoryPort.updateStockManaged(NO_ROW, true)).isFalse();
        assertThat(stockRepositoryPort.findBySkuId(NO_ROW)).isEmpty();
    }

    @Test
    @DisplayName("복원: 차감했던 수량을 더한다")
    void restore() {
        stockRepositoryPort.deduct(MANAGED, 3L);

        assertThat(stockRepositoryPort.restore(MANAGED, 3L)).isTrue();
        assertThat(quantityOf(MANAGED)).isEqualTo(5L);
    }

    @Test
    @DisplayName("CAS: 현재 수량이 기대 수량과 같으면 절대값으로 바꾼다")
    void casMatches() {
        assertThat(stockRepositoryPort.updateQuantityIfMatches(MANAGED, 5L, 12L)).isTrue();

        assertThat(quantityOf(MANAGED)).isEqualTo(12L);
    }

    @Test
    @DisplayName("CAS: 그 사이 차감돼 기대 수량과 다르면 0행이고 차감 결과가 그대로 남는다")
    void casMismatch() {
        stockRepositoryPort.deduct(MANAGED, 1L);

        assertThat(stockRepositoryPort.updateQuantityIfMatches(MANAGED, 5L, 12L)).isFalse();
        assertThat(quantityOf(MANAGED)).isEqualTo(4L);
    }

    @Test
    @DisplayName("관리 여부: 수량은 그대로 두고 관리 여부만 바꾼다")
    void updateStockManaged() {
        assertThat(stockRepositoryPort.updateStockManaged(MANAGED, false)).isTrue();

        StockQueryResult result = stockRepositoryPort.findBySkuId(MANAGED).orElseThrow();
        assertThat(result.stockManaged()).isFalse();
        assertThat(result.availableQuantity()).isEqualTo(5L);
    }

    /** bulk UPDATE 는 감사 리스너를 거치지 않는다 — 쿼리가 직접 세팅한 수정 시각이 저장되는지 본다. 기준 시각을 과거로 돌려 둔 뒤 비교한다. */
    @Test
    @DisplayName("조건부 UPDATE 4종 모두 updated_at 을 현재 시각으로 바꾼다")
    void bulkUpdatesTouchUpdatedAt() {
        assertTouched(() -> stockRepositoryPort.deduct(MANAGED, 1L));
        assertTouched(() -> stockRepositoryPort.restore(MANAGED, 1L));
        assertTouched(() -> stockRepositoryPort.updateQuantityIfMatches(MANAGED, 5L, 7L));
        assertTouched(() -> stockRepositoryPort.updateStockManaged(MANAGED, false));
    }

    @Test
    @DisplayName("첫 등록: 재고 행을 INSERT 하고 감사 시각이 채워진다")
    void create() {
        stockRepositoryPort.create(new Stock(NO_ROW, true, 7L));
        entityManager.clear();

        StockEntity saved = entityManager.find(StockEntity.class, NO_ROW);
        assertThat(saved.isStockManaged()).isTrue();
        assertThat(saved.getAvailableQuantity()).isEqualTo(7L);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("첫 등록: 이미 행이 있으면 덮어쓰지 않고 STOCK_ADJUSTMENT_CONFLICT 다 (PK 충돌)")
    void createDuplicate() {
        assertThatThrownBy(() -> stockRepositoryPort.create(new Stock(MANAGED, true, 99L)))
            .isInstanceOf(ProductException.class)
            .extracting(exception -> ((ProductException) exception).getErrorCode())
            .isEqualTo(ProductErrorCode.STOCK_ADJUSTMENT_CONFLICT);
    }

    @Test
    @DisplayName("조정 이력: 한 행이 그대로 저장되고 생성 시각이 채워진다")
    void appendHistory() {
        stockAdjustmentHistoryRepositoryPort.append(new StockAdjustmentHistory(
            9001L, MANAGED, 5L, 12L, 7L, true, true, StockAdjustmentReason.RESTOCK, "10월 입고분", 4611686018427387904L
        ));
        entityManager.flush();
        entityManager.clear();

        StockAdjustmentHistoryEntity saved = entityManager.find(StockAdjustmentHistoryEntity.class, 9001L);
        assertThat(saved.getSkuId()).isEqualTo(MANAGED);
        assertThat(saved.getBeforeQuantity()).isEqualTo(5L);
        assertThat(saved.getAfterQuantity()).isEqualTo(12L);
        assertThat(saved.getDelta()).isEqualTo(7L);
        assertThat(saved.isBeforeStockManaged()).isTrue();
        assertThat(saved.isAfterStockManaged()).isTrue();
        assertThat(saved.getReason()).isEqualTo(StockAdjustmentReason.RESTOCK);
        assertThat(saved.getMemo()).isEqualTo("10월 입고분");
        assertThat(saved.getAdminMemberId()).isEqualTo(4611686018427387904L);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    private void assertTouched(Runnable update) {
        stockRepository.updateStockManaged(MANAGED, true, LONG_AGO);
        Instant before = Instant.now().minusSeconds(1);

        update.run();

        assertThat(stockRepositoryPort.findBySkuId(MANAGED).orElseThrow().updatedAt()).isAfter(before);
    }

    private long quantityOf(long skuId) {
        return stockRepositoryPort.findBySkuId(skuId).orElseThrow().availableQuantity();
    }

    private static StockEntity stock(long skuId, boolean stockManaged, long availableQuantity) {
        return StockEntity.builder().skuId(skuId).stockManaged(stockManaged).availableQuantity(availableQuantity).build();
    }
}
