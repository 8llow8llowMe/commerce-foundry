package com.commercefoundry.domainlayer.payment.application.port.in;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentApprovalSummaryResponse;
import com.commercefoundry.domainlayer.payment.application.command.PaymentApprovalSummaryCommand;

public interface PaymentApprovalSummaryInternalUseCase {
    PaymentApprovalSummaryResponse summarize(PaymentApprovalSummaryCommand command);
}
