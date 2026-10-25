package com.commercefoundry.messaging.support;

import com.commercefoundry.messaging.outbox.OutboxRow;
import com.commercefoundry.messaging.outbox.OutboxStore;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 테스트용 outbox 저장소 — 실제 구현과 같은 계약을 흉내 낸다. {@link #append} 는 트랜잭션이 커밋될 때 보이고 롤백되면 사라진다
 * (실제 테이블이 업무 트랜잭션과 같은 커밋인 것과 같다). 서비스 단위 테스트가 "발행 요청" 을 단언할 때도 쓴다.
 */
public class InMemoryOutboxStore implements OutboxStore {

    private final AtomicLong sequence = new AtomicLong(1);
    private final Map<Long, Entry> rows = new ConcurrentHashMap<>();

    @Override
    public void append(OutboxRow row) {
        Entry entry = new Entry(withId(row, sequence.getAndIncrement()));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    rows.put(entry.row.id(), entry);
                }
            });
        } else {
            rows.put(entry.row.id(), entry);
        }
    }

    @Override
    public synchronized List<OutboxRow> findPublishable(Instant now, int limit) {
        return rows.values().stream()
            .filter(entry -> entry.publishedAt == null && !entry.row.availableAt().isAfter(now))
            .map(Entry::current)
            .sorted(Comparator.comparingLong(OutboxRow::id))
            .limit(limit)
            .toList();
    }

    @Override
    public synchronized boolean markPublished(long id, Instant publishedAt) {
        Entry entry = rows.get(id);
        if (entry == null || entry.publishedAt != null) {
            return false;
        }
        entry.publishedAt = publishedAt;
        return true;
    }

    @Override
    public synchronized void markFailed(long id, String errorCode) {
        Entry entry = rows.get(id);
        if (entry != null) {
            entry.attempts++;
            entry.lastErrorCode = errorCode;
        }
    }

    @Override
    public synchronized int deletePublishedBefore(Instant cutoff, int limit) {
        List<Long> ids = rows.values().stream()
            .filter(entry -> entry.publishedAt != null && entry.publishedAt.isBefore(cutoff))
            .map(entry -> entry.row.id())
            .sorted()
            .limit(limit)
            .toList();
        ids.forEach(rows::remove);
        return ids.size();
    }

    /** 커밋된 행 — 아이디 순. */
    public synchronized List<OutboxRow> rows() {
        return rows.values().stream().map(Entry::current).sorted(Comparator.comparingLong(OutboxRow::id)).toList();
    }

    public synchronized Instant publishedAt(long id) {
        return rows.get(id).publishedAt;
    }

    public synchronized String lastErrorCode(long id) {
        return rows.get(id).lastErrorCode;
    }

    /** 이미 보낸 것으로 표시한 행 — 정리 테스트용. */
    public void put(OutboxRow row, Instant publishedAt) {
        Entry entry = new Entry(withId(row, sequence.getAndIncrement()));
        entry.publishedAt = publishedAt;
        rows.put(entry.row.id(), entry);
    }

    private static OutboxRow withId(OutboxRow row, long id) {
        return new OutboxRow(id, row.aggregateType(), row.aggregateId(), row.eventType(), row.schemaVersion(), row.envelopeJson(), row.availableAt(), 0);
    }

    private static final class Entry {

        private final OutboxRow row;
        private Instant publishedAt;
        private int attempts;
        private String lastErrorCode;

        private Entry(OutboxRow row) {
            this.row = row;
        }

        private OutboxRow current() {
            return new OutboxRow(row.id(), row.aggregateType(), row.aggregateId(), row.eventType(), row.schemaVersion(), row.envelopeJson(), row.availableAt(),
                attempts);
        }
    }
}
