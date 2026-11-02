package com.commercefoundry.domainlayer.payment.application.info;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;

public record PaymentAdminInfo(
    long paymentId, long orderId, PaymentStatus status, Money amount, PaymentMethod method, PaymentUnknownReason unknownReason,
    boolean orderSynced, String orderSyncFailureCode, Instant createdAt, Instant updatedAt, Instant approvalStartedAt, Instant approvedAt
) {
}
