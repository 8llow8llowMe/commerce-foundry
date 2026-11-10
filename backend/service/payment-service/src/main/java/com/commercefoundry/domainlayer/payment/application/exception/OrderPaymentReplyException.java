package com.commercefoundry.domainlayer.payment.application.exception;

/** 내부 회신 실패 분류. 원문, 식별자, 원인 사슬은 보존하지 않으며 Kafka 진입이 retryable 분류를 사용한다. */
public final class OrderPaymentReplyException extends RuntimeException {
    private final Reason reason;

    public OrderPaymentReplyException(Reason reason) {
        super("주문 결제 회신 처리 실패: " + reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        INVALID_COMMAND(false), SOURCE_NOT_READY(true), SOURCE_UNAVAILABLE(true), SOURCE_UNAUTHORIZED(true),
        SOURCE_CONTRACT(false), SOURCE_MISMATCH(false), LOCAL_MISMATCH(false), LOCAL_NOT_FOUND(false), INVALID_ORIGIN(false),
        PAYMENT_VERSION(true), INBOX_DUPLICATE(true), STORAGE_UNAVAILABLE(true), STORAGE_CONTRACT(false);

        private final boolean retryable;
        Reason(boolean retryable) {
            this.retryable = retryable;
        }
        public boolean retryable() {
            return retryable;
        }
    }
}
