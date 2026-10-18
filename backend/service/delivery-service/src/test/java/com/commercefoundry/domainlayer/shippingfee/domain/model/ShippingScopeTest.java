package com.commercefoundry.domainlayer.shippingfee.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShippingScopeTest {

    @Test
    @DisplayName("KR 은 국내다")
    void korea() {
        assertThat(ShippingScope.ofCountry("KR")).hasValue(ShippingScope.DOMESTIC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"US", "JP", "kr"})
    @DisplayName("그 밖의 국가는 아직 배송 범위가 없다 (해외 배송비 #22)")
    void otherCountries(String countryCode) {
        assertThat(ShippingScope.ofCountry(countryCode)).isEmpty();
    }
}
