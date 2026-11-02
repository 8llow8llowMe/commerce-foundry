package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentCancelEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentCancelRepository;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository;
import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapper;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentCancelPersistenceAdapter implements PaymentCancelPersistencePort {

    private final PaymentRepository paymentRepository;
    private final PaymentCancelRepository cancelRepository;
    private final PaymentMapper paymentMapper;

    @Override
    public Optional<Payment> lockPayment(long paymentId) {
        return paymentRepository.findLockedById(paymentId).map(paymentMapper::toDomain);
    }

    @Override
    public Optional<PaymentCancel> findByClaimId(long claimId) {
        return cancelRepository.findById(claimId).map(PaymentCancelEntity::toDomain);
    }

    @Override
    public List<PaymentCancel> findByPaymentId(long paymentId) {
        return cancelRepository.findAllByPaymentId(paymentId).stream().map(PaymentCancelEntity::toDomain).toList();
    }

    @Override
    public PaymentCancel save(PaymentCancel cancel) {
        PaymentCancelEntity entity = cancelRepository.findById(cancel.claimId()).orElseGet(() -> PaymentCancelEntity.from(cancel));
        entity.setStatus(cancel.status());
        return cancelRepository.saveAndFlush(entity).toDomain();
    }
}
