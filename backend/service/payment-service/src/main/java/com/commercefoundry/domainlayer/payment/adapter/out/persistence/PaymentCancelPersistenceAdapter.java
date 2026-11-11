package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentCancelEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentAutoCancelRepository;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentCancelRepository;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository;
import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapper;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentCancelPersistencePort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentAutoCancelClaimCollisionException;
import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentCancelPersistenceAdapter implements PaymentCancelPersistencePort {

    private final PaymentRepository paymentRepository;
    private final PaymentCancelRepository cancelRepository;
    private final PaymentAutoCancelRepository autoCancelRepository;
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
    public boolean isAutoClaim(long claimId) {
        return autoCancelRepository.existsByClaimId(claimId);
    }

    @Override
    public List<PaymentCancel> findByPaymentId(long paymentId) {
        return cancelRepository.findAllByPaymentId(paymentId).stream().map(PaymentCancelEntity::toDomain).toList();
    }

    @Override
    public PaymentCancel insertNewAutoClaim(PaymentCancel cancel) {
        if (cancel.status() != PaymentCancel.Status.PENDING) throw new IllegalArgumentException("New auto claim must be pending");
        try {
            // A null @Version makes Spring Data persist this NEW entity even with an assigned ID.
            return cancelRepository.saveAndFlush(PaymentCancelEntity.from(cancel)).toDomain();
        } catch (DataIntegrityViolationException failure) {
            if (isUniqueViolation(failure)) {
                throw new PaymentAutoCancelClaimCollisionException();
            }
            throw failure;
        }
    }

    @Override
    public PaymentCancel insertVoided(PaymentCancel cancel) {
        if (cancel.status() != PaymentCancel.Status.VOIDED) throw new IllegalArgumentException("Seal row must be voided");
        try {
            // insertNewAutoClaim 과 같다 — null @Version 이라 persist(INSERT) 만 하고 기존 행을 덮어쓰지 않는다.
            return cancelRepository.saveAndFlush(PaymentCancelEntity.from(cancel)).toDomain();
        } catch (DataIntegrityViolationException failure) {
            if (isUniqueViolation(failure)) {
                // 같은 claimId 를 다른 결제 행 잠금 아래에서 먼저 넣었다. 판정을 다시 받아야 한다.
                throw new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT, failure);
            }
            throw failure;
        }
    }

    private static boolean isUniqueViolation(DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public PaymentCancel save(PaymentCancel cancel) {
        PaymentCancelEntity entity = cancelRepository.findById(cancel.claimId()).orElseGet(() -> PaymentCancelEntity.from(cancel));
        if (entity.getPaymentId() != cancel.paymentId() || entity.getAmount() != cancel.amount().amount() || entity.getCurrency() != cancel.amount().currency()) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED);
        }
        if (entity.getStatus() != cancel.status() && entity.getStatus() != PaymentCancel.Status.PENDING) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT);
        }
        entity.setStatus(cancel.status());
        return cancelRepository.saveAndFlush(entity).toDomain();
    }
}
