package com.commercefoundry.domainlayer.order.support;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.exception.ClaimRequestKeyConflictException;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult;
import com.commercefoundry.domainlayer.order.application.service.OrderHistoryWebFacade;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationProcessor;
import com.commercefoundry.domainlayer.order.domain.model.CancellationRefundPolicy;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCancelReason;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** H2 · PostgreSQL 에서 같게 확인하는 결제 후 취소 클레임 저장 계약 (#16 PR-2a-1) — 새 칸 · 결정적 키 유니크 · 낙관적 락 · 재시도 대상 조회 · 새 check 제약. */
public abstract class ClaimCancellationPersistenceContract {
    private static final AtomicLong IDS = new AtomicLong(960_000);
    protected static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    protected static final Duration LEASE = Duration.ofMinutes(2);
    @Autowired protected OrderRepositoryPort orders;
    @Autowired protected ClaimRepositoryPort claims;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected OrderPaymentRepositoryPort payments;
    @Autowired protected OrderHistoryWebFacade facade;
    @Autowired protected OrderCancellationProcessor cancellationProcessor;
    @Autowired protected PaymentCancelPort paymentCancelPort;

    protected TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    protected Order paidOrder() {
        long id = IDS.incrementAndGet();
        Order order = Order.place(id, 7, domesticQuote(quoteLine(101, 10_000, 2), quoteLine(102, 5_000, 1)), IDS::incrementAndGet, address("KR"),
            IDS.incrementAndGet(), NOW);
        tx().executeWithoutResult(s -> {
            orders.insert(order, OrderStatusHistory.created(IDS.incrementAndGet(), order));
            jdbc.update("update orders.orders set status = 'PAID', version = version + 1 where id = ?", id);
        });
        return tx().execute(s -> orders.findById(id).orElseThrow());
    }

    /** {@code now} 에 접수한 전체 취소 — 다음 시도 시각은 {@code now + LEASE} 다. */
    protected Claim newCancellation(Order order, Instant now) {
        return Claim.requestCancellation(IDS.incrementAndGet(), order, 77L, CancellationRefundPolicy.fullRefund(order), OrderCancelReason.ORDER_MISTAKE,
            IDS::incrementAndGet, now, LEASE);
    }

    protected Claim insert(Claim claim) {
        return tx().execute(s -> claims.insert(claim));
    }

    protected Claim update(Claim claim) {
        return tx().execute(s -> claims.update(claim));
    }

    private static Claim decided(Claim claim, String reason) {
        return new Claim(claim.id(), claim.orderId(), claim.requestKey(), claim.type(), claim.status(), claim.expectedRefund(), claim.paymentId(),
            claim.attemptCount(), claim.nextAttemptAt(), claim.failureCode(), new Claim.Decision(reason, 7, NOW.plusNanos(123_456_789), "PAYMENT_016"),
            claim.items(), claim.requestedAt(), claim.version());
    }

    @Test
    void staleAttemptFailureCannotRewritePersistedNewLeaseOrRefundPhase() {
        Claim source = insert(newCancellation(paidOrder(), NOW));
        assertThatThrownBy(() -> claims.update(source)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> claims.findByIdForUpdate(source.id())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        Claim held = update(source.holdUntil(NOW.plusSeconds(300)));
        cancellationProcessor.recordAttemptFailure(source);
        assertThat(claims.findByOrderIdAndRequestKey(source.orderId(), source.requestKey()).orElseThrow()).isEqualTo(held);
        Claim refunded = update(held.markConfirmedRefund(NOW));
        cancellationProcessor.recordAttemptFailure(held);
        assertThat(claims.findByOrderIdAndRequestKey(source.orderId(), source.requestKey()).orElseThrow()).isEqualTo(refunded);
        Claim locked = tx().execute(status -> claims.findByIdForUpdate(source.id()).orElseThrow());
        assertThat(locked).isEqualTo(refunded);
    }

    @Test
    void decisionRoundTripUpdateAndStaleWriterPreserveEvidence() {
        Claim original = insert(newCancellation(paidOrder(), NOW));
        assertThat(original.decision()).isNull();
        Claim saved = update(decided(original, "운영 확인"));
        assertThat(saved.decision()).isEqualTo(decided(original, "운영 확인").decision());
        assertThat(saved.decision().decidedAt()).isEqualTo(NOW.plusNanos(123_456_000));
        Claim read = claims.findByOrderIdAndRequestKey(saved.orderId(), saved.requestKey()).orElseThrow();
        assertThat(read).isEqualTo(saved);
        Claim refunded = update(saved.markRefunded(NOW));
        Claim completed = update(refunded.complete());
        assertThat(completed.decision()).isEqualTo(saved.decision());
        assertThat(completed.failureCode()).isNull();
        assertThatThrownBy(() -> update(original.holdUntil(NOW.plusSeconds(60)))).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(claims.findByOrderIdAndRequestKey(saved.orderId(), saved.requestKey()).orElseThrow()).isEqualTo(completed);
    }

    @Test
    void concurrentDecisionWritersHaveOneWinnerWithoutLosingEvidence() throws Exception {
        Claim original = insert(newCancellation(paidOrder(), NOW));
        AtomicLong writer = new AtomicLong();
        List<String> results = race(2, () -> {
            String reason = "운영 확인 " + writer.incrementAndGet();
            try {
                update(decided(original, reason));
                return reason;
            } catch (OptimisticLockingFailureException exception) {
                return "LOST";
            }
        });
        assertThat(results).filteredOn("LOST"::equals).hasSize(1);
        Claim read = claims.findByOrderIdAndRequestKey(original.orderId(), original.requestKey()).orElseThrow();
        assertThat(results).contains(read.decision().reason());
        assertThat(read.version()).isEqualTo(original.version() + 1);
        assertThat(read.decision().previousFailureCode()).isEqualTo("PAYMENT_016");
    }

    @Test
    void decisionColumnsAreAllNullOrCompleteCancellationEvidence() {
        Order order = paidOrder();
        long id = rawClaim(order.id(), "CANCELLATION", "REFUND_FAILED", 77L, 1000L, 0, null, "PAYMENT_019");
        for (String assignment : List.of("decision_reason = '확인'", "decided_by_member_id = 7", "decided_at = current_timestamp",
            "decision_failure_code = 'PAYMENT_016'")) {
            violates(() -> jdbc.update("update orders.claim set " + assignment + " where id = ?", id), "부분 결정 정보");
        }
        jdbc.update("update orders.claim set decision_reason = ?, decided_by_member_id = 7, decided_at = ?, decision_failure_code = ? where id = ?",
            "운영 확인", Timestamp.from(NOW), "PAYMENT_016", id);
        for (String assignment : List.of("decision_reason = null", "decided_by_member_id = null", "decided_at = null", "decision_failure_code = null",
            "decision_reason = ' '", "decision_failure_code = ' '", "decided_by_member_id = 0", "decided_by_member_id = -1")) {
            violates(() -> jdbc.update("update orders.claim set " + assignment + " where id = ?", id), "불완전 결정 정보");
        }
        violates(() -> jdbc.update("update orders.claim set decision_reason = ? where id = ?", "가".repeat(201), id), "사유 길이");
        violates(() -> jdbc.update("update orders.claim set decision_failure_code = ? where id = ?", "X".repeat(41), id), "코드 길이");
        violates(() -> jdbc.update("update orders.claim set failure_code = null where id = ?", id), "기존 실패 코드 제약 유지");
        long returned = rawClaim(order.id(), "RETURN", "REQUESTED", null, null, 0, null, null);
        violates(() -> jdbc.update("update orders.claim set decision_reason = '확인', decided_by_member_id = 7, decided_at = ?, "
            + "decision_failure_code = 'PAYMENT_016' where id = ?", Timestamp.from(NOW), returned), "반품에는 결정 정보 없음");
    }

    @Test
    void insertsAndReadsCancellationClaimWithNewColumnsAndReasons() {
        Order order = paidOrder();

        Claim saved = insert(newCancellation(order, NOW));
        Claim read = claims.findByOrderIdAndRequestKey(order.id(), Claim.cancellationRequestKey(order.id())).orElseThrow();

        assertThat(read).isEqualTo(saved);
        assertThat(read.type()).isEqualTo(Claim.Type.CANCELLATION);
        assertThat(read.paymentId()).isEqualTo(77L);
        assertThat(read.expectedRefund()).isEqualTo(order.quote().totalAmount());
        assertThat(read.attemptCount()).isZero();
        assertThat(read.nextAttemptAt()).as("접수 시각 + lease").isEqualTo(NOW.plus(LEASE));
        assertThat(read.failureCode()).isNull();
        assertThat(read.requestedAt()).isNotNull();
        assertThat(read.version()).isZero();
        assertThat(read.items()).extracting(ClaimItem::orderItemId, ClaimItem::quantity)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(order.itemIds().get(0), 2), org.assertj.core.groups.Tuple.tuple(order.itemIds().get(1), 1));
        assertThat(read.items()).extracting(ClaimItem::reason).containsOnly(ClaimItem.Reason.ORDER_MISTAKE);
        assertThat(claims.sumRequestedReturnQuantities(order.id())).as("취소 클레임은 반품 수량에 들지 않는다").isEmpty();
        assertThat(claims.findCancellationRetryTargets(NOW.plus(LEASE).minusSeconds(1), 100)).extracting(Claim::id)
            .as("lease 안에서는 스케줄러가 집지 않는다").doesNotContain(saved.id());
    }

    @Test
    void deterministicKeyAllowsOneFullCancellationPerOrder() {
        Order order = paidOrder();
        insert(newCancellation(order, NOW));

        assertThatThrownBy(() -> insert(newCancellation(order, NOW))).as("PostgreSQL 은 요청 키 충돌로 가르고, 제약 이름이 없는 H2 는 원래 예외다")
            .isInstanceOfAny(ClaimRequestKeyConflictException.class, DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from orders.claim where order_id = ?", Long.class, order.id())).isEqualTo(1);
    }

    @Test
    void updateBumpsVersionAndStaleWriterLoses() {
        Order order = paidOrder();
        Claim saved = insert(newCancellation(order, NOW));

        Claim retried = update(saved.retryLater(NOW, Duration.ofMinutes(1), Duration.ofHours(1)));
        Claim refunded = update(retried.markRefunded(NOW));
        Claim failed = update(refunded.failRestoration("PRODUCT_028"));

        assertThat(retried.version()).isEqualTo(1);
        assertThat(retried.requestedAt()).isEqualTo(saved.requestedAt());
        assertThat(failed.version()).isEqualTo(3);
        Claim read = claims.findByOrderIdAndRequestKey(order.id(), saved.requestKey()).orElseThrow();
        assertThat(read).isEqualTo(failed);
        assertThat(read.status()).isEqualTo(Claim.Status.RESTORATION_FAILED);
        assertThat(read.failureCode()).isEqualTo("PRODUCT_028");
        assertThat(read.nextAttemptAt()).isNull();
        assertThatThrownBy(() -> update(saved.failRefund("PAYMENT_016"))).as("앞선 버전으로 쓰면 진다").isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(claims.findByOrderIdAndRequestKey(order.id(), saved.requestKey()).orElseThrow()).isEqualTo(failed);
    }

    @Test
    void retryTargetsAreDueRequestedOrRefundedCancellationsInDueOrderWithItems() {
        // 다른 시험의 클레임(2026년)과 섞이지 않게 2000년 시각을 쓴다. 접수 클레임의 다음 시도는 접수 + 2분(lease)이다
        Instant base = Instant.parse("2000-01-01T00:00:00Z");
        Claim requestedLate = insert(newCancellation(paidOrder(), base.plusSeconds(30)));
        Claim requestedEarly = insert(newCancellation(paidOrder(), base.plusSeconds(10)));
        Claim refunded = update(insert(newCancellation(paidOrder(), base)).markRefunded(base.plusSeconds(20)));
        insert(newCancellation(paidOrder(), base.plusSeconds(3_600)));
        update(insert(newCancellation(paidOrder(), base)).failRefund("PAYMENT_016"));
        update(update(insert(newCancellation(paidOrder(), base)).markRefunded(base)).complete());
        Order delivered = paidOrder();
        insert(new Claim(IDS.incrementAndGet(), delivered.id(), UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(IDS.incrementAndGet(), delivered.itemIds().getFirst(), 1, ClaimItem.Reason.DEFECTIVE)), null, null));

        List<Claim> due = claims.findCancellationRetryTargets(base.plusSeconds(180), 10);
        List<Claim> first = claims.findCancellationRetryTargets(base.plusSeconds(180), 1);

        assertThat(due).extracting(Claim::id).containsExactly(refunded.id(), requestedEarly.id(), requestedLate.id());
        assertThat(due).allSatisfy(claim -> assertThat(claim.items()).hasSize(2));
        assertThat(due.getFirst()).isEqualTo(refunded);
        assertThat(first).extracting(Claim::id).containsExactly(refunded.id());
        assertThat(claims.findCancellationRetryTargets(base.plusSeconds(19), 10)).isEmpty();
    }

    @Test
    void cancellationChecksRequirePaymentRefundAndStatusConsistentAttemptColumns() {
        Order order = paidOrder();
        long id = order.id();

        violates(() -> rawClaim(id, "CANCELLATION", "REQUESTED", null, 1000L, 0, NOW, null), "결제 아이디 없음");
        violates(() -> rawClaim(id, "CANCELLATION", "REQUESTED", 77L, null, 0, NOW, null), "환불액 없음");
        violates(() -> rawClaim(id, "CANCELLATION", "REQUESTED", 77L, 0L, 0, NOW, null), "환불액 0");
        violates(() -> rawClaim(id, "CANCELLATION", "REQUESTED", 0L, 1000L, 0, NOW, null), "양수가 아닌 결제 아이디");
        violates(() -> rawClaim(id, "RETURN", "REQUESTED", 77L, null, 0, null, null), "반품에 결제 아이디");
        violates(() -> rawClaim(id, "RETURN", "REQUESTED", null, null, -1, null, null), "음수 재시도 횟수");
        violates(() -> rawClaim(id, "EXCHANGE", "REQUESTED", null, null, 0, null, null), "모르는 유형");
        violates(() -> rawClaim(id, "CANCELLATION", "CANCELED", 77L, 1000L, 0, null, null), "모르는 상태");
        violates(() -> rawClaim(id, "CANCELLATION", "REQUESTED", 77L, 1000L, 0, null, null), "대기 상태에 다음 시각 없음");
        violates(() -> rawClaim(id, "CANCELLATION", "REFUNDED", 77L, 1000L, 0, null, null), "복원 대기에 다음 시각 없음");
        violates(() -> rawClaim(id, "CANCELLATION", "COMPLETED", 77L, 1000L, 0, NOW, null), "종결에 다음 시각");
        violates(() -> rawClaim(id, "CANCELLATION", "REFUND_FAILED", 77L, 1000L, 0, null, null), "종결 실패에 코드 없음");
        violates(() -> rawClaim(id, "CANCELLATION", "REQUESTED", 77L, 1000L, 0, NOW, "PAYMENT_016"), "대기 상태에 실패 코드");
        violates(() -> rawClaim(id, "CANCELLATION", "COMPLETED", 77L, 1000L, 0, null, "PAYMENT_016"), "완료에 실패 코드");
        rawClaim(id, "CANCELLATION", "REQUESTED", 77L, 1000L, 0, NOW, null);
        rawClaim(id, "CANCELLATION", "REFUNDED", 77L, 1000L, 0, NOW, null);
        rawClaim(id, "CANCELLATION", "COMPLETED", 77L, 1000L, 0, null, null);
        rawClaim(id, "CANCELLATION", "REFUND_FAILED", 77L, 1000L, 0, null, "PAYMENT_016");
        rawClaim(id, "CANCELLATION", "RESTORATION_FAILED", 77L, 1000L, 0, null, "PRODUCT_028");
        long claimId = rawClaim(id, "RETURN", "REQUESTED", null, null, 0, null, null);
        for (String reason : List.of("CHANGE_OF_MIND", "DEFECTIVE", "WRONG_ITEM", "ORDER_MISTAKE", "PAYMENT_ISSUE", "OTHER")) {
            jdbc.update("insert into orders.claim_item (id, claim_id, order_item_id, quantity, reason, created_at, updated_at) values (?, ?, ?, 1, ?, ?, ?)",
                IDS.incrementAndGet(), claimId, IDS.incrementAndGet(), reason, Timestamp.from(NOW), Timestamp.from(NOW));
        }
        violates(() -> jdbc.update("insert into orders.claim_item (id, claim_id, order_item_id, quantity, reason, created_at, updated_at) "
            + "values (?, ?, ?, 1, 'LOST', ?, ?)", IDS.incrementAndGet(), claimId, IDS.incrementAndGet(), Timestamp.from(NOW), Timestamp.from(NOW)), "모르는 사유");
    }

    /**
     * 같은 주문의 고객 결제 후 취소 두 건이 동시에 들어오면(#16 PR-2) 행 잠금 · 결정적 키로 클레임은 하나, 결제 취소 호출도 한 번이다. 진 쪽은 같은 취소의 재생 200 이거나
     * 아직 커밋 전이면 409 {@code ORDER_025} 다.
     */
    @Test
    void concurrentPaidCancellationsCreateOneClaimAndOneRefundCall() throws Exception {
        Order order = paidOrder();
        long paymentId = IDS.incrementAndGet();
        tx().executeWithoutResult(s -> payments.insert(OrderPayment.paid(IDS.incrementAndGet(), order, paymentId, order.quote().totalAmount(), NOW,
            UUID.randomUUID())));
        when(paymentCancelPort.cancel(eq(paymentId), anyLong(), any())).thenReturn(PaymentCancelQueryResult.succeeded());

        List<String> results = race(2, () -> {
            try {
                return facade.cancel(order.memberId(), order.id(), OrderCancelReason.ORDER_MISTAKE).status().code();
            } catch (OrderException exception) {
                return exception.getErrorCode().getCode();
            }
        });

        assertThat(results).contains("CANCELED").allSatisfy(result -> assertThat(result).isIn("CANCELED", "CANCEL_REQUESTED", "ORDER_025"));
        assertThat(jdbc.queryForObject("select count(*) from orders.claim where order_id = ?", Long.class, order.id())).isEqualTo(1);
        verify(paymentCancelPort, times(1)).cancel(eq(paymentId), anyLong(), any());
    }

    /** 재시도 선점 두 건이 같은 클레임을 동시에 집으면 하나만 이긴다 — 진 쪽은 낙관적 락 예외로 원격 호출을 하지 않는다 (#16 PR-2). */
    @Test
    void concurrentHoldsLetOnlyOneWorkerWin() throws Exception {
        Claim due = insert(newCancellation(paidOrder(), Instant.parse("2000-01-01T00:00:00Z")));

        List<String> results = race(2, () -> {
            try {
                cancellationProcessor.hold(due);
                return "WON";
            } catch (OptimisticLockingFailureException exception) {
                return "LOST";
            }
        });

        assertThat(results).containsExactlyInAnyOrder("WON", "LOST");
    }

    /** 여러 스레드를 같은 시점에 출발시켜 결과(또는 예상 밖 예외의 타입 이름)를 모은다. */
    private static List<String> race(int threads, Callable<String> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (RuntimeException exception) {
                        return exception.getClass().getSimpleName();
                    }
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void violates(Runnable insert, String description) {
        assertThatThrownBy(insert::run).as(description).isInstanceOf(DataIntegrityViolationException.class);
    }

    private long rawClaim(long orderId, String type, String status, Long paymentId, Long refund, int attemptCount, Instant next, String failureCode) {
        long id = IDS.incrementAndGet();
        jdbc.update("insert into orders.claim (id, order_id, request_key, type, status, expected_refund_amount, expected_refund_currency, payment_id, "
                + "attempt_count, next_attempt_at, failure_code, version, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
            id, orderId, UUID.randomUUID(), type, status, refund, refund == null ? null : "KRW", paymentId, attemptCount,
            next == null ? null : Timestamp.from(next), failureCode, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }
}
