package com.commercefoundry.domainlayer.aiusage.adapter.out.persistence;

import com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.entity.AiUsageCallEntity;
import com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.repository.AiUsageCallRepository;
import com.commercefoundry.domainlayer.aiusage.application.port.out.AiUsageCallRepositoryPort;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiBudgetPeriod;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiCost;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiTokenUsage;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageCall;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiUsageCallRepositoryAdapter implements AiUsageCallRepositoryPort {

    private final AiUsageCallRepository aiUsageCallRepository;

    /** 바로 내보낸다 — 아이디 충돌(PK 위반)이 예약 트랜잭션 안에서 드러나 그대로 올라간다(계량 어댑터가 METERING_FAILED 로 번역). */
    @Override
    public void insert(AiUsageCall call) {
        if (!call.status().isPending()) {
            throw new IllegalArgumentException("새 호출은 PENDING 이어야 합니다: " + call.status());
        }
        aiUsageCallRepository.saveAndFlush(toEntity(call));
    }

    @Override
    public Optional<AiUsageCall> findById(long callId) {
        return aiUsageCallRepository.findById(callId).map(AiUsageCallRepositoryAdapter::toDomain);
    }

    @Override
    public boolean closeIfPending(AiUsageCall closed) {
        if (closed.status().isPending()) {
            throw new IllegalArgumentException("닫힌 호출이 아닙니다.");
        }
        AiTokenUsage usage = closed.usage();
        return aiUsageCallRepository.closeIfPending(
            closed.id(), AiUsageStatus.PENDING, closed.status(), closed.model(), usage.inputTokens(), usage.outputTokens(), usage.cacheCreationInputTokens(),
            usage.cacheReadInputTokens(), closed.costMicros(), closed.pricingVersion(), closed.pricedBy(), closed.reason(), closed.httpStatus(),
            closed.messageId(), closed.elapsedMs(), closed.settledAt()
        ) == 1;
    }

    @Override
    public List<AiUsageCall> findPendingCalledBefore(Instant calledBefore, int limit) {
        return aiUsageCallRepository.findByStatusAndCalledAtBeforeOrderByCalledAtAscIdAsc(AiUsageStatus.PENDING, calledBefore, Limit.of(limit)).stream()
            .map(AiUsageCallRepositoryAdapter::toDomain)
            .toList();
    }

    /** 아이디를 한 배치 읽고 in 절 한 문장으로 지운다 — JPQL delete 에는 건수 상한이 없다. */
    @Override
    public int deleteClosedCalledBefore(Instant cutoff, int limit) {
        List<Long> ids = aiUsageCallRepository.findClosedIdsCalledBefore(cutoff, AiUsageStatus.PENDING, Limit.of(limit));
        if (ids.isEmpty()) {
            return 0;
        }
        return aiUsageCallRepository.deleteByIdIn(ids);
    }

    private static AiUsageCallEntity toEntity(AiUsageCall call) {
        AiTokenUsage usage = call.usage();
        return AiUsageCallEntity.builder()
            .id(call.id())
            .period(call.period().value())
            .feature(call.feature())
            .promptVersion(call.promptVersion())
            .callerRef(call.callerRef())
            .requestedModel(call.requestedModel())
            .model(call.model())
            .structured(call.structured())
            .imageCount(call.imageCount())
            .inputChars(call.inputChars())
            .estimateCostMicros(call.estimateCostMicros())
            .inputTokens(usage.inputTokens())
            .outputTokens(usage.outputTokens())
            .cacheCreationInputTokens(usage.cacheCreationInputTokens())
            .cacheReadInputTokens(usage.cacheReadInputTokens())
            .costMicros(call.costMicros())
            .costCurrency(AiCost.CURRENCY)
            .pricingVersion(call.pricingVersion())
            .pricedBy(call.pricedBy())
            .status(call.status())
            .reason(call.reason())
            .httpStatus(call.httpStatus())
            .messageId(call.messageId())
            .elapsedMs(call.elapsedMs())
            .calledAt(call.calledAt())
            .settledAt(call.settledAt())
            .build();
    }

    private static AiUsageCall toDomain(AiUsageCallEntity entity) {
        return new AiUsageCall(
            entity.getId(), new AiBudgetPeriod(entity.getPeriod()), entity.getFeature(), entity.getPromptVersion(), entity.getCallerRef(),
            entity.getRequestedModel(), entity.getModel(), entity.isStructured(), entity.getImageCount(), entity.getInputChars(), entity.getEstimateCostMicros(),
            new AiTokenUsage(entity.getInputTokens(), entity.getOutputTokens(), entity.getCacheCreationInputTokens(), entity.getCacheReadInputTokens()),
            entity.getCostMicros(), entity.getPricingVersion(), entity.getPricedBy(), entity.getStatus(), entity.getReason(), entity.getHttpStatus(),
            entity.getMessageId(), entity.getElapsedMs(), entity.getCalledAt(), entity.getSettledAt()
        );
    }
}
