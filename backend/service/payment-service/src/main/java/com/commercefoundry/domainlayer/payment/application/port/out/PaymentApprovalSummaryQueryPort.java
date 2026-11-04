package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentApprovalSummaryQueryResult;
import java.time.Instant;
import java.util.List;

public interface PaymentApprovalSummaryQueryPort {
    List<PaymentApprovalSummaryQueryResult> summarize(Instant from, Instant to);
}
