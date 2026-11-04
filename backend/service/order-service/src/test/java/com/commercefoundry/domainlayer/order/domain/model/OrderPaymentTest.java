package com.commercefoundry.domainlayer.order.domain.model;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.domain.model.OrderPayment.Result;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 결제 완료 전이 {@link Order#markPaid} 와 결제 반영 기록 {@link OrderPayment} 의 불변식 (#308). */
class OrderPaymentTest {

    private static final Instant DUE = Instant.parse("2026-10-18T09:30:00Z");
    private static final Instant APPROVED_AT = Instant.parse("2026-10-18T09:10:00.123456789Z");
    private static final UUID EVENT_ID = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
    private static final long PAYMENT_ID = 5_000L;

    private static Order withStatus(OrderStatus status) {
        AtomicLong ids = new AtomicLong(10);
        Order order = Order.place(1L, 7L, domesticQuote(quoteLine(9001, 10_000, 2)), ids::incrementAndGet, address("KR"), 99L, DUE);
        return new Order(order.id(), order.memberId(), status, order.quote(), order.itemIds(), order.shippingAddress(),
            order.stockReservationId(), order.paymentDueAt(), null, null, Instant.EPOCH, 3L);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING_PAYMENT", "EXPIRED"})
    @DisplayName("markPaid — 결제 대기 · 만료면 결제 완료가 되고 선점 아이디가 확정한 선점으로 바뀐다(나머지 값 그대로)")
    void markPaid(OrderStatus status) {
        Order before = withStatus(status);

        Order paid = before.markPaid(123L);

        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.stockReservationId()).isEqualTo(123L);
        assertThat(paid).usingRecursiveComparison().ignoringFields("status", "stockReservationId").isEqualTo(before);
        assertThat(before.markPaid(before.stockReservationId()).stockReservationId()).isEqualTo(99L);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"PENDING_PAYMENT", "EXPIRED"})
    @DisplayName("markPaid — 결제 대기 · 만료가 아니면(이미 결제 완료 · 취소 등) 도메인 전이 예외다")
    void markPaidRejected(OrderStatus status) {
        assertThatThrownBy(() -> withStatus(status).markPaid(123L)).isInstanceOfSatisfying(OrderStatusTransitionException.class, exception -> {
            assertThat(exception.getCurrentStatus()).isEqualTo(status);
            assertThat(exception.getTargetStatus()).isEqualTo(OrderStatus.PAID);
        });
    }

    @Test
    @DisplayName("markPaid — 저장 전 주문 · 양수가 아닌 선점 아이디는 거절한다")
    void markPaidRejectsNewOrderOrInvalidReservation() {
        Order unsaved = Order.place(1L, 7L, domesticQuote(quoteLine(9001, 10_000, 1)), () -> 11L, address("KR"), 99L, DUE);

        assertThatThrownBy(() -> unsaved.markPaid(123L)).isInstanceOf(OrderStatusTransitionException.class);
        assertThatThrownBy(() -> withStatus(OrderStatus.PENDING_PAYMENT).markPaid(0L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결제 완료 이력 — SERVICE 주체 · 사유 PAYMENT_APPROVED, 만료에서도 남는다")
    void paymentApprovedHistory() {
        Order expired = withStatus(OrderStatus.EXPIRED);

        OrderStatusHistory history = OrderStatusHistory.changedByService(20L, expired, expired.markPaid(123L), OrderStatusHistory.PAYMENT_APPROVED);

        assertThat(history.fromStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(history.toStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(history.changedByType()).isEqualTo(StatusChangedBy.SERVICE);
        assertThat(history.changedById()).isNull();
        assertThat(history.reason()).isEqualTo("PAYMENT_APPROVED");
    }

    @Test
    @DisplayName("결제 완료 기록 — 주문 · 회원 · 확정 선점을 결제 완료 주문에서 가져오고 paid_order_id 는 주문 아이디다. 승인 시각은 마이크로초로 자른다")
    void paidRecord() {
        Order paid = withStatus(OrderStatus.PENDING_PAYMENT).markPaid(123L);

        OrderPayment record = OrderPayment.paid(30L, paid, PAYMENT_ID, krw(20_000), APPROVED_AT, EVENT_ID);

        assertThat(record.result()).isEqualTo(Result.PAID);
        assertThat(record.orderId()).isEqualTo(1L);
        assertThat(record.memberId()).isEqualTo(7L);
        assertThat(record.stockReservationId()).isEqualTo(123L);
        assertThat(record.paidOrderId()).isEqualTo(1L);
        assertThat(record.approvedAt()).isEqualTo(Instant.parse("2026-10-18T09:10:00.123456Z"));
        assertThat(record.isNew()).isTrue();
        assertThatThrownBy(() -> OrderPayment.paid(30L, withStatus(OrderStatus.PENDING_PAYMENT), PAYMENT_ID, krw(20_000), APPROVED_AT, EVENT_ID))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = Result.class, mode = EnumSource.Mode.EXCLUDE, names = "PAID")
    @DisplayName("거절 기록 — paid_order_id 는 비고 거절 사유 코드는 회신 reason(30자 이하)이다. 확정 전 거절이면 선점 · 승인 시각이 없어도 된다")
    void rejectedRecord(Result result) {
        OrderPayment record = OrderPayment.rejected(31L, 1L, PAYMENT_ID, 7L, krw(20_000), null, result, EVENT_ID);

        assertThat(record.paidOrderId()).isNull();
        assertThat(record.stockReservationId()).isNull();
        assertThat(result.rejectionReason()).isNotBlank().hasSizeLessThanOrEqualTo(30);
        assertThat(result.name()).isEqualTo("REJECTED_" + result.rejectionReason());
        assertThat(Result.valueOf("REJECTED_PAYMENT_NOT_DONE").rejectionReason()).isEqualTo("PAYMENT_NOT_DONE");
    }

    @ParameterizedTest
    @EnumSource(value = Result.class, mode = EnumSource.Mode.EXCLUDE, names = {"PAID", "REJECTED_ORDER_CANCELED", "REJECTED_PAYMENT_NOT_DONE"})
    @DisplayName("확정 선점 · 복원 클레임은 결제 완료 · 취소 주문 거절에만 — 이중 결제에서 진 쪽(ALREADY_PAID) 등은 이긴 결제의 선점을 가리키지 못한다(복원 시 초과 판매)")
    void confirmedReservationOnlyForPaidOrCanceled(Result result) {
        assertThat(result.recordsConfirmedReservation()).isFalse();
        assertThatThrownBy(() -> new OrderPayment(31L, 1L, PAYMENT_ID, 7L, krw(20_000), APPROVED_AT, result, 123L, EVENT_ID, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPayment(31L, 1L, PAYMENT_ID, 7L, krw(20_000), APPROVED_AT, result, 123L, EVENT_ID, 77L, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("확정 뒤 취소 거절 — 취소 주문 거절 결과 · 확정한 선점 · 복원 클레임을 함께 남기고 복원 시각이 찰 때까지 복원 대상이다(PR-C1)")
    void canceledAfterConfirmation() {
        OrderPayment record = OrderPayment.canceledAfterConfirmation(32L, 1L, PAYMENT_ID, 7L, krw(20_000), APPROVED_AT, 123L, EVENT_ID, 456L);

        assertThat(record.result()).isEqualTo(Result.REJECTED_ORDER_CANCELED);
        assertThat(record.stockReservationId()).isEqualTo(123L);
        assertThat(record.restorationClaimId()).isEqualTo(456L);
        assertThat(record.restoredAt()).isNull();
        assertThat(record.awaitsRestoration()).isTrue();
        assertThat(new OrderPayment(32L, 1L, PAYMENT_ID, 7L, krw(20_000), APPROVED_AT, Result.REJECTED_ORDER_CANCELED, 123L, EVENT_ID, 456L, APPROVED_AT, null)
            .awaitsRestoration()).isFalse();
        assertThat(OrderPayment.rejected(33L, 1L, PAYMENT_ID, 7L, krw(20_000), APPROVED_AT, Result.REJECTED_ORDER_CANCELED, EVENT_ID).awaitsRestoration())
            .isFalse();
        assertThat(record.paidOrderId()).isNull();
        assertThat(Result.PAID.recordsConfirmedReservation()).isTrue();
        assertThat(Result.REJECTED_ORDER_CANCELED.recordsConfirmedReservation()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("isAwaitingPaymentApproval — 결제 대기 · 만료만 true 다")
    void awaitingPaymentApproval(OrderStatus status) {
        assertThat(withStatus(status).isAwaitingPaymentApproval()).isEqualTo(status == OrderStatus.PENDING_PAYMENT || status == OrderStatus.EXPIRED);
    }

    @Test
    void notDoneRestorationRequiresOriginalApprovalAndClaimButKeepsLegacyC1Shapes() {
        OrderPayment pending = OrderPayment.rejectedAfterConfirmation(31L, 1L, PAYMENT_ID, 7L, krw(20_000), APPROVED_AT,
            Result.REJECTED_PAYMENT_NOT_DONE, 123L, EVENT_ID, 77L);
        assertThat(pending.awaitsRestoration()).isTrue();
        assertThat(pending.result().rejectionReason()).isEqualTo("PAYMENT_NOT_DONE");
        assertThatThrownBy(() -> new OrderPayment(31L, 1L, PAYMENT_ID, 7L, krw(20_000), APPROVED_AT,
            Result.REJECTED_PAYMENT_NOT_DONE, 123L, EVENT_ID, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPayment(31L, 1L, PAYMENT_ID, 7L, krw(20_000), null,
            Result.REJECTED_PAYMENT_NOT_DONE, 123L, EVENT_ID, 77L, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new OrderPayment(31L, 1L, PAYMENT_ID, 7L, krw(20_000), null,
            Result.REJECTED_ORDER_CANCELED, 123L, EVENT_ID, null, null, null).awaitsRestoration()).isFalse();
        assertThat(new OrderPayment(31L, 1L, PAYMENT_ID, 7L, krw(20_000), null,
            Result.REJECTED_ORDER_CANCELED, 123L, EVENT_ID, 77L, null, null).awaitsRestoration()).isTrue();
    }

    @Test
    @DisplayName("불변식 — 결제 완료는 확정 선점 · 승인 시각 필수, 복원 클레임은 확정 선점이 있는 취소 거절에만, 복원 시각은 클레임이 있을 때만, 금액은 양수")
    void invariants() {
        assertThat(Result.PAID.rejectionReason()).isNull();
        assertThatThrownBy(() -> OrderPayment.rejected(31L, 1L, PAYMENT_ID, 7L, krw(20_000), null, Result.PAID, EVENT_ID))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPayment(31L, 1L, PAYMENT_ID, 7L, krw(20_000), null, Result.PAID, 123L, EVENT_ID, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPayment(31L, 1L, PAYMENT_ID, 7L, krw(20_000), APPROVED_AT, Result.PAID, null, EVENT_ID, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPayment(31L, 1L, PAYMENT_ID, 7L, krw(20_000), APPROVED_AT, Result.PAID, 123L, EVENT_ID, 77L, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPayment(31L, 1L, PAYMENT_ID, 7L, krw(20_000), APPROVED_AT, Result.REJECTED_ORDER_CANCELED, null, EVENT_ID, 77L, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPayment(31L, 1L, PAYMENT_ID, 7L, krw(20_000), APPROVED_AT, Result.REJECTED_ORDER_CANCELED, 123L, EVENT_ID, null, DUE, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderPayment.rejected(31L, 1L, PAYMENT_ID, 7L, krw(0), null, Result.REJECTED_ORDER_NOT_FOUND, EVENT_ID))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new OrderPayment(31L, 1L, PAYMENT_ID, 7L, krw(20_000), APPROVED_AT, Result.REJECTED_ORDER_CANCELED, 123L, EVENT_ID, 77L, DUE, null)
            .restorationClaimId()).isEqualTo(77L);
    }
}
