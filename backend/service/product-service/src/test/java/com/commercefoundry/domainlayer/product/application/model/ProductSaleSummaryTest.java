package com.commercefoundry.domainlayer.product.application.model;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuStocks;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProductSaleSummaryTest {

    private static final Money BASE = Money.of(39_000, KRW);
    private static final Money LIST = Money.of(45_000, KRW);

    @Nested
    @DisplayName("of — 판매중 SKU 집계로 계산한다")
    class Of {

        @Test
        @DisplayName("판매중 SKU 가 없으면(0건 포함) 판매 종료 · 기본 판매가 · 옵션 가격 차이 없음이다")
        void noOnSaleSku() {
            ProductSaleSummary summary = ProductSaleSummary.of(SaleStatus.ON_SALE, BASE, LIST, false, false, null, null);

            assertThat(summary.saleStatus()).isEqualTo(SaleAvailability.STOP_SELLING);
            assertThat(summary.salePrice()).isEqualTo(BASE);
            assertThat(summary.hasOptionSurcharge()).isFalse();
            // (45000 − 39000) × 100 / 45000 = 13.3 → 13
            assertThat(summary.discountRate()).isEqualTo(13);
        }

        @Test
        @DisplayName("표시 판매가는 기본 판매가 + 최소 추가금이고, 할인율은 표시 판매가 기준이며, 최소 · 최대가 다르면 옵션 가격 차이가 있다")
        void minMaxDiffer() {
            ProductSaleSummary summary = ProductSaleSummary.of(SaleStatus.ON_SALE, BASE, LIST, true, true, 1_000L, 5_000L);

            assertThat(summary.saleStatus()).isEqualTo(SaleAvailability.ON_SALE);
            assertThat(summary.salePrice()).isEqualTo(Money.of(40_000, KRW));
            assertThat(summary.hasOptionSurcharge()).isTrue();
            // (45000 − 40000) × 100 / 45000 = 11.1 → 11
            assertThat(summary.discountRate()).isEqualTo(11);
            assertThat(summary.listPrice()).isEqualTo(LIST);
        }

        @Test
        @DisplayName("최소 · 최대 추가금이 같으면 옵션 가격 차이가 없다")
        void minMaxSame() {
            assertThat(ProductSaleSummary.of(SaleStatus.ON_SALE, BASE, LIST, true, true, 0L, 0L).hasOptionSurcharge()).isFalse();
        }

        @Test
        @DisplayName("정가가 없으면 할인율도 null 이다")
        void noListPrice() {
            ProductSaleSummary summary = ProductSaleSummary.of(SaleStatus.ON_SALE, BASE, null, true, true, 0L, 0L);

            assertThat(summary.listPrice()).isNull();
            assertThat(summary.discountRate()).isNull();
        }

        @Test
        @DisplayName("판매중 SKU 는 있지만 구매 가능 SKU 가 없으면 품절이고, 가격은 그대로 계산한다")
        void soldOut() {
            ProductSaleSummary summary = ProductSaleSummary.of(SaleStatus.ON_SALE, BASE, LIST, true, false, 1_000L, 5_000L);

            assertThat(summary.saleStatus()).isEqualTo(SaleAvailability.SOLD_OUT);
            assertThat(summary.salePrice()).isEqualTo(Money.of(40_000, KRW));
            assertThat(summary.hasOptionSurcharge()).isTrue();
        }

        @Test
        @DisplayName("상품이 판매 종료면 판매중 SKU 가 있어도 판매 종료다")
        void productStopSelling() {
            assertThat(ProductSaleSummary.of(SaleStatus.STOP_SELLING, BASE, LIST, true, true, 0L, 0L).saleStatus()).isEqualTo(SaleAvailability.STOP_SELLING);
        }

        /** FE 는 listPrice 가 null 이 아니면 취소선을 그린다 — 할인이 아닌 정가는 할인율과 함께 null 로 내린다. */
        @ParameterizedTest(name = "기본가 {0} · 정가 {1} · 최소 추가금 {2}")
        @CsvSource({
            // 정가 = 표시 판매가
            "39000, 39000, 0",
            // 기본가 < 정가 < 기본가 + 최소 추가금 — 표시 판매가(44000)가 정가보다 비싸다
            "39000, 42000, 5000",
            // (10000 − 9950) × 100 / 10000 = 0.5 → 0, 1% 미만 할인
            "9950, 10000, 0",
            // 정가 0
            "0, 0, 0"
        })
        @DisplayName("할인으로 보이지 않는 정가는 할인율과 함께 null 이다 — 두 필드는 함께 null 이거나 함께 값이 있다")
        void listPriceNullWithDiscountRate(long base, long list, long minAdditional) {
            ProductSaleSummary summary = ProductSaleSummary.of(SaleStatus.ON_SALE, Money.of(base, KRW), Money.of(list, KRW), true, true, minAdditional, minAdditional);

            assertThat(summary.discountRate()).isNull();
            assertThat(summary.listPrice()).isNull();
        }

        @Test
        @DisplayName("1% 이상 할인이면 정가와 할인율이 함께 있다")
        void listPriceWithDiscountRate() {
            // (10000 − 9900) × 100 / 10000 = 1
            ProductSaleSummary summary = ProductSaleSummary.of(SaleStatus.ON_SALE, Money.of(9_900, KRW), Money.of(10_000, KRW), true, true, 0L, 0L);

            assertThat(summary.discountRate()).isEqualTo(1);
            assertThat(summary.listPrice()).isEqualTo(Money.of(10_000, KRW));
        }
    }

    @Nested
    @DisplayName("ofSkus — 메모리의 SKU · 재고로 같은 규칙을 탄다")
    class OfSkus {

        @Test
        @DisplayName("판매 종료 SKU 의 추가금은 최소 · 최대에 섞지 않는다")
        void ignoresStopSellingSkus() {
            ProductSaleSummary summary = ProductSaleSummary.ofSkus(SaleStatus.ON_SALE, BASE, LIST, List.of(
                ProductFixtures.sku(1, 7, "", 0, SaleStatus.STOP_SELLING),
                ProductFixtures.sku(2, 7, "", 2_000, SaleStatus.ON_SALE),
                ProductFixtures.sku(3, 7, "", 2_000, SaleStatus.ON_SALE)
            ), ProductFixtures.inStock(1, 2, 3));

            assertThat(summary).isEqualTo(ProductSaleSummary.of(SaleStatus.ON_SALE, BASE, LIST, true, true, 2_000L, 2_000L));
            assertThat(summary.salePrice()).isEqualTo(Money.of(41_000, KRW));
            assertThat(summary.hasOptionSurcharge()).isFalse();
        }

        @Test
        @DisplayName("SKU 가 0건이면 판매 종료 · 기본 판매가다")
        void noSku() {
            assertThat(ProductSaleSummary.ofSkus(SaleStatus.ON_SALE, BASE, LIST, List.of(), ProductFixtures.inStock()))
                .isEqualTo(ProductSaleSummary.of(SaleStatus.ON_SALE, BASE, LIST, false, false, null, null));
        }

        @Test
        @DisplayName("SKU 가 있어도 모두 판매 종료면 판매 종료 · 기본 판매가다 — 판매 종료 SKU 의 추가금은 더하지 않는다")
        void allSkusStopSelling() {
            ProductSaleSummary summary = ProductSaleSummary.ofSkus(SaleStatus.ON_SALE, BASE, LIST, List.of(
                ProductFixtures.sku(1, 7, "", 3_000, SaleStatus.STOP_SELLING),
                ProductFixtures.sku(2, 7, "", 5_000, SaleStatus.STOP_SELLING)
            ), ProductFixtures.inStock(1, 2));

            assertThat(summary.saleStatus()).isEqualTo(SaleAvailability.STOP_SELLING);
            assertThat(summary.salePrice()).isEqualTo(BASE);
            assertThat(summary.hasOptionSurcharge()).isFalse();
            assertThat(summary).isEqualTo(ProductSaleSummary.of(SaleStatus.ON_SALE, BASE, LIST, false, false, null, null));
        }

        @Test
        @DisplayName("판매중 SKU 끼리 추가금이 다르면 최소 추가금을 더하고 옵션 가격 차이가 있다")
        void minMaxFromOnSaleSkus() {
            ProductSaleSummary summary = ProductSaleSummary.ofSkus(SaleStatus.ON_SALE, BASE, LIST, List.of(
                ProductFixtures.sku(1, 7, "", 5_000, SaleStatus.ON_SALE),
                ProductFixtures.sku(2, 7, "", 1_000, SaleStatus.ON_SALE)
            ), ProductFixtures.inStock(1, 2));

            assertThat(summary).isEqualTo(ProductSaleSummary.of(SaleStatus.ON_SALE, BASE, LIST, true, true, 1_000L, 5_000L));
        }

        @Test
        @DisplayName("판매중 SKU 가 모두 수량 0 이거나 재고 행이 없으면 품절이다 — 품절 SKU 의 추가금도 표시 판매가에 넣는다 (목록과 같다)")
        void soldOutWhenNoPurchasableSku() {
            ProductSaleSummary summary = ProductSaleSummary.ofSkus(SaleStatus.ON_SALE, BASE, LIST, List.of(
                ProductFixtures.sku(1, 7, "", 1_000, SaleStatus.ON_SALE),
                ProductFixtures.sku(2, 7, "", 3_000, SaleStatus.ON_SALE)
            ), SkuStocks.of(List.of(new Stock(1, true, 0))));

            assertThat(summary.saleStatus()).isEqualTo(SaleAvailability.SOLD_OUT);
            assertThat(summary).isEqualTo(ProductSaleSummary.of(SaleStatus.ON_SALE, BASE, LIST, true, false, 1_000L, 3_000L));
        }

        @Test
        @DisplayName("재고가 있는 SKU 가 판매 종료뿐이면 구매 가능 SKU 가 아니다 — 판매중 SKU 가 품절이라 품절이다")
        void stockOfStopSellingSkuDoesNotCount() {
            ProductSaleSummary summary = ProductSaleSummary.ofSkus(SaleStatus.ON_SALE, BASE, LIST, List.of(
                ProductFixtures.sku(1, 7, "", 0, SaleStatus.ON_SALE),
                ProductFixtures.sku(2, 7, "", 0, SaleStatus.STOP_SELLING)
            ), SkuStocks.of(List.of(new Stock(1, true, 0), new Stock(2, true, 5))));

            assertThat(summary.saleStatus()).isEqualTo(SaleAvailability.SOLD_OUT);
        }

        @Test
        @DisplayName("판매중 SKU 하나라도 무제한이거나 수량이 있으면 판매중이다")
        void onSaleWhenAnyPurchasable() {
            List<Sku> skus = List.of(ProductFixtures.sku(1, 7, "", 0, SaleStatus.ON_SALE), ProductFixtures.sku(2, 7, "", 0, SaleStatus.ON_SALE));

            assertThat(ProductSaleSummary.ofSkus(SaleStatus.ON_SALE, BASE, LIST, skus, SkuStocks.of(List.of(new Stock(1, true, 0), new Stock(2, false, 0))))
                .saleStatus()).isEqualTo(SaleAvailability.ON_SALE);
            assertThat(ProductSaleSummary.ofSkus(SaleStatus.ON_SALE, BASE, LIST, skus, SkuStocks.of(List.of(new Stock(2, true, 1))))
                .saleStatus()).isEqualTo(SaleAvailability.ON_SALE);
        }
    }
}
