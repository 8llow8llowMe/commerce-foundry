package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.application.command.PaymentApprovalCommand;

public interface OrderPaymentEventUseCase {
    void approve(PaymentApprovalCommand command);
}
