package com.commercefoundry.domainlayer.order.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ShippingFeeQuoteQueryTest {

    private static final Money KRW = Money.of(99_000, CurrencyCode.KRW);
    private static final Money USD = Money.of(12_000, CurrencyCode.USD);

    @Test
    @DisplayName("toString 은 우편번호를 가린다 — 배송지의 일부라 로그에 남기지 않는다")
    void toStringMasksPostalCode() {
        assertThat(new ShippingFeeQuoteQuery(KRW, "KR", "63100", null).toString()).doesNotContain("63100").contains("postalCode=***");
        assertThat(new ShippingFeeQuoteQuery(USD, "GB", "SW1A 1AA", 1_200).toString()).doesNotContain("SW1A").contains("postalCode=***");
        assertThat(new ShippingFeeQuoteQuery(USD, "US", null, 1_200).toString()).contains("postalCode=null", "countryCode=US", "weightGrams=1200");
    }

    @Test
    @DisplayName("주문 금액 · 국가 코드는 필수다")
    void requiredFields() {
        assertThatThrownBy(() -> new ShippingFeeQuoteQuery(null, "KR", "63100", null)).isInstanceOf(NullPointerException.class).hasMessage("orderAmount");
        assertThatThrownBy(() -> new ShippingFeeQuoteQuery(KRW, null, "63100", null)).isInstanceOf(NullPointerException.class).hasMessage("countryCode");
    }

    @Test
    @DisplayName("국내(KR)는 우편번호가 있으면 무게 없이 만든다 · 해외는 1 이상의 무게가 있으면 우편번호 없이 만든다")
    void validCombinations() {
        assertThat(new ShippingFeeQuoteQuery(KRW, "KR", "63100", null).weightGrams()).isNull();
        assertThat(new ShippingFeeQuoteQuery(USD, "US", null, 1).weightGrams()).isEqualTo(1);
        assertThat(new ShippingFeeQuoteQuery(USD, "US", "10001", 1_200).postalCode()).isEqualTo("10001");
    }

    @ParameterizedTest(name = "postalCode=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("국내(KR)인데 우편번호가 없거나 공백뿐이면 만들지 않는다 — 조립 버그가 delivery 400 → 503 으로 가려지지 않게")
    void domesticRequiresPostalCode(String postalCode) {
        assertThatThrownBy(() -> new ShippingFeeQuoteQuery(KRW, "KR", postalCode, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "weightGrams={0}")
    @NullSource
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("해외인데 총 무게가 없거나 1 미만이면 만들지 않는다 — 우편번호가 있어도 같다")
    void internationalRequiresPositiveWeight(Integer weightGrams) {
        assertThatThrownBy(() -> new ShippingFeeQuoteQuery(USD, "US", null, weightGrams)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShippingFeeQuoteQuery(USD, "US", "10001", weightGrams)).isInstanceOf(IllegalArgumentException.class);
    }
}
