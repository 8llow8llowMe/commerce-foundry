package com.commercefoundry.domainlayer.aiusage.adapter.out.persistence;

import com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.entity.AiUsageMonthEntity;
import com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.repository.AiUsageMonthRepository;
import com.commercefoundry.domainlayer.aiusage.application.port.out.AiUsageMonthRepositoryPort;
import com.commercefoundry.domainlayer.aiusage.application.port.out.query.AiUsageMonthQueryResult;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiCost;
import com.commercefoundry.persistence.util.ConstraintViolations;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AiUsageMonthRepositoryAdapter implements AiUsageMonthRepositoryPort {

    static final String PERIOD_CONSTRAINT = "uk_ai_usage_month_period";

    private final AiUsageMonthRepository aiUsageMonthRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final TransactionTemplate requiresNew;

    public AiUsageMonthRepositoryAdapter(
        AiUsageMonthRepository aiUsageMonthRepository, SnowflakeIdGenerator snowflakeIdGenerator, PlatformTransactionManager transactionManager
    ) {
        this.aiUsageMonthRepository = aiUsageMonthRepository;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean reserve(String period, long estimateMicros, long limitMicros, Instant now) {
        return aiUsageMonthRepository.reserve(period, estimateMicros, limitMicros, now) == 1;
    }

    /**
     * 새 트랜잭션에서 넣는다 — PostgreSQL 은 유니크 키 위반 뒤 그 트랜잭션을 중단시키므로, 경합에서 진 쪽이 바깥 트랜잭션을 망치지 않게 한다.
     * 예외는 {@code execute} 밖에서 잡는다 — 그때는 실패한 트랜잭션이 이미 롤백됐다.
     */
    @Override
    public boolean createIfAbsent(String period, long limitMicros, Instant now) {
        try {
            requiresNew.executeWithoutResult(status -> aiUsageMonthRepository.saveAndFlush(AiUsageMonthEntity.builder()
                .id(snowflakeIdGenerator.generateId())
                .period(period)
                .reservedMicros(0)
                .settledMicros(0)
                .callCount(0)
                .limitMicrosSnapshot(limitMicros)
                .costCurrency(AiCost.CURRENCY)
                .build()));
            return true;
        } catch (DataIntegrityViolationException conflict) {
            if (ConstraintViolations.violates(conflict, PERIOD_CONSTRAINT)) {
                return false;
            }
            throw conflict;
        }
    }

    @Override
    public void settle(String period, long estimateMicros, long actualMicros, int calls, Instant now) {
        if (aiUsageMonthRepository.settle(period, estimateMicros, actualMicros, calls, now) != 1) {
            throw new IllegalStateException("정산할 월 카운터가 없습니다: " + period);
        }
    }

    @Override
    public boolean markWarned(String period, long thresholdMicros, Instant now) {
        return aiUsageMonthRepository.markWarned(period, thresholdMicros, now) == 1;
    }

    @Override
    public boolean markExceededAtLimit(String period, long limitMicros, Instant now) {
        return aiUsageMonthRepository.markExceededAtLimit(period, limitMicros, now) == 1;
    }

    @Override
    public boolean markExceededByReserve(String period, long estimateMicros, long limitMicros, Instant now) {
        return aiUsageMonthRepository.markExceededByReserve(period, estimateMicros, limitMicros, now) == 1;
    }

    @Override
    public Optional<AiUsageMonthQueryResult> find(String period) {
        return aiUsageMonthRepository.findByPeriod(period).map(month -> new AiUsageMonthQueryResult(
            month.getPeriod(), month.getReservedMicros(), month.getSettledMicros(), month.getCallCount(), month.getLimitMicrosSnapshot(), month.getWarnedAt(),
            month.getExceededAt()
        ));
    }
}
