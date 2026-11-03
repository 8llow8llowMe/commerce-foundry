package com.commercefoundry.domainlayer.payment.application.command;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * order-service 의 결제 반영 회신 이벤트 하나 ({@code order-paid} · {@code order-payment-rejected}, {@code payment-guide.md} §6). 봉투 본문은 알림일 뿐이다 —
 * 처리는 order 원천 조회로 다시 확정한다.
 *
 * @param eventId 봉투 eventId — inbox 키
 * @param kind 반영 성공 · 거절
 * @param paymentId 결제 시도 아이디
 * @param orderId 주문 아이디 (봉투 aggregateId · Kafka 키와 같다)
 * @param rejectionReason 거절 사유 코드({@link #REJECTION_REASONS}). 성공이면 null
 */
public record OrderPaymentReplyCommand(UUID eventId, Kind kind, long paymentId, long orderId, String rejectionReason) {

    /** order-service {@code OrderPayment.Result#rejectionReason()} 값 — 문자열 계약이다. 더해지면 여기도 더한다. */
    public static final Set<String> REJECTION_REASONS = Set.of(
        "ORDER_CANCELED", "ORDER_NOT_FOUND", "PAYMENT_MISMATCH", "STOCK_UNAVAILABLE", "ALREADY_PAID", "PAYMENT_NOT_DONE"
    );

    public OrderPaymentReplyCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(kind, "kind");
        if (paymentId <= 0 || orderId <= 0) {
            throw new IllegalArgumentException("결제 · 주문 아이디는 양수여야 합니다");
        }
        if (kind == Kind.PAID ? rejectionReason != null : !REJECTION_REASONS.contains(rejectionReason)) {
            throw new IllegalArgumentException("회신 종류와 거절 사유가 맞지 않습니다");
        }
    }

    /** 회신 종류 — 봉투 {@code eventType} 과 일대일이다. */
    public enum Kind {
        PAID("order-paid"),
        REJECTED("order-payment-rejected");

        private final String eventType;

        Kind(String eventType) {
            this.eventType = eventType;
        }

        public String eventType() {
            return eventType;
        }
    }
}
