package com.commercefoundry.domainlayer.product.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 재고 선점 허용 전이 표 전수 ({@code order-flow-guide.md} §3) — 3 상태 × (확정 · 해제 2사유).
 */
class StockReservationTest {

    private static final long RESERVATION_ID = 7001L;
    private static final long ORDER_ID = 8001L;
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-18T09:30:00Z");
    private static final Instant NOW = Instant.parse("2026-10-18T09:10:00Z");
    private static final List<StockReservationItem> ITEMS = List.of(
        new StockReservationItem(1L, 1001L, 2, true),
        new StockReservationItem(2L, 1002L, 1, false)
    );

    @Nested
    @DisplayName("RESERVED")
    class Reserved {

        @Test
        @DisplayName("새 선점은 RESERVED · 버전 null 이고 활성 주문 아이디가 주문 아이디다")
        void reserve() {
            StockReservation reservation = reserved();

            assertThat(reservation.status()).isEqualTo(StockReservationStatus.RESERVED);
            assertThat(reservation.version()).isNull();
            assertThat(reservation.activeOrderId()).isEqualTo(ORDER_ID);
        }

        @Test
        @DisplayName("확정 → CONFIRMED · 확정 시각이 채워지고 버전 · 항목은 그대로다")
        void confirm() {
            StockReservation.Transition transition = saved(StockReservationStatus.RESERVED).confirm(NOW);

            assertThat(transition.changed()).isTrue();
            StockReservation confirmed = transition.reservation();
            assertThat(confirmed.status()).isEqualTo(StockReservationStatus.CONFIRMED);
            assertThat(confirmed.confirmedAt()).isEqualTo(NOW);
            assertThat(confirmed.releasedAt()).isNull();
            assertThat(confirmed.version()).isEqualTo(3L);
            assertThat(confirmed.items()).isEqualTo(ITEMS);
            assertThat(confirmed.activeOrderId()).isEqualTo(ORDER_ID);
        }

        @Test
        @DisplayName("만료 시각이 지났어도 스케줄러가 풀기 전이면 확정할 수 있다")
        void confirmAfterExpiry() {
            assertThat(saved(StockReservationStatus.RESERVED).confirm(EXPIRES_AT.plusSeconds(60)).reservation().status())
                .isEqualTo(StockReservationStatus.CONFIRMED);
        }

        @ParameterizedTest
        @EnumSource(StockReleaseReason.class)
        @DisplayName("해제 → RELEASED · 해제 시각 · 사유가 채워지고 활성 주문 아이디가 null 이 된다")
        void release(StockReleaseReason reason) {
            StockReservation.Transition transition = saved(StockReservationStatus.RESERVED).release(NOW, reason);

            assertThat(transition.changed()).isTrue();
            StockReservation released = transition.reservation();
            assertThat(released.status()).isEqualTo(StockReservationStatus.RELEASED);
            assertThat(released.releasedAt()).isEqualTo(NOW);
            assertThat(released.releaseReason()).isEqualTo(reason);
            assertThat(released.confirmedAt()).isNull();
            assertThat(released.activeOrderId()).isNull();
            assertThat(released.version()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("CONFIRMED")
    class Confirmed {

        @Test
        @DisplayName("확정 → 그대로(멱등) · 바뀌지 않았다고 알린다")
        void confirmAgain() {
            StockReservation confirmed = saved(StockReservationStatus.CONFIRMED);

            StockReservation.Transition transition = confirmed.confirm(NOW.plusSeconds(1));

            assertThat(transition.changed()).isFalse();
            assertThat(transition.reservation()).isSameAs(confirmed);
        }

        @ParameterizedTest
        @EnumSource(StockReleaseReason.class)
        @DisplayName("해제 → 409 STOCK_RESERVATION_CONFIRMED")
        void releaseIsRejected(StockReleaseReason reason) {
            assertRejected(() -> saved(StockReservationStatus.CONFIRMED).release(NOW, reason), ProductErrorCode.STOCK_RESERVATION_CONFIRMED);
        }
    }

    @Nested
    @DisplayName("RELEASED")
    class Released {

        @Test
        @DisplayName("확정 → 409 STOCK_RESERVATION_RELEASED")
        void confirmIsRejected() {
            assertRejected(() -> saved(StockReservationStatus.RELEASED).confirm(NOW), ProductErrorCode.STOCK_RESERVATION_RELEASED);
        }

        @ParameterizedTest
        @EnumSource(StockReleaseReason.class)
        @DisplayName("해제 → 그대로(멱등) · 처음 해제 시각 · 사유가 남는다")
        void releaseAgain(StockReleaseReason reason) {
            StockReservation released = saved(StockReservationStatus.RELEASED);

            StockReservation.Transition transition = released.release(NOW.plusSeconds(1), reason);

            assertThat(transition.changed()).isFalse();
            assertThat(transition.reservation()).isSameAs(released);
            assertThat(transition.reservation().releaseReason()).isEqualTo(StockReleaseReason.RELEASED_BY_CALLER);
        }
    }

    @Nested
    @DisplayName("만료 판정 · 항목")
    class Queries {

        @Test
        @DisplayName("RESERVED 이고 만료 시각이 지났거나 같으면 만료 대상이다")
        void expired() {
            StockReservation reservation = saved(StockReservationStatus.RESERVED);

            assertThat(reservation.isExpired(EXPIRES_AT.minusMillis(1))).isFalse();
            assertThat(reservation.isExpired(EXPIRES_AT)).isTrue();
            assertThat(reservation.isExpired(EXPIRES_AT.plusSeconds(1))).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = StockReservationStatus.class, names = {"CONFIRMED", "RELEASED"})
        @DisplayName("확정 · 해제된 선점은 만료 시각이 지나도 만료 대상이 아니다")
        void notExpiredWhenSettled(StockReservationStatus status) {
            assertThat(saved(status).isExpired(EXPIRES_AT.plusSeconds(1))).isFalse();
        }

        @Test
        @DisplayName("되돌릴 항목은 차감한 항목뿐이다 — 무제한 SKU(deducted=false)는 빠진다")
        void deductedItems() {
            assertThat(reserved().deductedItems()).extracting(StockReservationItem::skuId).containsExactly(1001L);
        }

        @Test
        @DisplayName("SKU 별 수량이 정확히 같아야 같은 항목이다")
        void sameQuantities() {
            StockReservation reservation = reserved();

            assertThat(reservation.hasSameQuantities(Map.of(1001L, 2, 1002L, 1))).isTrue();
            assertThat(reservation.hasSameQuantities(Map.of(1001L, 3, 1002L, 1))).isFalse();
            assertThat(reservation.hasSameQuantities(Map.of(1001L, 2))).isFalse();
            assertThat(reservation.hasSameQuantities(Map.of(1001L, 2, 1002L, 1, 1003L, 1))).isFalse();
        }
    }

    @Nested
    @DisplayName("불변식")
    class Invariants {

        @Test
        @DisplayName("항목이 비었거나 SKU 오름차순 · 중복 없음이 아니면 만들 수 없다")
        void items() {
            assertThatThrownBy(() -> StockReservation.reserve(RESERVATION_ID, ORDER_ID, List.of(), EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> StockReservation.reserve(RESERVATION_ID, ORDER_ID, List.of(ITEMS.get(1), ITEMS.get(0)), EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> StockReservation.reserve(RESERVATION_ID, ORDER_ID, List.of(ITEMS.get(0), ITEMS.get(0)), EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("상태와 시각 · 사유가 어긋나면 만들 수 없다")
        void statusAndTimestamps() {
            assertThatThrownBy(() -> new StockReservation(
                RESERVATION_ID, ORDER_ID, StockReservationStatus.CONFIRMED, EXPIRES_AT, null, null, null, ITEMS, 0L
            )).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StockReservation(
                RESERVATION_ID, ORDER_ID, StockReservationStatus.RELEASED, EXPIRES_AT, null, NOW, null, ITEMS, 0L
            )).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StockReservation(
                RESERVATION_ID, ORDER_ID, StockReservationStatus.RESERVED, EXPIRES_AT, NOW, null, null, ITEMS, 0L
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("수량은 1 이상이다")
        void quantity() {
            assertThatThrownBy(() -> new StockReservationItem(1L, 1001L, 0, true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static StockReservation reserved() {
        return StockReservation.reserve(RESERVATION_ID, ORDER_ID, ITEMS, EXPIRES_AT);
    }

    /** 저장된(버전 3) 선점. 확정 · 해제 상태는 시각 · 사유를 채운다. */
    private static StockReservation saved(StockReservationStatus status) {
        Instant confirmedAt = status == StockReservationStatus.CONFIRMED ? NOW.minusSeconds(60) : null;
        Instant releasedAt = status == StockReservationStatus.RELEASED ? NOW.minusSeconds(60) : null;
        StockReleaseReason reason = status == StockReservationStatus.RELEASED ? StockReleaseReason.RELEASED_BY_CALLER : null;
        return new StockReservation(RESERVATION_ID, ORDER_ID, status, EXPIRES_AT, confirmedAt, releasedAt, reason, ITEMS, 3L);
    }

    private static void assertRejected(Runnable transition, ProductErrorCode errorCode) {
        assertThatThrownBy(transition::run)
            .isInstanceOf(ProductException.class)
            .extracting(exception -> ((ProductException) exception).getErrorCode())
            .isEqualTo(errorCode);
    }
}
