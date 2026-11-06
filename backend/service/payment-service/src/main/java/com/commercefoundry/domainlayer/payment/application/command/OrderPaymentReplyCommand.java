package com.commercefoundry.domainlayer.payment.application.command;

import java.util.Set;
import java.util.UUID;

/** 회신 알림이다. 판정은 반드시 order 원천과 로컬 결제로 다시 검증한다. */
public record OrderPaymentReplyCommand(UUID eventId, Kind kind, long paymentId, long orderId, String rejectionReason) {
    public static final Set<String> REJECTION_REASONS = Set.of(
        "ORDER_CANCELED", "ORDER_NOT_FOUND", "PAYMENT_MISMATCH", "STOCK_UNAVAILABLE", "ALREADY_PAID", "PAYMENT_NOT_DONE"
    );

    public OrderPaymentReplyCommand {
        if (eventId == null || kind == null || paymentId <= 0 || orderId <= 0
            || (kind == Kind.PAID ? rejectionReason != null : rejectionReason == null || !REJECTION_REASONS.contains(rejectionReason))) {
            throw new IllegalArgumentException("주문 결제 회신 명령이 유효하지 않습니다");
        }
    }

    @Override
    public String toString() {
        return "OrderPaymentReplyCommand[redacted]";
    }

    public enum Kind {
        PAID("order-paid"), REJECTED("order-payment-rejected");
        private final String eventType;
        Kind(String eventType) {
            this.eventType = eventType;
        }
        public String eventType() {
            return eventType;
        }
    }
}
