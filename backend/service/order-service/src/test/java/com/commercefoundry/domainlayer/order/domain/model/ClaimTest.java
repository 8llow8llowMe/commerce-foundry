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
    @EnumSource(value = Claim.Status.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("취소 클레임의 다음 시도 시각은 대기 상태(REQUESTED · REFUNDED)에만, 실패 코드는 종결 실패에만 있다 — DB check 와 같다")
    void cancellationAttemptColumnsFollowStatus(Claim.Status status) {
        List<ClaimItem> items = List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.OTHER));
        Instant next = Claim.Type.CANCELLATION.isAwaitingAttempt(status) ? NOW : null;
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

    @Test
    void confirmedSuccessOverridesFailureWithoutChangingOrdinaryContract() {
        Claim base = requested();
        for (String code : List.of("PAYMENT_016", "PAYMENT_019", Claim.REFUND_UNCONFIRMED)) {
            Claim failed = base.failRefund(code);
            assertThat(base.hasSameRefundRequest(failed)).isTrue();
            assertThat(failed.markConfirmedRefund(NOW).status()).isEqualTo(Claim.Status.REFUNDED);
        }
        assertThat(base.hasSameRefundRequest(null)).isFalse();
        assertThat(base.markConfirmedRefund(NOW).hasRecordedRefund()).isTrue();
        assertThatThrownBy(() -> base.markRefunded(NOW).markConfirmedRefund(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refundFingerprintIncludesAllImmutableRequestFields() {
        Claim source = requested();
        for (int field = 0; field < 7; field++) {
            Claim.Type type = field == 3 ? Claim.Type.RETURN : source.type();
            Money amount = field == 5 ? krw(source.expectedRefund().amount() + 1)
                : field == 6 ? Money.of(source.expectedRefund().amount(), CurrencyCode.USD) : source.expectedRefund();
            List<ClaimItem> items = field == 3 ? source.items().stream()
                .map(item -> new ClaimItem(item.id(), item.orderItemId(), item.quantity(), ClaimItem.Reason.DEFECTIVE)).toList() : source.items();
            Claim changed = new Claim(field == 0 ? source.id() + 1 : source.id(), field == 1 ? source.orderId() + 1 : source.orderId(),
                field == 2 ? UUID.randomUUID() : source.requestKey(), type, source.status(), amount,
                field == 3 ? null : field == 4 ? source.paymentId() + 1 : source.paymentId(), source.attemptCount(),
                field == 3 ? null : source.nextAttemptAt(), source.failureCode(), items, source.requestedAt(), source.version());
            assertThat(source.hasSameRefundRequest(changed)).as("field %s", field).isFalse();
        }
    }

    @Test
    @DisplayName("수동 환불 확정 — 저장된 REFUND_FAILED 만 REFUNDED(횟수 0, 다음 시각 마이크로초, 실패 코드 비움)로 가고 결정에 직전 실패 코드를 남긴다 (#17 A3)")
    void adminRefundConfirmation() {
        for (String code : List.of("PAYMENT_016", Claim.REFUND_UNCONFIRMED)) {
            Claim failed = requested().retryLater(NOW, MINUTE, HOUR).failRefund(code);
            Claim.Decision decision = new Claim.Decision("결제 관리 화면에서 취소 완료 확인", 9, NOW, code);

            Claim refunded = failed.confirmRefundByAdmin(decision, NOW.plus(LEASE));

            assertThat(refunded.status()).isEqualTo(Claim.Status.REFUNDED);
            assertThat(refunded.attemptCount()).isZero();
            assertThat(refunded.nextAttemptAt()).isEqualTo(Instant.parse("2026-11-06T00:02:00.123456Z"));
            assertThat(refunded.failureCode()).isNull();
            assertThat(refunded.decision()).isEqualTo(decision);
            assertThat(refunded.hasSameRefundRequest(failed)).isTrue();
            assertThat(refunded.version()).isEqualTo(failed.version());
        }
    }

    @Test
    @DisplayName("수동 환불 확정은 실패 코드가 다른 결정 · 환불 실패가 아닌 상태 · 저장 전 · 반품 · 결정 없음을 거부한다")
    void adminRefundConfirmationRejections() {
        Claim failed = requested().failRefund("PAYMENT_016");
        assertThatThrownBy(() -> failed.confirmRefundByAdmin(new Claim.Decision("확인", 9, NOW, "PAYMENT_019"), NOW))
            .as("화면이 본 실패와 지금 실패가 다르다").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.confirmRefundByAdmin(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> failed.confirmRefundByAdmin(new Claim.Decision("확인", 9, NOW, "PAYMENT_016"), null)).isInstanceOf(NullPointerException.class);
        Claim requested = requested();
        Claim restorationFailed = requested.markRefunded(NOW).failRestoration("PAYMENT_016");
        for (Claim claim : List.of(requested, requested.markRefunded(NOW), requested.markRefunded(NOW).complete(), restorationFailed)) {
            assertThatThrownBy(() -> claim.confirmRefundByAdmin(new Claim.Decision("확인", 9, NOW, "PAYMENT_016"), NOW))
                .as(claim.status().name()).isInstanceOf(IllegalStateException.class);
        }
        Claim unsaved = new Claim(failed.id(), failed.orderId(), failed.requestKey(), failed.type(), failed.status(), failed.expectedRefund(), failed.paymentId(),
            0, null, "PAYMENT_016", failed.items(), null, null);
        assertThatThrownBy(() -> unsaved.confirmRefundByAdmin(new Claim.Decision("확인", 9, NOW, "PAYMENT_016"), NOW)).isInstanceOf(IllegalStateException.class);
        Claim returned = new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 0L);
        assertThatThrownBy(() -> returned.confirmRefundByAdmin(new Claim.Decision("확인", 9, NOW, "PAYMENT_016"), NOW))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> returned.completeByAdmin(new Claim.Decision("확인", 9, NOW, "PRODUCT_028"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("복원 생략 종결 — 저장된 RESTORATION_FAILED 만 COMPLETED(다음 시각 · 실패 코드 없음)로 가고 결정에 직전 실패 코드를 남긴다 (#17 A3)")
    void adminCompletion() {
        Claim failed = requested().markRefunded(NOW).failRestoration("PRODUCT_028");
        Claim.Decision decision = new Claim.Decision("재고 조정으로 맞춤", 9, NOW, "PRODUCT_028");

        Claim completed = failed.completeByAdmin(decision);

        assertThat(completed.status()).isEqualTo(Claim.Status.COMPLETED);
        assertThat(completed.nextAttemptAt()).isNull();
        assertThat(completed.failureCode()).isNull();
        assertThat(completed.decision()).isEqualTo(decision);
        assertThat(completed.hasRecordedRefund()).isTrue();
        assertThatThrownBy(() -> failed.completeByAdmin(new Claim.Decision("확인", 9, NOW, "PRODUCT_027"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> completed.completeByAdmin(decision)).as("이미 종결").isInstanceOf(IllegalStateException.class);
        Claim refundFailed = requested().failRefund("PRODUCT_028");
        assertThatThrownBy(() -> refundFailed.completeByAdmin(decision)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requested().completeByAdmin(decision)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.completeByAdmin(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("반품 승인 — 저장된 REQUESTED 반품만 APPROVED 로 가고, 환불액 · 차감액 · 통화는 정책 결과 하나에서, 다음 시각은 now + lease(마이크로초)다 (#17 B1)")
    void approveReturn() {
        Claim requested = returnRequested();

        Claim approved = requested.approveReturn(paidOrder(), 77L, new ReturnRefundPolicy.Refund(10_000, 3_000, CurrencyCode.KRW), true, 9, NOW, LEASE);

        assertThat(approved.status()).isEqualTo(Claim.Status.APPROVED);
        assertThat(approved.expectedRefund()).as("정책 금액 − 차감액").isEqualTo(krw(7_000));
        assertThat(approved.paymentId()).isEqualTo(77L);
        assertThat(approved.approval()).isEqualTo(new Claim.ReturnApproval(true, krw(3_000), 9, NOW));
        assertThat(approved.approval().approvedAt()).isEqualTo(Instant.parse("2026-11-06T00:00:00.123456Z"));
        assertThat(approved.attemptCount()).isZero();
        assertThat(approved.nextAttemptAt()).isEqualTo(Instant.parse("2026-11-06T00:02:00.123456Z"));
        assertThat(approved.failureCode()).isNull();
        assertThat(approved.isAwaitingAttempt()).isTrue();
        assertThat(approved.isAwaitingRefund()).isTrue();
        assertThat(approved.hasRecordedRefund()).isFalse();
        assertThat(approved.refundDeadlineBase()).as("반품의 결제 취소 키 기한은 승인 시각부터").isEqualTo(approved.approval().approvedAt());
        assertThat(approved).usingRecursiveComparison().comparingOnlyFields("id", "orderId", "requestKey", "type", "items", "requestedAt", "version")
            .isEqualTo(requested);
    }

    @Test
    @DisplayName("반품 승인은 반품 · 저장된 REQUESTED · 이 반품의 저장된 주문 · 0보다 큰 환불액(정책 − 차감) · 주문 통화 · 결제 총액 이하 · 양수 lease 만 받는다")
    void approveReturnRejections() {
        Claim requested = returnRequested();
        Order order = paidOrder();
        ReturnRefundPolicy.Refund refund = new ReturnRefundPolicy.Refund(100, 0, CurrencyCode.KRW);

        assertThatThrownBy(() -> requested.approveReturn(order, 77L, new ReturnRefundPolicy.Refund(100, 100, CurrencyCode.KRW), true, 9, NOW, LEASE))
            .as("정책 금액 = 차감액이면 0 원").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requested.approveReturn(order, 77L, new ReturnRefundPolicy.Refund(-1_000, 0, CurrencyCode.KRW), true, 9, NOW, LEASE))
            .as("음수 정책 금액").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requested.approveReturn(order, 77L, new ReturnRefundPolicy.Refund(100, 0, CurrencyCode.USD), true, 9, NOW, LEASE))
            .as("주문과 다른 통화").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requested.approveReturn(order, 77L, new ReturnRefundPolicy.Refund(28_001, 1, CurrencyCode.KRW), true, 9, NOW, LEASE))
            .as("결제 총액 28,000 을 넘는 정책 금액").isInstanceOf(IllegalArgumentException.class);
        assertThat(requested.approveReturn(order, 77L, new ReturnRefundPolicy.Refund(28_000, 0, CurrencyCode.KRW), true, 9, NOW, LEASE).expectedRefund())
            .as("결제 총액까지는 받는다").isEqualTo(krw(28_000));
        assertThatThrownBy(() -> requested.approveReturn(order, 0L, refund, true, 9, NOW, LEASE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requested.approveReturn(order, 77L, refund, true, 0, NOW, LEASE)).as("승인자 0").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requested.approveReturn(order, 77L, refund, true, 9, NOW, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        Order other = new Order(2L, order.memberId(), order.status(), order.quote(), order.itemIds(), order.shippingAddress(), order.stockReservationId(),
            order.paymentDueAt(), null, null, Instant.EPOCH, 2L);
        assertThatThrownBy(() -> requested.approveReturn(other, 77L, refund, true, 9, NOW, LEASE)).as("다른 주문").isInstanceOf(IllegalArgumentException.class);
        Order missingItem = new Order(1L, order.memberId(), order.status(), order.quote(), List.of(98L, 99L), order.shippingAddress(), order.stockReservationId(),
            order.paymentDueAt(), null, null, Instant.EPOCH, 2L);
        assertThatThrownBy(() -> requested.approveReturn(missingItem, 77L, refund, true, 9, NOW, LEASE)).as("주문에 없는 항목")
            .isInstanceOf(IllegalArgumentException.class);
        Order unsavedOrder = new Order(1L, order.memberId(), order.status(), order.quote(), order.itemIds(), order.shippingAddress(), order.stockReservationId(),
            order.paymentDueAt(), null, null, null, null);
        assertThatThrownBy(() -> requested.approveReturn(unsavedOrder, 77L, refund, true, 9, NOW, LEASE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requested.approveReturn(null, 77L, refund, true, 9, NOW, LEASE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> requested.approveReturn(order, 77L, null, true, 9, NOW, LEASE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> requested.approveReturn(order, 77L, refund, true, 9, null, LEASE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> requested.approveReturn(order, 77L, refund, true, 9, NOW, null)).isInstanceOf(NullPointerException.class);
        Claim approved = requested.approveReturn(order, 77L, refund, true, 9, NOW, LEASE);
        assertThatThrownBy(() -> approved.approveReturn(order, 77L, refund, true, 9, NOW, LEASE)).as("이미 승인").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requested.rejectReturn().approveReturn(order, 77L, refund, true, 9, NOW, LEASE)).as("거절 뒤")
            .isInstanceOf(IllegalStateException.class);
        Claim unsaved = new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, requested.items(), null, null);
        assertThatThrownBy(() -> unsaved.approveReturn(order, 77L, refund, true, 9, NOW, LEASE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requested().approveReturn(order, 77L, refund, true, 9, NOW, LEASE)).as("취소 클레임").isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("반품 거절 — 저장된 REQUESTED 반품만 REJECTED(끝 — 다음 시각 · 결제 · 승인 없음)로 간다. 취소 클레임 · 거절 뒤 · 저장 전은 거절하지 않는다 (#17 B1)")
    void rejectReturn() {
        Claim requested = returnRequested();

        Claim rejected = requested.rejectReturn();

        assertThat(rejected.status()).isEqualTo(Claim.Status.REJECTED);
        assertThat(rejected.paymentId()).isNull();
        assertThat(rejected.approval()).isNull();
        assertThat(rejected.nextAttemptAt()).isNull();
        assertThat(rejected.isAwaitingAttempt()).isFalse();
        assertThat(rejected.isAwaitingRefund()).isFalse();
        assertThat(rejected.hasRecordedRefund()).isFalse();
        assertThat(rejected.version()).isEqualTo(requested.version());
        assertThatThrownBy(rejected::rejectReturn).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requested().rejectReturn()).isInstanceOf(IllegalStateException.class);
        Claim unsaved = new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, requested.items(), null, null);
        assertThatThrownBy(unsaved::rejectReturn).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("대기 판정은 유형을 본다 — 환불 대기는 취소 REQUESTED · 반품 APPROVED, 복원 대기는 둘 다 REFUNDED 다")
    void awaitingAttemptDependsOnType() {
        for (Claim.Status status : Claim.Status.values()) {
            assertThat(Claim.Type.CANCELLATION.isAwaitingAttempt(status)).as("취소 %s", status)
                .isEqualTo(status == Claim.Status.REQUESTED || status == Claim.Status.REFUNDED);
            assertThat(Claim.Type.RETURN.isAwaitingAttempt(status)).as("반품 %s", status)
                .isEqualTo(status == Claim.Status.APPROVED || status == Claim.Status.REFUNDED);
        }
        assertThat(returnRequested().isAwaitingAttempt()).as("승인 전 반품은 시도 대상이 아니다").isFalse();
        assertThat(returnRequested().isAwaitingRefund()).isFalse();
        assertThat(requested().isAwaitingAttempt()).isTrue();
        assertThat(requested().isAwaitingRefund()).isTrue();
        assertThat(requested().markRefunded(NOW).isAwaitingRefund()).isFalse();
        assertThat(requested().refundDeadlineBase()).as("취소의 기한 기준은 접수 시각").isEqualTo(NOW);
        assertThatThrownBy(() -> returnRequested().refundDeadlineBase()).as("승인 전 반품은 기준이 없다").isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(Claim.Status.class)
    @DisplayName("반품 불변식 — 승인 이후(REQUESTED · REJECTED 밖)는 결제 · 승인 기록 · 0보다 큰 환불액이 있고, 다음 시각 · 실패 코드는 상태를 따른다. DB check 와 같다")
    void returnColumnsFollowStatus(Claim.Status status) {
        boolean approvedOrLater = status != Claim.Status.REQUESTED && status != Claim.Status.REJECTED;
        Instant next = Claim.Type.RETURN.isAwaitingAttempt(status) ? NOW : null;
        String code = status.isFailed() ? "PAYMENT_016" : null;
        Long payment = approvedOrLater ? 77L : null;
        Claim.ReturnApproval approval = approvedOrLater ? new Claim.ReturnApproval(true, krw(500), 9, NOW) : null;
        Money refund = approvedOrLater ? krw(1_000) : null;

        assertThat(returnClaim(status, refund, payment, next, code, approval).status()).isEqualTo(status);
        assertThatThrownBy(() -> returnClaim(status, refund, payment, next == null ? NOW : null, code, approval)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> returnClaim(status, refund, payment, next, code == null ? "PAYMENT_016" : null, approval))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> returnClaim(status, refund, payment == null ? 77L : null, next, code, approval)).isInstanceOf(IllegalArgumentException.class);
        Claim.ReturnApproval flipped = approval == null ? new Claim.ReturnApproval(true, krw(500), 9, NOW) : null;
        assertThatThrownBy(() -> returnClaim(status, refund, payment, next, code, flipped)).isInstanceOf(IllegalArgumentException.class);
        if (approvedOrLater) {
            assertThatThrownBy(() -> returnClaim(status, null, payment, next, code, approval)).as("환불액 없음").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> returnClaim(status, krw(0), payment, next, code, approval)).as("0 원").isInstanceOf(IllegalArgumentException.class);
            Claim.ReturnApproval usd = new Claim.ReturnApproval(true, Money.of(1, CurrencyCode.USD), 9, NOW);
            assertThatThrownBy(() -> returnClaim(status, refund, payment, next, code, usd)).as("차감 통화").isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("취소 클레임은 APPROVED · 승인 기록을 두지 않는다 — 거절 상태 취소는 막지 않는다(A4 몫)")
    void typeSpecificInvariants() {
        List<ClaimItem> items = List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.OTHER));
        assertThatThrownBy(() -> cancellation(Claim.Status.APPROVED, NOW, null, items)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.REQUESTED, krw(1_000), 77L, 0, NOW, null, null,
            new Claim.ReturnApproval(true, krw(0), 9, NOW), items, NOW, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(cancellation(Claim.Status.REJECTED, null, null, items).status()).isEqualTo(Claim.Status.REJECTED);
    }

    @Test
    @DisplayName("운영자 결정은 DB ck_claim_admin_decision 과 같다 — 접수 반품에는 없고, 직전 실패 코드 없는 결정은 거절에만 있다. B2 가 쓴 반품 결정은 읽을 수 있다 (#17 B1)")
    void decisionShapeMatchesDatabase() {
        Claim.Decision withCode = new Claim.Decision("검수 확인", 9, NOW, "PAYMENT_016");
        Claim.Decision rejection = new Claim.Decision("검수 불합격", 9, NOW, null);
        List<ClaimItem> returnItems = returnRequested().items();
        List<ClaimItem> cancelItems = List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.OTHER));

        assertThatThrownBy(() -> new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, null, 0, null, null, withCode,
            returnItems, NOW, 0L)).as("접수 반품의 결정").isInstanceOf(IllegalArgumentException.class);
        Claim rejectedReturn = new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REJECTED, null, null, 0, null, null, rejection,
            returnItems, NOW, 0L);
        assertThat(rejectedReturn.decision()).as("B2 거절 사유(직전 실패 코드 없음)를 읽는다 — B2 → B1 롤백").isEqualTo(rejection);
        assertThat(rejectedReturn.reservesReturnQuantity()).isFalse();
        Claim failedReturn = returnClaim(Claim.Status.REFUND_FAILED, krw(1_000), 77L, null, "PAYMENT_016", new Claim.ReturnApproval(true, krw(0), 9, NOW));
        assertThat(new Claim(failedReturn.id(), failedReturn.orderId(), failedReturn.requestKey(), failedReturn.type(), failedReturn.status(),
            failedReturn.expectedRefund(), failedReturn.paymentId(), 0, null, "PAYMENT_016", withCode, failedReturn.approval(), failedReturn.items(), NOW, 0L)
            .decision()).as("승인 뒤 반품의 수습 결정").isEqualTo(withCode);
        assertThatThrownBy(() -> new Claim(failedReturn.id(), failedReturn.orderId(), failedReturn.requestKey(), failedReturn.type(), failedReturn.status(),
            failedReturn.expectedRefund(), failedReturn.paymentId(), 0, null, "PAYMENT_016", rejection, failedReturn.approval(), failedReturn.items(), NOW, 0L))
            .as("거절이 아닌 반품의 실패 코드 없는 결정").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.REQUESTED, krw(1_000), 77L, 0, NOW, null, rejection,
            cancelItems, NOW, 0L)).as("거절이 아닌 취소의 실패 코드 없는 결정").isInstanceOf(IllegalArgumentException.class);
        assertThat(new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.REJECTED, krw(1_000), 77L, 0, null, null, rejection,
            cancelItems, NOW, 0L).decision()).as("취소 거절 결정(A4)").isEqualTo(rejection);
        assertThat(new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.REQUESTED, krw(1_000), 77L, 0, NOW, null, withCode,
            cancelItems, NOW, 0L).decision()).as("취소 클레임 결정은 그대로").isEqualTo(withCode);
    }

    @ParameterizedTest
    @EnumSource(Claim.Status.class)
    @DisplayName("반품 술어 — 수량을 잡는 반품 = 정산된 반품 + 열린 반품(서로 겹치지 않음) = 거절 밖 전부, 환불 진행 중은 승인 · 환불 실패. 취소 클레임은 모두 아니다")
    void returnPredicates(Claim.Status status) {
        boolean approvedOrLater = status != Claim.Status.REQUESTED && status != Claim.Status.REJECTED;
        Claim claim = returnClaim(status, approvedOrLater ? krw(1_000) : null, approvedOrLater ? 77L : null, Claim.Type.RETURN.isAwaitingAttempt(status) ? NOW : null,
            status.isFailed() ? "PAYMENT_016" : null, approvedOrLater ? new Claim.ReturnApproval(true, krw(0), 9, NOW) : null);

        assertThat(claim.reservesReturnQuantity()).isEqualTo(status != Claim.Status.REJECTED)
            .isEqualTo(Claim.returnQuantityReservingStatuses().contains(status)).isEqualTo(claim.isSettledReturn() || claim.isOpenReturn());
        assertThat(claim.isSettledReturn() && claim.isOpenReturn()).isFalse();
        assertThat(claim.isSettledReturn()).isEqualTo(claim.hasRecordedRefund());
        assertThat(claim.isOpenReturn()).isEqualTo(status == Claim.Status.REQUESTED || status == Claim.Status.APPROVED || status == Claim.Status.REFUND_FAILED);
        assertThat(claim.isReturnRefundInFlight()).isEqualTo(status == Claim.Status.APPROVED || status == Claim.Status.REFUND_FAILED);
        if (status != Claim.Status.APPROVED) {
            Claim cancellation = cancellation(status, Claim.Type.CANCELLATION.isAwaitingAttempt(status) ? NOW : null, status.isFailed() ? "PAYMENT_016" : null,
                List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.OTHER)));
            assertThat(List.of(cancellation.reservesReturnQuantity(), cancellation.isSettledReturn(), cancellation.isOpenReturn(),
                cancellation.isReturnRefundInFlight())).containsOnly(false);
        }
    }

    @Test
    @DisplayName("반품 승인 기록 — 관리자 양수 · 차감액 · 시각이 있어야 하고 시각은 마이크로초로 자른다")
    void returnApprovalValidation() {
        assertThat(new Claim.ReturnApproval(false, krw(0), 1, NOW).approvedAt()).isEqualTo(Instant.parse("2026-11-06T00:00:00.123456Z"));
        assertThatThrownBy(() -> new Claim.ReturnApproval(true, krw(0), 0, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim.ReturnApproval(true, null, 1, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Claim.ReturnApproval(true, krw(0), 1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("승인된 반품도 B1 에서는 취소 수명 전이(환불 확정 · 실패 · 재시도 · 선점 · 다시 열기 · 수동 결정)를 하지 않는다 — B2 에서 연다")
    void approvedReturnDoesNotUseCancellationTransitionsYet() {
        Claim approved = returnRequested().approveReturn(paidOrder(), 77L, new ReturnRefundPolicy.Refund(100, 0, CurrencyCode.KRW), true, 9, NOW, LEASE);

        assertThatThrownBy(() -> approved.markRefunded(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.markConfirmedRefund(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.failRefund("PAYMENT_016")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.retryLater(NOW, MINUTE, HOUR)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.holdUntil(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.reopen(NOW, 0)).isInstanceOf(IllegalStateException.class);
        assertThat(approved.hasSameRefundRequest(approved)).as("환불 지문은 아직 취소만").isFalse();
    }

    private static Claim returnRequested() {
        return new Claim(600, 1, UUID.fromString("00000000-0000-0000-0000-000000000600"), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(601, 11, 1, ClaimItem.Reason.CHANGE_OF_MIND)), NOW, 3L);
    }

    private static Claim returnClaim(Claim.Status status, Money refund, Long paymentId, Instant next, String failureCode, Claim.ReturnApproval approval) {
        return new Claim(600, 1, UUID.randomUUID(), Claim.Type.RETURN, status, refund, paymentId, 0, next, failureCode, null, approval,
            List.of(new ClaimItem(601, 11, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 0L);
    }

    private static Claim cancellation(Claim.Status status, Instant next, String failureCode, List<ClaimItem> items) {
        return new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, status, krw(1_000), 77L, 0, next, failureCode, items, NOW, 0L);
    }
}
