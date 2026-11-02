package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminSearchCriteria;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminQueryResult;
import org.springframework.data.domain.Page;

public interface PaymentAdminCustomRepository {
    Page<PaymentAdminQueryResult> search(PaymentAdminSearchCriteria criteria);
}
