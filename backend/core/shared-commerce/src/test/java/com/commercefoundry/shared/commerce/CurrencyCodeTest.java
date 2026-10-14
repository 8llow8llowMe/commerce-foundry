package com.commercefoundry.shared.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Currency;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CurrencyCodeTest {

    /** 통화를 추가할 때 자릿수를 손으로 잘못 적는 것을 막는다. JPY 를 2 로 적으면 금액이 100배가 된다. */
    @ParameterizedTest
    @EnumSource(CurrencyCode.class)
    void fractionDigitsMatchIso4217(CurrencyCode currencyCode) {
        assertThat(currencyCode.getFractionDigits())
            .isEqualTo(Currency.getInstance(currencyCode.name()).getDefaultFractionDigits());
    }
}
