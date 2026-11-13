package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom.PaymentAdminCustomRepository;
import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminSearchCriteria;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAdminQueryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminExternalRefundQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminQueryResult;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentAdminQueryAdapter implements PaymentAdminQueryPort {
    private final PaymentAdminCustomRepository repository;

    @Override
    public Page<PaymentAdminQueryResult> search(PaymentAdminSearchCriteria criteria) {
        return repository.search(criteria);
    }

    @Override
    public List<PaymentAdminExternalRefundQueryResult> findExternalRefundTotals(Collection<Long> paymentIds) {
        if (paymentIds.isEmpty()) {
            return List.of();
        }
        return repository.findExternalRefundTotals(paymentIds);
    }
}
