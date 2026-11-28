package com.commercefoundry.domainlayer.settlement.adapter.out.persistence;

import com.commercefoundry.domainlayer.settlement.adapter.out.persistence.entity.SettlementDayEntity;
import com.commercefoundry.domainlayer.settlement.adapter.out.persistence.repository.SettlementDayRepository;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementErrorCode;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementException;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementDayRepositoryPort;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDay;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 매출일 요약 저장 (#219). 잠금 실패 · 매출일 유니크 키 위반 · 버전 충돌은 모두 "같은 매출일의 다른 실행" 이라 409 {@code SETTLEMENT_002} 다 —
 * 그 트랜잭션은 이미 롤백 대상이라 같은 트랜잭션에서 다시 읽지 않는다. 쓰기는 바로 flush 해 충돌이 커밋 시점이 아니라 이 호출에서 드러나게 한다.
 */
@Component
@RequiredArgsConstructor
public class SettlementDayRepositoryAdapter implements SettlementDayRepositoryPort {

    private static final String SOLD_DATE_CONSTRAINT = "uk_settlement_day_sold_date";

    private final SettlementDayRepository repository;

    @Override
    public Optional<SettlementDay> lockBySoldDate(LocalDate soldDate) {
        try {
            return repository.findBySoldDateForUpdate(soldDate).map(SettlementDayEntity::toDomain);
        } catch (PessimisticLockingFailureException exception) {
            throw new SettlementException(SettlementErrorCode.RECONCILE_CONFLICT);
        }
    }

    @Override
    public SettlementDay save(SettlementDay day) {
        try {
            return repository.saveAndFlush(SettlementDayEntity.from(day)).toDomain();
        } catch (OptimisticLockingFailureException exception) {
            throw new SettlementException(SettlementErrorCode.RECONCILE_CONFLICT);
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.violates(exception, SOLD_DATE_CONSTRAINT)) {
                throw new SettlementException(SettlementErrorCode.RECONCILE_CONFLICT);
            }
            throw new DataIntegrityViolationException("매출일 요약 저장이 제약을 위반했습니다. soldDate=%s, constraint=%s"
                .formatted(day.soldDate(), ConstraintViolations.constraintNameOf(exception)));
        }
    }
}
