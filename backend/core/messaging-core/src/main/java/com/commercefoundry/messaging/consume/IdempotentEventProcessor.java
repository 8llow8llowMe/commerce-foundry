package com.commercefoundry.messaging.consume;

import com.commercefoundry.messaging.MessagingException;
import com.commercefoundry.messaging.event.EventEnvelope;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 멱등 컨슈머 (#304 A-4) — 같은 이벤트를 두 번 받아도 한 번만 처리한다. 처리 기록(inbox)을 상태를 바꾸는 트랜잭션과 같은 커밋으로 남기고,
 * Kafka 오프셋은 DB 커밋 뒤 커밋된다(컨테이너 ack 모드 RECORD) — 최소 한 번 전달 + 기록으로 한 번 처리.
 *
 * <p>두 가지로 쓴다.
 * <ul>
 *   <li>{@link #process} — 처리 기록과 핸들러를 한 트랜잭션으로 감싼다(호출자 트랜잭션이 있으면 합류 — 리스너는 보통 없다).
 *       이미 처리했으면 핸들러를 부르지 않는다.</li>
 *   <li>{@link #isProcessed} · {@link #record} — 핸들러가 원격 호출(Feign)을 섞어 트랜잭션을 나눠야 할 때, 리스너가 먼저 거르고
 *       상태를 쓰는 Processor 트랜잭션 안에서 {@link #record} 한다.</li>
 * </ul>
 * 상태 전이 멱등(이미 PAID 면 아무것도 안 함)이 2차 방어다. 로그에는 eventId · 종류만 남긴다.
 */
@Slf4j
public class IdempotentEventProcessor {

    private final Supplier<ProcessedEventStore> processedEventStore;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public IdempotentEventProcessor(Supplier<ProcessedEventStore> processedEventStore, TransactionTemplate transactionTemplate, Clock clock) {
        this.processedEventStore = processedEventStore;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 처리 기록과 핸들러를 한 트랜잭션으로 묶는다. 핸들러가 예외를 던지면 기록도 롤백돼 재전달 때 다시 처리한다.
     *
     * @return 이번에 처리했으면 true, 이미 처리한 이벤트라 건너뛰었으면 false
     */
    public boolean process(String consumerGroup, EventEnvelope envelope, Runnable handler) {
        Boolean processed = transactionTemplate.execute(status -> {
            ProcessedEventStore store = store();
            if (store.exists(consumerGroup, envelope.eventId())) {
                log.debug("duplicate event skipped. group={}, eventId={}, eventType={}", consumerGroup, envelope.eventId(), envelope.eventType());
                return false;
            }
            store.record(consumerGroup, envelope.eventId(), envelope.eventType(), clock.instant());
            handler.run();
            return true;
        });
        return Boolean.TRUE.equals(processed);
    }

    /** 이미 처리했는가 — 트랜잭션을 나누는 컨슈머가 처리 전에 거른다. */
    public boolean isProcessed(String consumerGroup, UUID eventId) {
        return store().exists(consumerGroup, eventId);
    }

    /**
     * 처리 기록을 남긴다 — 상태를 바꾸는 트랜잭션 안에서 불러야 한다.
     *
     * @throws MessagingException {@code NO_TRANSACTION}
     */
    public void record(String consumerGroup, EventEnvelope envelope) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new MessagingException(MessagingException.Reason.NO_TRANSACTION);
        }
        store().record(consumerGroup, envelope.eventId(), envelope.eventType(), clock.instant());
    }

    private ProcessedEventStore store() {
        ProcessedEventStore store = processedEventStore.get();
        if (store == null) {
            throw new MessagingException(MessagingException.Reason.STORE_MISSING);
        }
        return store;
    }
}
