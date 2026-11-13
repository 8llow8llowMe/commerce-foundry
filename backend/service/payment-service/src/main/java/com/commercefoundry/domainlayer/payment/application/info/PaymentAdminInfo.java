package com.commercefoundry.domainlayer.payment.application.info;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.Objects;

/** @param externalRefund 외부 확인 환불 합계(#693). 그런 원장이 없는 결제면 null */
public record PaymentAdminInfo(
    long paymentId, long orderId, PaymentStatus status, Money amount, PaymentMethod method, PaymentUnknownReason unknownReason,
    boolean orderSynced, String orderSyncFailureCode, Instant createdAt, Instant updatedAt, Instant approvalStartedAt, Instant approvedAt,
    ExternalRefund externalRefund
) {
    /**
     * 우리 PG 취소 호출 밖에서 확인해 기록한 환불의 채널별 합계 (#693). 두 금액은 같은 통화이며 없는 채널은 0 이다.
     *
     * @param pgAmount    EXTERNAL_PG_REFUNDED 합 — PG 잔액이 줄었다(PG 상점관리자 등)
     * @param offPgAmount OFF_PG_REFUNDED 합 — PG 밖(계좌 송금 등)으로 환불했다. PG 잔액 · 결제 상태에는 반영하지 않는다
     */
    public record ExternalRefund(Money pgAmount, Money offPgAmount) {
        public ExternalRefund {
            Objects.requireNonNull(pgAmount, "pgAmount");
            Objects.requireNonNull(offPgAmount, "offPgAmount");
            if (pgAmount.currency() != offPgAmount.currency()) {
                throw new IllegalArgumentException("외부 환불 합계의 통화가 다릅니다");
            }
        }
    }
}
