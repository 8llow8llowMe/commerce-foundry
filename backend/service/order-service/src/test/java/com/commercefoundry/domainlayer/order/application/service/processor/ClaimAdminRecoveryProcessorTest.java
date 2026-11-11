package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
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
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** 운영자 다시 시도의 선점 (#17 A2b) — 화면 버전 대조 · 자격 · lease · 저장 경합. 테스트 값은 모두 가짜다. */
class ClaimAdminRecoveryProcessorTest {
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final List<ClaimItem> ITEMS = List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.OTHER));

    private final ClaimRepositoryPort claims = mock(ClaimRepositoryPort.class);
    private final OrderRepositoryPort orders = mock(OrderRepositoryPort.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private static OrderCancellationProperties properties(boolean enabled) {
        return new OrderCancellationProperties(false, new OrderCancellationProperties.Retry(enabled, Duration.ofMinutes(1), 50, 48, Duration.ofMinutes(1),
            Duration.ofHours(1), LEASE));
    }

    private ClaimAdminRecoveryProcessor processor(boolean enabled) {
        return new ClaimAdminRecoveryProcessor(claims, orders, new SnowflakeIdGenerator(1, 3), properties(enabled), clock);
    }

    private Order storedOrder(OrderStatus status) {
        Order placed = Order.place(1, 7, domesticQuote(quoteLine(101, 1_000, 1)), new AtomicLong(10)::incrementAndGet, address("KR"), 99, NOW);
        Order order = new Order(1, placed.memberId(), status, placed.quote(), placed.itemIds(), placed.shippingAddress(), placed.stockReservationId(),
            placed.paymentDueAt(), null, null, NOW, 6L);
        when(orders.findById(1L)).thenReturn(Optional.of(order));
        return order;
    }

    private static Claim cancellation(Claim.Status status, Instant next, String failure) {
        return new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, status, krw(1_000), 77L, 5, next, failure, ITEMS, NOW, 4L);
    }

    private Claim stored(Claim claim) {
        when(claims.findById(2L)).thenReturn(Optional.of(claim));
        // doAnswer 형식 — 두 번째 stored() 가 앞 stub 을 null 인자로 부르지 않게 한다
        org.mockito.Mockito.doAnswer(invocation -> {
            Claim saved = invocation.getArgument(0);
            return new Claim(saved.id(), saved.orderId(), saved.requestKey(), saved.type(), saved.status(), saved.expectedRefund(), saved.paymentId(),
                saved.attemptCount(), saved.nextAttemptAt(), saved.failureCode(), saved.decision(), saved.items(), saved.requestedAt(), saved.version() + 1);
        }).when(claims).update(any());
        return claim;
    }

    private static void assertCode(Runnable call, OrderErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(OrderException.class)
            .satisfies(exception -> assertThat(((OrderException) exception).getErrorCode()).isEqualTo(code));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"REFUND_FAILED, REFUND_UNCONFIRMED, REQUESTED",
        "RESTORATION_FAILED, RESTORATION_UNCONFIRMED, REFUNDED", "RESTORATION_FAILED, PRODUCT_028, REFUNDED"})
    @DisplayName("종결 실패는 그 단계의 대기 상태로 다시 열어 lease 까지 선점하고 저장된 값(올라간 버전)을 돌려준다")
    void reopensFailedClaim(Claim.Status status, String failure, Claim.Status reopened) {
        stored(cancellation(status, null, failure));

        Claim held = processor(true).prepareRetry(2, 4);

        assertThat(held.status()).isEqualTo(reopened);
        assertThat(held.attemptCount()).isZero();
        assertThat(held.failureCode()).isNull();
        assertThat(held.nextAttemptAt()).isEqualTo(NOW.plus(LEASE));
        assertThat(held.version()).isEqualTo(5L);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PAYMENT_016", "PAYMENT_017", "PAYMENT_019"})
    @DisplayName("결제사가 거절한 환불은 409 ORDER_038 이고 저장하지 않는다 — 다시 보내도 같은 거절이라 수동 확정(A3)으로 넘긴다")
    void rejectedRefundIsNotRetried(String failure) {
        Claim rejected = stored(cancellation(Claim.Status.REFUND_FAILED, null, failure));

        assertCode(() -> processor(true).prepareRetry(2, 4), OrderErrorCode.CLAIM_RETRY_NOT_ALLOWED);
        assertThat(processor(true).isRetryable(rejected)).isFalse();
        verify(claims, never()).update(any());
    }

    private static Claim requestedLongAgo(Claim.Status status, Instant next, String failure, Duration age) {
        return new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, status, krw(1_000), 77L, 5, next, failure, ITEMS, NOW.minus(age), 4L);
    }

    @Test
    @DisplayName("결제 취소 키(접수 + 14일)가 lease 안에 끝나는 환불 단계는 409 ORDER_038 이다 — 재고 복원 단계는 기한과 무관하다")
    void refundStageAfterKeyValidityIsNotRetried() {
        Duration expired = Duration.ofDays(14).minus(LEASE);
        for (Claim refundStage : List.of(requestedLongAgo(Claim.Status.REFUND_FAILED, null, Claim.REFUND_UNCONFIRMED, expired),
            requestedLongAgo(Claim.Status.REQUESTED, NOW, null, expired))) {
            stored(refundStage);
            assertCode(() -> processor(true).prepareRetry(2, 4), OrderErrorCode.CLAIM_RETRY_NOT_ALLOWED);
            assertThat(processor(true).isRetryable(refundStage)).isFalse();
        }
        verify(claims, never()).update(any());
        stored(requestedLongAgo(Claim.Status.RESTORATION_FAILED, null, Claim.RESTORATION_UNCONFIRMED, Duration.ofDays(30)));
        assertThat(processor(true).prepareRetry(2, 4).attemptCount()).isZero();
    }

    @Test
    @DisplayName("기한이 가까운 환불을 다시 열면 남은 자동 재시도(회당 최대 1시간)가 기한 안에 끝나도록 시작 횟수를 올린다")
    void reopenedRefundKeepsRetriesInsideKeyValidity() {
        // 남은 기간 10시간 → 최대 10회만 남기고 48 − 10 = 38 회부터 센다
        stored(requestedLongAgo(Claim.Status.REFUND_FAILED, null, Claim.REFUND_UNCONFIRMED, Duration.ofDays(14).minusHours(10)));

        Claim held = processor(true).prepareRetry(2, 4);

        assertThat(held.status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(held.attemptCount()).isEqualTo(38);
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"REQUESTED", "REFUNDED"})
    @DisplayName("lease 가 끝난 대기 상태는 상태 · 횟수를 그대로 두고 다음 시각만 lease 로 미뤄 선점한다")
    void holdsDueAwaitingClaim(Claim.Status status) {
        stored(cancellation(status, NOW, null));

        Claim held = processor(true).prepareRetry(2, 4);

        assertThat(held.status()).isEqualTo(status);
        assertThat(held.attemptCount()).isEqualTo(5);
        assertThat(held.nextAttemptAt()).isEqualTo(NOW.plus(LEASE));
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"REQUESTED", "REFUNDED"})
    @DisplayName("자동 처리가 아직 쥐고 있는(lease 안) 대기 상태는 409 ORDER_039 이고 저장하지 않는다 — 같은 환불을 겹쳐 보내지 않는다")
    void rejectsActiveLease(Claim.Status status) {
        stored(cancellation(status, NOW.plusNanos(1_000), null));

        assertCode(() -> processor(true).prepareRetry(2, 4), OrderErrorCode.CLAIM_AUTO_PROCESSING);
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("화면 버전이 저장된 버전과 다르면 409 ORDER_037 이고 저장하지 않는다")
    void rejectsStaleVersion() {
        stored(cancellation(Claim.Status.REFUND_FAILED, null, Claim.REFUND_UNCONFIRMED));

        assertCode(() -> processor(true).prepareRetry(2, 3), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("버전 대조 뒤 저장에서 지면(스케줄러 · 고객 · 다른 운영자가 먼저 커밋) 409 ORDER_037 이다")
    void lostSaveIsConcurrentChange() {
        when(claims.findById(2L)).thenReturn(Optional.of(cancellation(Claim.Status.REFUND_FAILED, null, Claim.REFUND_UNCONFIRMED)));
        when(claims.update(any())).thenThrow(new ObjectOptimisticLockingFailureException("ClaimEntity", 2L));

        assertCode(() -> processor(true).prepareRetry(2, 4), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
    }

    @Test
    @DisplayName("처리 완료 · 주문 상태 이상 · 반품은 409 ORDER_038 이고 저장하지 않는다")
    void rejectsNonRetryableClaims() {
        for (Claim claim : List.of(cancellation(Claim.Status.COMPLETED, null, null),
            cancellation(Claim.Status.RESTORATION_FAILED, null, OrderCancellationProcessor.ORDER_STATUS_UNEXPECTED),
            new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)),
                NOW, 4L))) {
            when(claims.findById(2L)).thenReturn(Optional.of(claim));
            assertCode(() -> processor(true).prepareRetry(2, 4), OrderErrorCode.CLAIM_RETRY_NOT_ALLOWED);
        }
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("재시도 스케줄러가 꺼져 있으면 읽기 전에 503 ORDER_040 이다")
    void closedWhenSchedulerDisabled() {
        assertCode(() -> processor(false).prepareRetry(2, 4), OrderErrorCode.CLAIM_RETRY_UNAVAILABLE);
        verify(claims, never()).findById(2L);
    }

    @Test
    @DisplayName("없는 클레임은 404 ORDER_032 다")
    void notFound() {
        when(claims.findById(2L)).thenReturn(Optional.empty());

        assertCode(() -> processor(true).prepareRetry(2, 4), OrderErrorCode.NOT_FOUND_CLAIM);
    }

    @Test
    @DisplayName("재시도 가능 여부는 선점과 같은 조건이다 — 스케줄러 꺼짐 · lease 안 · 완료면 false")
    void retryableMatchesPrepareRetry() {
        Claim failed = cancellation(Claim.Status.REFUND_FAILED, null, Claim.REFUND_UNCONFIRMED);

        assertThat(processor(true).isRetryable(failed)).isTrue();
        assertThat(processor(false).isRetryable(failed)).isFalse();
        assertThat(processor(true).isRetryable(cancellation(Claim.Status.REQUESTED, NOW.plusSeconds(1), null))).isFalse();
        assertThat(processor(true).isRetryable(cancellation(Claim.Status.REQUESTED, NOW, null))).isTrue();
        assertThat(processor(true).isRetryable(cancellation(Claim.Status.COMPLETED, null, null))).isFalse();
    }

    @Test
    @DisplayName("다시 열 때 저장하는 값은 같은 클레임 아이디 · 결제 · 금액이다 — 원격은 이 값으로 멱등이다")
    void reopenKeepsRefundRequest() {
        Claim source = stored(cancellation(Claim.Status.REFUND_FAILED, null, Claim.REFUND_UNCONFIRMED));

        processor(true).prepareRetry(2, 4);

        ArgumentCaptor<Claim> saved = ArgumentCaptor.forClass(Claim.class);
        verify(claims).update(saved.capture());
        assertThat(saved.getValue().hasSameRefundRequest(source)).isTrue();
        assertThat(saved.getValue().version()).isEqualTo(4L);
    }

    // ---- 수동 환불 확정 · 복원 생략 종결 (#17 A3) ----

    private static final String REASON = "결제 관리 화면에서 취소 완료 확인";

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PAYMENT_016", Claim.REFUND_UNCONFIRMED})
    @DisplayName("수동 환불 확정 — 주문이 취소 요청이면 클레임 REFUNDED(lease) + 주문 취소 완료 + ADMIN 이력(사유)을 같이 저장한다. PG 는 부르지 않는다")
    void confirmRefundCompletesCancelRequestedOrder(String failure) {
        // 결과 미확인은 다시 시도 기한(접수 + 14일)이 lease 안에 끝난 뒤에만 확정 대상이다 — 거절은 기한과 무관하다
        stored(requestedLongAgo(Claim.Status.REFUND_FAILED, null, failure, Duration.ofDays(14).minus(LEASE)));
        Order order = storedOrder(OrderStatus.CANCEL_REQUESTED);

        Claim refunded = processor(true).confirmRefund(2, 4, REASON, 9);

        assertThat(refunded.status()).isEqualTo(Claim.Status.REFUNDED);
        assertThat(refunded.attemptCount()).isZero();
        assertThat(refunded.nextAttemptAt()).isEqualTo(NOW.plus(LEASE));
        assertThat(refunded.failureCode()).isNull();
        assertThat(refunded.decision()).isEqualTo(new Claim.Decision(REASON, 9, NOW, failure));
        assertThat(refunded.version()).isEqualTo(5L);
        ArgumentCaptor<Order> changed = ArgumentCaptor.forClass(Order.class);
        ArgumentCaptor<OrderStatusHistory> history = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(orders).saveStatusChange(changed.capture(), history.capture());
        assertThat(changed.getValue().status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(changed.getValue().version()).isEqualTo(order.version());
        assertThat(history.getValue()).extracting(OrderStatusHistory::orderId, OrderStatusHistory::fromStatus, OrderStatusHistory::toStatus,
                OrderStatusHistory::changedByType, OrderStatusHistory::changedById, OrderStatusHistory::reason)
            .containsExactly(1L, OrderStatus.CANCEL_REQUESTED, OrderStatus.CANCELED, StatusChangedBy.ADMIN, 9L, REASON);
    }

    @Test
    @DisplayName("수동 환불 확정 — 주문이 이미 취소 완료면 클레임만 바꾼다")
    void confirmRefundLeavesCanceledOrder() {
        stored(cancellation(Claim.Status.REFUND_FAILED, null, "PAYMENT_016"));
        storedOrder(OrderStatus.CANCELED);

        assertThat(processor(true).confirmRefund(2, 4, REASON, 9).status()).isEqualTo(Claim.Status.REFUNDED);
        verify(orders, never()).saveStatusChange(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CANCEL_REQUESTED", "CANCELED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("수동 환불 확정 — 주문이 취소 요청 · 취소 완료가 아니면 409 ORDER_041 이고 아무것도 저장하지 않는다")
    void confirmRefundRejectsUnexpectedOrderStatus(OrderStatus status) {
        stored(cancellation(Claim.Status.REFUND_FAILED, null, "PAYMENT_016"));
        storedOrder(status);

        assertCode(() -> processor(true).confirmRefund(2, 4, REASON, 9), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        verify(claims, never()).update(any());
        verify(orders, never()).saveStatusChange(any(), any());
    }

    @Test
    @DisplayName("수동 환불 확정 — 환불 실패가 아닌 클레임 · 반품은 409 ORDER_041 이고 저장하지 않는다")
    void confirmRefundRejectsNonRefundFailure() {
        storedOrder(OrderStatus.CANCEL_REQUESTED);
        for (Claim claim : List.of(cancellation(Claim.Status.REQUESTED, NOW, null), cancellation(Claim.Status.REFUNDED, NOW, null),
            cancellation(Claim.Status.COMPLETED, null, null), cancellation(Claim.Status.RESTORATION_FAILED, null, "PRODUCT_028"),
            new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)),
                NOW, 4L))) {
            when(claims.findById(2L)).thenReturn(Optional.of(claim));
            assertCode(() -> processor(true).confirmRefund(2, 4, REASON, 9), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
            assertThat(processor(true).isRefundConfirmable(claim)).as(claim.status().name()).isFalse();
        }
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("결과 미확인 환불은 다시 시도 기한 안이면 확정 409 ORDER_041 · refundConfirmable=false(다시 시도 가능), 기한이 lease 안에 끝나면 반대로 바뀐다")
    void unconfirmedRefundConfirmableOnlyAfterRetryCloses() {
        storedOrder(OrderStatus.CANCEL_REQUESTED);
        Duration closes = Duration.ofDays(14).minus(LEASE);
        Claim open = stored(requestedLongAgo(Claim.Status.REFUND_FAILED, null, Claim.REFUND_UNCONFIRMED, closes.minusNanos(1_000)));

        assertCode(() -> processor(true).confirmRefund(2, 4, REASON, 9), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        assertThat(processor(true).isRefundConfirmable(open)).isFalse();
        assertThat(processor(true).isRetryable(open)).isTrue();
        verify(claims, never()).update(any());

        Claim closed = stored(requestedLongAgo(Claim.Status.REFUND_FAILED, null, Claim.REFUND_UNCONFIRMED, closes));
        assertThat(processor(true).isRetryable(closed)).isFalse();
        assertThat(processor(true).isRefundConfirmable(closed)).isTrue();
        assertThat(processor(true).confirmRefund(2, 4, REASON, 9).status()).isEqualTo(Claim.Status.REFUNDED);
    }

    @Test
    @DisplayName("결제사가 거절한 환불은 접수 직후(기한 안)에도 확정할 수 있다 — 다시 시도는 닫혀 있다")
    void rejectedRefundConfirmableRegardlessOfDeadline() {
        Claim rejected = cancellation(Claim.Status.REFUND_FAILED, null, "PAYMENT_016");

        assertThat(processor(true).isRefundConfirmable(rejected)).isTrue();
        assertThat(processor(true).isRetryable(rejected)).isFalse();
    }

    @Test
    @DisplayName("상세 completable 은 종결 후보와 같다 — 주문 상태 이상(ORDER_STATUS_UNEXPECTED) · 복원 결과 미확인은 false")
    void completableMatchesCompletionCandidate() {
        assertThat(processor(true).isCompletable(cancellation(Claim.Status.RESTORATION_FAILED, null, "PRODUCT_028"))).isTrue();
        assertThat(processor(true).isCompletable(cancellation(Claim.Status.RESTORATION_FAILED, null, OrderCancellationProcessor.ORDER_STATUS_UNEXPECTED)))
            .isFalse();
        assertThat(processor(true).isCompletable(cancellation(Claim.Status.RESTORATION_FAILED, null, Claim.RESTORATION_UNCONFIRMED))).isFalse();
        assertThat(processor(true).isCompletable(cancellation(Claim.Status.REFUND_FAILED, null, "PAYMENT_016"))).isFalse();
    }

    @Test
    @DisplayName("수동 환불 확정 — 재시도 스케줄러가 꺼져 있으면 읽기 전에 503 ORDER_040 이다(이어지는 복원을 따라갈 쪽이 없다)")
    void confirmRefundClosedWhenSchedulerDisabled() {
        Claim failed = cancellation(Claim.Status.REFUND_FAILED, null, "PAYMENT_016");

        assertCode(() -> processor(false).confirmRefund(2, 4, REASON, 9), OrderErrorCode.CLAIM_RETRY_UNAVAILABLE);
        verify(claims, never()).findById(2L);
        assertThat(processor(false).isRefundConfirmable(failed)).isFalse();
        assertThat(processor(true).isRefundConfirmable(failed)).isTrue();
    }

    @Test
    @DisplayName("수동 환불 확정 · 종결 — 화면 버전이 다르면 409 ORDER_037 이다. 이미 바뀐 뒤의 같은 요청(더블클릭 · 재전송)도 이 응답이다")
    void decisionsRejectStaleVersion() {
        stored(cancellation(Claim.Status.REFUND_FAILED, null, "PAYMENT_016"));
        assertCode(() -> processor(true).confirmRefund(2, 3, REASON, 9), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        stored(cancellation(Claim.Status.RESTORATION_FAILED, null, "PRODUCT_028"));
        assertCode(() -> processor(true).complete(2, 3, REASON, 9), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        // 확정이 이미 커밋돼 REFUNDED(저장 버전 4)가 된 뒤 낡은 화면 버전(3)으로 다시 온 요청
        stored(cancellation(Claim.Status.REFUNDED, NOW, null));
        assertCode(() -> processor(true).confirmRefund(2, 3, REASON, 9), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        verify(claims, never()).update(any());
        verify(orders, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("수동 환불 확정 — 버전 대조 뒤 클레임 · 주문 저장에서 지면(스케줄러 · 늦은 환불 결과 · 다른 운영자가 먼저 커밋) 409 ORDER_037 이다")
    void confirmRefundLostSaveIsConcurrentChange() {
        when(claims.findById(2L)).thenReturn(Optional.of(cancellation(Claim.Status.REFUND_FAILED, null, "PAYMENT_016")));
        storedOrder(OrderStatus.CANCEL_REQUESTED);
        when(claims.update(any())).thenThrow(new ObjectOptimisticLockingFailureException("ClaimEntity", 2L));

        assertCode(() -> processor(true).confirmRefund(2, 4, REASON, 9), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        verify(orders, never()).saveStatusChange(any(), any());

        stored(cancellation(Claim.Status.REFUND_FAILED, null, "PAYMENT_016"));
        org.mockito.Mockito.doThrow(new ObjectOptimisticLockingFailureException("OrderEntity", 1L)).when(orders).saveStatusChange(any(), any());

        assertCode(() -> processor(true).confirmRefund(2, 4, REASON, 9), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
    }

    @Test
    @DisplayName("없는 클레임의 확정 · 종결은 404 ORDER_032 다")
    void decisionsNotFound() {
        when(claims.findById(2L)).thenReturn(Optional.empty());

        assertCode(() -> processor(true).confirmRefund(2, 4, REASON, 9), OrderErrorCode.NOT_FOUND_CLAIM);
        assertCode(() -> processor(true).complete(2, 4, REASON, 9), OrderErrorCode.NOT_FOUND_CLAIM);
    }

    @Test
    @DisplayName("복원 생략 종결 — 확정 실패 코드의 RESTORATION_FAILED + 주문 취소 완료면 COMPLETED 로 닫고 주문은 바꾸지 않는다. 스케줄러가 꺼져 있어도 된다")
    void completeClosesRejectedRestoration() {
        stored(cancellation(Claim.Status.RESTORATION_FAILED, null, "PRODUCT_028"));
        storedOrder(OrderStatus.CANCELED);

        Claim completed = processor(false).complete(2, 4, "재고 조정 화면에서 수량을 맞춤", 9);

        assertThat(completed.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(completed.failureCode()).isNull();
        assertThat(completed.nextAttemptAt()).isNull();
        assertThat(completed.decision()).isEqualTo(new Claim.Decision("재고 조정 화면에서 수량을 맞춤", 9, NOW, "PRODUCT_028"));
        assertThat(completed.version()).isEqualTo(5L);
        verify(orders, never()).saveStatusChange(any(), any());
    }

    @Test
    @DisplayName("복원 생략 종결 — 결과 미확인(RESTORATION_UNCONFIRMED) · 환불 실패 · 완료는 409 ORDER_041 이다(실제로 복원됐을 수 있다 — 다시 시도)")
    void completeRejectsUnconfirmedAndOtherClaims() {
        storedOrder(OrderStatus.CANCELED);
        for (Claim claim : List.of(cancellation(Claim.Status.RESTORATION_FAILED, null, Claim.RESTORATION_UNCONFIRMED),
            cancellation(Claim.Status.REFUND_FAILED, null, "PAYMENT_016"), cancellation(Claim.Status.COMPLETED, null, null),
            cancellation(Claim.Status.REFUNDED, NOW, null))) {
            when(claims.findById(2L)).thenReturn(Optional.of(claim));
            assertCode(() -> processor(true).complete(2, 4, REASON, 9), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        }
        verify(claims, never()).update(any());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "CANCELED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("복원 생략 종결 — 주문이 취소 완료가 아니면 409 ORDER_041 이다")
    void completeRequiresCanceledOrder(OrderStatus status) {
        stored(cancellation(Claim.Status.RESTORATION_FAILED, null, OrderCancellationProcessor.ORDER_STATUS_UNEXPECTED));
        storedOrder(status);

        assertCode(() -> processor(true).complete(2, 4, REASON, 9), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("복원 생략 종결 — 버전 대조 뒤 저장에서 지면 409 ORDER_037 이다")
    void completeLostSaveIsConcurrentChange() {
        when(claims.findById(2L)).thenReturn(Optional.of(cancellation(Claim.Status.RESTORATION_FAILED, null, "PRODUCT_028")));
        storedOrder(OrderStatus.CANCELED);
        when(claims.update(any())).thenThrow(new ObjectOptimisticLockingFailureException("ClaimEntity", 2L));

        assertCode(() -> processor(true).complete(2, 4, REASON, 9), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
    }
}
