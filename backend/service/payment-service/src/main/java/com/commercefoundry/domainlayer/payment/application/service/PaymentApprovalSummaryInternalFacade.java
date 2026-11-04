package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentApprovalSummaryResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.presenter.PaymentApprovalSummaryInternalPresenter;
import com.commercefoundry.domainlayer.payment.application.command.PaymentApprovalSummaryCommand;
import com.commercefoundry.domainlayer.payment.application.port.in.PaymentApprovalSummaryInternalUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentApprovalSummaryProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentApprovalSummaryInternalFacade implements PaymentApprovalSummaryInternalUseCase {
    private final PaymentApprovalSummaryProcessor processor;
    private final PaymentApprovalSummaryInternalPresenter presenter;

    @Override
    @Transactional(readOnly = true, timeout = 2)
    public PaymentApprovalSummaryResponse summarize(PaymentApprovalSummaryCommand command) {
        return presenter.toResponse(processor.summarize(command));
    }
}
