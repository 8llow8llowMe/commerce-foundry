package com.commercefoundry.domainlayer.shippingfee.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PostalCodeTest {

    @Test
    @DisplayName("숫자 5자리 문자열이다 — 앞자리 0 을 잃지 않는다")
    void keepsLeadingZero() {
        assertThat(new PostalCode("01000").value()).isEqualTo("01000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "6310", "631000", "6310a", " 63100", "63-10", "１２３４５"})
    @DisplayName("숫자 5자리가 아니면 거부한다")
    void rejectsInvalidFormat(String value) {
        assertThatThrownBy(() -> new PostalCode(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 은 거부한다")
    void rejectsNull() {
        assertThatThrownBy(() -> new PostalCode(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("자릿수가 고정이라 문자열 순서가 숫자 순서다")
    void ordersNumerically() {
        assertThat(new PostalCode("09999")).isLessThan(new PostalCode("10000"));
        assertThat(new PostalCode("63645").isAfter(new PostalCode("63644"))).isTrue();
        assertThat(new PostalCode("63644").isAfter(new PostalCode("63644"))).isFalse();
    }
}
