package com.commercefoundry.commerce.support.persistence;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyColumnsTest {

    @Test
    @DisplayName("required: 금액 · 통화를 Money 로 묶고, 통화가 없거나 음수면 데이터 오류다")
    void required() {
        assertThat(MoneyColumns.required(3_000L, KRW)).isEqualTo(Money.of(3_000, KRW));
        assertThatThrownBy(() -> MoneyColumns.required(3_000L, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MoneyColumns.required(-1L, KRW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("nullable: 둘 다 없으면 null, 둘 다 있으면 Money, 한쪽만 있으면 데이터 오류다")
    void nullable() {
        assertThat(MoneyColumns.nullable(null, null)).isNull();
        assertThat(MoneyColumns.nullable(50_000L, KRW)).isEqualTo(Money.of(50_000, KRW));
        assertThatThrownBy(() -> MoneyColumns.nullable(50_000L, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MoneyColumns.nullable(null, KRW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("0 · long 최대값 · USD 센트를 변환하거나 반올림하지 않는다")
    void preservesMinorUnits() {
        assertThat(MoneyColumns.required(0, KRW)).isEqualTo(Money.of(0, KRW));
        assertThat(MoneyColumns.required(Long.MAX_VALUE, USD)).isEqualTo(Money.of(Long.MAX_VALUE, USD));
        assertThat(MoneyColumns.nullable(8950L, USD)).isEqualTo(Money.of(8950L, USD));
    }

    @Test
    @DisplayName("nullable 음수 금액도 데이터 오류로 거절하고 원인을 보존한다")
    void rejectsNegativeNullableAmount() {
        assertThatThrownBy(() -> MoneyColumns.nullable(-1L, USD))
            .isInstanceOf(IllegalStateException.class)
            .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
