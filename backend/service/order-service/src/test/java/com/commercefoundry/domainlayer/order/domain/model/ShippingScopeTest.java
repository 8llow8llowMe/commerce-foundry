package com.commercefoundry.domainlayer.order.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShippingScopeTest {

    @Test
    @DisplayName("KR 은 국내다")
    void domestic() {
        assertThat(ShippingScope.ofCountry(ShippingScope.DOMESTIC_COUNTRY_CODE)).isEqualTo(ShippingScope.DOMESTIC);
        assertThat(ShippingScope.ofCountry("KR")).isEqualTo(ShippingScope.DOMESTIC);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"US", "JP", "GB", "kr"})
    @DisplayName("KR 이 아니면 해외다 — 형식(대문자 두 글자)은 요청 검증이 거른다")
    void international(String countryCode) {
        assertThat(ShippingScope.ofCountry(countryCode)).isEqualTo(ShippingScope.INTERNATIONAL);
    }

    @Test
    @DisplayName("국가 코드는 필수다")
    void required() {
        assertThatThrownBy(() -> ShippingScope.ofCountry(null)).isInstanceOf(NullPointerException.class);
    }
}
