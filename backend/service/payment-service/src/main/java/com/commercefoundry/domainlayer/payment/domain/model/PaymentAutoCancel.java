package com.commercefoundry.domainlayer.payment.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Durable automatic cancellation intent with a frozen PG key, amount and fenced execution lease. */
public record PaymentAutoCancel(long paymentId, Long claimId, String pgKey, Money amount, State state, int attempts,
    Instant nextAttemptAt, Instant leaseUntil, String leaseToken, Instant firstAttemptAt, String transactionKey, String manualReason) {

    public static final String MANUAL_REASON = "AUTO_CANCEL_MANUAL_REVIEW";
    public static final Duration KEY_WINDOW = Duration.ofDays(14);
    public static final Duration LEASE = Duration.ofMinutes(2);

    public PaymentAutoCancel {
        if (paymentId <= 0 || pgKey == null || !pgKey.matches("[A-Za-z0-9_=\\-]{6,64}") || attempts < 0) {
            throw new IllegalArgumentException("Invalid automatic cancellation identity");
        }
        Objects.requireNonNull(state);
        Objects.requireNonNull(nextAttemptAt);
        if ((claimId == null) != (amount == null) || (claimId != null && claimId <= 0)) {
            throw new IllegalArgumentException("Invalid frozen automatic claim mapping");
        }
        if (amount != null && amount.isZero()) {
            throw new IllegalArgumentException("Cancellation amount must be positive");
        }
        if ((leaseUntil == null) != (leaseToken == null) || (attempts > 0 && (amount == null || firstAttemptAt == null))) {
            throw new IllegalArgumentException("Invalid cancellation lease");
        }
    }

    public static PaymentAutoCancel intent(long paymentId, Instant now) {
        return new PaymentAutoCancel(paymentId, null, "payment-auto-cancel-v1-" + paymentId, null, State.PENDING, 0, now, null, null, null, null, null);
    }

    public boolean due(Instant now) {
        return state == State.PENDING && !nextAttemptAt.isAfter(now) && (leaseUntil == null || !leaseUntil.isAfter(now));
    }

    public boolean owns(String token, Instant now) {
        return state == State.PENDING && token != null && token.equals(leaseToken) && leaseUntil != null && leaseUntil.isAfter(now);
    }

    public boolean keyExpired(Instant now) {
        return firstAttemptAt != null && !now.isBefore(firstAttemptAt.plus(KEY_WINDOW));
    }

    public PaymentAutoCancel acquire(long reservedClaimId, Money remaining, String token, Instant now) {
        if (reservedClaimId <= 0 || !due(now) || (claimId != null && claimId != reservedClaimId) || (amount != null && !amount.equals(remaining))) {
            throw new IllegalStateException("Automatic cancellation cannot be acquired");
        }
        return new PaymentAutoCancel(paymentId, reservedClaimId, pgKey, remaining, state, Math.incrementExact(attempts), now,
            now.plus(LEASE), Objects.requireNonNull(token), firstAttemptAt == null ? now : firstAttemptAt, transactionKey, null);
    }

    public PaymentAutoCancel evidence(String key) {
        if (key == null || key.isBlank() || key.length() > 64 || (transactionKey != null && !transactionKey.equals(key))) {
            throw new IllegalArgumentException("Conflicting POST evidence");
        }
        return new PaymentAutoCancel(paymentId, claimId, pgKey, amount, state, attempts, nextAttemptAt, leaseUntil, leaseToken, firstAttemptAt, key, null);
    }

    public PaymentAutoCancel retry(Instant now) {
        long delay = Math.min(3600L, 30L << Math.min(attempts, 7));
        return new PaymentAutoCancel(paymentId, claimId, pgKey, amount, state, attempts, now.plusSeconds(delay), null, null, firstAttemptAt, transactionKey, null);
    }

    public PaymentAutoCancel finish(boolean confirmed) {
        if (state != State.PENDING || (confirmed && (amount == null || transactionKey == null))) {
            throw new IllegalStateException("Cancellation cannot be completed without pending intent and transaction evidence");
        }
        return new PaymentAutoCancel(paymentId, claimId, pgKey, amount, confirmed ? State.SUCCEEDED : State.MANUAL_REVIEW, attempts,
            nextAttemptAt, null, null, firstAttemptAt, transactionKey, confirmed ? null : MANUAL_REASON);
    }

    public enum State { PENDING, SUCCEEDED, MANUAL_REVIEW }
}
