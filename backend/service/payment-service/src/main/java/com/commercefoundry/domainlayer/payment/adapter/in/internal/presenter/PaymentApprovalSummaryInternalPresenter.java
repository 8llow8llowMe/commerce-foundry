package com.commercefoundry.domainlayer.payment.adapter.in.internal.presenter;

import com.commercefoundry.commerce.support.web.MoneyItems;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentApprovalSummaryResponse;
import com.commercefoundry.domainlayer.payment.application.info.PaymentApprovalSummaryInfo;
import org.springframework.stereotype.Component;

@Component
public class PaymentApprovalSummaryInternalPresenter {
    public PaymentApprovalSummaryResponse toResponse(PaymentApprovalSummaryInfo info) {
        return new PaymentApprovalSummaryResponse(info.from(), info.to(), info.groups().stream()
            .map(group -> new PaymentApprovalSummaryResponse.Group(group.method().toMetadata(), group.approvalCount(),
                MoneyItems.of(group.grossApprovedAmount()))).toList());
    }
}
