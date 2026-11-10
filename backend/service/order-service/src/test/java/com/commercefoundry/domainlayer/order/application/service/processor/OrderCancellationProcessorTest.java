package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.CancellationStepOutcome;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.CancellationRefundPolicy;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.StatusChangedBy;
import com.commercefoundry.global.properties.OrderCancellationProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

/** 결제 후 취소의 DB 단계 (#16 PR-2) — 접수 판정 · 결제 기록 대조 · 환불 · 복원 결과 반영 · 재시도 상한 · 선점. */
class OrderCancellationProcessorTest {

    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final long MEMBER = 7L;
    private static final long PAYMENT = 77L;
    private static final int MAX_ATTEMPTS = 3;

    private final OrderRepositoryPort orders = mock(OrderRepositoryPort.class);
    private final ClaimRepositoryPort claims = mock(ClaimRepositoryPort.class);
    private final OrderPaymentRepositoryPort payments = mock(OrderPaymentRepositoryPort.class);
    private final SnowflakeIdGenerator ids = new SnowflakeIdGenerator(3, 3);
    private final AtomicLong versions = new AtomicLong(10);

    private OrderCancellationProcessor processor(boolean allowWhileMaking) {
        return processor(allowWhileMaking, true);
    }

    private OrderCancellationProcessor processor(boolean allowWhileMaking, boolean paidCancellationOpen) {
        var retry = new OrderCancellationProperties.Retry(paidCancellationOpen, Duration.ofMinutes(1), 50, MAX_ATTEMPTS, Duration.ofMinutes(1),
            Duration.ofHours(1), Duration.ofMinutes(2));
        return new OrderCancellationProcessor(orders, claims, payments, new OrderCancellationProperties(allowWhileMaking, retry), ids,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void storeClaims() {
        when(claims.insert(any())).thenAnswer(invocation -> stored(invocation.getArgument(0), 0L));
        when(claims.update(any())).thenAnswer(invocation -> stored(invocation.getArgument(0), versions.incrementAndGet()));
    }

    private static Claim stored(Claim claim, long version) {
        return new Claim(claim.id(), claim.orderId(), claim.requestKey(), claim.type(), claim.status(), claim.expectedRefund(), claim.paymentId(),
            claim.attemptCount(), claim.nextAttemptAt(), claim.failureCode(), claim.items(), NOW, version);
    }

    private static Order order(OrderStatus status) {
        AtomicLong itemIds = new AtomicLong(100);
        Order placed = Order.place(1L, MEMBER, domesticQuote(quoteLine(9001, 10_000, 2), quoteLine(9002, 5_000, 1)), itemIds::incrementAndGet,
            address("KR"), 99L, NOW);
        return new Order(placed.id(), MEMBER, status, placed.quote(), placed.itemIds(), placed.shippingAddress(), placed.stockReservationId(),
            placed.paymentDueAt(), null, null, Instant.EPOCH, 2L);
    }

    private static OrderPayment paidRecord(Order order, long amount) {
        Order paid = new Order(order.id(), order.memberId(), OrderStatus.PAID, order.quote(), order.itemIds(), order.shippingAddress(),
            order.stockReservationId(), order.paymentDueAt(), null, null, Instant.EPOCH, 1L);
        return OrderPayment.paid(5L, paid, PAYMENT, krw(amount), NOW, UUID.randomUUID());
    }

    private static Claim requestedClaim() {
        Order order = order(OrderStatus.PAID);
        return stored(Claim.requestCancellation(400L, order, PAYMENT, CancellationRefundPolicy.fullRefund(order), OrderCancelReason.OTHER,
            new AtomicLong(500)::incrementAndGet, NOW, Duration.ofMinutes(2)), 0L);
    }

    private static Claim withAttempts(Claim claim, int attempts) {
        return new Claim(claim.id(), claim.orderId(), claim.requestKey(), claim.type(), claim.status(), claim.expectedRefund(), claim.paymentId(),
            attempts, claim.nextAttemptAt(), claim.failureCode(), claim.items(), claim.requestedAt(), claim.version());
    }

    private void givenLocked(Order order) {
        when(orders.findByIdAndMemberIdForUpdate(order.id(), MEMBER)).thenReturn(Optional.of(order));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "PREPARING"})
    @DisplayName("접수 — 환불액은 전체 취소액(배송비 포함)이고 결제 기록의 결제 아이디로, 다음 시각 now + lease 의 REQUESTED 클레임 · 취소 요청 · 고객 이력을 남긴다")
    void requestCreatesClaimStatusAndHistory(OrderStatus status) {
        Order order = order(status);
        givenLocked(order);
        when(payments.findPaidByOrderId(order.id())).thenReturn(Optional.of(paidRecord(order, 28_000)));

        OrderCancellationProcessor.Requested requested = processor(false).request(MEMBER, order.id(), OrderCancelReason.ORDER_MISTAKE);

        assertThat(requested.created()).isTrue();
        Claim claim = requested.claim();
        assertThat(claim.type()).isEqualTo(Claim.Type.CANCELLATION);
        assertThat(claim.status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(claim.paymentId()).isEqualTo(PAYMENT);
        assertThat(claim.expectedRefund()).isEqualTo(krw(28_000));
        assertThat(claim.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        assertThat(claim.requestKey()).isEqualTo(Claim.cancellationRequestKey(order.id()));
        assertThat(claim.items()).extracting(ClaimItem::reason).containsOnly(ClaimItem.Reason.ORDER_MISTAKE);
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        ArgumentCaptor<OrderStatusHistory> history = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(orders).saveStatusChange(saved.capture(), history.capture());
        assertThat(saved.getValue().status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(history.getValue().fromStatus()).isEqualTo(status);
        assertThat(history.getValue().changedByType()).isEqualTo(StatusChangedBy.CUSTOMER);
        assertThat(history.getValue().reason()).isEqualTo("ORDER_MISTAKE");
    }

    @Test
    @DisplayName("제작 중은 allow-while-making 일 때만 접수하고, 아니면 409 ORDER_020 이다")
    void makingFollowsFlag() {
        Order making = order(OrderStatus.MAKING);
        givenLocked(making);
        when(payments.findPaidByOrderId(making.id())).thenReturn(Optional.of(paidRecord(making, 28_000)));

        assertRejected(() -> processor(false).request(MEMBER, making.id(), OrderCancelReason.OTHER), OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);
        verify(claims, never()).insert(any());
        assertThat(processor(true).request(MEMBER, making.id(), OrderCancelReason.OTHER).created()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING_PAYMENT", "EXPIRED", "SHIPPING", "DELIVERED", "PURCHASE_CONFIRMED", "RETURN_REQUESTED", "RETURNED"})
    @DisplayName("결제 대기(판정 뒤 바뀜) · 만료 · 배송 중 이후는 409 ORDER_020 이고 아무것도 쓰지 않는다")
    void rejectsOtherStatuses(OrderStatus status) {
        Order order = order(status);
        givenLocked(order);

        assertRejected(() -> processor(true).request(MEMBER, order.id(), OrderCancelReason.OTHER), OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);
        verifyNoInteractions(payments);
        verify(orders, never()).saveStatusChange(any(), any());
        verify(claims, never()).insert(any());
    }

    @Test
    @DisplayName("없거나 남의 주문은 404 ORDER_001")
    void notFound() {
        when(orders.findByIdAndMemberIdForUpdate(1L, MEMBER)).thenReturn(Optional.empty());

        assertRejected(() -> processor(false).request(MEMBER, 1L, OrderCancelReason.OTHER), OrderErrorCode.NOT_FOUND_ORDER);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CANCEL_REQUESTED", "CANCELED"})
    @DisplayName("이미 취소 요청 · 취소 완료이고 취소 클레임이 있으면 쓰지 않고 재생, 클레임이 없으면 409 ORDER_020 이다")
    void replayOrReject(OrderStatus status) {
        Order order = order(status);
        givenLocked(order);
        Claim existing = requestedClaim();
        when(claims.findByOrderIdAndRequestKey(order.id(), Claim.cancellationRequestKey(order.id()))).thenReturn(Optional.of(existing), Optional.empty());

        OrderCancellationProcessor.Requested replay = processor(false).request(MEMBER, order.id(), OrderCancelReason.OTHER);

        assertThat(replay.created()).isFalse();
        assertThat(replay.claim()).isSameAs(existing);
        assertRejected(() -> processor(false).request(MEMBER, order.id(), OrderCancelReason.OTHER), OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);
        verify(orders, never()).saveStatusChange(any(), any());
        verify(claims, never()).insert(any());
    }

    @Test
    @DisplayName("결제 완료 기록이 없으면 500 ORDER_033, 환불액과 승인 금액이 다르면 500 ORDER_034 — 아무것도 쓰지 않는다")
    void paymentRecordAnomalies() {
        Order order = order(OrderStatus.PAID);
        givenLocked(order);
        when(payments.findPaidByOrderId(order.id())).thenReturn(Optional.empty(), Optional.of(paidRecord(order, 25_000)));

        assertRejected(() -> processor(false).request(MEMBER, order.id(), OrderCancelReason.OTHER), OrderErrorCode.CANCELLATION_PAYMENT_MISSING);
        assertRejected(() -> processor(false).request(MEMBER, order.id(), OrderCancelReason.OTHER), OrderErrorCode.CANCELLATION_AMOUNT_MISMATCH);
        verify(orders, never()).saveStatusChange(any(), any());
        verify(claims, never()).insert(any());
    }

    @Test
    @DisplayName("재생 판정 — 취소 요청 · 취소 완료 + 취소 클레임이면 그 주문, 아니면 빈 값, 없는 주문은 404")
    void findReplay() {
        Order requested = order(OrderStatus.CANCEL_REQUESTED);
        Order paid = order(OrderStatus.PAID);
        when(orders.findByIdAndMemberId(1L, MEMBER)).thenReturn(Optional.of(requested), Optional.of(requested), Optional.of(paid), Optional.empty());
        when(claims.findByOrderIdAndRequestKey(eq(1L), any())).thenReturn(Optional.of(requestedClaim()), Optional.empty());

        assertThat(processor(false).findReplay(MEMBER, 1L)).containsSame(requested);
        assertThat(processor(false).findReplay(MEMBER, 1L)).isEmpty();
        assertThat(processor(false).findReplay(MEMBER, 1L)).isEmpty();
        assertRejected(() -> processor(false).findReplay(MEMBER, 1L), OrderErrorCode.NOT_FOUND_ORDER);
    }

    @Test
    @DisplayName("환불 확정 — 클레임 REFUNDED(다음 시각 now + lease) + 주문 취소 완료 + SERVICE 이력")
    void refundSucceeded() {
        Claim claim = requestedClaim();
        Order requested = order(OrderStatus.CANCEL_REQUESTED);
        when(orders.findById(claim.orderId())).thenReturn(Optional.of(requested));

        Claim refunded = processor(false).recordRefund(claim, CancellationStepOutcome.done());

        assertThat(refunded.status()).isEqualTo(Claim.Status.REFUNDED);
        assertThat(refunded.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        assertThat(refunded.version()).isPositive();
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        ArgumentCaptor<OrderStatusHistory> history = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(orders).saveStatusChange(saved.capture(), history.capture());
        assertThat(saved.getValue().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(history.getValue().changedByType()).isEqualTo(StatusChangedBy.SERVICE);
        assertThat(history.getValue().reason()).isEqualTo(OrderStatusHistory.CANCELLATION_REFUNDED);
    }

    @Test
    @DisplayName("주문이 이미 취소 완료면 클레임만 REFUNDED 로 바꾸고 주문 · 이력은 쓰지 않는다")
    void refundSucceededOnCanceledOrder() {
        Claim claim = requestedClaim();
        when(orders.findById(claim.orderId())).thenReturn(Optional.of(order(OrderStatus.CANCELED)));

        assertThat(processor(false).recordRefund(claim, CancellationStepOutcome.done()).status()).isEqualTo(Claim.Status.REFUNDED);
        verify(orders, never()).saveStatusChange(any(), any());
    }

    @Test
    @DisplayName("미확정으로 닫힌 클레임(REFUND_UNCONFIRMED)의 늦은 환불 성공도 받아 REFUNDED + 주문 취소 완료다")
    void lateRefundSucceeded() {
        Claim unconfirmed = stored(requestedClaim().failRefund(Claim.REFUND_UNCONFIRMED), 4L);
        when(orders.findById(unconfirmed.orderId())).thenReturn(Optional.of(order(OrderStatus.CANCEL_REQUESTED)));

        assertThat(processor(false).recordRefund(unconfirmed, CancellationStepOutcome.done()).status()).isEqualTo(Claim.Status.REFUNDED);
        verify(orders).saveStatusChange(any(), any());
    }

    @Test
    @DisplayName("환불 거절은 REFUND_FAILED(원격 코드)이고 주문은 취소 요청 그대로다")
    void refundRejected() {
        Claim failed = processor(false).recordRefund(requestedClaim(), CancellationStepOutcome.failed("PAYMENT_016"));

        assertThat(failed.status()).isEqualTo(Claim.Status.REFUND_FAILED);
        assertThat(failed.failureCode()).isEqualTo("PAYMENT_016");
        verifyNoInteractions(orders);
    }

    @Test
    @DisplayName("환불 미확정은 상한 안이면 횟수 +1 · 다음 시각 1분 뒤, 상한에 닿으면 REFUND_FAILED(REFUND_UNCONFIRMED)")
    void refundUnknownRetriesUntilMaxAttempts() {
        Claim retried = processor(false).recordRefund(withAttempts(requestedClaim(), MAX_ATTEMPTS - 1), CancellationStepOutcome.retry());
        Claim exhausted = processor(false).recordRefund(withAttempts(requestedClaim(), MAX_ATTEMPTS), CancellationStepOutcome.retry());

        assertThat(retried.status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(retried.attemptCount()).isEqualTo(MAX_ATTEMPTS);
        assertThat(retried.nextAttemptAt()).isAfter(NOW);
        assertThat(exhausted.status()).isEqualTo(Claim.Status.REFUND_FAILED);
        assertThat(exhausted.failureCode()).isEqualTo(Claim.REFUND_UNCONFIRMED);
        verifyNoInteractions(orders);
    }

    @Test
    @DisplayName("이미 닫힌 클레임에 온 미확정 · 거절은 클레임을 바꾸지 않는다")
    void nonRequestedClaimIgnoresNonSuccess() {
        Claim unconfirmed = stored(requestedClaim().failRefund(Claim.REFUND_UNCONFIRMED), 4L);

        assertThat(processor(false).recordRefund(unconfirmed, CancellationStepOutcome.retry())).isSameAs(unconfirmed);
        assertThat(processor(false).recordRefund(unconfirmed, CancellationStepOutcome.failed("PAYMENT_016"))).isSameAs(unconfirmed);
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("복원 결과 — 성공 COMPLETED, 종결 RESTORATION_FAILED(PRODUCT 코드), 미확정은 상한 안이면 재시도 · 넘으면 RESTORATION_UNCONFIRMED")
    void restorationOutcomes() {
        Claim refunded = stored(requestedClaim().markRefunded(NOW), 1L);

        assertThat(processor(false).recordRestoration(refunded, CancellationStepOutcome.done()).status()).isEqualTo(Claim.Status.COMPLETED);
        Claim failed = processor(false).recordRestoration(refunded, CancellationStepOutcome.failed("PRODUCT_028"));
        assertThat(failed.status()).isEqualTo(Claim.Status.RESTORATION_FAILED);
        assertThat(failed.failureCode()).isEqualTo("PRODUCT_028");
        Claim retried = processor(false).recordRestoration(refunded, CancellationStepOutcome.retry());
        assertThat(retried.status()).isEqualTo(Claim.Status.REFUNDED);
        assertThat(retried.attemptCount()).isEqualTo(1);
        Claim exhausted = processor(false).recordRestoration(withAttempts(refunded, MAX_ATTEMPTS), CancellationStepOutcome.retry());
        assertThat(exhausted.status()).isEqualTo(Claim.Status.RESTORATION_FAILED);
        assertThat(exhausted.failureCode()).isEqualTo(Claim.RESTORATION_UNCONFIRMED);
        verifyNoInteractions(orders);
    }

    @Test
    @DisplayName("선점 — 상태 · 횟수는 두고 다음 시각만 now + lease 로 미뤄 저장한다")
    void holdDefersNextAttempt() {
        Claim claim = withAttempts(requestedClaim(), 2);

        Claim held = processor(false).hold(claim);

        assertThat(held.status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(held.attemptCount()).isEqualTo(2);
        assertThat(held.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        verify(claims).update(any());
    }

    @Test
    @DisplayName("재시도 대상은 지금 시각 · batch-size 로 읽는다")
    void retryTargetsUseNowAndBatchSize() {
        processor(false).findRetryTargets();

        verify(claims).findCancellationRetryTargets(NOW, 50);
        verify(orders, never()).findById(anyLong());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "PREPARING", "MAKING"})
    @DisplayName("재시도 스케줄러가 꺼져 있으면(결제 후 취소 닫힘) 결제 후 상태는 접수 전에 503 ORDER_035 이고 아무것도 쓰지 않는다")
    void closedGateRejectsPaidCancellation(OrderStatus status) {
        Order order = order(status);
        givenLocked(order);

        assertRejected(() -> processor(true, false).request(MEMBER, order.id(), OrderCancelReason.OTHER), OrderErrorCode.CANCELLATION_UNAVAILABLE);
        verifyNoInteractions(payments);
        verify(orders, never()).saveStatusChange(any(), any());
        verify(claims, never()).insert(any());
    }

    @Test
    @DisplayName("게이트가 닫혀도 이미 접수된 취소의 재요청은 재생이고, 배송 중 이후는 여전히 ORDER_020 이다")
    void closedGateStillReplaysAndKeepsStatusRejection() {
        Order requested = order(OrderStatus.CANCEL_REQUESTED);
        Order shipping = new Order(2L, MEMBER, OrderStatus.SHIPPING, requested.quote(), requested.itemIds(), requested.shippingAddress(), 98L,
            requested.paymentDueAt(), null, null, Instant.EPOCH, 2L);
        givenLocked(requested);
        givenLocked(shipping);
        Claim existing = requestedClaim();
        when(claims.findByOrderIdAndRequestKey(requested.id(), Claim.cancellationRequestKey(requested.id()))).thenReturn(Optional.of(existing));

        assertThat(processor(false, false).request(MEMBER, requested.id(), OrderCancelReason.OTHER).claim()).isSameAs(existing);
        assertRejected(() -> processor(false, false).request(MEMBER, shipping.id(), OrderCancelReason.OTHER), OrderErrorCode.ORDER_STATUS_TRANSITION_INVALID);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "PREPARING", "SHIPPING", "PENDING_PAYMENT"})
    @DisplayName("환불은 확정됐는데 주문이 취소 요청 · 취소 완료가 아니면(데이터 이상) 복원하지 않고 RESTORATION_FAILED(ORDER_STATUS_UNEXPECTED), 주문은 두다")
    void refundOnUnexpectedOrderStatusClosesClaim(OrderStatus status) {
        Claim claim = requestedClaim();
        when(orders.findById(claim.orderId())).thenReturn(Optional.of(order(status)));

        Claim closed = processor(false).recordRefund(claim, CancellationStepOutcome.done());

        assertThat(closed.status()).isEqualTo(Claim.Status.RESTORATION_FAILED);
        assertThat(closed.failureCode()).isEqualTo(OrderCancellationProcessor.ORDER_STATUS_UNEXPECTED);
        assertThat(closed.nextAttemptAt()).isNull();
        verify(orders, never()).saveStatusChange(any(), any());
    }

    @Test
    @DisplayName("반영하지 못한 시도는 다시 읽은 클레임 단계의 미확정 한 번으로 세고, 상한이면 *_UNCONFIRMED, 종결 · 없음이면 아무것도 하지 않는다")
    void attemptFailureCountsOnCurrentClaim() {
        Claim requested = requestedClaim();
        Claim refundedAtMax = withAttempts(stored(requestedClaim().markRefunded(NOW), 3L), MAX_ATTEMPTS);
        Claim completed = stored(requestedClaim().markRefunded(NOW).complete(), 5L);
        when(claims.findByOrderIdAndRequestKey(eq(1L), any()))
            .thenReturn(Optional.of(requested), Optional.of(refundedAtMax), Optional.of(completed), Optional.empty());
        ArgumentCaptor<Claim> saved = ArgumentCaptor.forClass(Claim.class);

        for (Claim attempted : java.util.List.of(requested, refundedAtMax, completed, requested)) {
            processor(false).recordAttemptFailure(attempted);
        }

        verify(claims, times(2)).update(saved.capture());
        assertThat(saved.getAllValues().get(0).status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(saved.getAllValues().get(0).attemptCount()).isEqualTo(1);
        assertThat(saved.getAllValues().get(1).status()).isEqualTo(Claim.Status.RESTORATION_FAILED);
        assertThat(saved.getAllValues().get(1).failureCode()).isEqualTo(Claim.RESTORATION_UNCONFIRMED);
    }

    @Test
    void oldAttemptCannotCountFailureAgainstNewLeaseOrPhase() {
        Claim attempted = requestedClaim();
        for (Claim current : java.util.List.of(stored(attempted.holdUntil(NOW.plusSeconds(60)), 1L), stored(attempted.markRefunded(NOW), 1L))) {
            when(claims.findByOrderIdAndRequestKey(eq(1L), any())).thenReturn(Optional.of(current));
            processor(false).recordAttemptFailure(attempted);
        }
        verify(claims, never()).update(any());
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, OrderErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
