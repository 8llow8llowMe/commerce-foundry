package com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.entity.AiUsageCallEntity;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiPricedBy;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * AI 호출 원장. 닫기는 "아직 PENDING 일 때만" 조건부 UPDATE 한 문장이다 ({@code coding-conventions.md} §9-5) — 반환값(0 또는 1)이 "내가 닫았다" 의 증거다.
 * bulk UPDATE 는 감사 리스너를 거치지 않아 {@code updatedAt} 을 인자로 받아 직접 세팅한다.
 */
public interface AiUsageCallRepository extends JpaRepository<AiUsageCallEntity, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update AiUsageCallEntity c
           set c.status = :status, c.model = :model, c.inputTokens = :inputTokens, c.outputTokens = :outputTokens,
               c.cacheCreationInputTokens = :cacheCreationInputTokens, c.cacheReadInputTokens = :cacheReadInputTokens, c.costMicros = :costMicros,
               c.pricingVersion = :pricingVersion, c.pricedBy = :pricedBy, c.reason = :reason, c.httpStatus = :httpStatus, c.messageId = :messageId,
               c.elapsedMs = :elapsedMs, c.settledAt = :settledAt, c.updatedAt = :settledAt
         where c.id = :id and c.status = :pending
        """)
    int closeIfPending(
        Long id, AiUsageStatus pending, AiUsageStatus status, String model, long inputTokens, long outputTokens, long cacheCreationInputTokens,
        long cacheReadInputTokens, Long costMicros, String pricingVersion, AiPricedBy pricedBy, String reason, Integer httpStatus, String messageId,
        Integer elapsedMs, Instant settledAt
    );

    /** 멈춘 예약 후보 — {@code idx_ai_usage_call_status_called_at}. 오래된 순. */
    List<AiUsageCallEntity> findByStatusAndCalledAtBeforeOrderByCalledAtAscIdAsc(AiUsageStatus status, Instant calledBefore, Limit limit);

    /** 보존 정리 후보 — {@code idx_ai_usage_call_called_at}. 아이디만 읽는다. */
    @Query("""
        select c.id from AiUsageCallEntity c
         where c.calledAt < :cutoff and c.status <> :pending
         order by c.calledAt asc, c.id asc
        """)
    List<Long> findClosedIdsCalledBefore(Instant cutoff, AiUsageStatus pending, Limit limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AiUsageCallEntity c where c.id in :ids")
    int deleteByIdIn(Collection<Long> ids);
}
