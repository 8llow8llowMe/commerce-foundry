package com.commercefoundry.domainlayer.order.domain.model;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ClaimAdminRecoveryPolicyTest {
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00.123456789Z");
    private static final Claim.Decision REJECTION = new Claim.Decision("검수 불합격", 9, NOW, null);
    private static final List<ClaimItem> ITEMS = List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.OTHER));

    private static Claim claim(Claim.Status status, String failure) {
        return new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, status, krw(1_000), 77L, 2,
            Claim.Type.CANCELLATION.isAwaitingAttempt(status) ? NOW : null, failure, ITEMS, NOW, 0L);
    }

    private static Order order(long id, OrderStatus status, Long version) {
        Order placed = Order.place(id, 7, domesticQuote(quoteLine(101, 1_000, 1)), new AtomicLong(10)::incrementAndGet,
            address("KR"), 99, NOW);
        return new Order(id, placed.memberId(), status, placed.quote(), placed.itemIds(), placed.shippingAddress(),
            placed.stockReservationId(), placed.paymentDueAt(), null, null, NOW, version);
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"APPROVED", "REJECTED"}, mode = EnumSource.Mode.EXCLUDE)
    void retryEligibilityDoesNotChangeState(Claim.Status status) {
        Claim source = claim(status, status.isFailed() ? "PAYMENT_016" : null);
        if (status == Claim.Status.COMPLETED) {
            assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRetry(source, NOW)).isInstanceOf(IllegalStateException.class);
        } else {
            assertThatCode(() -> ClaimAdminRecoveryPolicy.requireRetry(source, NOW)).doesNotThrowAnyException();
            assertThat(source.status()).isEqualTo(status);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"REQUESTED", "REFUNDED"})
    void activeLeaseCannotBeStolenAndDueBoundaryIsAllowed(Claim.Status status) {
        Claim due = claim(status, null);
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRetry(due, NOW.minusNanos(1))).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> ClaimAdminRecoveryPolicy.requireRetry(due, NOW)).doesNotThrowAnyException();
        assertThatCode(() -> ClaimAdminRecoveryPolicy.requireRetry(due, NOW.plusSeconds(1))).doesNotThrowAnyException();
    }

    @Test
    void unexpectedOrderStatusCannotBeRetried() {
        for (Claim.Status status : List.of(Claim.Status.REFUND_FAILED, Claim.Status.RESTORATION_FAILED)) {
            assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRetry(claim(status, "ORDER_STATUS_UNEXPECTED"), NOW))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
    void refundConfirmationAcceptsOnlyRefundFailure(Claim.Status status) {
        Claim source = claim(status, status.isFailed() ? "PAYMENT_016" : null);
        Order order = order(1, OrderStatus.CANCEL_REQUESTED, 0L);
        if (status == Claim.Status.REFUND_FAILED) {
            assertThat(ClaimAdminRecoveryPolicy.isRefundConfirmationCandidate(source, false)).isTrue();
            assertThatCode(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(source, order, false)).doesNotThrowAnyException();
        } else {
            assertThat(ClaimAdminRecoveryPolicy.isRefundConfirmationCandidate(source, true)).isFalse();
            assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(source, order, true)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void unconfirmedRefundIsConfirmableOnlyAfterRetryClosed() {
        Claim unconfirmed = claim(Claim.Status.REFUND_FAILED, Claim.REFUND_UNCONFIRMED);
        Order order = order(1, OrderStatus.CANCEL_REQUESTED, 0L);
        assertThat(ClaimAdminRecoveryPolicy.isRefundConfirmationCandidate(unconfirmed, false)).isFalse();
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(unconfirmed, order, false)).isInstanceOf(IllegalStateException.class);
        assertThat(ClaimAdminRecoveryPolicy.isRefundConfirmationCandidate(unconfirmed, true)).isTrue();
        assertThatCode(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(unconfirmed, order, true)).doesNotThrowAnyException();
    }

    @Test
    void refundConfirmationRequiresSameStoredCancelRequestedOrCanceledOrder() {
        Claim rejected = claim(Claim.Status.REFUND_FAILED, "PAYMENT_019");
        for (OrderStatus status : OrderStatus.values()) {
            if (status == OrderStatus.CANCEL_REQUESTED || status == OrderStatus.CANCELED) {
                assertThatCode(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(rejected, order(1, status, 0L), false))
                    .as(status.name()).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(rejected, order(1, status, 0L), false))
                    .as(status.name()).isInstanceOf(IllegalStateException.class);
            }
        }
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(rejected, order(2, OrderStatus.CANCEL_REQUESTED, 0L), false))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(rejected, order(1, OrderStatus.CANCEL_REQUESTED, null), false))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(rejected, null, false)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRODUCT_027", "PRODUCT_028", "PRODUCT_029", "PRODUCT_003", "RESTORATION_DATA_INVALID"})
    void completionRequiresSameStoredCanceledOrder(String failure) {
        Claim source = claim(Claim.Status.RESTORATION_FAILED, failure);
        assertThatCode(() -> ClaimAdminRecoveryPolicy.requireCompletion(source, order(1, OrderStatus.CANCELED, 0L))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireCompletion(source, order(2, OrderStatus.CANCELED, 0L)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireCompletion(source, order(1, OrderStatus.CANCELED, null)))
            .isInstanceOf(IllegalStateException.class);
        for (OrderStatus status : OrderStatus.values()) {
            if (status != OrderStatus.CANCELED) {
                assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireCompletion(source, order(1, status, 0L)))
                    .as(status.name()).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESTORATION_UNCONFIRMED", "REFUND_UNCONFIRMED", "PAYMENT_016", "PAYMENT_017", "PAYMENT_019", "UNKNOWN_001", "ORDER_STATUS_UNEXPECTED"})
    void ambiguousOrUnknownFailuresCannotBeCompleted(String failure) {
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireCompletion(claim(Claim.Status.RESTORATION_FAILED, failure),
            order(1, OrderStatus.CANCELED, 0L))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completionCandidateIsRequireCompletionWithoutOrder() {
        for (String failure : List.of("PRODUCT_027", "PRODUCT_028", "PRODUCT_029", "PRODUCT_003", "RESTORATION_DATA_INVALID")) {
            assertThat(ClaimAdminRecoveryPolicy.isCompletionCandidate(claim(Claim.Status.RESTORATION_FAILED, failure))).as(failure).isTrue();
        }
        // 주문 상태 이상은 주문이 취소 완료로 갈 경로가 없어 종결 버튼만 보이고 늘 409 였다 — 후보가 아니다(수습 경로는 #695)
        for (String failure : List.of("RESTORATION_UNCONFIRMED", "REFUND_UNCONFIRMED", "PAYMENT_016", "UNKNOWN_001", "ORDER_STATUS_UNEXPECTED")) {
            assertThat(ClaimAdminRecoveryPolicy.isCompletionCandidate(claim(Claim.Status.RESTORATION_FAILED, failure))).as(failure).isFalse();
        }
        for (Claim.Status status : List.of(Claim.Status.REQUESTED, Claim.Status.REFUNDED, Claim.Status.REFUND_FAILED, Claim.Status.COMPLETED)) {
            assertThat(ClaimAdminRecoveryPolicy.isCompletionCandidate(claim(status, status.isFailed() ? "PRODUCT_028" : null))).as(status.name()).isFalse();
        }
        Claim base = claim(Claim.Status.RESTORATION_FAILED, "PRODUCT_028");
        Claim unsaved = new Claim(base.id(), base.orderId(), base.requestKey(), base.type(), base.status(), base.expectedRefund(), base.paymentId(),
            base.attemptCount(), base.nextAttemptAt(), base.failureCode(), base.items(), null, null);
        Claim returned = new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 0L);
        assertThat(ClaimAdminRecoveryPolicy.isCompletionCandidate(unsaved)).isFalse();
        assertThat(ClaimAdminRecoveryPolicy.isCompletionCandidate(returned)).isFalse();
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.isCompletionCandidate(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"REQUESTED", "REFUNDED", "REFUND_FAILED", "COMPLETED"})
    void completionIsNotAnIdempotentSuccessClassifier(Claim.Status status) {
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireCompletion(claim(status, status.isFailed() ? "PRODUCT_028" : null),
            order(1, OrderStatus.CANCELED, 0L))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void policiesRejectUnsavedAndReturnClaims() {
        Claim base = claim(Claim.Status.REFUND_FAILED, "PAYMENT_016");
        Claim unsaved = new Claim(base.id(), base.orderId(), base.requestKey(), base.type(), base.status(), base.expectedRefund(), base.paymentId(),
            base.attemptCount(), base.nextAttemptAt(), base.failureCode(), base.items(), null, null);
        Claim returned = new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 0L);
        for (Claim source : List.of(unsaved, returned)) {
            assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRetry(source, NOW)).isInstanceOf(IllegalStateException.class);
            assertThat(ClaimAdminRecoveryPolicy.isRefundConfirmationCandidate(source, true)).isFalse();
            assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(source, order(1, OrderStatus.CANCEL_REQUESTED, 0L), true))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireCompletion(source, order(1, OrderStatus.CANCELED, 0L)))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void newStatusesAreNotRecoveryTargets() {
        // #17 B1 — 거절된 취소(A4 의 결과)와 승인 · 거절된 반품은 다시 시도 · 수동 확정 · 종결 대상이 아니다(반품 수습은 B2)
        Claim rejectedCancellation = claim(Claim.Status.REJECTED, null);
        Claim requestedReturn = new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 0L);
        Claim approvedReturn = new Claim(2, 1, requestedReturn.requestKey(), Claim.Type.RETURN, Claim.Status.APPROVED, krw(1_000), 77L, 0, NOW, null, null,
            new Claim.ReturnApproval(ReturnRefundPolicy.Fault.CUSTOMER, true, krw(0), 7, NOW), requestedReturn.items(), NOW, 0L);
        // #17 B2b — 승인된 반품은 수습 대상이다: lease 가 끝난 환불 대기는 취소의 REQUESTED 와 같이 다시 시도(선점)한다. 확정 · 종결 후보는 아니다
        assertThatCode(() -> ClaimAdminRecoveryPolicy.requireRetry(approvedReturn, NOW.plusSeconds(1))).doesNotThrowAnyException();
        assertThat(ClaimAdminRecoveryPolicy.isRefundConfirmationCandidate(approvedReturn, true)).isFalse();
        assertThat(ClaimAdminRecoveryPolicy.isCompletionCandidate(approvedReturn)).isFalse();
        for (Claim source : List.of(rejectedCancellation, requestedReturn, requestedReturn.rejectReturn(REJECTION))) {
            assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRetry(source, NOW.plusSeconds(1))).as(source.type() + " " + source.status())
                .isInstanceOf(IllegalStateException.class);
            assertThat(ClaimAdminRecoveryPolicy.isRefundConfirmationCandidate(source, true)).isFalse();
            assertThat(ClaimAdminRecoveryPolicy.isCompletionCandidate(source)).isFalse();
        }
    }

    @Test
    void decisionValidatesWholeEvidenceAndTruncatesTime() {
        Claim.Decision decision = new Claim.Decision("운영 확인", 7, NOW, Claim.REFUND_UNCONFIRMED);
        assertThat(decision.decidedAt()).isEqualTo(Instant.parse("2026-11-10T00:00:00.123456Z"));
        assertThat(new Claim.Decision("가".repeat(200), 7, NOW, "X".repeat(40))).isNotNull();
        for (String reason : List.of("", " \t\n", "가".repeat(201))) {
            assertThatThrownBy(() -> new Claim.Decision(reason, 7, NOW, "CODE")).isInstanceOf(IllegalArgumentException.class);
        }
        for (String code : List.of("", " \t\n", "X".repeat(41))) {
            assertThatThrownBy(() -> new Claim.Decision("확인", 7, NOW, code)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Claim.Decision(null, 7, NOW, "CODE")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Claim.Decision("확인", 7, null, "CODE")).isInstanceOf(NullPointerException.class);
        // #17 B1 — 직전 실패 코드 없는 결정은 거절(REJECTED) 클레임용으로 받는다(DB ck_claim_admin_decision 과 같다). 상태와의 짝은 클레임 생성자가 본다
        assertThat(new Claim.Decision("확인", 7, NOW, null).previousFailureCode()).isNull();
        assertThatThrownBy(() -> new Claim.Decision("확인", 0, NOW, "CODE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Claim.Decision("확인", -1, NOW, "CODE")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oldConstructorHasNoDecisionAndExistingTransitionsPreserveEvidence() {
        Claim base = claim(Claim.Status.REQUESTED, null);
        assertThat(base.decision()).isNull();
        Claim.Decision decision = new Claim.Decision("운영 확인", 7, NOW, "PAYMENT_016");
        Claim evidence = new Claim(base.id(), base.orderId(), base.requestKey(), base.type(), base.status(), base.expectedRefund(), base.paymentId(),
            base.attemptCount(), base.nextAttemptAt(), base.failureCode(), decision, base.items(), base.requestedAt(), base.version());
        assertThat(evidence.holdUntil(NOW.plusSeconds(60)).decision()).isEqualTo(decision);
        assertThat(evidence.retryLater(NOW, Duration.ofSeconds(1), Duration.ofSeconds(2)).decision()).isEqualTo(decision);
        assertThat(evidence.failRefund("PAYMENT_019").decision()).isEqualTo(decision);
        assertThat(evidence.failRefund(Claim.REFUND_UNCONFIRMED).markRefunded(NOW).decision()).isEqualTo(decision);
        assertThat(evidence.markRefunded(NOW).complete().decision()).isEqualTo(decision);
        assertThat(evidence.markRefunded(NOW).failRestoration("PRODUCT_028").decision()).isEqualTo(decision);
        assertThatThrownBy(() -> new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, null, 0, null, null,
            decision, List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 0L)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Claim returnClaim(Claim.Status status, String failure) {
        return new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, status, krw(1_000), 77L, 0, Claim.Type.RETURN.isAwaitingAttempt(status) ? NOW : null, failure,
            null, new Claim.ReturnApproval(ReturnRefundPolicy.Fault.CUSTOMER, true, krw(0), 7, NOW), List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 0L);
    }

    @Test
    void returnBlockedRefundIsRetriedFirstAndConfirmedOnlyAfterRetryCloses() {
        // #17 B2b — 반품의 016 은 같은 결제의 다른 환불에 막힌 것일 수 있다(돈이 나가지 않았을 수 있다) — 다시 시도가 먼저, 수동 확정은 기한이 닫힌 뒤에만
        Claim waiting = returnClaim(Claim.Status.REFUND_FAILED, Claim.REFUND_BLOCKED_CODE);
        assertThatCode(() -> ClaimAdminRecoveryPolicy.requireRetry(waiting, NOW)).doesNotThrowAnyException();
        assertThat(ClaimAdminRecoveryPolicy.isRefundConfirmationCandidate(waiting, false)).isFalse();
        assertThat(ClaimAdminRecoveryPolicy.isRefundConfirmationCandidate(waiting, true)).isTrue();
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(waiting, order(1, OrderStatus.RETURN_REQUESTED, 0L), false))
            .isInstanceOf(IllegalStateException.class);

        Claim rejected = returnClaim(Claim.Status.REFUND_FAILED, "PAYMENT_017");
        assertThat(ClaimAdminRecoveryPolicy.isRefundConfirmationCandidate(rejected, false)).as("그 밖의 결제사 거절은 늘 후보").isTrue();
        // 취소의 016 은 그대로 결제사 거절이다(주문당 한 건이라 겹침이 없다)
        assertThat(ClaimAdminRecoveryPolicy.isRefundConfirmationCandidate(claim(Claim.Status.REFUND_FAILED, "PAYMENT_016"), false)).isTrue();
    }

    @Test
    void returnRefundConfirmationRequiresReturnRequestedOrder() {
        Claim rejected = returnClaim(Claim.Status.REFUND_FAILED, "PAYMENT_017");
        assertThatCode(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(rejected, order(1, OrderStatus.RETURN_REQUESTED, 0L), false))
            .doesNotThrowAnyException();
        for (OrderStatus status : List.of(OrderStatus.DELIVERED, OrderStatus.RETURNED, OrderStatus.PURCHASE_CONFIRMED, OrderStatus.CANCEL_REQUESTED, OrderStatus.CANCELED)) {
            assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(rejected, order(1, status, 0L), false)).as(status.name())
                .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(rejected, order(9, OrderStatus.RETURN_REQUESTED, 0L), false))
            .as("다른 주문").isInstanceOf(IllegalStateException.class);
    }

    // ---- #17 A4 취소 거절 자격 ----

    private static final long RECORDED_PAYMENT_ID = 77L;

    private static OrderStatus requireRejection(Claim claim, Order order, List<Claim> orderClaims, OrderStatus from, OrderStatus to, long paymentId) {
        return ClaimAdminRecoveryPolicy.requireCancellationRejection(claim, order, orderClaims, from, to, paymentId);
    }

    private static Claim cancellation(long id, Claim.Status status, String failure) {
        return new Claim(id, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, status, krw(1_000), 77L, 0,
            Claim.Type.CANCELLATION.isAwaitingAttempt(status) ? NOW : null, failure, ITEMS, NOW, 0L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAYMENT_016", "PAYMENT_017", "PAYMENT_019", "PAYMENT_001"})
    void cancellationRejectionCandidateIsProviderRejectedRefundFailure(String failure) {
        assertThat(ClaimAdminRecoveryPolicy.isCancellationRejectionCandidate(claim(Claim.Status.REFUND_FAILED, failure))).isTrue();
    }

    @Test
    void cancellationRejectionCandidateExcludesEverythingElse() {
        // R1 — 결과 미확인 환불 실패는 돈이 나갔을 수 있다. 다른 상태 · 저장 전 · 반품(막힘 016 포함)은 후보가 아니다
        assertThat(ClaimAdminRecoveryPolicy.isCancellationRejectionCandidate(claim(Claim.Status.REFUND_FAILED, Claim.REFUND_UNCONFIRMED))).isFalse();
        for (Claim.Status status : List.of(Claim.Status.REQUESTED, Claim.Status.REFUNDED, Claim.Status.COMPLETED, Claim.Status.REJECTED,
            Claim.Status.RESTORATION_FAILED)) {
            assertThat(ClaimAdminRecoveryPolicy.isCancellationRejectionCandidate(claim(status, status.isFailed() ? "PRODUCT_028" : null))).as(status.name())
                .isFalse();
        }
        Claim base = claim(Claim.Status.REFUND_FAILED, "PAYMENT_016");
        Claim unsaved = new Claim(base.id(), base.orderId(), base.requestKey(), base.type(), base.status(), base.expectedRefund(), base.paymentId(),
            base.attemptCount(), base.nextAttemptAt(), base.failureCode(), base.items(), null, null);
        assertThat(ClaimAdminRecoveryPolicy.isCancellationRejectionCandidate(unsaved)).isFalse();
        assertThat(ClaimAdminRecoveryPolicy.isCancellationRejectionCandidate(returnClaim(Claim.Status.REFUND_FAILED, "PAYMENT_017"))).isFalse();
        assertThat(ClaimAdminRecoveryPolicy.isCancellationRejectionCandidate(returnClaim(Claim.Status.REFUND_FAILED, Claim.REFUND_BLOCKED_CODE))).isFalse();
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.isCancellationRejectionCandidate(null)).isInstanceOf(NullPointerException.class);
        // R2 — 다음 시도 시각이 있는 취소(환불 대기 선점)는 후보가 아니고, 다음 시각이 있는 환불 실패는 만들 수 없다(도메인 불변식)
        assertThat(ClaimAdminRecoveryPolicy.isCancellationRejectionCandidate(claim(Claim.Status.REQUESTED, null))).isFalse();
        assertThatThrownBy(() -> new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.REFUND_FAILED, krw(1_000), 77L, 0, NOW,
            "PAYMENT_016", ITEMS, NOW, 0L)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "MAKING", "PREPARING"})
    void cancellationRejectionReturnsRestoreTargetWhenAllRulesHold(OrderStatus from) {
        Claim failed = claim(Claim.Status.REFUND_FAILED, "PAYMENT_016");
        // 같은 주문의 이 클레임 자신(열린 취소지만 아이디로 뺀다) · 정산된 취소 · 거절된 취소는 R5 를 막지 않는다
        List<Claim> orderClaims = List.of(failed, cancellation(5, Claim.Status.COMPLETED, null), cancellation(6, Claim.Status.REJECTED, null),
            cancellation(7, Claim.Status.RESTORATION_FAILED, "PRODUCT_028"));

        OrderStatus restoreTo = requireRejection(failed, order(1, OrderStatus.CANCEL_REQUESTED, 0L), orderClaims, from, OrderStatus.CANCEL_REQUESTED,
            RECORDED_PAYMENT_ID);

        assertThat(restoreTo).isEqualTo(from);
        assertThat(failed.status()).as("판정은 상태를 바꾸지 않는다").isEqualTo(Claim.Status.REFUND_FAILED);
    }

    @Test
    void cancellationRejectionRequiresR1ProviderRejectedRefundFailure() {
        Order order = order(1, OrderStatus.CANCEL_REQUESTED, 0L);
        for (Claim source : List.of(claim(Claim.Status.REFUND_FAILED, Claim.REFUND_UNCONFIRMED), claim(Claim.Status.REQUESTED, null),
            claim(Claim.Status.REFUNDED, null), claim(Claim.Status.COMPLETED, null), claim(Claim.Status.REJECTED, null),
            claim(Claim.Status.RESTORATION_FAILED, "PRODUCT_028"), returnClaim(Claim.Status.REFUND_FAILED, "PAYMENT_017"))) {
            assertThatThrownBy(() -> requireRejection(source, order, List.of(source), OrderStatus.PAID, OrderStatus.CANCEL_REQUESTED, RECORDED_PAYMENT_ID))
                .as(source.type() + " " + source.status() + " " + source.failureCode()).isInstanceOf(IllegalStateException.class);
        }
        Claim base = claim(Claim.Status.REFUND_FAILED, "PAYMENT_016");
        Claim unsaved = new Claim(base.id(), base.orderId(), base.requestKey(), base.type(), base.status(), base.expectedRefund(), base.paymentId(),
            base.attemptCount(), base.nextAttemptAt(), base.failureCode(), base.items(), null, null);
        assertThatThrownBy(() -> requireRejection(unsaved, order, List.of(), OrderStatus.PAID, OrderStatus.CANCEL_REQUESTED, RECORDED_PAYMENT_ID))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requireRejection(null, order, List.of(), OrderStatus.PAID, OrderStatus.CANCEL_REQUESTED, RECORDED_PAYMENT_ID))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void cancellationRejectionRequiresR4SameStoredCancelRequestedOrder() {
        Claim failed = claim(Claim.Status.REFUND_FAILED, "PAYMENT_019");
        for (OrderStatus status : OrderStatus.values()) {
            if (status != OrderStatus.CANCEL_REQUESTED) {
                assertThatThrownBy(() -> requireRejection(failed, order(1, status, 0L), List.of(failed), OrderStatus.PAID, OrderStatus.CANCEL_REQUESTED,
                    RECORDED_PAYMENT_ID)).as(status.name()).isInstanceOf(IllegalStateException.class);
            }
        }
        assertThatThrownBy(() -> requireRejection(failed, order(2, OrderStatus.CANCEL_REQUESTED, 0L), List.of(), OrderStatus.PAID, OrderStatus.CANCEL_REQUESTED,
            RECORDED_PAYMENT_ID)).as("다른 주문").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requireRejection(failed, order(1, OrderStatus.CANCEL_REQUESTED, null), List.of(), OrderStatus.PAID,
            OrderStatus.CANCEL_REQUESTED, RECORDED_PAYMENT_ID)).as("저장 전 주문").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requireRejection(failed, null, List.of(), OrderStatus.PAID, OrderStatus.CANCEL_REQUESTED, RECORDED_PAYMENT_ID))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void cancellationRejectionRequiresR5NoOtherOpenCancellation() {
        Claim failed = claim(Claim.Status.REFUND_FAILED, "PAYMENT_016");
        Order order = order(1, OrderStatus.CANCEL_REQUESTED, 0L);
        for (Claim other : List.of(cancellation(5, Claim.Status.REQUESTED, null), cancellation(5, Claim.Status.REFUND_FAILED, "PAYMENT_017"),
            cancellation(5, Claim.Status.REFUND_FAILED, Claim.REFUND_UNCONFIRMED))) {
            assertThatThrownBy(() -> requireRejection(failed, order, List.of(failed, other), OrderStatus.PAID, OrderStatus.CANCEL_REQUESTED,
                RECORDED_PAYMENT_ID)).as(other.status() + " " + other.failureCode()).isInstanceOf(IllegalStateException.class);
        }
        Claim otherOrder = new Claim(5, 9, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.COMPLETED, krw(1_000), 77L, 0, null, null, ITEMS, NOW, 0L);
        assertThatThrownBy(() -> requireRejection(failed, order, List.of(failed, otherOrder), OrderStatus.PAID, OrderStatus.CANCEL_REQUESTED,
            RECORDED_PAYMENT_ID)).as("다른 주문의 클레임").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requireRejection(failed, order, null, OrderStatus.PAID, OrderStatus.CANCEL_REQUESTED, RECORDED_PAYMENT_ID))
            .isInstanceOf(NullPointerException.class);
        assertThat(requireRejection(failed, order, List.of(), OrderStatus.PAID, OrderStatus.CANCEL_REQUESTED, RECORDED_PAYMENT_ID))
            .as("빈 목록도 다른 열린 취소가 없다").isEqualTo(OrderStatus.PAID);
    }

    @Test
    void cancellationRejectionRequiresR6LatestHistoryFromPaidBeforeShipmentToCancelRequested() {
        Claim failed = claim(Claim.Status.REFUND_FAILED, "PAYMENT_016");
        Order order = order(1, OrderStatus.CANCEL_REQUESTED, 0L);
        for (OrderStatus from : OrderStatus.values()) {
            boolean restorable = from == OrderStatus.PAID || from == OrderStatus.MAKING || from == OrderStatus.PREPARING;
            if (restorable) {
                assertThat(requireRejection(failed, order, List.of(failed), from, OrderStatus.CANCEL_REQUESTED, RECORDED_PAYMENT_ID)).isEqualTo(from);
            } else {
                assertThatThrownBy(() -> requireRejection(failed, order, List.of(failed), from, OrderStatus.CANCEL_REQUESTED, RECORDED_PAYMENT_ID))
                    .as(from.name()).isInstanceOf(IllegalStateException.class);
            }
        }
        // 최신 행이 취소 요청으로 들어온 행이 아니면(이력 없음 · 다른 전이) 되돌릴 상태를 알 수 없다 — 닫힌 쪽
        for (OrderStatus to : OrderStatus.values()) {
            if (to != OrderStatus.CANCEL_REQUESTED) {
                assertThatThrownBy(() -> requireRejection(failed, order, List.of(failed), OrderStatus.PAID, to, RECORDED_PAYMENT_ID)).as(to.name())
                    .isInstanceOf(IllegalStateException.class);
            }
        }
        assertThatThrownBy(() -> requireRejection(failed, order, List.of(failed), null, OrderStatus.CANCEL_REQUESTED, RECORDED_PAYMENT_ID))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requireRejection(failed, order, List.of(failed), OrderStatus.PAID, null, RECORDED_PAYMENT_ID))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requireRejection(failed, order, List.of(failed), null, null, RECORDED_PAYMENT_ID)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellationRejectionRequiresR7RecordedPaymentMatchesClaim() {
        Claim failed = claim(Claim.Status.REFUND_FAILED, "PAYMENT_016");
        Order order = order(1, OrderStatus.CANCEL_REQUESTED, 0L);

        assertThatThrownBy(() -> requireRejection(failed, order, List.of(failed), OrderStatus.PAID, OrderStatus.CANCEL_REQUESTED, 78L))
            .isInstanceOf(IllegalStateException.class);
        assertThat(requireRejection(failed, order, List.of(failed), OrderStatus.PAID, OrderStatus.CANCEL_REQUESTED, 77L)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void rejectedCancellationCannotBeRetriedConfirmedCompletedOrRejectedAgain() {
        Claim failed = claim(Claim.Status.REFUND_FAILED, "PAYMENT_017");
        Order order = order(1, OrderStatus.CANCEL_REQUESTED, 0L);
        OrderStatus restoreTo = requireRejection(failed, order, List.of(failed), OrderStatus.PREPARING, OrderStatus.CANCEL_REQUESTED, RECORDED_PAYMENT_ID);
        Claim rejected = failed.rejectCancellation(new Claim.Decision("결제사 콘솔에서 무환불 확인", 9, NOW, "PAYMENT_017"));
        Order restored = order.rejectCancellation(restoreTo);

        assertThat(rejected.status()).isEqualTo(Claim.Status.REJECTED);
        assertThat(restored.status()).isEqualTo(OrderStatus.PREPARING);
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRetry(rejected, NOW.plusSeconds(1))).as("다시 시도").isInstanceOf(IllegalStateException.class);
        assertThat(ClaimAdminRecoveryPolicy.isRefundConfirmationCandidate(rejected, true)).as("확정 후보").isFalse();
        for (Order target : List.of(order, restored, order(1, OrderStatus.CANCELED, 0L))) {
            assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(rejected, target, true)).as("확정 " + target.status())
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireCompletion(rejected, target)).as("종결 " + target.status())
                .isInstanceOf(IllegalStateException.class);
        }
        assertThat(ClaimAdminRecoveryPolicy.isCompletionCandidate(rejected)).isFalse();
        assertThat(ClaimAdminRecoveryPolicy.isCancellationRejectionCandidate(rejected)).as("다시 거절").isFalse();
        assertThatThrownBy(() -> requireRejection(rejected, order, List.of(rejected), OrderStatus.PREPARING, OrderStatus.CANCEL_REQUESTED,
            RECORDED_PAYMENT_ID)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requireRejection(failed, restored, List.of(rejected), OrderStatus.PREPARING, OrderStatus.CANCEL_REQUESTED,
            RECORDED_PAYMENT_ID)).as("주문이 이미 되돌아갔다").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rejected.reopen(NOW.plusSeconds(60), 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rejected.confirmRefundByAdmin(new Claim.Decision("확인", 9, NOW, "PAYMENT_017"), NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rejected.markRefunded(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rejected.markConfirmedRefund(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void returnCompletionDoesNotLookAtOrderStatusButKeepsUnconfirmedAndMismatchOut() {
        Claim restorationFailed = returnClaim(Claim.Status.RESTORATION_FAILED, "PRODUCT_028");
        for (OrderStatus status : List.of(OrderStatus.RETURN_REQUESTED, OrderStatus.RETURNED, OrderStatus.DELIVERED, OrderStatus.PURCHASE_CONFIRMED)) {
            assertThatCode(() -> ClaimAdminRecoveryPolicy.requireCompletion(restorationFailed, order(1, status, 0L))).as(status.name()).doesNotThrowAnyException();
        }
        assertThat(ClaimAdminRecoveryPolicy.isCompletionCandidate(returnClaim(Claim.Status.RESTORATION_FAILED, Claim.RESTORATION_UNCONFIRMED))).isFalse();
        Claim mismatch = returnClaim(Claim.Status.RESTORATION_FAILED, "ORDER_STATUS_UNEXPECTED");
        assertThat(ClaimAdminRecoveryPolicy.isCompletionCandidate(mismatch)).isFalse();
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRetry(mismatch, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> ClaimAdminRecoveryPolicy.requireRetry(restorationFailed, NOW)).doesNotThrowAnyException();
    }
}
