package com.commercefoundry.domainlayer.payment.application.info;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;

public record PaymentApprovalSummaryInfo(Instant from, Instant to, List<Group> groups) {
    public record Group(PaymentMethod method, long approvalCount, Money grossApprovedAmount) {
    }
}
