package com.commercefoundry.domainlayer.payment.application.port.in;

import com.commercefoundry.domainlayer.payment.adapter.in.admin.dto.item.PaymentAdminItem;
import com.commercefoundry.domainlayer.payment.application.command.PaymentAdminSearchCommand;
import com.commercefoundry.persistence.dto.PageResponse;

public interface PaymentAdminUseCase {
    PageResponse<PaymentAdminItem> search(PaymentAdminSearchCommand command);
}
