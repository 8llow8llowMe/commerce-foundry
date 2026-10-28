package com.commercefoundry.global.messaging;

import com.commercefoundry.messaging.consume.ProcessedEventStore;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * messaging-core {@link ProcessedEventStore} 의 product 스키마 구현 — product-service 가 inbox 의 첫 구현처다.
 *
 * <ul>
 *   <li>{@link #record} 는 업무 트랜잭션 안에서만 부른다({@code MANDATORY} — 트랜잭션 밖이면 실패). 처리 기록이 업무와 다른 커밋이면 중복을 막지 못한다.
 *       INSERT 만 한다 — 같은 키가 동시에 들어오면 flush · 커밋 때 유니크 키 위반으로 그 트랜잭션이 롤백되고, 재전달에서 {@link #exists} 로 걸러진다.</li>
 *   <li>{@link #exists} 는 업무 트랜잭션이 있으면 합류하고, 없으면 읽기 전용 트랜잭션을 연다 — 트랜잭션을 나누는 컨슈머가 처리 전에 거르는
 *       {@code IdempotentEventProcessor#isProcessed} 는 트랜잭션 밖에서 부른다.</li>
 *   <li>{@link #deleteProcessedBefore} 는 정리 작업이 트랜잭션 밖에서 부르므로 자기 트랜잭션을 연다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class JpaProcessedEventStore implements ProcessedEventStore {

    private final ProcessedEventRepository processedEventRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    @Transactional(readOnly = true)
    public boolean exists(String consumerGroup, UUID eventId) {
        return processedEventRepository.existsByConsumerGroupAndEventId(consumerGroup, eventId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String consumerGroup, UUID eventId, String eventType, Instant processedAt) {
        processedEventRepository.save(ProcessedEventEntity.builder()
            .id(snowflakeIdGenerator.generateId())
            .consumerGroup(consumerGroup)
            .eventId(eventId)
            .eventType(eventType)
            .processedAt(processedAt)
            .build());
    }

    @Override
    @Transactional
    public int deleteProcessedBefore(Instant cutoff, int limit) {
        List<Long> ids = processedEventRepository.findIdsProcessedBefore(cutoff, Limit.of(limit));
        return ids.isEmpty() ? 0 : processedEventRepository.deleteByIdIn(ids);
    }
}
