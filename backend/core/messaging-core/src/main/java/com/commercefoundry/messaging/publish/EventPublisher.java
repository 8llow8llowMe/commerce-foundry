package com.commercefoundry.messaging.publish;

import com.commercefoundry.messaging.MessagingException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.outbox.OutboxRow;
import com.commercefoundry.messaging.outbox.OutboxStore;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 이벤트 발행 — Kafka 로 바로 보내지 않고 업무 트랜잭션 안에서 outbox 행을 저장한다 (#304 A-3 transactional outbox).
 * 업무가 롤백되면 행도 사라져 이벤트가 나가지 않고, 커밋되면 릴레이가 반드시 한 번 이상 보낸다.
 *
 * <p>트랜잭션 밖에서 부르면 {@code NO_TRANSACTION} 이다 — 원자성을 코드가 강제한다. 메시징이 꺼져 있어도 저장한다(켜지면 쌓인 행부터 나간다).
 */
public class EventPublisher {

    private final Supplier<OutboxStore> outboxStore;
    private final EventEnvelopeCodec codec;
    private final String producer;
    private final Clock clock;

    /** @param outboxStore 이 서비스의 outbox 저장소 — 없으면 발행할 때 {@code STORE_MISSING} 이다(발행하지 않는 서비스는 빈이 없어도 뜬다) */
    public EventPublisher(Supplier<OutboxStore> outboxStore, EventEnvelopeCodec codec, String producer, Clock clock) {
        this.outboxStore = outboxStore;
        this.codec = codec;
        this.producer = producer;
        this.clock = clock;
    }

    /** 지금 보낸다. @return 봉투의 eventId */
    public UUID publish(DomainEvent event) {
        return publish(event, null);
    }

    /**
     * {@code availableAt} 부터 보낸다 — 예약 · 재시도 백오프 같은 지연 발행은 릴레이의 {@code available_at <= now} 조건이 처리한다(별도 주기 조회 없음).
     *
     * @param availableAt 없거나 과거면 지금
     * @throws MessagingException {@code NO_TRANSACTION} · {@code READ_ONLY_TRANSACTION} · {@code PAYLOAD_TOO_LARGE} · {@code FORBIDDEN_FIELD} · {@code STORE_MISSING}
     */
    public UUID publish(DomainEvent event, Instant availableAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new MessagingException(MessagingException.Reason.NO_TRANSACTION);
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            // 읽기 전용 트랜잭션은 flush 하지 않아 outbox INSERT 가 예외 없이 사라진다
            throw new MessagingException(MessagingException.Reason.READ_ONLY_TRANSACTION);
        }
        OutboxStore store = outboxStore.get();
        if (store == null) {
            throw new MessagingException(MessagingException.Reason.STORE_MISSING);
        }
        Instant now = clock.instant();
        EventEnvelope envelope = new EventEnvelope(
            UUID.randomUUID(), event.eventType(), event.schemaVersion(), now, producer, event.aggregateType(), event.aggregateId(), codec.toTree(event.payload())
        );
        Instant sendAt = availableAt == null || availableAt.isBefore(now) ? now : availableAt;
        store.append(new OutboxRow(
            null, event.aggregateType(), event.aggregateId(), event.eventType(), event.schemaVersion(), codec.encode(envelope), sendAt, 0
        ));
        return envelope.eventId();
    }
}
