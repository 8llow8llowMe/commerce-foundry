package com.commercefoundry.domainlayer.shippingfee.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ShippingScopeTest {

    @Test
    @DisplayName("KR 은 국내다")
    void korea() {
        assertThat(ShippingScope.ofCountry("KR")).hasValue(ShippingScope.DOMESTIC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"US", "JP", "SG", "ZZ"})
    @DisplayName("그 밖의 대문자 두 글자 국가는 해외다 — 배송 가능 여부는 해외 정책의 국가 그룹이 정한다")
    void otherCountriesAreInternational(String countryCode) {
        assertThat(ShippingScope.ofCountry(countryCode)).hasValue(ShippingScope.INTERNATIONAL);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"kr", "us", "K", "KOR", "K1", " KR"})
    @DisplayName("국가 코드 형식이 아니면 범위가 없다")
    void malformed(String countryCode) {
        assertThat(ShippingScope.ofCountry(countryCode)).isEmpty();
    }
}
