package com.commercefoundry.domainlayer.settlement.adapter.out.persistence;

import com.commercefoundry.domainlayer.settlement.adapter.out.persistence.entity.SettlementMismatchEntity;
import com.commercefoundry.domainlayer.settlement.adapter.out.persistence.repository.SettlementMismatchRepository;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementErrorCode;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementException;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementMismatchRepositoryPort;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatch;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 대사 불일치 저장 (#219). (결제키, 종류) 유니크 키 위반 · 버전 충돌은 다른 날짜의 대사가 같은 결제를 동시에 판정한 것이라 409 {@code SETTLEMENT_002} 다 —
 * 호출자가 다시 부르면 새 트랜잭션에서 다시 판정한다.
 */
@Component
@RequiredArgsConstructor
public class SettlementMismatchRepositoryAdapter implements SettlementMismatchRepositoryPort {

    private static final String PAYMENT_KEY_TYPE_CONSTRAINT = "uk_settlement_mismatch_payment_key_type";

    private final SettlementMismatchRepository repository;

    @Override
    public List<SettlementMismatch> findByPaymentKeys(Collection<String> paymentKeys) {
        return InClauseChunks.query(paymentKeys, repository::findAllByPaymentKeyIn).stream().map(SettlementMismatchEntity::toDomain).toList();
    }

    @Override
    public List<SettlementMismatch> saveAll(Collection<SettlementMismatch> mismatches) {
        try {
            List<SettlementMismatchEntity> saved = repository.saveAll(mismatches.stream().map(SettlementMismatchEntity::from).toList());
            repository.flush();
            return saved.stream().map(SettlementMismatchEntity::toDomain).toList();
        } catch (OptimisticLockingFailureException exception) {
            throw new SettlementException(SettlementErrorCode.RECONCILE_CONFLICT);
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.violates(exception, PAYMENT_KEY_TYPE_CONSTRAINT)) {
                throw new SettlementException(SettlementErrorCode.RECONCILE_CONFLICT);
            }
            throw new DataIntegrityViolationException("대사 불일치 저장이 제약을 위반했습니다. constraint=%s".formatted(ConstraintViolations.constraintNameOf(exception)));
        }
    }
}
