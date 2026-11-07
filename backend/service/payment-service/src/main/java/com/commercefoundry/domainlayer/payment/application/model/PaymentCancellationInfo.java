package com.commercefoundry.domainlayer.payment.application.model;

public record PaymentCancellationInfo(State state, String transactionKey) {
    public enum State { OPEN, UNATTRIBUTED, CONFIRMED, EVIDENCE, RETRY, MANUAL_REVIEW }
}
