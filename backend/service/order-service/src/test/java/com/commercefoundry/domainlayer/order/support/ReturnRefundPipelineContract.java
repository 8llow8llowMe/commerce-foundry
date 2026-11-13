package com.commercefoundry.domainlayer.order.support;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationProcessor;
import com.commercefoundry.domainlayer.order.domain.model.CancellationRefundPolicy;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * H2 · PostgreSQL 에서 같게 확인하는 반품 환불 · 복원 엔진 계약 (#17 B2a) — 실제 파이프라인 · Processor · DB 에 payment · product 호출만 목으로 둔다.
 * 승인 API(B2c) 전이라 승인된 반품은 도메인 {@link Claim#approveReturn} 으로 만든 시드 행이다.
 *
 * <p>주문 항목은 SKU 101 × 2(10,000) · SKU 102 × 1(5,000), 배송비 3,000 — 결제 총액 28,000.
 */
public abstract class ReturnRefundPipelineContract {
    private static final AtomicLong IDS = new AtomicLong(970_000);
    protected static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    protected static final Duration LEASE = Duration.ofMinutes(2);
    protected static final long PAYMENT_ID = 77L;
    @Autowired protected OrderRepositoryPort orders;
    @Autowired protected ClaimRepositoryPort claims;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected OrderCancellationPipelineProcessor pipeline;
    @Autowired protected OrderCancellationProcessor processor;
    @Autowired protected PaymentCancelPort paymentCancelPort;
    @Autowired protected StockRestorationPort stockRestorationPort;

    @BeforeEach
    void resetRemotes() {
        reset(paymentCancelPort, stockRestorationPort);
    }

    protected TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    protected Order order(OrderStatus status) {
        long id = IDS.incrementAndGet();
        Order order = Order.place(id, 7, domesticQuote(quoteLine(101, 10_000, 2), quoteLine(102, 5_000, 1)), IDS::incrementAndGet, address("KR"),
            IDS.incrementAndGet(), NOW);
        tx().executeWithoutResult(s -> {
            orders.insert(order, OrderStatusHistory.created(IDS.incrementAndGet(), order));
            jdbc.update("update orders.orders set status = ?, delivered_at = ?, version = version + 1 where id = ?", status.name(), Timestamp.from(NOW), id);
        });
        return read(order.id());
    }

    protected Order read(long orderId) {
        return tx().execute(s -> orders.findById(orderId).orElseThrow());
    }

    protected Claim readClaim(long claimId) {
        return claims.findById(claimId).orElseThrow();
    }

    protected void setOrderStatus(Order order, OrderStatus status) {
        tx().executeWithoutResult(s -> jdbc.update("update orders.orders set status = ?, version = version + 1 where id = ?", status.name(), order.id()));
    }

    /** 고객 반품 접수(REQUESTED) — 항목별 수량(0 이면 뺀다). */
    protected Claim requestReturn(Order order, int first, int second) {
        List<ClaimItem> items = new ArrayList<>();
        if (first > 0) {
            items.add(new ClaimItem(IDS.incrementAndGet(), order.itemIds().get(0), first, ClaimItem.Reason.DEFECTIVE));
        }
        if (second > 0) {
            items.add(new ClaimItem(IDS.incrementAndGet(), order.itemIds().get(1), second, ClaimItem.Reason.CHANGE_OF_MIND));
        }
        return update(null, new Claim(IDS.incrementAndGet(), order.id(), UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, items, null,
            null));
    }

    /** 시드 승인 — B2c 승인 API 대신 도메인 승인을 그대로 저장한다. */
    protected Claim approve(Claim requested, Order order, long refund, boolean restock, Instant now) {
        return update(requested, requested.approveReturn(order, PAYMENT_ID, new ReturnRefundPolicy.Refund(refund, 0, CurrencyCode.KRW), restock, 9L, now,
            LEASE));
    }

    private Claim update(Claim previous, Claim next) {
        return tx().execute(s -> previous == null ? claims.insert(next) : claims.update(next));
    }

    protected List<Map<String, Object>> returnHistories(Order order) {
        return jdbc.queryForList("select from_status, to_status, changed_by_type, changed_by_id, reason from orders.order_status_history"
            + " where order_id = ? and reason = ?", order.id(), OrderStatusHistory.RETURN_REFUNDED);
    }

    protected void paymentAnswers(PaymentCancelQueryResult result) {
        when(paymentCancelPort.cancel(eq(PAYMENT_ID), anyLong(), any())).thenReturn(result);
    }

    @Test
    void returnAwaitingInspectionIsNotProgressed() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim requested = requestReturn(order, 1, 0);

        assertThat(pipeline.progress(requested)).isEqualTo(requested);

        assertThat(readClaim(requested.id())).isEqualTo(requested);
        verify(paymentCancelPort, never()).cancel(anyLong(), anyLong(), any());
        verify(stockRestorationPort, never()).restoreReturn(anyLong(), anyLong(), any());
    }

    @Test
    void fullReturnRefundSettlesOrderReturnedWithServiceHistoryAndRestoresWithReturnReason() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim approved = approve(requestReturn(order, 2, 1), order, 28_000, true, NOW);
        paymentAnswers(PaymentCancelQueryResult.succeeded());

        Claim done = pipeline.progress(approved);

        assertThat(done.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(readClaim(approved.id())).isEqualTo(done);
        verify(paymentCancelPort).cancel(PAYMENT_ID, approved.id(), krw(28_000));
        verify(stockRestorationPort).restoreReturn(approved.id(), order.stockReservationId(), new TreeMap<>(Map.of(101L, 2, 102L, 1)));
        verify(stockRestorationPort, never()).restoreCancellation(anyLong(), anyLong(), any());
        assertThat(read(order.id()).status()).isEqualTo(OrderStatus.RETURNED);
        assertThat(returnHistories(order)).singleElement().satisfies(history -> {
            assertThat(history.get("from_status")).isEqualTo("RETURN_REQUESTED");
            assertThat(history.get("to_status")).isEqualTo("RETURNED");
            assertThat(history.get("changed_by_type")).isEqualTo("SERVICE");
            assertThat(history.get("changed_by_id")).isNull();
        });
    }

    @Test
    void partialReturnWithoutRestockCompletesInRecordingAndOrderGoesBackToDelivered() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim approved = approve(requestReturn(order, 1, 0), order, 10_000, false, NOW);
        paymentAnswers(PaymentCancelQueryResult.succeeded());

        Claim done = pipeline.progress(approved);

        assertThat(done.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(done.nextAttemptAt()).isNull();
        assertThat(done.approval().restock()).isFalse();
        verify(stockRestorationPort, never()).restoreReturn(anyLong(), anyLong(), any());
        verify(stockRestorationPort, never()).restoreCancellation(anyLong(), anyLong(), any());
        Order settled = read(order.id());
        assertThat(settled.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(settled.deliveredAt()).as("배송 완료 시각은 그대로").isEqualTo(order.deliveredAt());
        assertThat(returnHistories(order)).singleElement().satisfies(history -> assertThat(history.get("to_status")).isEqualTo("DELIVERED"));
    }

    @Test
    void anotherOpenReturnKeepsOrderRequestedAndStaleSourceReloadsByClaimId() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim approved = approve(requestReturn(order, 1, 0), order, 10_000, true, NOW);
        Claim other = requestReturn(order, 0, 1);
        // 같은 주문에 반품이 둘 — 넘겨받은 값이 낡아 첫 저장이 지면 주문이 아니라 클레임 아이디로 다시 읽어야 한다
        Claim held = update(approved, approved.holdUntil(NOW.plus(LEASE)));
        paymentAnswers(PaymentCancelQueryResult.succeeded());

        Claim done = pipeline.progress(approved);

        assertThat(done.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(done.version()).isGreaterThan(held.version());
        assertThat(readClaim(other.id())).isEqualTo(other);
        assertThat(read(order.id()).status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(returnHistories(order)).as("주문 상태가 그대로면 이력도 없다").isEmpty();
        verify(paymentCancelPort, times(1)).cancel(PAYMENT_ID, approved.id(), krw(10_000));
    }

    @Test
    void refundedReturnRestoresEvenAfterOrderMovedOn() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim approved = approve(requestReturn(order, 1, 0), order, 10_000, true, NOW);
        Claim refunded = update(approved, approved.markRefunded(NOW.plus(LEASE)));
        // 다른 반품 정리 뒤 자동 구매 확정이 먼저 갔어도 환불된 반품의 복원은 주문 상태를 보지 않는다
        setOrderStatus(order, OrderStatus.PURCHASE_CONFIRMED);

        Claim done = pipeline.progress(refunded);

        assertThat(done.status()).isEqualTo(Claim.Status.COMPLETED);
        verify(paymentCancelPort, never()).cancel(anyLong(), anyLong(), any());
        verify(stockRestorationPort).restoreReturn(refunded.id(), order.stockReservationId(), new TreeMap<>(Map.of(101L, 1)));
        assertThat(read(order.id()).status()).isEqualTo(OrderStatus.PURCHASE_CONFIRMED);
    }

    @Test
    void refundRecordedWhileOrderIsNotReturnRequestedFailsClosedWithoutRestoration() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim approved = approve(requestReturn(order, 1, 0), order, 10_000, true, NOW);
        setOrderStatus(order, OrderStatus.DELIVERED);
        paymentAnswers(PaymentCancelQueryResult.succeeded());

        Claim done = pipeline.progress(approved);

        assertThat(done.status()).isEqualTo(Claim.Status.RESTORATION_FAILED);
        assertThat(done.failureCode()).isEqualTo(OrderCancellationProcessor.ORDER_STATUS_UNEXPECTED);
        assertThat(readClaim(approved.id())).isEqualTo(done);
        verify(stockRestorationPort, never()).restoreReturn(anyLong(), anyLong(), any());
        assertThat(read(order.id()).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(returnHistories(order)).isEmpty();
    }

    @Test
    void rejectedUnknownAndUncountedAttemptsKeepOrderRequested() {
        Order order = order(OrderStatus.RETURN_REQUESTED);
        Claim rejectedSource = approve(requestReturn(order, 1, 0), order, 10_000, true, NOW);
        paymentAnswers(PaymentCancelQueryResult.rejected("PAYMENT_017"));

        Claim rejected = pipeline.progress(rejectedSource);

        assertThat(rejected.status()).isEqualTo(Claim.Status.REFUND_FAILED);
        assertThat(rejected.failureCode()).isEqualTo("PAYMENT_017");
        assertThat(rejected.isRefundRejected()).isTrue();
        assertThat(read(order.id()).status()).isEqualTo(OrderStatus.RETURN_REQUESTED);

        Order second = order(OrderStatus.RETURN_REQUESTED);
        Claim unknownSource = approve(requestReturn(second, 0, 1), second, 5_000, true, NOW);
        paymentAnswers(PaymentCancelQueryResult.unknown());
        Claim retried = pipeline.progress(unknownSource);
        assertThat(retried.status()).isEqualTo(Claim.Status.APPROVED);
        assertThat(retried.attemptCount()).isEqualTo(1);
        processor.recordAttemptFailure(retried);
        assertThat(readClaim(unknownSource.id()).attemptCount()).as("반품도 클레임 아이디로 다시 읽어 한 번 센다").isEqualTo(2);
        processor.recordAttemptFailure(retried);
        assertThat(readClaim(unknownSource.id()).attemptCount()).as("낡은 시도는 세지 않는다").isEqualTo(2);
        assertThat(read(second.id()).status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        verify(stockRestorationPort, never()).restoreReturn(anyLong(), anyLong(), any());
    }

    @Test
    void retryTargetsMixAwaitingCancellationsAndApprovedOrRefundedReturns() {
        // 다른 시험의 행(2026년)과 섞이지 않게 2001년 시각을 쓴다
        Instant base = Instant.parse("2001-01-01T00:00:00Z");
        Order paid = order(OrderStatus.PAID);
        Claim cancellation = update(null, Claim.requestCancellation(IDS.incrementAndGet(), paid, PAYMENT_ID, CancellationRefundPolicy.fullRefund(paid),
            OrderCancelReason.ORDER_MISTAKE, IDS::incrementAndGet, base, LEASE));
        Order returning = order(OrderStatus.RETURN_REQUESTED);
        Claim approved = approve(requestReturn(returning, 1, 0), returning, 10_000, true, base.plusSeconds(5));
        Claim refundedSource = approve(requestReturn(returning, 1, 0), returning, 10_000, true, base);
        Claim refunded = update(refundedSource, refundedSource.markRefunded(base.plusSeconds(60)));
        Claim inspection = requestReturn(returning, 0, 1);
        Order failing = order(OrderStatus.RETURN_REQUESTED);
        Claim failedSource = approve(requestReturn(failing, 1, 0), failing, 10_000, true, base);
        update(failedSource, failedSource.failRefund("PAYMENT_016"));

        List<Claim> due = claims.findCancellationRetryTargets(base.plusSeconds(180), 10);

        assertThat(due).extracting(Claim::id).containsExactly(refunded.id(), cancellation.id(), approved.id()).doesNotContain(inspection.id());
        assertThat(due).allSatisfy(claim -> assertThat(claim.items()).isNotEmpty());
        assertThat(claims.findCancellationRetryTargets(base.plusSeconds(180), 1)).extracting(Claim::id).containsExactly(refunded.id());
        assertThat(claims.findCancellationRetryTargets(base.plusSeconds(59), 10)).isEmpty();
    }
}
