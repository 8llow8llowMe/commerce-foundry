package com.commercefoundry.domainlayer.product.domain.model;

import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.ON_SALE;
import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.STOP_SELLING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class SaleAvailabilityTest {

    private static final StockSnapshot UNLIMITED = StockSnapshot.unlimited();
    private static final StockSnapshot IN_STOCK = new StockSnapshot(true, 5);
    private static final StockSnapshot EMPTY = new StockSnapshot(true, 0);

    static Stream<Arguments> skuCases() {
        return Stream.of(
            Arguments.of(ON_SALE, ON_SALE, UNLIMITED, SaleAvailability.ON_SALE),
            Arguments.of(ON_SALE, ON_SALE, IN_STOCK, SaleAvailability.ON_SALE),
            Arguments.of(ON_SALE, ON_SALE, EMPTY, SaleAvailability.SOLD_OUT),
            // 재고 행이 없는 SKU 는 품절이다
            Arguments.of(ON_SALE, ON_SALE, StockSnapshot.empty(), SaleAvailability.SOLD_OUT),
            Arguments.of(STOP_SELLING, ON_SALE, UNLIMITED, SaleAvailability.STOP_SELLING),
            Arguments.of(ON_SALE, STOP_SELLING, UNLIMITED, SaleAvailability.STOP_SELLING),
            Arguments.of(STOP_SELLING, STOP_SELLING, IN_STOCK, SaleAvailability.STOP_SELLING),
            // 판매 종료가 품절보다 앞선다
            Arguments.of(STOP_SELLING, ON_SALE, EMPTY, SaleAvailability.STOP_SELLING),
            Arguments.of(ON_SALE, STOP_SELLING, EMPTY, SaleAvailability.STOP_SELLING)
        );
    }

    @ParameterizedTest(name = "상품 {0} · SKU {1} · 재고 {2} → {3}")
    @MethodSource("skuCases")
    @DisplayName("SKU 판정: 판매 종료 → 품절 → 판매중 순으로 본다")
    void ofSku(SaleStatus productStatus, SaleStatus skuStatus, StockSnapshot stock, SaleAvailability expected) {
        assertThat(SaleAvailability.ofSku(productStatus, skuStatus, stock)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "상품 {0} · 판매중 SKU {1} · 구매 가능 SKU {2} → {3}")
    @CsvSource({
        "ON_SALE, true, true, ON_SALE",
        // 판매중 SKU 가 모두 재고 없음
        "ON_SALE, true, false, SOLD_OUT",
        // 판매중 SKU 없음 (SKU 0건 포함) — 품절이 아니라 판매 종료다
        "ON_SALE, false, false, STOP_SELLING",
        // 판매중 SKU 가 없으면 구매 가능 여부는 보지 않는다
        "ON_SALE, false, true, STOP_SELLING",
        "STOP_SELLING, true, true, STOP_SELLING",
        "STOP_SELLING, true, false, STOP_SELLING",
        "STOP_SELLING, false, false, STOP_SELLING"
    })
    @DisplayName("상품 판정: 상품 판매 종료 → 판매중 SKU 없음(판매 종료) → 구매 가능 SKU 있음(판매중) → 그 밖(품절) 순으로 본다")
    void ofProduct(SaleStatus productStatus, boolean hasOnSaleSku, boolean hasPurchasableSku, SaleAvailability expected) {
        assertThat(SaleAvailability.ofProduct(productStatus, hasOnSaleSku, hasPurchasableSku)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "상품 {0} · 판매중 SKU {1}")
    @CsvSource({"ON_SALE, true", "ON_SALE, false", "STOP_SELLING, true", "STOP_SELLING, false"})
    @DisplayName("판매중 SKU 가 모두 구매 가능하면(구매 가능 SKU 유무 = 판매중 SKU 유무) 품절이 나오지 않는다")
    void noSoldOutWhenEveryOnSaleSkuIsPurchasable(SaleStatus productStatus, boolean hasOnSaleSku) {
        assertThat(SaleAvailability.ofProduct(productStatus, hasOnSaleSku, hasOnSaleSku)).isNotEqualTo(SaleAvailability.SOLD_OUT);
    }

    @Test
    @DisplayName("응답 metadata 는 {code, name, description} 이다")
    void metadata() {
        assertThat(SaleAvailability.SOLD_OUT.toMetadata())
            .isEqualTo(CodeNameDescriptionMetadata.of("SOLD_OUT", "품절", "재고가 모두 소진되었습니다."));
        assertThat(SaleAvailability.STOP_SELLING.toMetadata().name()).isEqualTo("판매 종료");
    }

    @Test
    @DisplayName("재고 상태: 무제한은 품절이 아니고, 관리 중 수량 0 만 품절이다")
    void stockSnapshot() {
        assertThat(UNLIMITED.isSoldOut()).isFalse();
        assertThat(IN_STOCK.isSoldOut()).isFalse();
        assertThat(EMPTY.isSoldOut()).isTrue();
        assertThat(new StockSnapshot(false, 0).isSoldOut()).isFalse();
        assertThatThrownBy(() -> new StockSnapshot(true, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "노출 {0} · 상품 {1} · SKU {2} → 판매 가능 조건 {3}")
    @CsvSource({
        "true,  ON_SALE,      ON_SALE,      true",
        "false, ON_SALE,      ON_SALE,      false",
        "true,  STOP_SELLING, ON_SALE,      false",
        "true,  ON_SALE,      STOP_SELLING, false"
    })
    @DisplayName("재고를 보기 전의 판매 가능 조건(재고 선점이 쓴다) — 노출 · 상품 판매중 · SKU 판매중")
    void isOpenForSale(boolean visible, SaleStatus productStatus, SaleStatus skuStatus, boolean expected) {
        Product product = ProductFixtures.product(1L, productStatus, visible, false);
        Sku sku = ProductFixtures.sku(2L, 1L, "", 0L, skuStatus);

        assertThat(SaleAvailability.isOpenForSale(product, sku)).isEqualTo(expected);
    }

    @Test
    @DisplayName("구매 가능(서비스 간 SKU 조회의 purchasable) = 판매 가능 조건 && 품절 아님 — 무제한은 수량 0 이어도 구매 가능")
    void isPurchasable() {
        Product product = ProductFixtures.product(1L);
        Sku sku = ProductFixtures.sku(2L, 1L, "", 0L, ON_SALE);

        assertThat(SaleAvailability.isPurchasable(product, sku, IN_STOCK)).isTrue();
        assertThat(SaleAvailability.isPurchasable(product, sku, UNLIMITED)).isTrue();
        assertThat(SaleAvailability.isPurchasable(product, sku, EMPTY)).isFalse();
        assertThat(SaleAvailability.isPurchasable(ProductFixtures.product(1L, ON_SALE, false, false), sku, IN_STOCK)).isFalse();
    }
}
