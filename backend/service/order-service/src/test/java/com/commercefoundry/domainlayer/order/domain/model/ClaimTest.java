package com.commercefoundry.domainlayer.order.domain.model;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ClaimTest {

    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00.123456789Z");
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration LEASE = Duration.ofMinutes(2);

    private static Order paidOrder() {
        AtomicLong ids = new AtomicLong(10);
        Order placed = Order.place(1L, 7L, domesticQuote(quoteLine(9001, 10_000, 2), quoteLine(9002, 5_000, 1)), ids::incrementAndGet, address("KR"), 99L,
            NOW);
        return new Order(placed.id(), placed.memberId(), OrderStatus.PAID, placed.quote(), placed.itemIds(), placed.shippingAddress(),
            placed.stockReservationId(), placed.paymentDueAt(), null, null, Instant.EPOCH, 2L);
    }

    private static Claim requested() {
        AtomicLong ids = new AtomicLong(500);
        Order order = paidOrder();
        return stored(Claim.requestCancellation(400L, order, 77L, CancellationRefundPolicy.fullRefund(order), OrderCancelReason.ORDER_MISTAKE,
            ids::incrementAndGet, NOW, LEASE));
    }

    private static Claim stored(Claim claim) {
        return new Claim(claim.id(), claim.orderId(), claim.requestKey(), claim.type(), claim.status(), claim.expectedRefund(), claim.paymentId(),
            claim.attemptCount(), claim.nextAttemptAt(), claim.failureCode(), claim.items(), NOW, 0L);
    }

    @Test
    @DisplayName("전체 취소 클레임은 주문 전 항목 · 전 수량, 결정적 키, 사유 이름 매핑, 환불액 · 결제 아이디, 접수 시각(마이크로초)을 싣는다")
    void requestCancellation() {
        Order order = paidOrder();
        AtomicLong ids = new AtomicLong(500);

        Claim claim = Claim.requestCancellation(400L, order, 77L, krw(28_000), OrderCancelReason.PAYMENT_ISSUE, ids::incrementAndGet, NOW, LEASE);

        assertThat(claim.type()).isEqualTo(Claim.Type.CANCELLATION);
        assertThat(claim.status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(claim.requestKey()).isEqualTo(Claim.cancellationRequestKey(order.id())).isEqualTo(Claim.cancellationRequestKey(1L));
        assertThat(claim.requestKey()).isNotEqualTo(Claim.cancellationRequestKey(2L));
        assertThat(claim.requestKey()).isEqualTo(UUID.nameUUIDFromBytes("cancellation:1".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(claim.items()).extracting(ClaimItem::orderItemId, ClaimItem::quantity, ClaimItem::reason).containsExactly(
            org.assertj.core.groups.Tuple.tuple(order.itemIds().get(0), 2, ClaimItem.Reason.PAYMENT_ISSUE),
            org.assertj.core.groups.Tuple.tuple(order.itemIds().get(1), 1, ClaimItem.Reason.PAYMENT_ISSUE));
        assertThat(claim.expectedRefund()).isEqualTo(krw(28_000));
        assertThat(claim.paymentId()).isEqualTo(77L);
        assertThat(claim.attemptCount()).isZero();
        assertThat(claim.nextAttemptAt()).isEqualTo(Instant.parse("2026-11-06T00:02:00.123456Z"));
        assertThat(claim.failureCode()).isNull();
        assertThat(claim.isNew()).isTrue();
    }

    @Test
    @DisplayName("취소 사유는 모두 클레임 사유로 이름 그대로 옮겨지고, 반품 접수 사유는 반품 3종만이다")
    void reasonMapping() {
        for (OrderCancelReason reason : OrderCancelReason.values()) {
            assertThat(ClaimItem.Reason.of(reason).name()).isEqualTo(reason.name());
        }
        assertThat(List.of(ClaimItem.Reason.values())).filteredOn(ClaimItem.Reason::isReturnReason)
            .containsExactly(ClaimItem.Reason.CHANGE_OF_MIND, ClaimItem.Reason.DEFECTIVE, ClaimItem.Reason.WRONG_ITEM);
    }

    @Test
    @DisplayName("취소 클레임은 결제 아이디 · 0보다 큰 주문 통화 환불액(총액 이하)이 있어야 하고, 반품은 결제 아이디를 두지 않는다")
    void cancellationInvariants() {
        Order order = paidOrder();
        AtomicLong ids = new AtomicLong(500);
        List<ClaimItem> items = List.of(new ClaimItem(1, 11, 1, ClaimItem.Reason.OTHER));

        assertThatThrownBy(() -> Claim.requestCancellation(400L, order, 77L, krw(28_001), OrderCancelReason.OTHER, ids::incrementAndGet, NOW, LEASE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Claim.requestCancellation(400L, order, 77L, Money.of(100, CurrencyCode.USD), OrderCancelReason.OTHER, ids::incrementAndGet,
            NOW, LEASE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Claim.requestCancellation(400L, order, 77L, krw(0), OrderCancelReason.OTHER, ids::incrementAndGet, NOW, LEASE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Claim.requestCancellation(400L, order, 0L, krw(100), OrderCancelReason.OTHER, ids::incrementAndGet, NOW, LEASE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim(1, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.REQUESTED, null, 77L, 0, null, null, items, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim(1, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, 77L, 0, null, null, items, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim(1, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.REQUESTED, krw(1), 77L, -1, null, null, items, null,
            null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim(1, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.REFUND_FAILED, krw(1), 77L, 0, null, "X".repeat(41),
            items, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("성공 경로 — REQUESTED → REFUNDED(횟수 0, 다음 시각) → COMPLETED(다음 시각 없음). 같은 전이를 다시 하면 그대로다")
    void successLifecycle() {
        Claim requested = requested().retryLater(NOW, MINUTE, HOUR);

        Claim refunded = requested.markRefunded(NOW);
        Claim completed = refunded.complete();

        assertThat(refunded.status()).isEqualTo(Claim.Status.REFUNDED);
        assertThat(refunded.attemptCount()).as("복원 단계는 0 부터 센다").isZero();
        assertThat(refunded.nextAttemptAt()).isEqualTo(Instant.parse("2026-11-06T00:00:00.123456Z"));
        assertThat(refunded.markRefunded(NOW.plusSeconds(60))).isSameAs(refunded);
        assertThat(completed.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(completed.nextAttemptAt()).isNull();
        assertThat(completed.complete()).isSameAs(completed);
        assertThat(completed).usingRecursiveComparison().comparingOnlyFields("id", "orderId", "requestKey", "expectedRefund", "paymentId", "items", "version")
            .isEqualTo(requested);
    }

    @Test
    @DisplayName("종결 실패 — REQUESTED 만 REFUND_FAILED, REFUNDED 만 RESTORATION_FAILED 이고 실패 코드를 남기며 다음 시각을 지운다")
    void failures() {
        Claim requested = requested();
        Claim refunded = requested.markRefunded(NOW);

        Claim refundFailed = requested.failRefund("PAYMENT_016");
        Claim restorationFailed = refunded.failRestoration("PRODUCT_028");

        assertThat(refundFailed.status()).isEqualTo(Claim.Status.REFUND_FAILED);
        assertThat(refundFailed.failureCode()).isEqualTo("PAYMENT_016");
        assertThat(refundFailed.nextAttemptAt()).isNull();
        assertThat(restorationFailed.status()).isEqualTo(Claim.Status.RESTORATION_FAILED);
        assertThat(restorationFailed.failureCode()).isEqualTo("PRODUCT_028");
        assertThat(restorationFailed.nextAttemptAt()).isNull();
        assertThatThrownBy(() -> refunded.failRefund("PAYMENT_016")).as("환불 확정 뒤에는 환불 실패로 못 간다").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requested.failRestoration("PRODUCT_028")).as("환불 전에는 복원 실패가 없다").isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"REFUND_FAILED", "RESTORATION_FAILED", "COMPLETED"})
    @DisplayName("종결 상태에서는 환불 확정 · 재시도 · 실패 전이를 하지 않는다(완료의 완료는 그대로)")
    void terminalStatusesRejectTransitions(Claim.Status terminal) {
        Claim base = requested();
        Claim claim = new Claim(base.id(), base.orderId(), base.requestKey(), base.type(), terminal, base.expectedRefund(), base.paymentId(), 3, null,
            terminal == Claim.Status.COMPLETED ? null : "PAYMENT_016", base.items(), NOW, 4L);

        assertThatThrownBy(() -> claim.markRefunded(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> claim.retryLater(NOW, MINUTE, HOUR)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> claim.failRefund("X_001")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> claim.failRestoration("X_001")).isInstanceOf(IllegalStateException.class);
        if (terminal != Claim.Status.COMPLETED) {
            assertThatThrownBy(claim::complete).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("미확정 재시도는 상태를 두고 횟수 +1, 간격은 1분부터 두 배 · 상한 1시간이다(큰 횟수도 넘치지 않는다)")
    void retryBackoff() {
        Claim claim = requested();
        List<Long> minutes = new java.util.ArrayList<>();
        for (int attempt = 0; attempt < 8; attempt++) {
            claim = claim.retryLater(NOW, MINUTE, HOUR);
            minutes.add(Duration.between(Instant.parse("2026-11-06T00:00:00.123456Z"), claim.nextAttemptAt()).toMinutes());
        }

        assertThat(claim.status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(claim.attemptCount()).isEqualTo(8);
        assertThat(minutes).containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L);
        Claim many = new Claim(claim.id(), claim.orderId(), claim.requestKey(), claim.type(), Claim.Status.REFUNDED, claim.expectedRefund(), claim.paymentId(),
            200, NOW, null, claim.items(), NOW, 9L);
        assertThat(many.retryLater(NOW, MINUTE, HOUR).nextAttemptAt()).isEqualTo(Instant.parse("2026-11-06T01:00:00.123456Z"));
        Claim finalClaim = claim;
        assertThatThrownBy(() -> finalClaim.retryLater(NOW, Duration.ZERO, HOUR)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> finalClaim.retryLater(NOW, HOUR, MINUTE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("저장 전 클레임 · 반품 클레임은 취소 수명 전이를 하지 않는다")
    void onlyStoredCancellationClaimsTransition() {
        Order order = paidOrder();
        AtomicLong ids = new AtomicLong(500);
        Claim unsaved = Claim.requestCancellation(400L, order, 77L, krw(100), OrderCancelReason.OTHER, ids::incrementAndGet, NOW, LEASE);
        Claim returnClaim = new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 0L);

        assertThatThrownBy(() -> unsaved.markRefunded(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> unsaved.retryLater(NOW, MINUTE, HOUR)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> returnClaim.markRefunded(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> returnClaim.retryLater(NOW, MINUTE, HOUR)).isInstanceOf(IllegalStateException.class);
        assertThat(returnClaim.paymentId()).isNull();
        assertThat(returnClaim.attemptCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Claim.Status.class)
    @DisplayName("취소 클레임의 다음 시도 시각은 대기 상태(REQUESTED · REFUNDED)에만, 실패 코드는 종결 실패에만 있다 — DB check 와 같다")
    void cancellationAttemptColumnsFollowStatus(Claim.Status status) {
        List<ClaimItem> items = List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.OTHER));
        Instant next = status.isAwaitingAttempt() ? NOW : null;
        String code = status.isFailed() ? "PAYMENT_016" : null;

        assertThat(cancellation(status, next, code, items).status()).isEqualTo(status);
        assertThatThrownBy(() -> cancellation(status, next == null ? NOW : null, code, items)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cancellation(status, next, code == null ? "PAYMENT_016" : null, items)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("항목 사유는 유형과 짝이 맞아야 한다 — 반품은 반품 사유 3종, 취소는 취소 사유(OrderCancelReason 과 같은 이름)만")
    void itemReasonsMatchType() {
        for (ClaimItem.Reason reason : ClaimItem.Reason.values()) {
            List<ClaimItem> items = List.of(new ClaimItem(3, 11, 1, reason));
            if (reason.isReturnReason()) {
                new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, items, NOW, 0L);
            } else {
                assertThatThrownBy(() -> new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, items, NOW, 0L))
                    .as(reason.name()).isInstanceOf(IllegalArgumentException.class);
            }
            if (reason.isCancellationReason()) {
                cancellation(Claim.Status.REQUESTED, NOW, null, items);
            } else {
                assertThatThrownBy(() -> cancellation(Claim.Status.REQUESTED, NOW, null, items)).as(reason.name()).isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThat(List.of(ClaimItem.Reason.values())).filteredOn(ClaimItem.Reason::isCancellationReason)
            .containsExactly(ClaimItem.Reason.CHANGE_OF_MIND, ClaimItem.Reason.ORDER_MISTAKE, ClaimItem.Reason.PAYMENT_ISSUE, ClaimItem.Reason.OTHER);
    }

    @Test
    @DisplayName("접수의 다음 시도 시각은 now + lease 이고, lease 는 양수여야 한다")
    void leaseDefersFirstScheduledAttempt() {
        Order order = paidOrder();
        AtomicLong ids = new AtomicLong(500);

        assertThat(Claim.requestCancellation(400L, order, 77L, krw(100), OrderCancelReason.OTHER, ids::incrementAndGet, NOW, Duration.ofSeconds(30))
            .nextAttemptAt()).isEqualTo(Instant.parse("2026-11-06T00:00:30.123456Z"));
        assertThatThrownBy(() -> Claim.requestCancellation(400L, order, 77L, krw(100), OrderCancelReason.OTHER, ids::incrementAndGet, NOW, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("미확정으로 닫힌 환불 실패(REFUND_UNCONFIRMED)만 늦게 온 환불 성공을 받아 REFUNDED 로 간다 — 종결 거절 코드는 그대로 거절한다")
    void lateRefundSuccessOnlyForUnconfirmed() {
        Claim base = requested();
        Claim unconfirmed = base.retryLater(NOW, MINUTE, HOUR).failRefund(Claim.REFUND_UNCONFIRMED);
        Claim rejected = base.failRefund("PAYMENT_016");

        Claim refunded = unconfirmed.markRefunded(NOW.plus(LEASE));

        assertThat(refunded.status()).isEqualTo(Claim.Status.REFUNDED);
        assertThat(refunded.failureCode()).isNull();
        assertThat(refunded.attemptCount()).as("복원 단계 횟수는 0 부터").isZero();
        assertThat(refunded.nextAttemptAt()).isEqualTo(Instant.parse("2026-11-06T00:02:00.123456Z"));
        assertThatThrownBy(() -> rejected.markRefunded(NOW)).isInstanceOf(IllegalStateException.class);
        Claim restorationUnconfirmed = base.markRefunded(NOW).failRestoration(Claim.RESTORATION_UNCONFIRMED);
        assertThatThrownBy(() -> restorationUnconfirmed.markRefunded(NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(Claim.REFUND_UNCONFIRMED.length()).isLessThanOrEqualTo(Claim.FAILURE_CODE_MAX_LENGTH);
        assertThat(Claim.RESTORATION_UNCONFIRMED.length()).isLessThanOrEqualTo(Claim.FAILURE_CODE_MAX_LENGTH);
    }

    @Test
    @DisplayName("선점은 대기 상태(REQUESTED · REFUNDED)의 다음 시각만 미루고 상태 · 횟수는 두며, 종결 · 저장 전 클레임은 선점하지 않는다")
    void holdUntilDefersOnlyAwaitingClaims() {
        Claim requested = requested().retryLater(NOW, MINUTE, HOUR);
        Instant until = NOW.plus(LEASE);

        Claim held = requested.holdUntil(until);
        Claim heldRefunded = requested.markRefunded(NOW).holdUntil(until);

        assertThat(held.status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(held.attemptCount()).isEqualTo(1);
        assertThat(held.nextAttemptAt()).isEqualTo(Instant.parse("2026-11-06T00:02:00.123456Z"));
        assertThat(heldRefunded.status()).isEqualTo(Claim.Status.REFUNDED);
        assertThatThrownBy(() -> requested.failRefund("PAYMENT_016").holdUntil(until)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requested.markRefunded(NOW).complete().holdUntil(until)).isInstanceOf(IllegalStateException.class);
        Order order = paidOrder();
        Claim unsaved = Claim.requestCancellation(400L, order, 77L, krw(100), OrderCancelReason.OTHER, new AtomicLong(500)::incrementAndGet, NOW, LEASE);
        assertThatThrownBy(() -> unsaved.holdUntil(until)).isInstanceOf(IllegalStateException.class);
    }

    private static final Claim.Decision DECISION = new Claim.Decision("PG 거절 확인", 9L, NOW);

    @Test
    @DisplayName("운영자 재시도 — 환불 실패는 환불 대기로, 복원 실패는 복원 대기로 다시 열고 횟수 0 · 실패 코드 없음 · 다음 시각 lease, 결정 칸은 둔다")
    void reopenReturnsFailedClaimToItsAwaitingStep() {
        Claim requested = requested().retryLater(NOW, MINUTE, HOUR);
        Instant until = NOW.plus(LEASE);

        Claim refundReopened = requested.failRefund("PAYMENT_016").reopen(until);
        Claim restorationFailed = requested.markRefunded(NOW).retryLater(NOW, MINUTE, HOUR).failRestoration(Claim.RESTORATION_UNCONFIRMED);
        Claim restorationReopened = restorationFailed.reopen(until);
        Claim confirmedThenFailed = requested.failRefund("PAYMENT_016").confirmRefundByAdmin(DECISION, NOW).failRestoration("PRODUCT_028");

        assertThat(refundReopened.status()).isEqualTo(Claim.Status.REQUESTED);
        assertThat(refundReopened.attemptCount()).isZero();
        assertThat(refundReopened.failureCode()).isNull();
        assertThat(refundReopened.nextAttemptAt()).isEqualTo(Instant.parse("2026-11-06T00:02:00.123456Z"));
        assertThat(restorationReopened.status()).isEqualTo(Claim.Status.REFUNDED);
        assertThat(restorationReopened.attemptCount()).isZero();
        assertThat(restorationReopened.failureCode()).isNull();
        assertThat(confirmedThenFailed.reopen(until).decision()).as("앞선 운영자 결정은 지우지 않는다").isEqualTo(DECISION);
        for (Claim notFailed : List.of(requested, requested.markRefunded(NOW), requested.markRefunded(NOW).complete(), requested.failRefund("PAYMENT_016").reject(DECISION))) {
            assertThatThrownBy(() -> notFailed.reopen(until)).as(notFailed.status().name()).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("수동 환불 확정 — 환불 실패(코드 무관)만 REFUNDED(횟수 0, 다음 시각, 결정 칸)로 가고, 그 밖의 상태는 거절한다")
    void confirmRefundByAdminOnlyFromRefundFailed() {
        Claim requested = requested().retryLater(NOW, MINUTE, HOUR);

        for (String code : List.of("PAYMENT_016", Claim.REFUND_UNCONFIRMED)) {
            Claim confirmed = requested.failRefund(code).confirmRefundByAdmin(DECISION, NOW.plus(LEASE));
            assertThat(confirmed.status()).isEqualTo(Claim.Status.REFUNDED);
            assertThat(confirmed.attemptCount()).isZero();
            assertThat(confirmed.failureCode()).isNull();
            assertThat(confirmed.nextAttemptAt()).isEqualTo(Instant.parse("2026-11-06T00:02:00.123456Z"));
            assertThat(confirmed.decision()).isEqualTo(DECISION);
        }
        for (Claim other : List.of(requested, requested.markRefunded(NOW), requested.markRefunded(NOW).failRestoration("PRODUCT_028"))) {
            assertThatThrownBy(() -> other.confirmRefundByAdmin(DECISION, NOW)).as(other.status().name()).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("거절 — 종결 거절 코드의 환불 실패만 REJECTED(다음 시각 · 실패 코드 없음, 결정 칸)로 가고, 미확정(REFUND_UNCONFIRMED)은 거절하지 않는다")
    void rejectOnlyTerminalRefundFailure() {
        Claim requested = requested();

        Claim rejected = requested.failRefund("PAYMENT_016").reject(DECISION);

        assertThat(rejected.status()).isEqualTo(Claim.Status.REJECTED);
        assertThat(rejected.failureCode()).isNull();
        assertThat(rejected.nextAttemptAt()).isNull();
        assertThat(rejected.decision()).isEqualTo(DECISION);
        Claim unconfirmed = requested.failRefund(Claim.REFUND_UNCONFIRMED);
        assertThat(unconfirmed.isRefundUnconfirmed()).isTrue();
        assertThatThrownBy(() -> unconfirmed.reject(DECISION)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requested.reject(DECISION)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requested.markRefunded(NOW).failRestoration("PRODUCT_028").reject(DECISION)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rejected.reject(DECISION)).as("거절된 클레임은 다시 거절하지 않는다(멱등은 부르는 쪽이 본다)").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rejected.markRefunded(NOW)).as("거절 뒤 늦게 온 성공도 받지 않는다").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rejected.holdUntil(NOW)).as("거절은 스케줄러가 집지 않는다").isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("복원 생략 종결 — 재고 복원 실패만 COMPLETED(실패 코드 없음, 결정 칸)로 간다")
    void completeByAdminOnlyFromRestorationFailed() {
        Claim refunded = requested().markRefunded(NOW);

        Claim completed = refunded.failRestoration("PRODUCT_028").completeByAdmin(DECISION);

        assertThat(completed.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(completed.failureCode()).isNull();
        assertThat(completed.nextAttemptAt()).isNull();
        assertThat(completed.decision()).isEqualTo(DECISION);
        for (Claim other : List.of(requested(), refunded, refunded.complete(), requested().failRefund("PAYMENT_016"))) {
            assertThatThrownBy(() -> other.completeByAdmin(DECISION)).as(other.status().name()).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("결정 칸 불변식 — 거절에는 결정이 있어야 하고, 결정 사유는 1~200자 · 결정자 양수 · 시각 필수(마이크로초로 자른다), 반품은 수습 전이를 하지 않는다")
    void decisionInvariants() {
        List<ClaimItem> items = List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.OTHER));

        assertThatThrownBy(() -> new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.REJECTED, krw(1_000), 77L, 0, null, null, null, items,
            NOW, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.COMPLETED, krw(1_000), 77L, 0, null, null, DECISION, items, NOW, 0L)
            .decision()).isEqualTo(DECISION);
        assertThat(DECISION.decidedAt()).isEqualTo(Instant.parse("2026-11-06T00:00:00.123456Z"));
        assertThatThrownBy(() -> new Claim.Decision(" ", 9L, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim.Decision("x".repeat(201), 9L, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim.Decision("사유", 0L, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim.Decision("사유", 9L, null)).isInstanceOf(NullPointerException.class);
        Claim returnClaim = new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 0L);
        assertThatThrownBy(() -> returnClaim.reject(DECISION)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> returnClaim.reopen(NOW)).isInstanceOf(IllegalStateException.class);
    }

    private static Claim cancellation(Claim.Status status, Instant next, String failureCode, List<ClaimItem> items) {
        return new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, status, krw(1_000), 77L, 0, next, failureCode,
            status == Claim.Status.REJECTED ? DECISION : null, items, NOW, 0L);
    }
}
