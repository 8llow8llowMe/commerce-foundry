package com.commercefoundry.domainlayer.payment.application.port.out.query;

import com.commercefoundry.commerce.support.persistence.MoneyColumns;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;

/** 어드민 목록에 필요한 컬럼만 읽는다. 결제키 · 고객 멱등 키 · PG 원문 · 회원 개인정보는 없다. */
public record PaymentAdminQueryResult(
    long paymentId, long orderId, PaymentStatus status, long totalAmount, CurrencyCode totalCurrency,
    PaymentMethod method, PaymentUnknownReason unknownReason, boolean orderSynced, String orderSyncFailureCode,
    Instant createdAt, Instant updatedAt, Instant approvalStartedAt, Instant approvedAt
) {
    public Money amount() {
        return MoneyColumns.required(totalAmount, totalCurrency);
    }
}
