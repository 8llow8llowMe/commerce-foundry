package com.commercefoundry.domainlayer.product.domain.model;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProductPricingTest {

    @Nested
    @DisplayName("skuSalePrice")
    class SkuSalePrice {

        @Test
        @DisplayName("기본 판매가 + 옵션 추가금이다")
        void basePlusAdditional() {
            assertThat(ProductPricing.skuSalePrice(Money.of(39_000, KRW), Money.of(5_000, KRW))).isEqualTo(Money.of(44_000, KRW));
            assertThat(ProductPricing.skuSalePrice(Money.of(8_950, USD), Money.zero(USD))).isEqualTo(Money.of(8_950, USD));
        }

        @Test
        @DisplayName("통화가 다르면 계산하지 않는다")
        void currencyMismatch() {
            assertThatThrownBy(() -> ProductPricing.skuSalePrice(Money.of(39_000, KRW), Money.of(500, USD)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("discountRate")
    class DiscountRate {

        @ParameterizedTest(name = "정가 {0} · 판매가 {1} → {2}%")
        @CsvSource({
            "10000, 7000, 30",
            // 33.33 → 33
            "30000, 20001, 33",
            // 66.66 → 66 (반올림하면 67)
            "3, 1, 66",
            // 0.99 → 0
            "10000, 9901, 0",
            // 판매가 0 은 100%
            "10000, 0, 100"
        })
        @DisplayName("(정가 − 판매가) × 100 / 정가 를 정수로 버린다")
        void truncates(long list, long sale, int expected) {
            assertThat(ProductPricing.discountRate(Money.of(list, KRW), Money.of(sale, KRW))).isEqualTo(expected);
        }

        @Test
        @DisplayName("USD 도 최소 단위(센트)로 같은 계산을 한다")
        void usd() {
            // (8950 − 8000) × 100 / 8950 = 10.61 → 10
            assertThat(ProductPricing.discountRate(Money.of(8_950, USD), Money.of(8_000, USD))).isEqualTo(10);
        }

        @Test
        @DisplayName("정가가 없으면 null 이다")
        void noListPrice() {
            assertThat(ProductPricing.discountRate(null, Money.of(10_000, KRW))).isNull();
        }

        @ParameterizedTest(name = "정가 {0} · 판매가 {1}")
        @CsvSource({"10000, 10000", "10000, 12000", "0, 0"})
        @DisplayName("정가가 판매가 이하면 null 이다")
        void listNotGreaterThanSale(long list, long sale) {
            assertThat(ProductPricing.discountRate(Money.of(list, KRW), Money.of(sale, KRW))).isNull();
        }

        @Test
        @DisplayName("통화가 다르면 null 이다")
        void currencyMismatch() {
            assertThat(ProductPricing.discountRate(Money.of(10_000, USD), Money.of(7_000, KRW))).isNull();
        }

        @Test
        @DisplayName("판매가는 필수다")
        void saleRequired() {
            assertThatThrownBy(() -> ProductPricing.discountRate(Money.of(10_000, KRW), null)).isInstanceOf(NullPointerException.class);
        }
    }
}
