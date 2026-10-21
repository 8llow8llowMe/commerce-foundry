package com.commercefoundry.domainlayer.order.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ShippingScopeTest {

    @Test
    @DisplayName("KR 은 국내다")
    void domestic() {
        assertThat(ShippingScope.ofCountry(ShippingScope.DOMESTIC_COUNTRY_CODE)).contains(ShippingScope.DOMESTIC);
        assertThat(ShippingScope.ofCountry("KR")).contains(ShippingScope.DOMESTIC);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"US", "JP", "GB", "ZZ"})
    @DisplayName("KR 이 아닌 대문자 두 글자는 해외다 — 배송할 수 있는 국가인지는 delivery 가 정한다")
    void international(String countryCode) {
        assertThat(ShippingScope.ofCountry(countryCode)).contains(ShippingScope.INTERNATIONAL);
    }

    @ParameterizedTest(name = "[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"kr", "Kr", "us", "KOR", "K", " KR", "KR ", "K1", "한국"})
    @DisplayName("국가 코드 형식(대문자 두 글자)이 아니면(소문자 · 세 글자 · null · 빈 값) 빈 값이다 — 소문자 kr 을 해외로 보지 않는다")
    void notCountryCode(String countryCode) {
        assertThat(ShippingScope.ofCountry(countryCode)).isEmpty();
    }
}
