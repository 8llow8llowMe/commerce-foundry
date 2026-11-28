package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.CancellationStepOutcome;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 결제 후 취소 엔진 일반화 (#684 PR-2) — 부분 취소가 있는 주문의 전체 취소(남은 수량 · 남은 금액) · 취소 정리(취소 완료 · 결제 완료 복귀) · fail-closed ·
 * 클레임 아이디 재조회 · 정리 뒤 복원 · 운영자 수습을 실제 Processor 와 파이프라인에 가짜 저장소 · 원격을 붙여 잇는다. 값은 모두 가짜다.
 *
 * <p>주문: A 40,000 × 1(SKU 9001) · B 10,000 × 2(SKU 9002), 상품 60,000 — 5만 원 이상 무료배송(기본 배송비 3,000), 결제 60,000.
 * A 를 먼저 취소하면 남은 B 20,000 이 기준 아래라 배송비 3,000 을 다시 받아 환불 37,000, 남은 수량 전체 취소는 {@code due(B × 2)} = 23,000 이다.
 */
class OrderCancellationEngineTest {

    private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final long ORDER = 1L;
    private static final long MEMBER = 7L;
    private static final long PAYMENT = 77L;
    private static final long RESERVATION = 99L;
    private static final long ITEM_A = 101L;
    private static final long ITEM_B = 102L;
    private static final long ADMIN = 9L;
    private static final String REASON = "결제 관리 화면에서 취소 완료 확인";

    private final List<String> events = new ArrayList<>();
    private final InMemoryClaims claims = new InMemoryClaims(events);
    private final Map<Long, Order> orderRows = new HashMap<>();
    private final List<OrderStatusHistory> histories = new ArrayList<>();
    private final OrderRepositoryPort orders = mock(OrderRepositoryPort.class);
    private final OrderPaymentRepositoryPort payments = mock(OrderPaymentRepositoryPort.class);
    private final Deque<PaymentCancelQueryResult> paymentAnswers = new ArrayDeque<>();
    private final List<String> paymentCalls = new ArrayList<>();
    private final List<String> restorations = new ArrayList<>();
    private final OrderCancellationProperties properties = new OrderCancellationProperties(false,
        new OrderCancellationProperties.Retry(true, Duration.ofMinutes(1), 50, 3, Duration.ofMinutes(1), Duration.ofHours(1), LEASE));
    private final SnowflakeIdGenerator ids = new SnowflakeIdGenerator(5, 5);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private OrderCancellationProcessor processor;
    private OrderCancellationPipelineProcessor pipeline;
    private ClaimAdminRecoveryProcessor recovery;
    /** 주문 NOWAIT 잠금 직전에 끼워 넣을 동작 — 다른 TX 의 커밋 · 잠금 경합(예외)을 흉내 낸다. */
    private Runnable onOrderLock = () -> { };

    @BeforeEach
    void wire() {
        when(orders.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(orderRows.get(call.<Long>getArgument(0))));
        when(orders.findByIdForUpdate(anyLong())).thenAnswer(call -> {
            events.add("lock order");
            onOrderLock.run();
            return Optional.ofNullable(orderRows.get(call.<Long>getArgument(0)));
        });
        when(orders.findByIdAndMemberIdForUpdate(anyLong(), anyLong())).thenAnswer(call -> {
            events.add("lock order");
            long memberId = call.getArgument(1);
            return Optional.ofNullable(orderRows.get(call.<Long>getArgument(0))).filter(order -> order.memberId() == memberId);
        });
        doAnswer(call -> {
            Order changed = call.getArgument(0);
            Order current = orderRows.get(changed.id());
            if (!Objects.equals(current.version(), changed.version())) {
                throw new ObjectOptimisticLockingFailureException("OrderEntity", changed.id());
            }
            orderRows.put(changed.id(), with(changed, changed.status(), changed.version() + 1));
            histories.add(call.getArgument(1));
            events.add("save order " + changed.status());
            return null;
        }).when(orders).saveStatusChange(any(), any());
        when(payments.findPaidByOrderId(ORDER)).thenAnswer(call -> Optional.of(paidRecord()));
        PaymentCancelPort paymentCancelPort = (paymentId, claimId, amount) -> {
            paymentCalls.add(paymentId + ":" + claimId + ":" + amount.amount() + " " + amount.currency());
            return paymentAnswers.isEmpty() ? PaymentCancelQueryResult.succeeded() : paymentAnswers.poll();
        };
        StockRestorationPort stockRestorationPort = new StockRestorationPort() {
            @Override
            public void restoreCancellation(long claimId, long reservationId, SortedMap<Long, Integer> quantitiesBySkuId) {
                restorations.add(claimId + ":" + reservationId + ":" + quantitiesBySkuId);
            }

            @Override
            public void restoreReturn(long claimId, long reservationId, SortedMap<Long, Integer> quantitiesBySkuId) {
                throw new AssertionError("취소 클레임은 반품 복원을 부르지 않는다");
            }
        };
        processor = new OrderCancellationProcessor(orders, claims, payments, properties, ids, clock);
        pipeline = new OrderCancellationPipelineProcessor(processor, paymentCancelPort, stockRestorationPort);
        recovery = new ClaimAdminRecoveryProcessor(claims, orders, ids, properties, clock);
    }

    // ---- 접수(tx1) — 남은 수량 전부 · due(before) · 결제 대조 ----

    @Test
    @DisplayName("선행 부분 취소가 없으면 전체 취소는 전 항목 · 전 수량 · 결제 총액 · 결정적 키로 지금과 같다")
    void fullCancellationWithoutPartialIsUnchanged() {
        givenOrder(OrderStatus.PAID);

        Claim claim = processor.request(MEMBER, ORDER, OrderCancelReason.OTHER).claim();

        assertThat(quantities(claim)).containsExactlyInAnyOrderEntriesOf(Map.of(ITEM_A, 1, ITEM_B, 2));
        assertThat(claim.expectedRefund()).isEqualTo(krw(60_000));
        assertThat(claim.requestKey()).isEqualTo(Claim.cancellationRequestKey(ORDER));
        assertThat(claim.paymentId()).isEqualTo(PAYMENT);
        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
    }

    @Test
    @DisplayName("선행 부분 취소(정산) 뒤 전체 취소 — 남은 수량 · 남은 금액만 환불하고 최종 취소 완료, 재고는 남은 수량만 복원한다")
    void fullCancellationAfterSettledPartialRefundsOnlyTheRest() {
        givenOrder(OrderStatus.PAID);
        Claim partial = completed(seedPartial(500L, Map.of(ITEM_A, 1), 37_000));

        Claim claim = processor.request(MEMBER, ORDER, OrderCancelReason.CHANGE_OF_MIND).claim();
        Claim progressed = pipeline.progress(claim);

        assertThat(quantities(claim)).containsExactlyEntriesOf(Map.of(ITEM_B, 2));
        assertThat(claim.expectedRefund()).isEqualTo(krw(23_000));
        assertThat(claim.requestKey()).isEqualTo(Claim.cancellationRequestKey(ORDER));
        assertThat(paymentCalls).containsExactly(PAYMENT + ":" + claim.id() + ":23000 KRW");
        assertThat(restorations).containsExactly(claim.id() + ":" + RESERVATION + ":{9002=2}");
        assertThat(progressed.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(claims.get(partial.id())).isEqualTo(partial);
        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(histories).extracting(OrderStatusHistory::toStatus, OrderStatusHistory::changedByType, OrderStatusHistory::reason).containsExactly(
            org.assertj.core.groups.Tuple.tuple(OrderStatus.CANCEL_REQUESTED, StatusChangedBy.CUSTOMER, "CHANGE_OF_MIND"),
            org.assertj.core.groups.Tuple.tuple(OrderStatus.CANCELED, StatusChangedBy.SERVICE, OrderStatusHistory.CANCELLATION_REFUNDED));
    }

    @Test
    @DisplayName("접수는 주문 잠금 → 클레임 목록 → 접수 순서로 한 트랜잭션 안에서 한다")
    void requestReadsClaimsUnderOrderLock() {
        givenOrder(OrderStatus.PAID);
        completed(seedPartial(500L, Map.of(ITEM_A, 1), 37_000));
        events.clear();

        processor.request(MEMBER, ORDER, OrderCancelReason.OTHER);

        assertThat(events).containsSubsequence("lock order", "list claims", "insert claim");
        assertThat(events.indexOf("lock order")).isZero();
    }

    @Test
    @DisplayName("ORDER_034 일반화 — 정산된 취소 환불 합 + 이번 환불 ≠ 결제 금액이면 접수하지 않는다(500)")
    void settledRefundSumMismatchIsRejected() {
        givenOrder(OrderStatus.PAID);
        completed(seedPartial(500L, Map.of(ITEM_A, 1), 36_999));

        assertCode(() -> processor.request(MEMBER, ORDER, OrderCancelReason.OTHER), OrderErrorCode.CANCELLATION_AMOUNT_MISMATCH);
        assertThat(events).doesNotContain("insert claim");
        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.PAID);
        assertThat(histories).isEmpty();
    }

    @Test
    @DisplayName("결제 완료 주문에 열린 취소가 남아 있으면(데이터 이상) 전체 취소를 접수하지 않는다 — ORDER_034, 아무것도 쓰지 않는다")
    void openCancellationOnPaidOrderIsRejected() {
        givenOrder(OrderStatus.PAID);
        seedPartial(500L, Map.of(ITEM_A, 1), 37_000);

        assertCode(() -> processor.request(MEMBER, ORDER, OrderCancelReason.OTHER), OrderErrorCode.CANCELLATION_AMOUNT_MISMATCH);
        assertThat(events).doesNotContain("insert claim");
        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.PAID);
    }

    // ---- 환불 확정 반영(tx2) — 정리 판정 ----

    @Test
    @DisplayName("부분 취소의 환불이 확정되면 결제 완료로 돌아가고 SERVICE · PARTIAL_CANCELLATION_REFUNDED 이력, 재고는 그 클레임 수량만 복원한다")
    void partialRefundReturnsOrderToPaid() {
        givenOrder(OrderStatus.CANCEL_REQUESTED);
        Claim partial = seedPartial(500L, Map.of(ITEM_A, 1), 37_000);

        Claim progressed = pipeline.progress(partial);

        assertThat(progressed.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.PAID);
        assertThat(histories).singleElement().satisfies(history -> {
            assertThat(history.fromStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
            assertThat(history.toStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(history.changedByType()).isEqualTo(StatusChangedBy.SERVICE);
            assertThat(history.reason()).isEqualTo(OrderStatusHistory.PARTIAL_CANCELLATION_REFUNDED);
        });
        assertThat(paymentCalls).containsExactly(PAYMENT + ":500:37000 KRW");
        assertThat(restorations).containsExactly("500:" + RESERVATION + ":{9001=1}");
    }

    @Test
    @DisplayName("판정 입력 바꿔 넣기 — 잠금 뒤 읽은 목록의 이번 클레임은 아직 환불 대기지만 판정은 환불 완료로 본다(누락하면 취소 요청에 멈춘다)")
    void settlementUsesRecordedClaimInsteadOfStaleListEntry() {
        givenOrder(OrderStatus.CANCEL_REQUESTED);
        Claim partial = seedPartial(500L, Map.of(ITEM_A, 1), 37_000);
        List<Claim.Status> listed = new ArrayList<>();
        claims.onList = rows -> rows.stream().filter(row -> row.id() == partial.id()).forEach(row -> listed.add(row.status()));

        Claim recorded = processor.recordRefund(partial, CancellationStepOutcome.done());

        assertThat(listed).as("판정 직전 목록의 이번 클레임").containsExactly(Claim.Status.REQUESTED);
        assertThat(recorded.status()).isEqualTo(Claim.Status.REFUNDED);
        assertThat(orderRows.get(ORDER).status()).as("STILL_REQUESTED 로 멈추지 않는다").isEqualTo(OrderStatus.PAID);
        assertThat(events).containsSubsequence("lock order", "list claims", "update claim REFUNDED", "save order PAID");
    }

    @Test
    @DisplayName("전체 취소도 목록의 갱신 전 값에 갇히지 않는다 — 부분 취소 뒤 남은 수량 클레임의 환불 확정은 취소 완료다")
    void remainderRefundSettlesToCanceled() {
        givenOrder(OrderStatus.CANCEL_REQUESTED);
        completed(seedPartial(500L, Map.of(ITEM_A, 1), 37_000));
        Claim remainder = seedPartial(600L, Map.of(ITEM_B, 2), 23_000);

        processor.recordRefund(remainder, CancellationStepOutcome.done());

        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(histories).singleElement().extracting(OrderStatusHistory::reason).isEqualTo(OrderStatusHistory.CANCELLATION_REFUNDED);
    }

    enum Anomaly { OVER_QUANTITY, CANCELED_ORDER_PARTIAL }

    @ParameterizedTest
    @EnumSource(Anomaly.class)
    @DisplayName("정리 판정이 불가능하면(데이터 이상) 롤백하지 않는다 — 환불 기록을 남기고 RESTORATION_FAILED(ORDER_STATUS_UNEXPECTED), 주문 · 재고는 그대로")
    void unsettleableOrderFailsClosed(Anomaly anomaly) {
        Claim partial;
        if (anomaly == Anomaly.OVER_QUANTITY) {
            // 같은 수량이 정산된 취소와 열린 취소에 두 번 잡혔다 — 정책이 IllegalStateException
            givenOrder(OrderStatus.CANCEL_REQUESTED);
            completed(seedPartial(500L, Map.of(ITEM_A, 1), 37_000));
            partial = seedPartial(600L, Map.of(ITEM_A, 1), 37_000);
        } else {
            // 취소 완료 주문의 일부 수량 정리 — 정리 전이(취소 완료 → 결제 완료)가 없다
            givenOrder(OrderStatus.CANCELED);
            partial = seedPartial(600L, Map.of(ITEM_A, 1), 37_000);
        }
        OrderStatus before = orderRows.get(ORDER).status();

        Claim progressed = pipeline.progress(partial);

        assertThat(progressed.status()).isEqualTo(Claim.Status.RESTORATION_FAILED);
        assertThat(progressed.failureCode()).isEqualTo(OrderCancellationProcessor.ORDER_STATUS_UNEXPECTED);
        assertThat(claims.get(partial.id()).hasRecordedRefund()).as("환불 기록은 남는다").isTrue();
        assertThat(claims.get(partial.id()).nextAttemptAt()).isNull();
        assertThat(orderRows.get(ORDER).status()).isEqualTo(before);
        assertThat(histories).isEmpty();
        assertThat(restorations).isEmpty();
        assertThat(recovery.isRetryable(claims.get(partial.id()))).as("ORDER_STATUS_UNEXPECTED 는 다시 시도하지 않는다").isFalse();
    }

    // ---- 재조회 · 멱등 — 클레임 아이디 ----

    @Test
    @DisplayName("환불 미확정은 같은 클레임 아이디 · 같은 금액으로 다시 보내고, 확정되면 정리 · 복원까지 잇는다")
    void unknownRefundIsRetriedWithSameClaim() {
        givenOrder(OrderStatus.CANCEL_REQUESTED);
        Claim partial = seedPartial(500L, Map.of(ITEM_A, 1), 37_000);
        paymentAnswers.add(PaymentCancelQueryResult.unknown());

        Claim first = pipeline.progress(partial);
        Claim second = pipeline.progress(claims.get(partial.id()));

        assertThat(first.status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(first.attemptCount()).isEqualTo(1);
        assertThat(second.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(paymentCalls).hasSize(2).containsOnly(PAYMENT + ":500:37000 KRW");
        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.PAID);
        assertThat(restorations).hasSize(1);
    }

    @Test
    @DisplayName("환불 반영에서 버전으로 지면 클레임 아이디로 다시 읽는다 — 주문의 결정적 키로 찾지 않아 부분 취소 클레임도 이어진다")
    void lostRefundRecordingReloadsByClaimId() {
        givenOrder(OrderStatus.CANCEL_REQUESTED);
        Claim partial = seedPartial(500L, Map.of(ITEM_A, 1), 37_000);
        claims.concurrentWrites = 1;

        Claim progressed = pipeline.progress(partial);

        assertThat(progressed.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.PAID);
        assertThat(events).contains("find claim 500").doesNotContain("find by key");
        assertThat(paymentCalls).hasSize(1);
    }

    @Test
    @DisplayName("같은 클레임 재전송 — 이미 정리된 클레임을 낡은 값으로 다시 돌려도 payment 는 같은 클레임 아이디(멱등 200)이고 정리 · 복원은 다시 하지 않는다")
    void resentClaimIsIdempotent() {
        givenOrder(OrderStatus.CANCEL_REQUESTED);
        Claim partial = seedPartial(500L, Map.of(ITEM_A, 1), 37_000);
        pipeline.progress(partial);
        Order settled = orderRows.get(ORDER);
        int historyCount = histories.size();

        Claim replay = pipeline.progress(partial);

        assertThat(replay).isSameAs(partial);
        assertThat(paymentCalls).hasSize(2).containsOnly(PAYMENT + ":500:37000 KRW");
        assertThat(restorations).hasSize(1);
        assertThat(claims.get(partial.id()).status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(orderRows.get(ORDER)).isEqualTo(settled);
        assertThat(histories).hasSize(historyCount);
        assertThat(events).doesNotContain("find by key");
    }

    @Test
    @DisplayName("tx2 의 주문 잠금에서 졌는데 다른 쪽이 이 환불을 이미 기록했으면 503 없이 이미 반영된 경우로 끝낸다(복원은 그쪽이 잇는다)")
    void lostOrderLockAfterRefundRecordedElsewhereIsReplay() {
        givenOrder(OrderStatus.CANCEL_REQUESTED);
        Claim partial = seedPartial(500L, Map.of(ITEM_A, 1), 37_000);
        boolean[] first = {true};
        onOrderLock = () -> {
            if (first[0]) {
                first[0] = false;
                // 다른 실행이 같은 클레임의 환불을 먼저 기록 · 정리하고 아직 주문 행을 쥐고 있다
                claims.put(partial.markRefunded(NOW.plus(LEASE)), 1L);
                orderRows.put(ORDER, with(orderRows.get(ORDER), OrderStatus.PAID, 4L));
                throw new jakarta.persistence.PessimisticLockException("order row is locked");
            }
        };

        Claim result = pipeline.progress(partial);

        assertThat(result).isSameAs(partial);
        assertThat(claims.get(partial.id()).status()).isEqualTo(Claim.Status.REFUNDED);
        assertThat(restorations).as("복원은 기록한 쪽이 잇는다").isEmpty();
        assertThat(paymentCalls).hasSize(1);
        assertThat(events).contains("find claim 500").doesNotContain("find by key");
    }

    @Test
    @DisplayName("tx2 의 주문 잠금에서 졌고 환불이 아직 기록되지 않았으면 503 ORDER_036 이다 — 클레임 · 주문은 그대로라 같은 클레임으로 다시 보낸다")
    void lostOrderLockWithoutRecordedRefundIs503() {
        givenOrder(OrderStatus.CANCEL_REQUESTED);
        Claim partial = seedPartial(500L, Map.of(ITEM_A, 1), 37_000);
        onOrderLock = () -> {
            throw new jakarta.persistence.PessimisticLockException("order row is locked");
        };

        assertThatThrownBy(() -> pipeline.progress(partial)).isInstanceOfSatisfying(OrderException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(OrderErrorCode.CANCELLATION_REFUND_RECORDING_UNAVAILABLE);
            assertThat(failure.getCause()).isInstanceOf(jakarta.persistence.PessimisticLockException.class);
        });
        assertThat(claims.get(partial.id())).isEqualTo(partial);
        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(histories).isEmpty();
        assertThat(restorations).isEmpty();

        onOrderLock = () -> { };
        assertThat(pipeline.progress(claims.get(partial.id())).status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(paymentCalls).hasSize(2).containsOnly(PAYMENT + ":500:37000 KRW");
        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("반영하지 못한 시도의 실패 횟수도 클레임 아이디로 다시 읽은 그 클레임에 센다")
    void attemptFailureCountsOnClaimById() {
        givenOrder(OrderStatus.CANCEL_REQUESTED);
        completed(seedPartial(400L, Map.of(ITEM_B, 1), 10_000));
        Claim partial = seedPartial(500L, Map.of(ITEM_A, 1), 37_000);

        processor.recordAttemptFailure(partial);

        assertThat(claims.get(partial.id()).attemptCount()).isEqualTo(1);
        assertThat(claims.get(400L).attemptCount()).isZero();
        assertThat(events).doesNotContain("find by key");
    }

    // ---- 정리 뒤 복원 — 정리를 다시 부르지 않는다 ----

    @Test
    @DisplayName("정리 뒤 상품 준비중으로 넘어간 주문에 복원 재시도가 와도 전이 예외 없이 복원만 끝낸다")
    void restorationRetryAfterSettlementDoesNotTouchOrder() {
        givenOrder(OrderStatus.PREPARING);
        Claim refunded = claims.put(seedPartial(500L, Map.of(ITEM_A, 1), 37_000).markRefunded(NOW.plus(LEASE)), 1L);

        Claim progressed = pipeline.progress(refunded);

        assertThat(progressed.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.PREPARING);
        assertThat(histories).isEmpty();
        assertThat(restorations).containsExactly("500:" + RESERVATION + ":{9001=1}");
    }

    @Test
    @DisplayName("정리 뒤 상품 준비중인 주문의 복원 실패를 운영자가 다시 시도해도 주문은 그대로 복원만 잇는다")
    void adminRestorationRetryAfterSettlementDoesNotTouchOrder() {
        givenOrder(OrderStatus.PREPARING);
        Claim failed = claims.put(seedPartial(500L, Map.of(ITEM_A, 1), 37_000).markRefunded(NOW).failRestoration("PRODUCT_028"), 2L);

        Claim reopened = recovery.prepareRetry(failed.id(), 2L);
        Claim progressed = pipeline.progress(reopened);

        assertThat(reopened.status()).isEqualTo(Claim.Status.REFUNDED);
        assertThat(progressed.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.PREPARING);
        assertThat(histories).isEmpty();
    }

    // ---- 운영자 수습 — 수동 환불 확정 · 복원 생략 종결의 결제 완료 복귀 ----

    @Test
    @DisplayName("수동 환불 확정 — 부분 취소의 환불 실패를 확정하면 주문이 결제 완료로 돌아가고 ADMIN 이력(사유)을 남긴다")
    void adminRefundConfirmationReturnsPartialOrderToPaid() {
        givenOrder(OrderStatus.CANCEL_REQUESTED);
        Claim failed = claims.put(seedPartial(500L, Map.of(ITEM_A, 1), 37_000).failRefund("PAYMENT_019"), 1L);

        Claim confirmed = recovery.confirmRefund(failed.id(), 1L, REASON, ADMIN);

        assertThat(confirmed.status()).isEqualTo(Claim.Status.REFUNDED);
        assertThat(confirmed.decision()).isEqualTo(new Claim.Decision(REASON, ADMIN, NOW, "PAYMENT_019"));
        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.PAID);
        assertThat(histories).singleElement().satisfies(history -> {
            assertThat(history.fromStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
            assertThat(history.toStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(history.changedByType()).isEqualTo(StatusChangedBy.ADMIN);
            assertThat(history.changedById()).isEqualTo(ADMIN);
            assertThat(history.reason()).isEqualTo(REASON);
        });
        assertThat(events).containsSubsequence("lock order", "lock claim", "list claims", "update claim REFUNDED", "save order PAID");
        assertThat(paymentCalls).isEmpty();
    }

    @Test
    @DisplayName("수동 환불 확정 — 주문 잠금 뒤 잠가 다시 읽은 클레임의 버전이 화면 버전과 다르거나 클레임 잠금에서 지면 409 ORDER_037, 아무것도 저장하지 않는다")
    void adminRefundConfirmationRechecksVersionUnderLock() {
        givenOrder(OrderStatus.CANCEL_REQUESTED);
        Claim failed = claims.put(seedPartial(500L, Map.of(ITEM_A, 1), 37_000).failRefund("PAYMENT_019"), 1L);
        // 첫 읽기(버전 1)와 잠금 사이에 다른 쪽이 커밋했다
        claims.onLockClaim = () -> claims.put(claims.get(failed.id()), 2L);

        assertCode(() -> recovery.confirmRefund(failed.id(), 1L, REASON, ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        assertThat(events).containsSubsequence("lock order", "lock claim");
        assertThat(claims.get(failed.id()).status()).isEqualTo(Claim.Status.REFUND_FAILED);
        assertThat(claims.get(failed.id()).decision()).isNull();
        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(histories).isEmpty();
        assertThat(events).doesNotContain("update claim REFUNDED");

        claims.onLockClaim = () -> {
            throw new jakarta.persistence.PessimisticLockException("claim row is locked");
        };
        assertCode(() -> recovery.confirmRefund(failed.id(), 2L, REASON, ADMIN), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        assertThat(claims.get(failed.id()).status()).isEqualTo(Claim.Status.REFUND_FAILED);
    }

    @Test
    @DisplayName("수동 환불 확정 — 부분 취소 뒤 남은 수량 클레임을 확정하면 취소 완료다")
    void adminRefundConfirmationOfRemainderCancelsOrder() {
        givenOrder(OrderStatus.CANCEL_REQUESTED);
        completed(seedPartial(400L, Map.of(ITEM_A, 1), 37_000));
        Claim failed = claims.put(seedPartial(500L, Map.of(ITEM_B, 2), 23_000).failRefund("PAYMENT_019"), 1L);

        recovery.confirmRefund(failed.id(), 1L, REASON, ADMIN);

        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(histories).singleElement().extracting(OrderStatusHistory::changedByType).isEqualTo(StatusChangedBy.ADMIN);
    }

    @Test
    @DisplayName("수동 환불 확정 — 정리 판정이 불가능하면(데이터 이상) 저장하지 않고 409 ORDER_041 이다")
    void adminRefundConfirmationRefusesUnsettleableOrder() {
        givenOrder(OrderStatus.CANCEL_REQUESTED);
        completed(seedPartial(400L, Map.of(ITEM_A, 1), 37_000));
        Claim failed = claims.put(seedPartial(500L, Map.of(ITEM_A, 1), 37_000).failRefund("PAYMENT_019"), 1L);

        assertCode(() -> recovery.confirmRefund(failed.id(), 1L, REASON, ADMIN), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        assertThat(claims.get(failed.id())).isEqualTo(failed);
        assertThat(orderRows.get(ORDER).status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(histories).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "PREPARING", "SHIPPING", "CANCEL_REQUESTED"})
    @DisplayName("복원 생략 종결 — 일부 수량 취소는 정리 뒤 주문이 결제 완료 · 출고 · 다음 취소로 넘어갔어도 종결한다(주문은 그대로)")
    void adminCompletionOfPartialIgnoresOrderStatus(OrderStatus status) {
        givenOrder(status);
        Claim failed = claims.put(seedPartial(500L, Map.of(ITEM_A, 1), 37_000).markRefunded(NOW).failRestoration("PRODUCT_028"), 2L);

        Claim completed = recovery.complete(failed.id(), 2L, "재고 조정 화면에서 수량을 맞춤", ADMIN);

        assertThat(completed.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(orderRows.get(ORDER).status()).isEqualTo(status);
        assertThat(histories).isEmpty();
    }

    @Test
    @DisplayName("복원 생략 종결 — 주문 전 수량 취소는 여전히 주문이 취소 완료여야 한다")
    void adminCompletionOfWholeOrderStillRequiresCanceled() {
        givenOrder(OrderStatus.PAID);
        Claim failed = claims.put(seedPartial(500L, Map.of(ITEM_A, 1, ITEM_B, 2), 60_000).markRefunded(NOW).failRestoration("PRODUCT_028"), 2L);

        assertCode(() -> recovery.complete(failed.id(), 2L, "재고 조정 화면에서 수량을 맞춤", ADMIN), OrderErrorCode.CLAIM_DECISION_NOT_ALLOWED);
        orderRows.put(ORDER, with(orderRows.get(ORDER), OrderStatus.CANCELED, 3L));
        assertThatCode(() -> recovery.complete(failed.id(), 2L, "재고 조정 화면에서 수량을 맞춤", ADMIN)).doesNotThrowAnyException();
    }

    // ---- 도우미 ----

    private void givenOrder(OrderStatus status) {
        orderRows.put(ORDER, order(status));
    }

    private static Order order(OrderStatus status) {
        Order placed = Order.place(ORDER, MEMBER, domesticQuote(quoteLine(9001, 40_000, 1), quoteLine(9002, 10_000, 2)), new AtomicLong(100)::incrementAndGet,
            address("KR"), RESERVATION, NOW);
        return new Order(ORDER, MEMBER, status, placed.quote(), placed.itemIds(), placed.shippingAddress(), RESERVATION, placed.paymentDueAt(), null, null,
            NOW, 3L);
    }

    private static Order with(Order order, OrderStatus status, long version) {
        return new Order(order.id(), order.memberId(), status, order.quote(), order.itemIds(), order.shippingAddress(), order.stockReservationId(),
            order.paymentDueAt(), order.shippedAt(), order.deliveredAt(), order.createdAt(), version);
    }

    private static OrderPayment paidRecord() {
        return OrderPayment.paid(5L, order(OrderStatus.PAID), PAYMENT, krw(60_000), NOW, UUID.nameUUIDFromBytes("event".getBytes(StandardCharsets.UTF_8)));
    }

    /** 저장된 환불 대기 취소 클레임(버전 0) — 고객 멱등 키(부분 취소) 자리다. 주문 전 수량이면 주문 전 수량 취소다. */
    private Claim seedPartial(long claimId, Map<Long, Integer> quantities, long refund) {
        Claim requested = Claim.requestCancellation(claimId, order(OrderStatus.PAID), UUID.nameUUIDFromBytes(("partial:" + claimId).getBytes(StandardCharsets.UTF_8)),
            quantities, PAYMENT, krw(refund), OrderCancelReason.OTHER, new AtomicLong(claimId * 10)::incrementAndGet, NOW, LEASE);
        return claims.put(requested, 0L);
    }

    /** 환불 · 복원이 끝난(정산된) 취소로 바꿔 저장한다. */
    private Claim completed(Claim stored) {
        return claims.put(stored.markRefunded(NOW).complete(), stored.version() + 2);
    }

    private static Map<Long, Integer> quantities(Claim claim) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (ClaimItem item : claim.items()) {
            quantities.put(item.orderItemId(), item.quantity());
        }
        return quantities;
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, OrderErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    /** 클레임 저장소 가짜 — 버전 대조(낡은 쓰기는 낙관적 락 예외)와 호출 순서 기록만 한다. 수량 합 질의는 이 시험에서 쓰지 않는다. */
    private static final class InMemoryClaims implements ClaimRepositoryPort {
        private final Map<Long, Claim> rows = new LinkedHashMap<>();
        private final List<String> events;
        /** 다음 쓰기 전에 다른 워커가 먼저 커밋한 것처럼 그 행의 버전만 올릴 횟수 — 이 쓰기는 낡은 버전으로 진다. */
        private int concurrentWrites;
        private java.util.function.Consumer<List<Claim>> onList = rows -> { };
        /** 클레임 NOWAIT 잠금 직전에 끼워 넣을 동작 — 첫 읽기와 잠금 사이의 다른 커밋 · 잠금 경합을 흉내 낸다. */
        private Runnable onLockClaim = () -> { };

        private InMemoryClaims(List<String> events) {
            this.events = events;
        }

        Claim put(Claim claim, long version) {
            Claim stored = new Claim(claim.id(), claim.orderId(), claim.requestKey(), claim.type(), claim.status(), claim.expectedRefund(), claim.paymentId(),
                claim.attemptCount(), claim.nextAttemptAt(), claim.failureCode(), claim.decision(), claim.approval(), claim.items(),
                claim.requestedAt() == null ? NOW : claim.requestedAt(), version);
            rows.put(claim.id(), stored);
            return stored;
        }

        Claim get(long claimId) {
            return rows.get(claimId);
        }

        @Override
        public Optional<Claim> findByOrderIdAndRequestKey(long orderId, UUID requestKey) {
            events.add("find by key");
            return rows.values().stream().filter(claim -> claim.orderId() == orderId && claim.requestKey().equals(requestKey)).findFirst();
        }

        @Override
        public Optional<Claim> findById(long claimId) {
            events.add("find claim " + claimId);
            return Optional.ofNullable(rows.get(claimId));
        }

        @Override
        public List<Claim> findAllByOrderId(long orderId) {
            events.add("list claims");
            List<Claim> listed = rows.values().stream().filter(claim -> claim.orderId() == orderId).sorted(Comparator.comparingLong(Claim::id).reversed()).toList();
            onList.accept(listed);
            return listed;
        }

        @Override
        public Optional<Claim> findByIdForUpdate(long claimId) {
            events.add("lock claim");
            onLockClaim.run();
            return Optional.ofNullable(rows.get(claimId));
        }

        @Override
        public Map<Long, Long> sumRequestedReturnQuantities(long orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<Long, Long> sumCanceledQuantities(long orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<Long, Map<Long, Long>> sumCanceledQuantitiesByOrderIds(Collection<Long> orderIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Claim insert(Claim claim) {
            if (!claim.isNew() || rows.values().stream().anyMatch(row -> row.orderId() == claim.orderId() && row.requestKey().equals(claim.requestKey()))) {
                throw new IllegalStateException("duplicate claim");
            }
            events.add("insert claim");
            return put(claim, 0L);
        }

        @Override
        public Claim update(Claim claim) {
            Claim current = rows.get(claim.id());
            if (concurrentWrites > 0 && current != null) {
                concurrentWrites--;
                current = put(current, current.version() + 1);
            }
            if (current == null || !Objects.equals(current.version(), claim.version())) {
                throw new ObjectOptimisticLockingFailureException("ClaimEntity", claim.id());
            }
            events.add("update claim " + claim.status());
            return put(claim, claim.version() + 1);
        }

        @Override
        public List<Claim> findCancellationRetryTargets(Instant now, int limit) {
            throw new UnsupportedOperationException();
        }
    }
}
