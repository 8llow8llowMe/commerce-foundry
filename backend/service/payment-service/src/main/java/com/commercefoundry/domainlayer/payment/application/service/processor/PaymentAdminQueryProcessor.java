package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.command.PaymentAdminSearchCommand;
import com.commercefoundry.domainlayer.payment.application.info.PaymentAdminInfo;
import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminSearchCriteria;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAdminQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentAdminQueryProcessor {
    private final PaymentAdminQueryPort queryPort;

    public Page<PaymentAdminInfo> search(PaymentAdminSearchCommand command) {
        return queryPort.search(new PaymentAdminSearchCriteria(command.status(), command.orderId(), command.orderSynced(), command.page(), command.size()))
            .map(row -> new PaymentAdminInfo(row.paymentId(), row.orderId(), row.status(), row.amount(), row.method(), row.unknownReason(),
                row.orderSynced(), row.orderSyncFailureCode(), row.createdAt(), row.updatedAt(), row.approvalStartedAt(), row.approvedAt()));
    }
}
