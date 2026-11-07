package com.commercefoundry.domainlayer.sizechart.application.port.out;

import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import org.springframework.data.domain.Page;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 호출한 Processor의 트랜잭션에서 사용한다. 제약·낙관적 충돌의 업무 오류 변환은 호출자가 담당한다. */
public interface SizeChartDraftRepositoryPort {

    /** Physically deletes at most limit rows with expiresAt <= cutoff; safe for competing cleanup instances. */
    int deleteExpiredBatch(Instant cutoff, int limit);

    Optional<SizeChartDraft> findById(long draftId);

    /** Local only, newest Snowflake ID first; optional filters are combined. */
    Page<SizeChartDraft> findPage(Long productId, Long requestedByMemberId, Instant now, int page, int size);

    Optional<SizeChartDraft> findByRequestedByMemberIdAndIdempotencyKey(long actorId, UUID idempotencyKey);

    /** 새 REQUESTED만 INSERT한다. 같은 ID 또는 actor+key를 덮어쓰지 않는다. */
    SizeChartDraft insert(SizeChartDraft draft);

    /** 관리 엔티티 갱신 후 flush한다. 입력은 불변이고, 오래된 버전은 낙관적 충돌로 반환한다. */
    SizeChartDraft update(SizeChartDraft draft, Instant now);
}
