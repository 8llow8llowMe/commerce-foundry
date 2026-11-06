package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderStatusHistoryQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.CancellationRefundPolicy;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.StatusChangedBy;
import com.commercefoundry.global.properties.OrderCancellationProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

/** 결제 후 취소 클레임의 운영자 수습 DB 단계 (#17 PR-A) — 상태 전제별 성공 · 바꾸지 않은 성공 · 409 · 원상태 조회 실패 · 미확정 거절 금지. */
class ClaimAdminActionProcessorTest {

    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final long ADMIN = 9L;
    private static final long CLAIM = 400L;
    private static final String REASON = "PG 거절 확인";

    private final OrderRepositoryPort orders = mock(OrderRepositoryPort.class);
    private final ClaimRepositoryPort claims = mock(ClaimRepositoryPort.class);
    private final SnowflakeIdGenerator ids = new SnowflakeIdGenerator(3, 3);
    private final AtomicLong versions = new AtomicLong(10);

    private ClaimAdminActionProcessor processor(boolean paidCancellationOpen) {
        var retry = new OrderCancellationProperties.Retry(paidCancellationOpen, Duration.ofMinutes(1), 50, 3, Duration.ofMinutes(1), Duration.ofHours(1), LEASE);
        return new ClaimAdminActionProcessor(claims, orders, new OrderCancellationProperties(false, retry), ids, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ClaimAdminActionProcessor processor() {
        return processor(true);
    }

    @BeforeEach
    void storeClaims() {
        when(claims.update(any())).thenAnswer(invocation -> withVersion(invocation.getArgument(0), versions.incrementAndGet()));
    }

    private static Claim withVersion(Claim claim, long version) {
        return new Claim(claim.id(), claim.orderId(), claim.requestKey(), claim.type(), claim.status(), claim.expectedRefund(), claim.paymentId(),
            claim.attemptCount(), claim.nextAttemptAt(), claim.failureCode(), claim.decision(), claim.items(), NOW, version);
    }

    private static Order order(OrderStatus status) {
        AtomicLong itemIds = new AtomicLong(100);
        Order placed = Order.place(1L, 7L, domesticQuote(quoteLine(9001, 10_000, 2)), itemIds::incrementAndGet, address("KR"), 99L, NOW);
        return new Order(placed.id(), 7L, status, placed.quote(), placed.itemIds(), placed.shippingAddress(), placed.stockReservationId(),
            placed.paymentDueAt(), null, null, Instant.EPOCH, 2L);
    }

    private static Claim requested() {
        Order order = order(OrderStatus.PAID);
        return withVersion(Claim.requestCancellation(CLAIM, order, 77L, CancellationRefundPolicy.fullRefund(order), OrderCancelReason.OTHER,
            new AtomicLong(500)::incrementAndGet, NOW, LEASE), 1L);
    }

    private static Claim refundFailed(String code) {
        return requested().failRefund(code);
    }

    private static Claim restorationFailed(String code) {
        return requested().markRefunded(NOW).failRestoration(code);
    }

    private void givenClaim(Claim claim) {
        when(claims.findById(CLAIM)).thenReturn(Optional.of(claim));
    }

    private void givenOrder(Order order, OrderStatus... historyFroms) {
        when(orders.findById(order.id())).thenReturn(Optional.of(order));
        List<OrderStatusHistoryQueryResult> histories = new java.util.ArrayList<>();
        histories.add(new OrderStatusHistoryQueryResult(null, OrderStatus.PENDING_PAYMENT, StatusChangedBy.CUSTOMER, 7L, null, NOW));
        for (OrderStatus from : historyFroms) {
            histories.add(new OrderStatusHistoryQueryResult(from, OrderStatus.CANCEL_REQUESTED, StatusChangedBy.CUSTOMER, 7L, "OTHER", NOW));
        }
        when(orders.findHistories(order.id())).thenReturn(histories);
    }

    private static void assertRejected(ThrowableAssert.ThrowingCallable call, OrderErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    // --- 재시도 ---

    @Test
    @DisplayName("재시도 — 환불 실패는 환불 대기로, 복원 실패는 복원 대기로 다시 열어(횟수 0 · 다음 시각 now + lease) 저장하고 바뀐 것으로 돌려준다")
    void retryReopensFailedClaims() {
        givenClaim(refundFailed("PAYMENT_016"));
        ClaimAdminActionProcessor.Prepared refund = processor().prepareRetry(CLAIM);
        givenClaim(restorationFailed("PRODUCT_028"));
        ClaimAdminActionProcessor.Prepared restoration = processor().prepareRetry(CLAIM);

        assertThat(refund.changed()).isTrue();
        assertThat(refund.claim().status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(refund.claim().failureCode()).isNull();
        assertThat(refund.claim().nextAttemptAt()).isEqualTo(NOW.plus(LEASE));
        assertThat(restoration.claim().status()).isEqualTo(Claim.Status.REFUNDED);
        assertThat(restoration.claim().attemptCount()).isZero();
    }

    @Test
    @DisplayName("재시도 — 대기 상태는 선점(다음 시각만 lease 로 미룸), 완료는 아무것도 쓰지 않고 바뀌지 않은 것으로 돌려준다")
    void retryHoldsAwaitingAndSkipsCompleted() {
        Claim awaiting = requested().retryLater(NOW, Duration.ofMinutes(1), Duration.ofHours(1));
        givenClaim(awaiting);

        ClaimAdminActionProcessor.Prepared held = processor().prepareRetry(CLAIM);

        assertThat(held.changed()).isTrue();
        assertThat(held.claim().status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(held.claim().attemptCount()).isEqualTo(1);
        assertThat(held.claim().nextAttemptAt()).isEqualTo(NOW.plus(LEASE));

        Claim completed = requested().markRefunded(NOW).complete();
        givenClaim(completed);
        ClaimAdminActionProcessor.Prepared unchanged = processor().prepareRetry(CLAIM);
        assertThat(unchanged.changed()).isFalse();
        assertThat(unchanged.claim()).isSameAs(completed);
        verify(claims).update(any());
    }

    @Test
    @DisplayName("재시도 — 거절 · 주문 상태 이상으로 닫힌 복원 실패 · 반품은 409 ORDER_036, 없는 클레임 404 ORDER_032, 스케줄러 꺼짐 503 ORDER_035 — 아무것도 쓰지 않는다")
    void retryRejections() {
        givenClaim(refundFailed("PAYMENT_016").reject(new Claim.Decision(REASON, ADMIN, NOW)));
        assertRejected(() -> processor().prepareRetry(CLAIM), OrderErrorCode.CLAIM_STATUS_INVALID);
        givenClaim(restorationFailed(OrderCancellationProcessor.ORDER_STATUS_UNEXPECTED));
        assertRejected(() -> processor().prepareRetry(CLAIM), OrderErrorCode.CLAIM_STATUS_INVALID);
        givenClaim(new Claim(CLAIM, 1L, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 0L));
        assertRejected(() -> processor().prepareRetry(CLAIM), OrderErrorCode.CLAIM_STATUS_INVALID);
        when(claims.findById(CLAIM)).thenReturn(Optional.empty());
        assertRejected(() -> processor().prepareRetry(CLAIM), OrderErrorCode.NOT_FOUND_CLAIM);
        givenClaim(refundFailed("PAYMENT_016"));
        assertRejected(() -> processor(false).prepareRetry(CLAIM), OrderErrorCode.CANCELLATION_UNAVAILABLE);
        verify(claims, never()).update(any());
    }

    // --- 거절 ---

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "MAKING", "PREPARING"})
    @DisplayName("거절 — 클레임 REJECTED(결정 칸) · 주문은 취소 요청으로 바뀐 마지막 이력의 이전 상태 · ADMIN 이력(사유)을 함께 저장한다")
    void rejectRestoresStatusBeforeCancellation(OrderStatus before) {
        Order order = order(OrderStatus.CANCEL_REQUESTED);
        givenClaim(refundFailed("PAYMENT_016"));
        // 앞선 취소 요청(결제 완료에서)이 있었어도 마지막 줄을 따른다
        givenOrder(order, OrderStatus.PAID, before);

        assertThat(processor().reject(CLAIM, REASON, ADMIN)).isTrue();

        ArgumentCaptor<Claim> claim = ArgumentCaptor.forClass(Claim.class);
        verify(claims).update(claim.capture());
        assertThat(claim.getValue().status()).isEqualTo(Claim.Status.REJECTED);
        assertThat(claim.getValue().decision()).isEqualTo(new Claim.Decision(REASON, ADMIN, NOW));
        ArgumentCaptor<Order> reverted = ArgumentCaptor.forClass(Order.class);
        ArgumentCaptor<OrderStatusHistory> history = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(orders).saveStatusChange(reverted.capture(), history.capture());
        assertThat(reverted.getValue().status()).isEqualTo(before);
        assertThat(history.getValue().fromStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(history.getValue().toStatus()).isEqualTo(before);
        assertThat(history.getValue().changedByType()).isEqualTo(StatusChangedBy.ADMIN);
        assertThat(history.getValue().changedById()).isEqualTo(ADMIN);
        assertThat(history.getValue().reason()).isEqualTo(REASON);
    }

    @Test
    @DisplayName("거절 — 이미 거절이면 아무것도 쓰지 않고 false, 미확정(REFUND_UNCONFIRMED)은 409 ORDER_038, 환불 실패가 아니면 409 ORDER_036")
    void rejectPreconditions() {
        givenClaim(refundFailed("PAYMENT_016").reject(new Claim.Decision(REASON, ADMIN, NOW)));
        assertThat(processor().reject(CLAIM, REASON, ADMIN)).isFalse();
        givenClaim(refundFailed(Claim.REFUND_UNCONFIRMED));
        assertRejected(() -> processor().reject(CLAIM, REASON, ADMIN), OrderErrorCode.REFUND_UNVERIFIED);
        for (Claim other : List.of(requested(), requested().markRefunded(NOW), restorationFailed("PRODUCT_028"))) {
            givenClaim(other);
            assertRejected(() -> processor().reject(CLAIM, REASON, ADMIN), OrderErrorCode.CLAIM_STATUS_INVALID);
        }
        verify(claims, never()).update(any());
        verify(orders, never()).saveStatusChange(any(), any());
    }

    @Test
    @DisplayName("거절 — 되돌릴 상태를 이력에서 못 찾으면 500 ORDER_041, 주문이 취소 요청이 아니면 409 ORDER_020 이고 아무것도 쓰지 않는다")
    void rejectNeedsCancelRequestedOrderAndOriginHistory() {
        givenClaim(refundFailed("PAYMENT_016"));
        Order requested = order(OrderStatus.CANCEL_REQUESTED);
        givenOrder(requested);
        assertRejected(() -> processor().reject(CLAIM, REASON, ADMIN), OrderErrorCode.CANCELLATION_ORIGIN_NOT_FOUND);
        givenOrder(requested, OrderStatus.SHIPPING);
        assertRejected(() -> processor().reject(CLAIM, REASON, ADMIN), OrderErrorCode.CANCELLATION_ORIGIN_NOT_FOUND);
        givenOrder(order(OrderStatus.CANCELED), OrderStatus.PAID);
        assertRejected(() -> processor().reject(CLAIM, REASON, ADMIN), OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);
        verify(claims, never()).update(any());
        verify(orders, never()).saveStatusChange(any(), any());
    }

    // --- 수동 환불 확정 ---

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CANCEL_REQUESTED", "CANCELED"})
    @DisplayName("수동 환불 확정 — 환불 실패(코드 무관)를 REFUNDED(결정 칸 · 다음 시각 lease)로, 주문이 취소 요청이면 취소 완료 + ADMIN 이력(사유)")
    void confirmRefundMarksRefundedAndCancelsOrder(OrderStatus orderStatus) {
        givenClaim(refundFailed(Claim.REFUND_UNCONFIRMED));
        givenOrder(order(orderStatus), OrderStatus.PAID);

        Optional<Claim> confirmed = processor().confirmRefund(CLAIM, REASON, ADMIN);

        assertThat(confirmed).hasValueSatisfying(claim -> {
            assertThat(claim.status()).isEqualTo(Claim.Status.REFUNDED);
            assertThat(claim.decision()).isEqualTo(new Claim.Decision(REASON, ADMIN, NOW));
            assertThat(claim.nextAttemptAt()).isEqualTo(NOW.plus(LEASE));
            assertThat(claim.version()).isEqualTo(11L);
        });
        if (orderStatus == OrderStatus.CANCEL_REQUESTED) {
            ArgumentCaptor<Order> canceled = ArgumentCaptor.forClass(Order.class);
            ArgumentCaptor<OrderStatusHistory> history = ArgumentCaptor.forClass(OrderStatusHistory.class);
            verify(orders).saveStatusChange(canceled.capture(), history.capture());
            assertThat(canceled.getValue().status()).isEqualTo(OrderStatus.CANCELED);
            assertThat(history.getValue().changedByType()).isEqualTo(StatusChangedBy.ADMIN);
            assertThat(history.getValue().reason()).isEqualTo(REASON);
        } else {
            verify(orders, never()).saveStatusChange(any(), any());
        }
    }

    @Test
    @DisplayName("수동 환불 확정 — 이미 환불 확정 뒤(REFUNDED · COMPLETED · RESTORATION_FAILED)면 빈 값, 접수 · 거절은 409 ORDER_036, 주문이 이상하면 409 ORDER_020")
    void confirmRefundPreconditions() {
        for (Claim done : List.of(requested().markRefunded(NOW), requested().markRefunded(NOW).complete(), restorationFailed("PRODUCT_028"))) {
            givenClaim(done);
            assertThat(processor().confirmRefund(CLAIM, REASON, ADMIN)).as(done.status().name()).isEmpty();
        }
        for (Claim other : List.of(requested(), refundFailed("PAYMENT_016").reject(new Claim.Decision(REASON, ADMIN, NOW)))) {
            givenClaim(other);
            assertRejected(() -> processor().confirmRefund(CLAIM, REASON, ADMIN), OrderErrorCode.CLAIM_STATUS_INVALID);
        }
        givenClaim(refundFailed("PAYMENT_016"));
        givenOrder(order(OrderStatus.PAID));
        assertRejected(() -> processor().confirmRefund(CLAIM, REASON, ADMIN), OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);
        verify(claims, never()).update(any());
        verify(orders, never()).saveStatusChange(any(), any());
    }

    // --- 복원 생략 종결 ---

    @Test
    @DisplayName("복원 생략 종결 — 재고 복원 실패만 COMPLETED(결정 칸), 이미 완료면 false, 그 밖은 409 ORDER_036. 주문은 건드리지 않는다")
    void completeOnlyRestorationFailed() {
        givenClaim(restorationFailed("PRODUCT_028"));
        assertThat(processor().complete(CLAIM, REASON, ADMIN)).isTrue();
        ArgumentCaptor<Claim> claim = ArgumentCaptor.forClass(Claim.class);
        verify(claims).update(claim.capture());
        assertThat(claim.getValue().status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(claim.getValue().decision().reason()).isEqualTo(REASON);

        givenClaim(requested().markRefunded(NOW).complete());
        assertThat(processor().complete(CLAIM, REASON, ADMIN)).isFalse();
        for (Claim other : List.of(requested(), requested().markRefunded(NOW), refundFailed("PAYMENT_016"))) {
            givenClaim(other);
            assertRejected(() -> processor().complete(CLAIM, REASON, ADMIN), OrderErrorCode.CLAIM_STATUS_INVALID);
        }
        verify(claims).update(any());
        verify(orders, never()).saveStatusChange(any(), any());
    }
}
