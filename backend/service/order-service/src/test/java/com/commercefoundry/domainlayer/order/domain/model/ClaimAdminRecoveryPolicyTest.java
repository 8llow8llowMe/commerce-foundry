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
    private static final List<ClaimItem> ITEMS = List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.OTHER));

    private static Claim claim(Claim.Status status, String failure) {
        return new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, status, krw(1_000), 77L, 2,
            status.isAwaitingAttempt() ? NOW : null, failure, ITEMS, NOW, 0L);
    }

    private static Order order(long id, OrderStatus status, Long version) {
        Order placed = Order.place(id, 7, domesticQuote(quoteLine(101, 1_000, 1)), new AtomicLong(10)::incrementAndGet,
            address("KR"), 99, NOW);
        return new Order(id, placed.memberId(), status, placed.quote(), placed.itemIds(), placed.shippingAddress(),
            placed.stockReservationId(), placed.paymentDueAt(), null, null, NOW, version);
    }

    @ParameterizedTest
    @EnumSource(Claim.Status.class)
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
    @EnumSource(Claim.Status.class)
    void refundConfirmationAcceptsOnlyRefundFailure(Claim.Status status) {
        Claim source = claim(status, status.isFailed() ? Claim.REFUND_UNCONFIRMED : null);
        if (status == Claim.Status.REFUND_FAILED) {
            assertThatCode(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(source)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(source)).isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRODUCT_027", "PRODUCT_028", "PRODUCT_029", "PRODUCT_003", "RESTORATION_DATA_INVALID", "ORDER_STATUS_UNEXPECTED"})
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
    @ValueSource(strings = {"RESTORATION_UNCONFIRMED", "REFUND_UNCONFIRMED", "PAYMENT_016", "PAYMENT_017", "PAYMENT_019", "UNKNOWN_001"})
    void ambiguousOrUnknownFailuresCannotBeCompleted(String failure) {
        assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireCompletion(claim(Claim.Status.RESTORATION_FAILED, failure),
            order(1, OrderStatus.CANCELED, 0L))).isInstanceOf(IllegalStateException.class);
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
            assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireRefundConfirmation(source)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ClaimAdminRecoveryPolicy.requireCompletion(source, order(1, OrderStatus.CANCELED, 0L)))
                .isInstanceOf(IllegalStateException.class);
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
        assertThatThrownBy(() -> new Claim.Decision("확인", 7, NOW, null)).isInstanceOf(NullPointerException.class);
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
}
