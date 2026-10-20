package com.commercefoundry.domainlayer.product.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.commercefoundry.domainlayer.product.domain.model.StockRestorationReason;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockRestorationCommandTest {

    @Test
    @DisplayName("같은 SKU 는 수량을 합치고 SKU 아이디 오름차순으로 놓는다 — 복원(재고 행 잠금) 순서다")
    void mergesAndSorts() {
        StockRestorationCommand command = new StockRestorationCommand(6001L, 7001L, StockRestorationReason.CANCELLATION, List.of(
            new StockRestorationCommand.Item(1003L, 1), new StockRestorationCommand.Item(1001L, 2), new StockRestorationCommand.Item(1003L, 2)
        ));

        assertThat(command.items()).containsExactly(new StockRestorationCommand.Item(1001L, 2), new StockRestorationCommand.Item(1003L, 3));
        assertThat(command.quantitiesBySkuId()).containsExactly(entry(1001L, 2), entry(1003L, 3));
    }

    @Test
    @DisplayName("항목이 없거나 사유가 없거나 수량이 1 미만이면 만들 수 없다 — 요청 DTO 를 거치지 않은 진입점도 막는다")
    void rules() {
        assertThatThrownBy(() -> new StockRestorationCommand(6001L, 7001L, StockRestorationReason.RETURN, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StockRestorationCommand(6001L, 7001L, null, List.of(new StockRestorationCommand.Item(1001L, 1))))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StockRestorationCommand.Item(1001L, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
