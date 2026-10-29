package com.commercefoundry.domainlayer.skucost.application.service.processor;

import com.commercefoundry.domainlayer.skucost.application.command.SkuCostCreateCommand;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostErrorCode;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostException;
import com.commercefoundry.domainlayer.skucost.application.port.out.SkuCostRepositoryPort;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCost;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostAppendViolation;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostTimeline;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SKU 원가 이력 한 행 추가 — 최신 행을 읽어 타임라인 규칙({@link SkuCostTimeline})으로 판정한 뒤 넣는다.
 * 판정과 추가 사이에 다른 추가가 끼지 않도록 Facade 가 먼저 SKU 행을 잠근다. 그래서 트랜잭션 밖이면 실패한다.
 * 잠금을 비켜 간 동시 추가(같은 시작 시각)는 유니크 키가 409 {@code SKU_COST_004} 로 막는다.
 *
 * <p>시각은 마이크로초로 잘라 판정 · 저장한다 — DB {@code timestamptz(6)} 에 저장되는 값과 같아야 단조 판정이 저장 뒤에도 그대로 맞다.
 */
@Component
@RequiredArgsConstructor
public class SkuCostAppendProcessor {

    private final SkuCostRepositoryPort skuCostRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public SkuCost append(SkuCostCreateCommand command) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant effectiveFrom = command.effectiveFrom() == null ? now : command.effectiveFrom().truncatedTo(ChronoUnit.MICROS);
        SkuCost candidate = new SkuCost(
            snowflakeIdGenerator.generateId(),
            command.skuId(),
            command.costType(),
            command.amount(),
            effectiveFrom,
            command.reason(),
            command.adminMemberId(),
            null
        );
        SkuCost latest = skuCostRepositoryPort.findLatest(command.skuId(), command.costType()).orElse(null);
        SkuCostTimeline.validateAppend(latest, candidate, now).ifPresent(violation -> {
            throw toException(violation, latest);
        });
        return skuCostRepositoryPort.insert(candidate);
    }

    private static SkuCostException toException(SkuCostAppendViolation violation, SkuCost latest) {
        return switch (violation) {
            case IN_FUTURE -> new SkuCostException(SkuCostErrorCode.EFFECTIVE_FROM_IN_FUTURE);
            case CURRENCY_MISMATCH -> new SkuCostException(SkuCostErrorCode.CURRENCY_MISMATCH, latest.amount().currency().name());
            case RETROACTIVE_NOT_ALLOWED -> new SkuCostException(SkuCostErrorCode.RETROACTIVE_NOT_ALLOWED);
            case NOT_AFTER_LATEST -> new SkuCostException(SkuCostErrorCode.EFFECTIVE_FROM_NOT_AFTER_LATEST, latest.effectiveFrom().toString());
        };
    }
}
