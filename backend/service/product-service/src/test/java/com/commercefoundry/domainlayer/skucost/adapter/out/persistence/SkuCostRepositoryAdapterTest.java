package com.commercefoundry.domainlayer.skucost.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostErrorCode;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostException;
import com.commercefoundry.domainlayer.skucost.application.port.out.SkuCostRepositoryPort;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCost;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * SKU 원가 이력 저장 · 조회를 PostgreSQL 에서 실제로 질의한다 — 유니크 위반은 409 · PRODUCT_077, 시점 원가는 유형별 한 행, 이력은 시작 내림차순 · 아이디 내림차순과 건수.
 * 스냅숏 DDL 을 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한다. Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다.
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
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, SkuCostRepositoryAdapter.class})
class SkuCostRepositoryAdapterTest {

    private static final long SKU_ID = 1001L;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private SkuCostRepositoryPort port;

    @Test
    @DisplayName("저장한 행은 기록 시각이 채워져 돌아오고, 다시 읽으면 금액 · 통화 · 시작이 그대로다")
    void insertReturnsRecordedRow() {
        SkuCost saved = port.insert(cost(1L, SKU_ID, SkuCostType.GOODS, 0, "2026-10-01T00:00:00.123456Z"));
        entityManager.clear();

        assertThat(saved.recordedAt()).isNotNull();
        SkuCost reloaded = port.findLatest(SKU_ID, SkuCostType.GOODS).orElseThrow();
        assertThat(reloaded.amount()).isEqualTo(Money.of(0, CurrencyCode.KRW));
        assertThat(reloaded.effectiveFrom()).isEqualTo(Instant.parse("2026-10-01T00:00:00.123456Z"));
        assertThat(reloaded.reason()).isEqualTo("단가 변경");
    }

    @Test
    @DisplayName("같은 SKU · 유형 · 시작 시각이 이미 있으면 409 · PRODUCT_077 이다 — 유형이 다르면 같은 시각이어도 된다")
    void uniqueViolationIsConflict() {
        port.insert(cost(1L, SKU_ID, SkuCostType.GOODS, 10_000, "2026-10-01T00:00:00Z"));
        port.insert(cost(2L, SKU_ID, SkuCostType.PACKAGING, 500, "2026-10-01T00:00:00Z"));

        assertThatThrownBy(() -> port.insert(cost(3L, SKU_ID, SkuCostType.GOODS, 12_000, "2026-10-01T00:00:00Z")))
            .isInstanceOf(SkuCostException.class)
            .extracting(exception -> ((SkuCostException) exception).getErrorCode())
            .isEqualTo(SkuCostErrorCode.COST_CONFLICT);
    }

    @Test
    @DisplayName("최신 행은 시작이 가장 늦은 행이다 — 아이디 순이 아니다(소급 입력)")
    void latestIsByEffectiveFrom() {
        port.insert(cost(10L, SKU_ID, SkuCostType.GOODS, 10_000, "2026-10-01T00:00:00Z"));
        port.insert(cost(5L, SKU_ID, SkuCostType.GOODS, 9_000, "2026-11-01T00:00:00Z"));
        port.insert(cost(20L, SKU_ID, SkuCostType.PACKAGING, 500, "2026-12-01T00:00:00Z"));
        entityManager.clear();

        assertThat(port.findLatest(SKU_ID, SkuCostType.GOODS)).map(SkuCost::id).contains(5L);
        assertThat(port.findLatest(2002L, SkuCostType.GOODS)).isEmpty();
    }

    @Test
    @DisplayName("시점 원가는 유형마다 시작이 그 시각 이하인 가장 늦은 행 하나다 — 시작 전 유형 · 다른 SKU 는 빠진다")
    void currentPicksOneRowPerType() {
        port.insert(cost(1L, SKU_ID, SkuCostType.GOODS, 10_000, "2026-09-01T00:00:00Z"));
        port.insert(cost(2L, SKU_ID, SkuCostType.GOODS, 12_000, "2026-10-01T00:00:00Z"));
        port.insert(cost(3L, SKU_ID, SkuCostType.GOODS, 13_000, "2026-11-01T00:00:00Z"));
        port.insert(cost(4L, SKU_ID, SkuCostType.PACKAGING, 500, "2026-11-01T00:00:00Z"));
        port.insert(cost(5L, 2002L, SkuCostType.GOODS, 1, "2026-09-01T00:00:00Z"));
        entityManager.clear();

        assertThat(port.findCurrent(SKU_ID, Instant.parse("2026-10-15T00:00:00Z"))).extracting(SkuCost::id).containsExactly(2L);
        assertThat(port.findCurrent(SKU_ID, Instant.parse("2026-10-01T00:00:00Z"))).extracting(SkuCost::id).containsExactly(2L);
        assertThat(port.findCurrent(SKU_ID, Instant.parse("2026-11-01T00:00:00Z"))).extracting(SkuCost::id).containsExactlyInAnyOrder(3L, 4L);
        assertThat(port.findCurrent(SKU_ID, Instant.parse("2026-08-01T00:00:00Z"))).isEmpty();
    }

    @Test
    @DisplayName("이력은 시작 내림차순 · 아이디 내림차순이고, 유형을 주면 그 유형만이다. 전체 건수가 맞다")
    void historyPage() {
        port.insert(cost(1L, SKU_ID, SkuCostType.GOODS, 10_000, "2026-09-01T00:00:00Z"));
        port.insert(cost(2L, SKU_ID, SkuCostType.PACKAGING, 500, "2026-10-01T00:00:00Z"));
        port.insert(cost(3L, SKU_ID, SkuCostType.GOODS, 12_000, "2026-10-01T00:00:00Z"));
        port.insert(cost(4L, SKU_ID, SkuCostType.GOODS, 11_000, "2026-08-01T00:00:00Z"));
        port.insert(cost(5L, 2002L, SkuCostType.GOODS, 1, "2026-12-01T00:00:00Z"));
        entityManager.clear();

        Page<SkuCost> first = port.findHistoryPage(SKU_ID, null, 0, 3);
        assertThat(first.getContent()).extracting(SkuCost::id).containsExactly(3L, 2L, 1L);
        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(port.findHistoryPage(SKU_ID, null, 1, 3).getContent()).extracting(SkuCost::id).containsExactly(4L);

        Page<SkuCost> goods = port.findHistoryPage(SKU_ID, SkuCostType.GOODS, 0, 20);
        assertThat(goods.getContent()).extracting(SkuCost::id).containsExactly(3L, 1L, 4L);
        assertThat(goods.getTotalElements()).isEqualTo(3);
    }

    private static SkuCost cost(long id, long skuId, SkuCostType type, long amount, String effectiveFrom) {
        return new SkuCost(id, skuId, type, Money.of(amount, CurrencyCode.KRW), Instant.parse(effectiveFrom), "단가 변경", 7L, null);
    }
}
