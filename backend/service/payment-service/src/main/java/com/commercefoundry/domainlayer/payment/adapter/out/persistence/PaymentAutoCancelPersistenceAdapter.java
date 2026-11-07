package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentAutoCancelEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentAutoCancelRepository;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAutoCancelStore;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAutoCancel;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentAutoCancelPersistenceAdapter implements PaymentAutoCancelStore {
    private final PaymentAutoCancelRepository repository;

    @Override
    public Optional<PaymentAutoCancel> find(long paymentId) {
        return repository.findById(paymentId).map(PaymentAutoCancelEntity::toDomain);
    }

    @Override
    public PaymentAutoCancel save(PaymentAutoCancel intent) {
        var entity = repository.findById(intent.paymentId()).orElseGet(() -> PaymentAutoCancelEntity.from(intent));
        entity.apply(intent);
        return repository.saveAndFlush(entity).toDomain();
    }
}
