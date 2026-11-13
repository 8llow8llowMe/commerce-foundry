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
import org.springframework.dao.OptimisticLockingFailureException;
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

    @Override
    public PaymentCancel recordExternalRefund(PaymentCancel cancel) {
        if (!cancel.status().refundedExternally()) throw new IllegalArgumentException("External refund row must be externally refunded");
        try {
            if (cancelRepository.findById(cancel.claimId()).isEmpty()) {
                // insertVoided 와 같다 — null @Version 이라 persist(INSERT) 만 하고 먼저 커밋된 같은 claimId 행을 덮어쓰지 않는다.
                return cancelRepository.saveAndFlush(PaymentCancelEntity.from(cancel)).toDomain();
            }
            return update(cancel);
        } catch (DataIntegrityViolationException failure) {
            if (isUniqueViolation(failure)) {
                throw new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT, failure);
            }
            throw failure;
        } catch (OptimisticLockingFailureException failure) {
            // 결제 행 잠금 밖에서 같은 행을 바꾼 쓰기가 있었다. 판정을 다시 받아야 한다.
            throw new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_CONFLICT, failure);
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
        if (cancel.status().refundedExternally()) {
            // 외부 확인 환불은 PG 검증 · 결제 행 잠금을 거친 recordExternalRefund 로만 쓴다 — 일반 저장 경로로 새지 않게 막는다
            throw new IllegalArgumentException("External refund must be written through recordExternalRefund");
        }
        return update(cancel);
    }

    /** 행이 없으면 만들고, 있으면 전이 표 안에서 상태만 바꾼다. 같은 트랜잭션에서 읽은 엔티티면 그 버전으로 낙관 락을 확인한다. */
    private PaymentCancel update(PaymentCancel cancel) {
        PaymentCancelEntity entity = cancelRepository.findById(cancel.claimId()).orElseGet(() -> PaymentCancelEntity.from(cancel));
        if (entity.getPaymentId() != cancel.paymentId() || entity.getAmount() != cancel.amount().amount() || entity.getCurrency() != cancel.amount().currency()) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_KEY_REUSED);
        }
        // 전이 표(PaymentCancel.Status.canBecome) 밖의 상태 변경은 거부한다 — 종결 행을 되돌리거나 VOIDED 를 PG 밖 환불로 바꾸지 않는다
        if (entity.getStatus() != cancel.status() && !entity.getStatus().canBecome(cancel.status())) {
            throw new PaymentException(PaymentErrorCode.CANCELLATION_CONFLICT);
        }
        entity.setStatus(cancel.status());
        return cancelRepository.saveAndFlush(entity).toDomain();
    }
}
