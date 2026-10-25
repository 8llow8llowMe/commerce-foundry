package com.commercefoundry.messaging.support;

import com.commercefoundry.messaging.consume.ProcessedEventStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 테스트용 처리 기록(inbox) — 실제 테이블처럼 기록이 트랜잭션 롤백과 함께 사라지고, 같은 키를 두 번 넣으면 실패한다(유니크 키).
 *
 * <p>동시성 · 예외 타입은 흉내 내지 않는다 — 기록이 커밋 전에 바로 보이고 중복은 즉시 {@code IllegalStateException} 이다
 * (실제 DB 는 커밋 전 행이 남에게 보이지 않고, 중복은 flush · 커밋 때 {@code DataIntegrityViolationException} 이다).
 * 동시 처리 · 예외 타입은 서비스 어댑터 통합 테스트에서 본다.
 */
public class InMemoryProcessedEventStore implements ProcessedEventStore {

    private final Map<String, Instant> processed = new ConcurrentHashMap<>();

    @Override
    public boolean exists(String consumerGroup, UUID eventId) {
        return processed.containsKey(key(consumerGroup, eventId));
    }

    @Override
    public void record(String consumerGroup, UUID eventId, String eventType, Instant processedAt) {
        String key = key(consumerGroup, eventId);
        if (processed.putIfAbsent(key, processedAt) != null) {
            throw new IllegalStateException("uk_processed_event 위반");
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        processed.remove(key);
                    }
                }
            });
        }
    }

    @Override
    public int deleteProcessedBefore(Instant cutoff, int limit) {
        List<String> keys = processed.entrySet().stream().filter(entry -> entry.getValue().isBefore(cutoff)).map(Map.Entry::getKey).limit(limit).toList();
        keys.forEach(processed::remove);
        return keys.size();
    }

    public int size() {
        return processed.size();
    }

    private static String key(String consumerGroup, UUID eventId) {
        return consumerGroup + "|" + eventId;
    }
}
