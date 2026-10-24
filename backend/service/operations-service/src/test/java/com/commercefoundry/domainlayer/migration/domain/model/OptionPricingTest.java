package com.commercefoundry.domainlayer.migration.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SKU 판매가 → 기본가 + 0 이상 추가금 (#8 메모, {@code migration-guide.md} §5). */
class OptionPricingTest {

    @Test
    @DisplayName("기본가는 SKU 최저 판매가, 추가금은 그 차이다 — 음수 옵션가가 있던 상품도 추가금이 0 이상이 된다")
    void movesBaseToLowestSku() {
        // 원본: 기본가 39000, 옵션가 0 · -2000 · +3000
        OptionPricing pricing = OptionPricing.of(List.of(39_000L, 37_000L, 42_000L), 45_000L);

        assertThat(pricing.basePrice()).isEqualTo(37_000L);
        assertThat(pricing.additionalPrices()).containsExactly(2_000L, 0L, 5_000L);
        assertThat(pricing.listPrice()).isEqualTo(45_000L);
    }

    @Test
    @DisplayName("정가가 옮긴 기본가보다 낮으면 비운다 — 정가가 판매가보다 낮게 보이지 않게")
    void dropsListPriceBelowBase() {
        assertThat(OptionPricing.of(List.of(50_000L), 40_000L).listPrice()).isNull();
        assertThat(OptionPricing.of(List.of(50_000L), null).listPrice()).isNull();
        assertThat(OptionPricing.of(List.of(50_000L), 50_000L).listPrice()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("SKU 가 없거나 판매가가 음수면 계약 위반이다 — 매퍼가 먼저 걸러야 한다")
    void rejectsContractViolation() {
        assertThatThrownBy(() -> OptionPricing.of(List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OptionPricing.of(List.of(1_000L, -1L), null)).isInstanceOf(IllegalArgumentException.class);
    }
}
