package com.commercefoundry.domainlayer.order.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderQuoteLineTest {

    private static final Money PRICE = Money.of(12_000, CurrencyCode.KRW);
    private static final OrderCustomOptions CHEST = OrderCustomOptions.of(List.of(new OrderCustomOption("chest", "96")));

    private static OrderQuoteLine line(int quantity, Money lineAmount, OrderCustomOptions customOptions, boolean customOrder) {
        return new OrderQuoteLine(1001, 7001, "저고리", "S", PRICE, quantity, lineAmount, customOptions, customOrder, null, null);
    }

    @Test
    @DisplayName("of 는 줄 금액을 단가 × 수량으로 계산한다 — 무게 · HS 는 없어도(null) 된다")
    void ofCalculatesLineAmount() {
        OrderQuoteLine line = OrderQuoteLine.of(1001, 7001, "저고리", "", PRICE, 3, OrderCustomOptions.empty(), false, null, null);

        assertThat(line.lineAmount()).isEqualTo(Money.of(36_000, CurrencyCode.KRW));
        assertThat(line.weightGrams()).isNull();
        assertThat(line.hsCode()).isNull();
    }

    @ParameterizedTest(name = "quantity={0}")
    @ValueSource(ints = {1, OrderQuoteLine.MAX_QUANTITY})
    @DisplayName("수량 1 · 99 는 만든다")
    void quantityBoundaries(int quantity) {
        assertThat(line(quantity, PRICE.times(quantity), OrderCustomOptions.empty(), false).quantity()).isEqualTo(quantity);
    }

    @ParameterizedTest(name = "quantity={0}")
    @ValueSource(ints = {0, -1, OrderQuoteLine.MAX_QUANTITY + 1})
    @DisplayName("수량이 1~99 밖이면 만들지 않는다")
    void quantityOutOfRange(int quantity) {
        assertThatThrownBy(() -> line(quantity, PRICE, OrderCustomOptions.empty(), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderQuoteLine.of(1001, 7001, "저고리", "S", PRICE, quantity, OrderCustomOptions.empty(), false, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("줄 금액이 단가 × 수량과 다르거나 통화가 다르면 만들지 않는다")
    void lineAmountMismatch() {
        assertThatThrownBy(() -> line(2, PRICE, OrderCustomOptions.empty(), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line(2, Money.of(24_000, CurrencyCode.USD), OrderCustomOptions.empty(), false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("맞춤 제작 입력은 맞춤 제작 상품의 줄에만 있다")
    void customOptionsOnlyForCustomOrder() {
        assertThat(line(1, PRICE, CHEST, true).customOptions()).isEqualTo(CHEST);
        assertThat(line(1, PRICE, OrderCustomOptions.empty(), true).customOptions().isEmpty()).isTrue();
        assertThatThrownBy(() -> line(1, PRICE, CHEST, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("상품명 · 옵션명 · 금액 · 맞춤 입력 묶음은 필수다")
    void requiredFields() {
        assertThatThrownBy(() -> new OrderQuoteLine(1001, 7001, null, "S", PRICE, 1, PRICE, OrderCustomOptions.empty(), false, null, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderQuoteLine(1001, 7001, "저고리", null, PRICE, 1, PRICE, OrderCustomOptions.empty(), false, null, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> line(1, PRICE, null, false)).isInstanceOf(NullPointerException.class);
    }
}
