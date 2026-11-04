package com.commercefoundry.domainlayer.payment.application.port.out.query;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.shared.commerce.Money;

public record PaymentApprovalSummaryQueryResult(PaymentMethod method, long approvalCount, Money grossApprovedAmount) {
}
