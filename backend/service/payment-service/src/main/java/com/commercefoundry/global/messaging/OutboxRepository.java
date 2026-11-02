package com.commercefoundry.global.messaging;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * outbox 행. 넣은 뒤에는 상태가 아닌 칸만 조건부 UPDATE 한 문장으로 바꾼다 ({@code coding-conventions.md} §9-1 · §9-5). 반환값은 바뀐 행 수다.
 * bulk UPDATE 는 감사 리스너를 거치지 않아 {@code updatedAt} 을 인자로 받아 직접 세팅하고, 버전도 직접 올린다.
 */
public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

    /** 보낼 행 — 아이디 오름차순(같은 키의 발행 순서). */
    List<OutboxEntity> findByPublishedAtIsNullAndAvailableAtLessThanEqualOrderByIdAsc(Instant now, Limit limit);

    /** 아직 표시되지 않았을 때만 발행 시각을 쓴다 — 락이 만료돼 두 릴레이가 겹쳐도 한 번만 표시된다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update OutboxEntity o
           set o.publishedAt = :publishedAt, o.updatedAt = :publishedAt, o.version = o.version + 1
         where o.id = :id and o.publishedAt is null
        """)
    int markPublished(Long id, Instant publishedAt);

    /** 발행 실패 — 아직 보내지 않은 행의 시도 수를 올리고 실패 코드를 남긴다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update OutboxEntity o
           set o.publishAttempts = o.publishAttempts + 1, o.lastErrorCode = :errorCode, o.updatedAt = :now, o.version = o.version + 1
         where o.id = :id and o.publishedAt is null
        """)
    int markFailed(Long id, String errorCode, Instant now);

    /** 보존 기간이 지난 발행 완료 행의 아이디 — 발행 시각 오름차순({@code idx_outbox_published_at_available_at} 순서). */
    @Query("select o.id from OutboxEntity o where o.publishedAt < :cutoff order by o.publishedAt asc, o.id asc")
    List<Long> findIdsPublishedBefore(Instant cutoff, Limit limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from OutboxEntity o where o.id in :ids")
    int deleteByIdIn(Collection<Long> ids);

    /** 미발행 행 수 — 지표. */
    long countByPublishedAtIsNull();

    /** 가장 오래된 미발행 행의 발행 가능 시각 — 지표. 없으면 null. */
    @Query("select min(o.availableAt) from OutboxEntity o where o.publishedAt is null")
    Instant findOldestUnpublishedAvailableAt();
}
