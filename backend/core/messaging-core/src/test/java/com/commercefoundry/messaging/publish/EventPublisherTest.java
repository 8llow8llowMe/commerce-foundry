package com.commercefoundry.messaging.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.messaging.MessagingException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.outbox.OutboxRow;
import com.commercefoundry.messaging.support.InMemoryOutboxStore;
import com.commercefoundry.messaging.support.NoOpTransactionManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** 발행 = 업무 트랜잭션 안의 outbox 저장 — 롤백이면 남지 않고, 커밋이면 남는다. */
class EventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");

    record SampleCreated(String sampleId) {
    }

    private final InMemoryOutboxStore store = new InMemoryOutboxStore();
    private final EventEnvelopeCodec codec = new EventEnvelopeCodec(65536);
    private final EventPublisher publisher = new EventPublisher(() -> store, codec, "messaging-test", Clock.fixed(NOW, ZoneOffset.UTC));
    private final TransactionTemplate transaction = new TransactionTemplate(new NoOpTransactionManager());

    private static DomainEvent event() {
        return new DomainEvent("sample", "42", "sample-created", 1, new SampleCreated("42"));
    }

    @Test
    @DisplayName("커밋하면 outbox 행이 남는다 — 봉투에 eventId · 종류 · 버전 · 생산자 · 키가 들어 있다")
    void commitStores() {
        UUID eventId = transaction.execute(status -> publisher.publish(event()));

        OutboxRow row = store.rows().getFirst();
        assertThat(row.aggregateId()).isEqualTo("42");
        assertThat(row.eventType()).isEqualTo("sample-created");
        assertThat(row.availableAt()).isEqualTo(NOW);
        EventEnvelope envelope = codec.decode(row.envelopeJson());
        assertThat(envelope.eventId()).isEqualTo(eventId);
        assertThat(envelope.producer()).isEqualTo("messaging-test");
        assertThat(codec.payload(envelope, SampleCreated.class)).isEqualTo(new SampleCreated("42"));
    }

    @Test
    @DisplayName("업무가 롤백되면 outbox 행도 없다 — 이벤트가 나가지 않는다")
    void rollbackLeavesNothing() {
        transaction.executeWithoutResult(status -> {
            publisher.publish(event());
            status.setRollbackOnly();
        });

        assertThat(store.rows()).isEmpty();
    }

    @Test
    @DisplayName("트랜잭션 밖에서 부르면 NO_TRANSACTION 이다 — 원자성을 코드가 강제한다")
    void requiresTransaction() {
        assertThatThrownBy(() -> publisher.publish(event()))
            .isInstanceOfSatisfying(MessagingException.class, exception -> assertThat(exception.getReason()).isEqualTo(MessagingException.Reason.NO_TRANSACTION));
    }

    @Test
    @DisplayName("읽기 전용 트랜잭션이면 READ_ONLY_TRANSACTION 이다 — flush 되지 않아 행이 조용히 사라지는 것을 막는다")
    void rejectsReadOnlyTransaction() {
        TransactionTemplate readOnly = new TransactionTemplate(new NoOpTransactionManager());
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.execute(status -> publisher.publish(event())))
            .isInstanceOfSatisfying(MessagingException.class, exception -> assertThat(exception.getReason()).isEqualTo(MessagingException.Reason.READ_ONLY_TRANSACTION));
    }

    @Test
    @DisplayName("개인정보로 보이는 필드는 저장 전에 막는다 — 메시지에는 필드 이름만")
    void rejectsForbiddenFields() {
        record Shipped(String orderId, String shippingAddress) {
        }
        DomainEvent event = new DomainEvent("order", "42", "order-shipped", 1, new Shipped("42", "서울 어딘가 MARKER"));

        assertThatThrownBy(() -> transaction.execute(status -> publisher.publish(event)))
            .isInstanceOfSatisfying(MessagingException.class, exception -> assertThat(exception.getReason()).isEqualTo(MessagingException.Reason.FORBIDDEN_FIELD))
            .message().contains("shippingAddress").doesNotContain("MARKER");
        assertThat(store.rows()).isEmpty();
    }

    @Test
    @DisplayName("toString 에 페이로드가 없다")
    void toStringHidesPayload() {
        assertThat(new DomainEvent("sample", "42", "sample-created", 1, new SampleCreated("MARKER")).toString()).doesNotContain("MARKER");
    }

    @Test
    @DisplayName("지연 발행 — 미래 시각은 그대로, 과거 시각은 지금으로")
    void availableAt() {
        transaction.executeWithoutResult(status -> {
            publisher.publish(event(), NOW.plusSeconds(60));
            publisher.publish(event(), NOW.minusSeconds(60));
        });

        assertThat(store.rows()).extracting(OutboxRow::availableAt).containsExactly(NOW.plusSeconds(60), NOW);
    }

    @Test
    @DisplayName("저장소 빈이 없으면 STORE_MISSING")
    void storeMissing() {
        EventPublisher noStore = new EventPublisher(() -> null, codec, "messaging-test", Clock.systemUTC());

        assertThatThrownBy(() -> transaction.execute(status -> noStore.publish(event())))
            .isInstanceOfSatisfying(MessagingException.class, exception -> assertThat(exception.getReason()).isEqualTo(MessagingException.Reason.STORE_MISSING));
    }
}
