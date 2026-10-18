package com.commercefoundry.domainlayer.product.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockAdjustmentHistoryTest {

    @Test
    @DisplayName("조정 전후 재고에서 수량 변화(delta)를 계산한다 — 줄면 음수다")
    void deltaFromBeforeAndAfter() {
        StockAdjustmentHistory history = StockAdjustmentHistory.of(
            1L, new Stock(10L, true, 8L), new Stock(10L, true, 5L), StockAdjustmentReason.DAMAGE, "파손 3벌", 99L
        );

        assertThat(history.beforeQuantity()).isEqualTo(8L);
        assertThat(history.afterQuantity()).isEqualTo(5L);
        assertThat(history.delta()).isEqualTo(-3L);
        assertThat(history.skuId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("delta 가 전후 차이와 다르면 만들 수 없다")
    void inconsistentDelta() {
        assertThatThrownBy(() -> new StockAdjustmentHistory(1L, 10L, 8L, 5L, 3L, true, true, StockAdjustmentReason.DAMAGE, null, 99L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("조정 전후 SKU 가 다르면 만들 수 없다")
    void differentSku() {
        assertThatThrownBy(() -> StockAdjustmentHistory.of(1L, new Stock(10L, true, 0L), new Stock(11L, true, 1L), StockAdjustmentReason.OTHER, null, 99L))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
