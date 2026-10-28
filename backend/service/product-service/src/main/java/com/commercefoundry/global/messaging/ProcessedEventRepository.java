package com.commercefoundry.global.messaging;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** 처리한 이벤트 기록 (inbox). 넣고 지우기만 한다 — 고치지 않는다. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, Long> {

    /** 유니크 키 {@code uk_processed_event_consumer_group_event_id} 로 찾는다. */
    boolean existsByConsumerGroupAndEventId(String consumerGroup, UUID eventId);

    /** 보존 기간이 지난 기록의 아이디 — 처리 시각 오름차순({@code idx_processed_event_processed_at} 순서). */
    @Query("select p.id from ProcessedEventEntity p where p.processedAt < :cutoff order by p.processedAt asc, p.id asc")
    List<Long> findIdsProcessedBefore(Instant cutoff, Limit limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProcessedEventEntity p where p.id in :ids")
    int deleteByIdIn(Collection<Long> ids);
}
