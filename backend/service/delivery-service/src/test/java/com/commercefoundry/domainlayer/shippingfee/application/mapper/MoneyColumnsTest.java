package com.commercefoundry.domainlayer.shippingfee.application.mapper;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
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
}
