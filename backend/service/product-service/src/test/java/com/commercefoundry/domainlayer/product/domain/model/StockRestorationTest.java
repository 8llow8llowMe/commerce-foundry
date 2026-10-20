package com.commercefoundry.domainlayer.product.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 결제 후 재고 복원 — 복원 기록의 불변식 · 멱등 비교, 그리고 선점이 정하는 복원 상한({@link StockReservation#skuIdsExceedingRestorable}).
 */
class StockRestorationTest {

    private static final long RESERVATION_ID = 7001L;
    private static final Instant NOW = Instant.parse("2026-10-18T09:10:00Z");

    @Nested
    @DisplayName("복원 상한 — 선점 수량 − 이미 복원한 수량")
    class Restorable {

        /** 1001 × 3 (차감), 1002 × 2 (무제한), 1003 × 1 (차감) 확정 선점. */
        private final StockReservation confirmed = new StockReservation(
            RESERVATION_ID, 8001L, StockReservationStatus.CONFIRMED, NOW.plusSeconds(600), NOW, null, null, List.of(
                new StockReservationItem(1L, 1001L, 3, true),
                new StockReservationItem(2L, 1002L, 2, false),
                new StockReservationItem(3L, 1003L, 1, true)
            ), 1L
        );

        @Test
        @DisplayName("복원한 적이 없으면 선점 수량까지 받는다 — 경계(같음)는 통과다")
        void upToReservedQuantity() {
            assertThat(confirmed.skuIdsExceedingRestorable(Map.of(1001L, 3, 1002L, 2, 1003L, 1), Map.of())).isEmpty();
            assertThat(confirmed.skuIdsExceedingRestorable(Map.of(1001L, 4), Map.of())).containsExactly(1001L);
        }

        @Test
        @DisplayName("이미 복원한 수량을 뺀 만큼만 받는다 — 무제한 SKU 도 같은 상한이다")
        void subtractsRestored() {
            Map<Long, Long> restored = Map.of(1001L, 2L, 1002L, 2L);

            assertThat(confirmed.skuIdsExceedingRestorable(Map.of(1001L, 1), restored)).isEmpty();
            assertThat(confirmed.skuIdsExceedingRestorable(Map.of(1001L, 2, 1002L, 1, 1003L, 1), restored)).containsExactly(1001L, 1002L);
        }

        @Test
        @DisplayName("선점에 없는 SKU 는 상한 0 이라 넘는 것으로 보고, 넘는 SKU 를 모두 오름차순으로 낸다")
        void unknownSkuExceeds() {
            assertThat(confirmed.skuIdsExceedingRestorable(Map.of(9999L, 1, 1003L, 2, 1001L, 1), Map.of())).containsExactly(1003L, 9999L);
        }
    }

    @Nested
    @DisplayName("복원 기록")
    class Record {

        @Test
        @DisplayName("재고를 되돌릴 항목은 restored=true 만이고 SKU 오름차순이다")
        void restoredItems() {
            StockRestoration restoration = restoration(StockRestorationReason.CANCELLATION, List.of(
                new StockRestorationItem(11L, 1001L, 1, true), new StockRestorationItem(12L, 1002L, 2, false), new StockRestorationItem(13L, 1003L, 1, true)
            ));

            assertThat(restoration.restoredItems()).extracting(StockRestorationItem::skuId).containsExactly(1001L, 1003L);
        }

        @Test
        @DisplayName("같은 요청 — 선점 · 사유 · SKU 별 수량이 모두 같아야 한다")
        void sameRequest() {
            StockRestoration restoration = restoration(StockRestorationReason.RETURN, List.of(
                new StockRestorationItem(11L, 1001L, 1, true), new StockRestorationItem(12L, 1002L, 2, false)
            ));

            assertThat(restoration.isSameRequest(RESERVATION_ID, StockRestorationReason.RETURN, Map.of(1001L, 1, 1002L, 2))).isTrue();
            assertThat(restoration.isSameRequest(7002L, StockRestorationReason.RETURN, Map.of(1001L, 1, 1002L, 2))).isFalse();
            assertThat(restoration.isSameRequest(RESERVATION_ID, StockRestorationReason.CANCELLATION, Map.of(1001L, 1, 1002L, 2))).isFalse();
            assertThat(restoration.isSameRequest(RESERVATION_ID, StockRestorationReason.RETURN, Map.of(1001L, 1, 1002L, 1))).isFalse();
            assertThat(restoration.isSameRequest(RESERVATION_ID, StockRestorationReason.RETURN, Map.of(1001L, 1))).isFalse();
        }

        @Test
        @DisplayName("항목은 비어 있지 않고 SKU 오름차순 · SKU 마다 한 건이며, 수량은 1 이상이다")
        void invariants() {
            assertThatThrownBy(() -> restoration(StockRestorationReason.RETURN, List.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> restoration(StockRestorationReason.RETURN, List.of(
                new StockRestorationItem(11L, 1002L, 1, true), new StockRestorationItem(12L, 1001L, 1, true)
            ))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> restoration(StockRestorationReason.RETURN, List.of(
                new StockRestorationItem(11L, 1001L, 1, true), new StockRestorationItem(12L, 1001L, 1, true)
            ))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> restoration(null, List.of(new StockRestorationItem(11L, 1001L, 1, true)))).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new StockRestorationItem(11L, 1001L, 0, true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static StockRestoration restoration(StockRestorationReason reason, List<StockRestorationItem> items) {
        return new StockRestoration(9001L, 6001L, RESERVATION_ID, 8001L, reason, items);
    }
}
