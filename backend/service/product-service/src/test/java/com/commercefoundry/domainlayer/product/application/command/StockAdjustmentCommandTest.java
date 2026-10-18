package com.commercefoundry.domainlayer.product.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockAdjustmentCommandTest {

    @Test
    @DisplayName("수량 · 관리 여부 중 하나가 있어야 하고, 수량을 바꾸면 기대 수량이 필요하다 — 요청 DTO 를 거치지 않은 진입점도 막는다")
    void rules() {
        assertThatThrownBy(() -> base().build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().quantity(3L).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().quantity(-1L).expectedQuantity(0L).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().stockManaged(true).reason(null).build()).isInstanceOf(NullPointerException.class);

        StockAdjustmentCommand quantityOnly = base().quantity(3L).expectedQuantity(0L).build();
        assertThat(quantityOnly.changesQuantity()).isTrue();
        assertThat(quantityOnly.changesStockManaged()).isFalse();
        assertThat(base().stockManaged(false).build().changesQuantity()).isFalse();
    }

    private static StockAdjustmentCommand.StockAdjustmentCommandBuilder base() {
        return StockAdjustmentCommand.builder().skuId(10L).reason(StockAdjustmentReason.RESTOCK).adminMemberId(99L);
    }
}
