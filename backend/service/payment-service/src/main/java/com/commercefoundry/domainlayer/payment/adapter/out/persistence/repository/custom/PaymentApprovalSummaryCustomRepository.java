package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentApprovalSummaryQueryResult;
import java.time.Instant;
import java.util.List;

public interface PaymentApprovalSummaryCustomRepository {
    List<PaymentApprovalSummaryQueryResult> summarize(Instant from, Instant to);
}
