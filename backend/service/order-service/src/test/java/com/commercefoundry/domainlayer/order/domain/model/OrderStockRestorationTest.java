package com.commercefoundry.domainlayer.order.domain.model;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderStockRestorationTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private final OrderPayment record = OrderPayment.canceledAfterConfirmation(1, 20, 10, 30, krw(4000), NOW, 77, UUID.randomUUID(), 555);
    private final OrderStockRestoration pending = OrderStockRestoration.forPaymentRecord(9, record, NOW);

    @Test
    @DisplayName("결제 기록 출처 작업은 기록의 주문 · 선점 · 클레임으로 RESTORE_PENDING 이다")
    void forPaymentRecordCopiesRecord() {
        assertThat(pending.origin()).isEqualTo(OrderStockRestoration.Origin.PAYMENT_RECORD);
        assertThat(pending.status()).isEqualTo(Status.RESTORE_PENDING);
        assertThat(pending.claimId()).isEqualTo(555);
        assertThat(pending.stockReservationId()).isEqualTo(77);
        assertThatThrownBy(() -> OrderStockRestoration.forPaymentRecord(9,
            OrderPayment.rejected(2, 20, 11, 30, krw(4000), NOW, OrderPayment.Result.REJECTED_ORDER_CANCELED, UUID.randomUUID()), NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("선점은 시도를 세고 다음 시각을 두 배씩, 상한까지 미룬다")
    void claimAttemptBacksOffExponentiallyUpToMax() {
        OrderStockRestoration first = pending.claimAttempt(NOW, Duration.ofMinutes(1), Duration.ofMinutes(3));
        OrderStockRestoration second = first.claimAttempt(first.nextAttemptAt(), Duration.ofMinutes(1), Duration.ofMinutes(3));
        OrderStockRestoration third = second.claimAttempt(second.nextAttemptAt(), Duration.ofMinutes(1), Duration.ofMinutes(3));

        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(second.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(3)));
        assertThat(third.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(6)));
    }

    @Test
    @DisplayName("HELD 는 사유를 갖고 다음 시각이 없으며, 다시 선점 · HELD 할 수 없다")
    void heldIsTerminalForSweep() {
        OrderStockRestoration held = pending.hold("PRODUCT_028");

        assertThat(held.status()).isEqualTo(Status.HELD);
        assertThat(held.holdCode()).isEqualTo("PRODUCT_028");
        assertThat(held.nextAttemptAt()).isNull();
        assertThatThrownBy(() -> held.claimAttempt(NOW, Duration.ofMinutes(1), Duration.ofHours(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> held.hold(OrderStockRestoration.OWNERSHIP_MISMATCH)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending.hold(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("상태와 다음 시각 · 복원 시각 · HELD 사유가 어긋나면 만들 수 없다")
    void rejectsInconsistentState() {
        assertThatThrownBy(() -> new OrderStockRestoration(1, 2, 3, 4, 5, OrderStockRestoration.Origin.PAYMENT_RECORD, Status.RESTORED, null, 0, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderStockRestoration(1, 2, 3, 4, 5, OrderStockRestoration.Origin.PAYMENT_RECORD, Status.RESTORE_PENDING, null, 0, null, null,
            null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new OrderStockRestoration(1, 2, 3, 4, 5, OrderStockRestoration.Origin.PAYMENT_RECORD, Status.RESTORED, null, 1, null, NOW, null).status())
            .isEqualTo(Status.RESTORED);
        for (String code : new String[] {"", " ", "PRODUCT_028"}) {
            assertThatThrownBy(() -> new OrderStockRestoration(1, 2, 3, 4, 5, OrderStockRestoration.Origin.PAYMENT_RECORD,
                Status.RESTORE_PENDING, code, 0, NOW, null, null)).isInstanceOf(IllegalArgumentException.class);
        }
        OrderStockRestoration restored = new OrderStockRestoration(1, 2, 3, 4, 5, OrderStockRestoration.Origin.PAYMENT_RECORD,
            Status.RESTORED, null, 1, null, NOW, null);
        assertThatThrownBy(() -> restored.hold("PRODUCT_028")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> restored.claimAttempt(NOW, Duration.ofSeconds(1), Duration.ofMinutes(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pending.claimAttempt(NOW, Duration.ZERO, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending.claimAttempt(NOW.minusSeconds(1), Duration.ofSeconds(1), Duration.ofMinutes(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
