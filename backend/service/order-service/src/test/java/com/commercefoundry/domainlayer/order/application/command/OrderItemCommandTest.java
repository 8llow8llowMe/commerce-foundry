package com.commercefoundry.domainlayer.order.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOptions;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderItemCommandTest {

    @Test
    @DisplayName("수량 1 과 줄당 상한(99)은 받는다")
    void quantityBounds() {
        assertThat(new OrderItemCommand(1001, 1, OrderCustomOptions.empty()).quantity()).isEqualTo(1);
        assertThat(new OrderItemCommand(1001, OrderQuoteLine.MAX_QUANTITY, OrderCustomOptions.empty()).quantity()).isEqualTo(OrderQuoteLine.MAX_QUANTITY);
    }

    @ParameterizedTest(name = "quantity={0}")
    @ValueSource(ints = {0, -1, OrderQuoteLine.MAX_QUANTITY + 1})
    @DisplayName("수량이 1~99 밖이면 IllegalArgumentException 이다 — 요청 DTO 가 먼저 400 으로 거르고, 여기는 마지막 방어선이다")
    void quantityOutOfRange(int quantity) {
        assertThatThrownBy(() -> new OrderItemCommand(1001, quantity, OrderCustomOptions.empty())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("맞춤 제작 입력은 필수다 — 없으면 빈 묶음이다")
    void customOptionsRequired() {
        assertThatThrownBy(() -> new OrderItemCommand(1001, 1, null)).isInstanceOf(NullPointerException.class);
    }
}
