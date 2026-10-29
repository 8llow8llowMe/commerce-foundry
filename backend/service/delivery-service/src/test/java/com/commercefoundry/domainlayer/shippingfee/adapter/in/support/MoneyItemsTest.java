package com.commercefoundry.domainlayer.shippingfee.adapter.in.support;

import com.commercefoundry.commerce.support.web.MoneyItems;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.shared.commerce.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyItemsTest {

    @Test
    @DisplayName("최소 화폐 단위 금액과 통화 코드 이름을 그대로 쓴다")
    void convertsMinorUnitsAndCurrencyCode() {
        assertThat(MoneyItems.of(Money.of(1_500, USD))).isEqualTo(MoneyItem.of(1_500, "USD"));
        assertThat(MoneyItems.of(Money.of(3_000, KRW))).isEqualTo(MoneyItem.of(3_000, "KRW"));
    }

    @Test
    @DisplayName("null 은 null 이다 (무료배송 기준이 없는 정책 등)")
    void nullStaysNull() {
        assertThat(MoneyItems.of(null)).isNull();
    }
}
