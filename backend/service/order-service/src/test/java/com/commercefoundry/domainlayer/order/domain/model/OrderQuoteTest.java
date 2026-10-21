package com.commercefoundry.domainlayer.order.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderQuoteTest {

    private static final OrderQuoteLine JACKET = OrderQuoteLine.of(1001, 7001, "저고리", "S", krw(12_000), 2, OrderCustomOptions.empty(), false, null, null);
    private static final OrderQuoteLine SKIRT = OrderQuoteLine.of(1002, 7002, "치마", "", krw(30_500), 1, OrderCustomOptions.empty(), false, null, null);
    private static final ShippingFeeQuote DOMESTIC_FEE = new ShippingFeeQuote(
        krw(3_000), krw(3_000), false, krw(0), null, null, 3L, ShippingScope.DOMESTIC, null, null, null);

    private static Money krw(long amount) {
        return Money.of(amount, CurrencyCode.KRW);
    }

    @Test
    @DisplayName("상품 합계 = Σ 줄 금액, 총액 = 상품 합계 + 배송비면 만든다 — 줄 목록은 바뀌지 않는다")
    void valid() {
        List<OrderQuoteLine> lines = new ArrayList<>(List.of(JACKET, SKIRT));

        OrderQuote quote = new OrderQuote(CurrencyCode.KRW, lines, krw(54_500), DOMESTIC_FEE, krw(57_500), "KR");
        lines.clear();

        assertThat(quote.lines()).containsExactly(JACKET, SKIRT);
        assertThatThrownBy(() -> quote.lines().add(JACKET)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("상품 합계나 총액이 계산과 다르면 만들지 않는다")
    void sumMismatch() {
        assertThatThrownBy(() -> new OrderQuote(CurrencyCode.KRW, List.of(JACKET, SKIRT), krw(54_000), DOMESTIC_FEE, krw(57_000), "KR"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderQuote(CurrencyCode.KRW, List.of(JACKET, SKIRT), krw(54_500), DOMESTIC_FEE, krw(54_500), "KR"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("줄 · 배송비의 통화가 주문 통화와 다르면 만들지 않는다")
    void currencyMismatch() {
        assertThatThrownBy(() -> new OrderQuote(CurrencyCode.USD, List.of(JACKET), krw(24_000), DOMESTIC_FEE, krw(27_000), "KR"))
            .isInstanceOf(IllegalArgumentException.class);
        ShippingFeeQuote dollarFee = new ShippingFeeQuote(
            Money.of(300, CurrencyCode.USD), Money.of(300, CurrencyCode.USD), false, Money.zero(CurrencyCode.USD), null, null, 3L, ShippingScope.DOMESTIC, null, null, null);
        assertThatThrownBy(() -> new OrderQuote(CurrencyCode.KRW, List.of(JACKET), krw(24_000), dollarFee, krw(24_300), "KR"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("줄이 없거나 견적 범위가 배송 국가와 다르면 만들지 않는다")
    void emptyLinesOrScopeMismatch() {
        assertThatThrownBy(() -> new OrderQuote(CurrencyCode.KRW, List.of(), krw(0), DOMESTIC_FEE, krw(3_000), "KR"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderQuote(CurrencyCode.KRW, List.of(JACKET), krw(24_000), DOMESTIC_FEE, krw(27_000), "US"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderQuote(CurrencyCode.KRW, List.of(JACKET), krw(24_000), DOMESTIC_FEE, krw(27_000), "kr"))
            .as("국가 코드 형식이 아니면 조립 버그다").isInstanceOf(IllegalArgumentException.class);
    }
}
