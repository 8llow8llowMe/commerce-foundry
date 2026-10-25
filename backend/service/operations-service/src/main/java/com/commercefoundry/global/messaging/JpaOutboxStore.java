package com.commercefoundry.global.messaging;

import com.commercefoundry.messaging.outbox.OutboxRow;
import com.commercefoundry.messaging.outbox.OutboxStore;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * messaging-core {@link OutboxStore} 의 operations 스키마 구현.
 *
 * <p>{@link #append} 는 업무 트랜잭션에 합류한다({@code MANDATORY} — 트랜잭션 밖이면 실패). 나머지는 릴레이가 트랜잭션 밖에서 부르므로 메서드마다 자기 짧은
 * 트랜잭션을 연다.
 */
@Component
@RequiredArgsConstructor
public class JpaOutboxStore implements OutboxStore {

    /** {@code last_error_code} 칸 길이. */
    static final int ERROR_CODE_MAX_LENGTH = 100;

    private final OutboxRepository outboxRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OutboxRow row) {
        outboxRepository.save(OutboxEntity.builder()
            .id(snowflakeIdGenerator.generateId())
            .aggregateType(row.aggregateType())
            .aggregateId(row.aggregateId())
            .eventType(row.eventType())
            .schemaVersion(row.schemaVersion())
            .payload(row.envelopeJson())
            .availableAt(row.availableAt())
            .publishAttempts(0)
            .build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutboxRow> findPublishable(Instant now, int limit) {
        return outboxRepository.findByPublishedAtIsNullAndAvailableAtLessThanEqualOrderByIdAsc(now, Limit.of(limit)).stream()
            .map(JpaOutboxStore::toRow)
            .toList();
    }

    @Override
    @Transactional
    public boolean markPublished(long id, Instant publishedAt) {
        return outboxRepository.markPublished(id, publishedAt) == 1;
    }

    @Override
    @Transactional
    public void markFailed(long id, String errorCode) {
        String code = errorCode == null || errorCode.length() <= ERROR_CODE_MAX_LENGTH ? errorCode : errorCode.substring(0, ERROR_CODE_MAX_LENGTH);
        outboxRepository.markFailed(id, code, clock.instant());
    }

    @Override
    @Transactional
    public int deletePublishedBefore(Instant cutoff, int limit) {
        List<Long> ids = outboxRepository.findIdsPublishedBefore(cutoff, Limit.of(limit));
        return ids.isEmpty() ? 0 : outboxRepository.deleteByIdIn(ids);
    }

    private static OutboxRow toRow(OutboxEntity entity) {
        return new OutboxRow(
            entity.getId(), entity.getAggregateType(), entity.getAggregateId(), entity.getEventType(), entity.getSchemaVersion(), entity.getPayload(),
            entity.getAvailableAt(), entity.getPublishAttempts()
        );
    }
}
