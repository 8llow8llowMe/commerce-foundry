package com.commercefoundry.domainlayer.order.domain.model;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 부분 취소(#684 PR-2)의 운영자 수습 자격 — 일부 수량 취소의 복원 생략 종결은 주문 상태를 보지 않고(정리 뒤 결제 완료 · 출고 · 다음 취소로 넘어간다),
 * 주문 전 수량 취소는 여전히 취소 완료를 요구한다. 값은 모두 가짜다.
 */
class ClaimAdminRecoveryPolicyPartialCancellationTest {

    private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");

    /** A(항목 11) 1,000 × 1 · B(항목 12) 2,000 × 2. */
    private static Order order(OrderStatus status) {
        Order placed = Order.place(1, 7, domesticQuote(quoteLine(101, 1_000, 1), quoteLine(102, 2_000, 2)), new AtomicLong(10)::incrementAndGet,
            address("KR"), 99, NOW);
        return new Order(1, 7, status, placed.quote(), placed.itemIds(), placed.shippingAddress(), 99, placed.paymentDueAt(), null, null, NOW, 3L);
    }

    private static Claim cancellation(Claim.Status status, String failure, List<ClaimItem> items) {
        return new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, status, krw(1_000), 77L, 0,
            Claim.Type.CANCELLATION.isAwaitingAttempt(status) ? NOW : null, failure, items, NOW, 4L);
    }

    private static final List<ClaimItem> PARTIAL = List.of(new ClaimItem(30, 11, 1, ClaimItem.Reason.OTHER));
    private static final List<ClaimItem> REMAINDER = List.of(new ClaimItem(31, 12, 2, ClaimItem.Reason.OTHER));
    private static final List<ClaimItem> WHOLE = List.of(new ClaimItem(32, 11, 1, ClaimItem.Reason.OTHER), new ClaimItem(33, 12, 2, ClaimItem.Reason.OTHER));

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("일부 수량 취소의 복원 생략 종결은 주문 상태를 보지 않는다")
    void partialCompletionIgnoresOrderStatus(OrderStatus status) {
        for (List<ClaimItem> items : List.of(PARTIAL, REMAINDER)) {
            assertThatCode(() -> ClaimAdminRecoveryPolicy.requireCompletion(cancellation(Claim.Status.RESTORATION_FAILED, "PRODUCT_028", items), order(status)))
                .as(status.name()).doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "CANCELED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("주문 전 수량 취소의 복원 생략 종결은 여전히 주문 취소 완료를 요구한다")
    void wholeOrderCompletionStillRequiresCanceled(OrderStatus status) {
        Claim whole = cancellation(Claim.Status.RESTORATION_FAILED, "PRODUCT_028", WHOLE);

        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireCompletion(whole, order(status))).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> ClaimAdminRecoveryPolicy.requireCompletion(whole, order(OrderStatus.CANCELED))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDER_STATUS_UNEXPECTED", "RESTORATION_UNCONFIRMED"})
    @DisplayName("일부 수량 취소도 주문 정리 실패 · 복원 결과 미확인은 종결하지 않는다")
    void partialCompletionKeepsUnconfirmedAndUnsettledOut(String failure) {
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireCompletion(cancellation(Claim.Status.RESTORATION_FAILED, failure, PARTIAL),
            order(OrderStatus.PAID))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("부분 취소의 수동 환불 확정은 취소 요청 주문에서 받고, 결제 완료로 돌아간 주문의 환불 실패(열린 취소)는 데이터 이상이라 받지 않는다")
    void partialRefundConfirmationRequiresCancelRequested() {
        Claim failed = cancellation(Claim.Status.REFUND_FAILED, "PAYMENT_019", PARTIAL);

        assertThatCode(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(failed, order(OrderStatus.CANCEL_REQUESTED), false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(failed, order(OrderStatus.PAID), false))
            .isInstanceOf(IllegalStateException.class);
    }
}
