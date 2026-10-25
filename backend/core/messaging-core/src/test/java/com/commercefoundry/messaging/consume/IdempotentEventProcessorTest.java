package com.commercefoundry.messaging.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.messaging.MessagingException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.support.InMemoryProcessedEventStore;
import com.commercefoundry.messaging.support.NoOpTransactionManager;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 멱등 컨슈머 — 같은 eventId 는 한 번만 처리하고, 처리가 실패하면 기록도 롤백돼 다시 받으면 처리한다. */
class IdempotentEventProcessorTest {

    private static final String GROUP = "commerce-foundry.test.messaging-test.sample";

    private final InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
    private final TransactionTemplate transaction = new TransactionTemplate(new NoOpTransactionManager());
    private final IdempotentEventProcessor processor = new IdempotentEventProcessor(() -> store, transaction, Clock.systemUTC());

    private static EventEnvelope envelope() {
        return new EventEnvelope(UUID.randomUUID(), "sample-created", 1, Instant.now(), "messaging-test", "sample", "42",
            JsonMapper.builder().build().createObjectNode());
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 받아도 핸들러는 한 번만 돈다")
    void processesOnce() {
        EventEnvelope envelope = envelope();
        AtomicInteger handled = new AtomicInteger();

        assertThat(processor.process(GROUP, envelope, handled::incrementAndGet)).isTrue();
        assertThat(processor.process(GROUP, envelope, handled::incrementAndGet)).isFalse();

        assertThat(handled).hasValue(1);
        assertThat(processor.isProcessed(GROUP, envelope.eventId())).isTrue();
    }

    @Test
    @DisplayName("처리가 실패하면 기록도 롤백된다 — 재전달 때 다시 처리한다")
    void failureRollsBackRecord() {
        EventEnvelope envelope = envelope();
        AtomicInteger handled = new AtomicInteger();

        assertThatThrownBy(() -> processor.process(GROUP, envelope, () -> {
            throw new IllegalStateException("downstream 503");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(processor.isProcessed(GROUP, envelope.eventId())).isFalse();

        assertThat(processor.process(GROUP, envelope, handled::incrementAndGet)).isTrue();
        assertThat(handled).hasValue(1);
    }

    @Test
    @DisplayName("그룹이 다르면 따로 처리한다")
    void perGroup() {
        EventEnvelope envelope = envelope();

        assertThat(processor.process(GROUP, envelope, () -> { })).isTrue();
        assertThat(processor.process(GROUP + "-other", envelope, () -> { })).isTrue();
    }

    @Test
    @DisplayName("직접 기록(트랜잭션을 나누는 컨슈머)은 트랜잭션 안에서만 된다")
    void recordRequiresTransaction() {
        EventEnvelope envelope = envelope();

        assertThatThrownBy(() -> processor.record(GROUP, envelope))
            .isInstanceOfSatisfying(MessagingException.class, exception -> assertThat(exception.getReason()).isEqualTo(MessagingException.Reason.NO_TRANSACTION));
        transaction.executeWithoutResult(status -> processor.record(GROUP, envelope));
        assertThat(processor.isProcessed(GROUP, envelope.eventId())).isTrue();
    }
}
