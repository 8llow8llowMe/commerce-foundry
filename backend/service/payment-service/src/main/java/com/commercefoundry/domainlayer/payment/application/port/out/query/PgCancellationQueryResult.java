package com.commercefoundry.domainlayer.payment.application.port.out.query;

/** POST evidence and GET confirmation are distinct; POST alone never completes a cancellation. */
public record PgCancellationQueryResult(State state, String transactionKey) {
    public enum State { OPEN, UNATTRIBUTED, CONFIRMED, EVIDENCE, RETRY, MANUAL_REVIEW }
    public static PgCancellationQueryResult of(State state) { return new PgCancellationQueryResult(state, null); }
}
