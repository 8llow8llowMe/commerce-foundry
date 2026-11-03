package com.commercefoundry.domainlayer.payment.domain.model;

import java.time.Duration;
import java.time.Instant;

/** 수신 원문을 보관하지 않는 영속 작업. claimToken과 version이 만료된 작업자의 결과를 차단한다. */
public record PaymentWebhookInbox(
    long id, long paymentId, String fingerprint, State state, int attempts, Instant availableAt,
    String claimToken, Reason reason, Instant receivedAt, Instant updatedAt, Long version
) {
    public PaymentWebhookInbox {
        availableAt = availableAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        receivedAt = receivedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        updatedAt = updatedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    public enum State { PENDING, PROCESSING, RETRY, COMPLETED, MANUAL_REVIEW }
    public enum Reason { NONE, SNAPSHOT_CHANGED, PG_RETRY, PG_CONTRACT, PAYMENT_MISSING, UNSUPPORTED_STATE, LATE_APPROVAL, EXHAUSTED }

    public static PaymentWebhookInbox received(long id, long paymentId, String fingerprint, Instant now) {
        return new PaymentWebhookInbox(id, paymentId, fingerprint, State.PENDING, 0, now, null, Reason.NONE, now, now, null);
    }

    public boolean due(Instant now) {
        return (state == State.PENDING || state == State.RETRY || state == State.PROCESSING) && !availableAt.isAfter(now);
    }

    public PaymentWebhookInbox claim(String token, Instant now, Duration lease) {
        if (!due(now)) { throw new IllegalStateException("webhook inbox is not due"); }
        return new PaymentWebhookInbox(id, paymentId, fingerprint, State.PROCESSING, attempts + 1, now.plus(lease),
            token, reason, receivedAt, now, version);
    }

    public boolean owns(String token, Instant now) {
        return state == State.PROCESSING && claimToken.equals(token) && availableAt.isAfter(now);
    }

    public PaymentWebhookInbox finish(State next, Reason nextReason, Instant due, Instant now) {
        if (state != State.PROCESSING || next == State.PROCESSING || next == State.PENDING) {
            throw new IllegalStateException("invalid webhook inbox transition");
        }
        return new PaymentWebhookInbox(id, paymentId, fingerprint, next, attempts, due, null, nextReason, receivedAt, now, version);
    }

    @Override public String toString() { return "PaymentWebhookInbox[id=" + id + ", state=" + state + "]"; }
}
