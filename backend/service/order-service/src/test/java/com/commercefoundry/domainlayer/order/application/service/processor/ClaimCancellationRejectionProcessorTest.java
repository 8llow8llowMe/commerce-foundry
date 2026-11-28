package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderStatusHistoryQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.StatusChangedBy;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * 결제 후 취소 거절의 DB 단계 (#17 A4) — 자격 확인(tx1)의 판정 순서 · 읽기 범위와 거절 기록(tx2)의 주문 NOWAIT 잠금 · 재판정 · 저장 · 경합 코드.
 * 금액 · 아이디는 모두 가짜다.
 */
class ClaimCancellationRejectionProcessorTest {
    private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");
    private static final long ORDER_ID = 1L;
    private static final long CLAIM_ID = 50L;
    private static final long PAYMENT_ID = 77L;
    private static final long ADMIN = 9L;
    private static final long VERSION = 4L;
    private static final String REASON = "결제사 화면에서 환불 없음 확인, 고객과 통화해 출고 진행";

    private final OrderRepositoryPort orders = mock(OrderRepositoryPort.class);
    private final ClaimRepositoryPort claims = mock(ClaimRepositoryPort.class);
    private final OrderPaymentRepositoryPort payments = mock(OrderPaymentRepositoryPort.class);
    private final ClaimCancellationRejectionProcessor processor = new ClaimCancellationRejectionProcessor(claims, orders, payments,
        new SnowflakeIdGenerator(1, 1), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void savesWhatItIsGiven() {
        when(claims.update(any())).thenAnswer(invocation -> {
            Claim claim = invocation.getArgument(0);
            return new Claim(claim.id(), claim.orderId(), claim.requestKey(), claim.type(), claim.status(), claim.expectedRefund(), claim.paymentId(),
                claim.attemptCount(), claim.nextAttemptAt(), claim.failureCode(), claim.decision(), claim.items(), claim.requestedAt(), claim.version() + 1);
        });
    }

    private static Order order(OrderStatus status) {
        return new Order(ORDER_ID, 7L, status, domesticQuote(quoteLine(9001, 30_000, 1)), List.of(101L), address("KR"), 99L, Instant.EPOCH, null, null,
            Instant.EPOCH, 3L);
    }

    /** 결제사가 종결 거절한 취소 환불 실패 — 거절 후보(R1 · R2). */
    private static Claim rejectedRefund(String failureCode) {
        return cancellation(CLAIM_ID, Claim.Status.REFUND_FAILED, failureCode);
    }

    private static Claim cancellation(long id, Claim.Status status, String failureCode) {
        return new Claim(id, ORDER_ID, Claim.cancellationRequestKey(ORDER_ID), Claim.Type.CANCELLATION, status, krw(30_000), PAYMENT_ID, 1,
            Claim.Type.CANCELLATION.isAwaitingAttempt(status) ? NOW : null, failureCode,
            List.of(new ClaimItem(id + 1, 101L, 1, ClaimItem.Reason.ORDER_MISTAKE)), NOW, VERSION);
    }

    private static OrderPayment payment(long paymentId) {
        return new OrderPayment(5L, ORDER_ID, paymentId, 7L, krw(30_000), NOW, OrderPayment.Result.PAID, 99L, UUID.randomUUID(), null, null, null);
    }

    private static OrderStatusHistoryQueryResult history(OrderStatus from, OrderStatus to) {
        return new OrderStatusHistoryQueryResult(from, to, StatusChangedBy.CUSTOMER, 7L, "ORDER_MISTAKE", NOW);
    }

    /** 접수 → 결제 완료 → (그 상태) → 취소 요청 순 이력. 마지막 행만 판정에 쓴다. */
    private static List<OrderStatusHistoryQueryResult> historiesEndingWith(OrderStatus restoreTo) {
        List<OrderStatusHistoryQueryResult> rows = new ArrayList<>(List.of(history(null, OrderStatus.PENDING_PAYMENT),
            history(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID)));
        if (restoreTo != OrderStatus.PAID) {
            rows.add(history(OrderStatus.PAID, restoreTo));
        }
        rows.add(history(restoreTo, OrderStatus.CANCEL_REQUESTED));
        return rows;
    }

    /** 자격을 모두 갖춘 상태 — tx1 은 findById, tx2 는 findByIdForUpdate 로 같은 주문을 읽는다. */
    private Claim eligible(OrderStatus restoreTo) {
        Claim claim = rejectedRefund("PAYMENT_019");
        Order order = order(OrderStatus.CANCEL_REQUESTED);
        when(claims.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(claims.findAllByOrderId(ORDER_ID)).thenReturn(List.of(claim));
        when(payments.findPaidByOrderId(ORDER_ID)).thenReturn(Optional.of(payment(PAYMENT_ID)));
        when(orders.findHistories(ORDER_ID)).thenReturn(historiesEndingWith(restoreTo));
        return claim;
    }

    private static void assertCode(Runnable call, OrderErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OrderException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
    }

    private void assertNothingWritten() {
        verify(claims, never()).update(any());
        verify(orders, never()).saveStatusChange(any(), any());
    }

    // ---- tx1 자격 확인 ----

    @Test
    @DisplayName("자격 확인 — 주문 R4 → 클레임 목록 R5 → 결제 기록 R7 → 이력 R6 순으로 읽고 저장된 클레임을 낸다. 잠그지 않고 아무것도 쓰지 않는다")
    void prepareReadsInOrderWithoutLockOrWrite() {
        Claim claim = eligible(OrderStatus.PAID);

        assertThat(processor.prepare(CLAIM_ID, VERSION)).isEqualTo(claim);

        InOrder reads = inOrder(claims, orders, payments);
        reads.verify(claims).findById(CLAIM_ID);
        reads.verify(orders).findById(ORDER_ID);
        reads.verify(claims).findAllByOrderId(ORDER_ID);
        reads.verify(payments).findPaidByOrderId(ORDER_ID);
        reads.verify(orders).findHistories(ORDER_ID);
        verify(orders, never()).findByIdForUpdate(anyLong());
        assertNothingWritten();
    }

    @Test
    @DisplayName("판정 순서 — 없는 클레임은 404 ORDER_032 이고 아무것도 더 읽지 않는다")
    void missingClaimIsNotFound() {
        when(claims.findById(CLAIM_ID)).thenReturn(Optional.empty());

        assertCode(() -> processor.prepare(CLAIM_ID, VERSION), OrderErrorCode.NOT_FOUND_CLAIM);
        assertCode(() -> processor.reject(CLAIM_ID, VERSION, REASON, ADMIN), OrderErrorCode.NOT_FOUND_CLAIM);

        verifyNoInteractions(orders, payments);
    }

    @Test
    @DisplayName("판정 순서 — 버전 불일치는 자격보다 먼저다: 후보가 아닌 클레임이라도 화면 버전이 다르면 409 ORDER_037")
    void staleVersionComesBeforeEligibility() {
        when(claims.findById(CLAIM_ID)).thenReturn(Optional.of(cancellation(CLAIM_ID, Claim.Status.COMPLETED, null)));

        assertCode(() -> processor.prepare(CLAIM_ID, VERSION + 1), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        assertCode(() -> processor.reject(CLAIM_ID, VERSION + 1, REASON, ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);

        verifyNoInteractions(orders, payments);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REFUND_UNCONFIRMED", "RESTORATION", "COMPLETED", "REJECTED", "RETURN"})
    @DisplayName("판정 순서 — 클레임만 본 후보(R1 · R2)가 아니면 409 ORDER_041 이고 주문을 읽거나 잠그지 않는다(결과 미확인 · 복원 실패 · 끝 · 거절됨 · 반품)")
    void nonCandidateIsRefusedBeforeOrder(String kind) {
        Claim claim = switch (kind) {
            case "REFUND_UNCONFIRMED" -> rejectedRefund(Claim.REFUND_UNCONFIRMED);
            case "RESTORATION" -> cancellation(CLAIM_ID, Claim.Status.RESTORATION_FAILED, "PRODUCT_028");
            case "COMPLETED" -> cancellation(CLAIM_ID, Claim.Status.COMPLETED, null);
            case "REJECTED" -> rejectedRefund("PAYMENT_019").rejectCancellation(new Claim.Decision(REASON, ADMIN, NOW, "PAYMENT_019"));
            default -> new Claim(CLAIM_ID, ORDER_ID, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
                List.of(new ClaimItem(CLAIM_ID + 1, 101L, 1, ClaimItem.Reason.CHANGE_OF_MIND)), NOW, VERSION);
        };
        when(claims.findById(CLAIM_ID)).thenReturn(Optional.of(claim));

        assertCode(() -> processor.prepare(CLAIM_ID, VERSION), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        assertCode(() -> processor.reject(CLAIM_ID, VERSION, REASON, ADMIN), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);

        verifyNoInteractions(orders, payments);
        assertNothingWritten();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "PREPARING", "CANCELED", "SHIPPING"})
    @DisplayName("판정 순서 — 주문이 취소 요청이 아니면(R4) 409 ORDER_041 이고 클레임 목록 · 결제 기록 · 이력을 읽지 않는다")
    void orderNotCancelRequestedStopsBeforeOtherReads(OrderStatus status) {
        eligible(OrderStatus.PAID);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order(status)));
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order(status)));

        assertCode(() -> processor.prepare(CLAIM_ID, VERSION), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        assertCode(() -> processor.reject(CLAIM_ID, VERSION, REASON, ADMIN), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);

        verify(claims, never()).findAllByOrderId(anyLong());
        verifyNoInteractions(payments);
        verify(orders, never()).findHistories(anyLong());
        assertNothingWritten();
    }

    @Test
    @DisplayName("판정 순서 — 클레임의 주문이 없으면(데이터 이상) 409 ORDER_041")
    void missingOrderIsRefused() {
        eligible(OrderStatus.PAID);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.empty());
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

        assertCode(() -> processor.prepare(CLAIM_ID, VERSION), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        assertCode(() -> processor.reject(CLAIM_ID, VERSION, REASON, ADMIN), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        assertNothingWritten();
    }

    @Test
    @DisplayName("판정 순서 — 결제 완료 기록이 없으면(R7, 데이터 이상) 409 ORDER_041 이고 이력은 읽지 않는다")
    void missingPaymentRecordIsRefusedBeforeHistories() {
        eligible(OrderStatus.PAID);
        when(payments.findPaidByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        assertCode(() -> processor.prepare(CLAIM_ID, VERSION), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);

        verify(orders, never()).findHistories(anyLong());
    }

    @Test
    @DisplayName("R7 — 주문의 결제 완료 기록이 클레임의 결제와 다르면 409 ORDER_041")
    void differentPaymentRecordIsRefused() {
        eligible(OrderStatus.PAID);
        when(payments.findPaidByOrderId(ORDER_ID)).thenReturn(Optional.of(payment(PAYMENT_ID + 1)));

        assertCode(() -> processor.prepare(CLAIM_ID, VERSION), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        assertCode(() -> processor.reject(CLAIM_ID, VERSION, REASON, ADMIN), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        assertNothingWritten();
    }

    @Test
    @DisplayName("R5 — 같은 주문에 다른 열린 취소(환불 대기 · 환불 실패)가 있으면 409 ORDER_041")
    void otherOpenCancellationIsRefused() {
        Claim claim = eligible(OrderStatus.PAID);
        for (Claim other : List.of(cancellation(CLAIM_ID + 10, Claim.Status.REQUESTED, null), cancellation(CLAIM_ID + 10, Claim.Status.REFUND_FAILED,
            Claim.REFUND_UNCONFIRMED))) {
            when(claims.findAllByOrderId(ORDER_ID)).thenReturn(List.of(other, claim));

            assertCode(() -> processor.prepare(CLAIM_ID, VERSION), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
            assertCode(() -> processor.reject(CLAIM_ID, VERSION, REASON, ADMIN), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        }
        assertNothingWritten();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY", "CREATED_ONLY", "SHIPPING_TO_CANCEL", "PAID_TO_PREPARING_LAST", "CANCEL_REQUESTED_TO_PAID_LAST"})
    @DisplayName("R6 — 이력 최신 행이 결제 완료 · 제작 중 · 상품 준비중 → 취소 요청이 아니면(없음 · 다른 행) 되돌릴 상태를 확인하지 못해 409 ORDER_041")
    void historyMismatchIsRefused(String kind) {
        eligible(OrderStatus.PAID);
        List<OrderStatusHistoryQueryResult> histories = switch (kind) {
            case "EMPTY" -> List.of();
            case "CREATED_ONLY" -> List.of(history(null, OrderStatus.PENDING_PAYMENT));
            case "SHIPPING_TO_CANCEL" -> List.of(history(null, OrderStatus.PENDING_PAYMENT), history(OrderStatus.SHIPPING, OrderStatus.CANCEL_REQUESTED));
            // 앞 행이 맞아도 마지막 행으로만 본다
            case "PAID_TO_PREPARING_LAST" -> List.of(history(OrderStatus.PAID, OrderStatus.CANCEL_REQUESTED), history(OrderStatus.PAID, OrderStatus.PREPARING));
            default -> List.of(history(OrderStatus.PAID, OrderStatus.CANCEL_REQUESTED), history(OrderStatus.CANCEL_REQUESTED, OrderStatus.PAID));
        };
        when(orders.findHistories(ORDER_ID)).thenReturn(histories);

        assertCode(() -> processor.prepare(CLAIM_ID, VERSION), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        assertCode(() -> processor.reject(CLAIM_ID, VERSION, REASON, ADMIN), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        assertNothingWritten();
    }

    // ---- tx2 거절 기록 ----

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "MAKING", "PREPARING"})
    @DisplayName("거절 기록 — 주문을 NOWAIT 으로 잠근 뒤 재판정하고, 클레임 REJECTED(결정 = 사유 · 관리자 · 직전 실패 코드)와 정책이 이력에서 꺼낸 상태로의 주문 복귀 + ADMIN 이력을 저장한다")
    void rejectRestoresPolicyStatusWithAdminHistory(OrderStatus restoreTo) {
        eligible(restoreTo);

        Claim saved = processor.reject(CLAIM_ID, VERSION, REASON, ADMIN);

        InOrder steps = inOrder(claims, orders, payments);
        steps.verify(claims).findById(CLAIM_ID);
        steps.verify(orders).findByIdForUpdate(ORDER_ID);
        steps.verify(claims).findAllByOrderId(ORDER_ID);
        steps.verify(payments).findPaidByOrderId(ORDER_ID);
        steps.verify(orders).findHistories(ORDER_ID);
        steps.verify(claims).update(any());
        steps.verify(orders).saveStatusChange(any(), any());
        verify(orders, never()).findById(anyLong());

        ArgumentCaptor<Claim> claim = ArgumentCaptor.forClass(Claim.class);
        verify(claims).update(claim.capture());
        assertThat(claim.getValue().status()).isEqualTo(Claim.Status.REJECTED);
        assertThat(claim.getValue().isRejectedCancellation()).isTrue();
        assertThat(claim.getValue().failureCode()).isNull();
        assertThat(claim.getValue().nextAttemptAt()).isNull();
        assertThat(claim.getValue().decision()).isEqualTo(new Claim.Decision(REASON, ADMIN, NOW, "PAYMENT_019"));
        assertThat(claim.getValue().version()).as("화면이 본 버전으로 저장한다 — 낙관락이 대조한다").isEqualTo(VERSION);
        assertThat(saved.version()).isEqualTo(VERSION + 1);

        ArgumentCaptor<Order> order = ArgumentCaptor.forClass(Order.class);
        ArgumentCaptor<OrderStatusHistory> history = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(orders).saveStatusChange(order.capture(), history.capture());
        assertThat(order.getValue().status()).as("정책 반환값(이력 최신 행의 이전 상태)만 주문 복귀에 쓴다").isEqualTo(restoreTo);
        assertThat(history.getValue()).satisfies(row -> {
            assertThat(row.fromStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
            assertThat(row.toStatus()).isEqualTo(restoreTo);
            assertThat(row.changedByType()).isEqualTo(StatusChangedBy.ADMIN);
            assertThat(row.changedById()).isEqualTo(ADMIN);
            assertThat(row.reason()).isEqualTo(REASON);
        });
    }

    @Test
    @DisplayName("거절 기록 — 주문 잠금에서 지면(NOWAIT) 기다리지 않고 409 ORDER_037 이고 아무것도 쓰지 않는다")
    void lockLossIsConcurrentChange() {
        eligible(OrderStatus.PAID);
        for (RuntimeException lockFailure : List.of(new CannotAcquireLockException("nowait"), new PessimisticLockException("nowait"),
            new LockTimeoutException("nowait"))) {
            doThrow(lockFailure).when(orders).findByIdForUpdate(ORDER_ID);

            assertCode(() -> processor.reject(CLAIM_ID, VERSION, REASON, ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        }
        verify(claims, never()).findAllByOrderId(anyLong());
        assertNothingWritten();
    }

    @Test
    @DisplayName("거절 기록 — 클레임 저장의 낙관락에서 지면 409 ORDER_037 이고 주문은 저장하지 않는다")
    void claimSaveConflictIsConcurrentChange() {
        eligible(OrderStatus.PAID);
        // when(...) 로 다시 걸면 @BeforeEach 의 thenAnswer 가 null 인자로 먼저 불린다 — doThrow 로 건다
        doThrow(new OptimisticLockingFailureException("stale claim")).when(claims).update(any());

        assertCode(() -> processor.reject(CLAIM_ID, VERSION, REASON, ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);

        verify(orders, never()).saveStatusChange(any(), any());
    }

    @Test
    @DisplayName("거절 기록 — 클레임 저장에서 DB 가 잠금 대기 · 교착으로 이쪽을 끊으면 500 이 아니라 409 ORDER_037 이다")
    void lockFailureOnClaimSaveIsConcurrentChange() {
        eligible(OrderStatus.PAID);
        doThrow(new CannotAcquireLockException("deadlock detected")).when(claims).update(any());

        assertCode(() -> processor.reject(CLAIM_ID, VERSION, REASON, ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);

        verify(orders, never()).saveStatusChange(any(), any());
    }

    @Test
    @DisplayName("거절 기록 — 주문 저장의 낙관락에서 지면 409 ORDER_037 이다(같은 트랜잭션의 클레임 저장도 롤백된다 — 통합 시험이 본다)")
    void orderSaveConflictIsConcurrentChange() {
        eligible(OrderStatus.PAID);
        doThrow(new OptimisticLockingFailureException("stale order")).when(orders).saveStatusChange(any(), any());

        assertCode(() -> processor.reject(CLAIM_ID, VERSION, REASON, ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
    }

    @Test
    @DisplayName("상세 표시 — 클레임만 본 후보(R1 · R2)다. 주문 · 결제 기록을 읽지 않는다")
    void rejectableIsClaimOnlyCandidate() {
        assertThat(processor.isRejectable(rejectedRefund("PAYMENT_016"))).isTrue();
        assertThat(processor.isRejectable(rejectedRefund(Claim.REFUND_UNCONFIRMED))).isFalse();
        assertThat(processor.isRejectable(cancellation(CLAIM_ID, Claim.Status.REQUESTED, null))).isFalse();

        verifyNoInteractions(orders, payments, claims);
    }

    @Test
    @DisplayName("거절은 재고 복원 · 환불을 하지 않는다 — 재고 복원 · 결제 취소 포트를 주입받지 않는다")
    void doesNotDependOnRestorationOrRefundPorts() {
        List<Class<?>> fieldTypes = Arrays.stream(ClaimCancellationRejectionProcessor.class.getDeclaredFields()).<Class<?>>map(Field::getType).toList();

        assertThat(fieldTypes).doesNotContain(StockRestorationPort.class, PaymentCancelPort.class, OrderCancellationPipelineProcessor.class);
    }
}
