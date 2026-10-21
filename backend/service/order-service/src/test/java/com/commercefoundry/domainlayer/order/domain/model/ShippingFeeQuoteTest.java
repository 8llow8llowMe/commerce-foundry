package com.commercefoundry.domainlayer.order.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ShippingFeeQuoteTest {

    private static Money krw(long amount) {
        return Money.of(amount, CurrencyCode.KRW);
    }

    private static Money usd(long amount) {
        return Money.of(amount, CurrencyCode.USD);
    }

    private static ShippingFeeQuote domestic(Integer totalWeightGrams) {
        return new ShippingFeeQuote(krw(3_000), krw(3_000), false, krw(0), null, krw(50_000), 3L, ShippingScope.DOMESTIC, null, null, totalWeightGrams);
    }

    private static ShippingFeeQuote international(Integer totalWeightGrams) {
        return new ShippingFeeQuote(usd(4_500), usd(1_500), false, usd(0), null, null, 7L, ShippingScope.INTERNATIONAL, "북미", usd(3_000), totalWeightGrams);
    }

    @Test
    @DisplayName("국내 견적은 총 무게가 없고, 해외 견적은 1 이상의 총 무게가 있다")
    void totalWeightByScope() {
        assertThat(domestic(null).totalWeightGrams()).isNull();
        assertThat(international(1).totalWeightGrams()).isEqualTo(1);
        assertThat(international(Integer.MAX_VALUE).totalWeightGrams()).isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> domestic(500)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "totalWeightGrams={0}")
    @NullSource
    @ValueSource(ints = {0, -1})
    @DisplayName("해외 견적에 총 무게가 없거나 1 미만이면 만들지 않는다")
    void internationalRequiresWeight(Integer totalWeightGrams) {
        assertThatThrownBy(() -> international(totalWeightGrams)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("금액 통화가 섞이면 만들지 않는다")
    void currencyMixed() {
        assertThatThrownBy(() -> new ShippingFeeQuote(krw(3_000), usd(3_000), false, krw(0), null, null, 3L, ShippingScope.DOMESTIC, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShippingFeeQuote(usd(4_500), usd(1_500), false, usd(0), null, krw(1), 7L, ShippingScope.INTERNATIONAL, "북미", usd(3_000), 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("국내 배송비 = (무료배송이면 0, 아니면 기본 배송비) + 도서산간 추가 배송비 — 도서산간은 무료배송이어도 받는다")
    void domesticFormula() {
        assertThat(new ShippingFeeQuote(krw(6_000), krw(3_000), false, krw(3_000), "제주", krw(50_000), 3L, ShippingScope.DOMESTIC, null, null, null)
            .shippingFee()).isEqualTo(krw(6_000));
        assertThat(new ShippingFeeQuote(krw(3_000), krw(3_000), true, krw(3_000), "제주", krw(50_000), 3L, ShippingScope.DOMESTIC, null, null, null)
            .shippingFee()).isEqualTo(krw(3_000));
        assertThat(new ShippingFeeQuote(krw(0), krw(3_000), true, krw(0), null, krw(50_000), 3L, ShippingScope.DOMESTIC, null, null, null)
            .shippingFee()).isEqualTo(krw(0));

        assertThatThrownBy(() -> new ShippingFeeQuote(krw(3_000), krw(3_000), false, krw(3_000), "제주", krw(50_000), 3L, ShippingScope.DOMESTIC, null, null, null))
            .as("도서산간 추가 배송비를 빠뜨렸다").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShippingFeeQuote(krw(6_000), krw(3_000), true, krw(3_000), "제주", krw(50_000), 3L, ShippingScope.DOMESTIC, null, null, null))
            .as("무료배송인데 기본 배송비를 받았다").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShippingFeeQuote(krw(0), krw(3_000), false, krw(0), null, null, 3L, ShippingScope.DOMESTIC, null, null, null))
            .as("무료배송이 아닌데 0 이다").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("해외 배송비 = 무료배송이면 0, 아니면 기본료 + 무게 요금. 무게 요금은 해외 견적에 늘 있다")
    void internationalFormula() {
        assertThat(international(1_900).shippingFee()).isEqualTo(usd(4_500));
        assertThat(new ShippingFeeQuote(usd(0), usd(1_500), true, usd(0), null, usd(10_000), 7L, ShippingScope.INTERNATIONAL, "북미", usd(3_000), 1_900)
            .freeShippingApplied()).isTrue();

        assertThatThrownBy(() -> new ShippingFeeQuote(usd(1_500), usd(1_500), false, usd(0), null, null, 7L, ShippingScope.INTERNATIONAL, "북미", usd(3_000), 1_900))
            .as("무게 요금을 빠뜨렸다").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShippingFeeQuote(usd(3_000), usd(1_500), true, usd(0), null, usd(10_000), 7L, ShippingScope.INTERNATIONAL, "북미", usd(3_000), 1_900))
            .as("무료배송이면 무게 요금도 받지 않는다").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShippingFeeQuote(usd(1_500), usd(1_500), false, usd(0), null, null, 7L, ShippingScope.INTERNATIONAL, "북미", null, 1_900))
            .as("해외 견적에 무게 요금이 없다").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("무료배송 기준 없이 무료배송이 적용됐으면 만들지 않는다")
    void freeShippingRequiresThreshold() {
        assertThatThrownBy(() -> new ShippingFeeQuote(krw(0), krw(3_000), true, krw(0), null, null, 3L, ShippingScope.DOMESTIC, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShippingFeeQuote(usd(0), usd(1_500), true, usd(0), null, null, 7L, ShippingScope.INTERNATIONAL, "북미", usd(3_000), 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("배송비 · 기본 배송비 · 도서산간 추가 배송비 · 범위는 필수다")
    void requiredFields() {
        assertThatThrownBy(() -> new ShippingFeeQuote(null, krw(3_000), false, krw(0), null, null, 3L, ShippingScope.DOMESTIC, null, null, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ShippingFeeQuote(krw(3_000), krw(3_000), false, krw(0), null, null, 3L, null, null, null, null))
            .isInstanceOf(NullPointerException.class);
    }
}
