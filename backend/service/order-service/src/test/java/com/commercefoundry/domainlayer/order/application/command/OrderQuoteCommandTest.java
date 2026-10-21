package com.commercefoundry.domainlayer.order.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderQuoteCommandTest {

    private static final OrderItemCommand ITEM = new OrderItemCommand(1001, 1, OrderCustomOptions.empty());

    @Test
    @DisplayName("줄은 1~50개다 — 요청 DTO 가 먼저 400 으로 거르고, 여기는 마지막 방어선이라 IllegalArgumentException 이다")
    void lineLimit() {
        assertThat(new OrderQuoteCommand(Collections.nCopies(OrderQuoteCommand.MAX_LINES, ITEM), "KR", "63100").items()).hasSize(OrderQuoteCommand.MAX_LINES);

        assertThatThrownBy(() -> new OrderQuoteCommand(Collections.nCopies(OrderQuoteCommand.MAX_LINES + 1, ITEM), "KR", "63100"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderQuoteCommand(List.of(), "KR", "63100")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("줄 목록은 복사해 둔다 — 넘긴 목록을 바꿔도 command 는 바뀌지 않는다")
    void copiesItems() {
        List<OrderItemCommand> items = new ArrayList<>(List.of(ITEM));

        OrderQuoteCommand command = new OrderQuoteCommand(items, "KR", "63100");
        items.clear();

        assertThat(command.items()).containsExactly(ITEM);
    }

    @Test
    @DisplayName("toString 은 우편번호를 가린다 — 배송지의 일부라 로그에 남기지 않는다. 우편번호가 없으면 null 그대로다")
    void toStringMasksPostalCode() {
        assertThat(new OrderQuoteCommand(List.of(ITEM), "KR", "63100").toString()).contains("countryCode=KR", "postalCode=***").doesNotContain("63100");
        assertThat(new OrderQuoteCommand(List.of(ITEM), "GB", "SW1A 1AA").toString()).doesNotContain("SW1A");
        assertThat(new OrderQuoteCommand(List.of(ITEM), "US", null).toString()).contains("postalCode=null");
    }
}
