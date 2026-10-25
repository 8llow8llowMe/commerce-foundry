package com.commercefoundry.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.messaging.properties.MessagingProperties;
import com.commercefoundry.messaging.support.InMemoryOutboxStore;
import com.commercefoundry.messaging.support.InMemoryProcessedEventStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;

/** 릴레이 — 아이디 순으로 키 · 헤더를 실어 보내고 표시한다. 첫 실패에서 멈춰 순서를 지키고, 정리는 보존 기간을 본다. */
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");
    private static final TopicNames TOPICS = new TopicNames("commerce-foundry.test", "messaging-test");

    private final EventEnvelopeCodec codec = new EventEnvelopeCodec(65536);
    private InMemoryOutboxStore store;
    private InMemoryProcessedEventStore processed;
    private KafkaOperations<String, String> kafka;
    private OutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = new InMemoryOutboxStore();
        processed = new InMemoryProcessedEventStore();
        kafka = mock(KafkaOperations.class);
        MessagingProperties properties = new MessagingProperties(true, "commerce-foundry.test", "messaging-test", List.of(), 65536, null, null, null);
        relay = new OutboxRelay(store, processed, kafka, new DefaultLockingTaskExecutor(configuration -> Optional.of(() -> { })),
            TOPICS, codec, properties, Clock.fixed(NOW, ZoneOffset.UTC), null);
    }

    private OutboxRow row(String aggregateId, String eventType) {
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), eventType, 1, NOW, "messaging-test", "sample", aggregateId, codec.toTree(Map.of("id", aggregateId)));
        return new OutboxRow(null, "sample", aggregateId, eventType, 1, codec.encode(envelope), NOW, 0);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    @Test
    @DisplayName("보낼 행을 아이디 순으로 보내고 표시한다 — 키는 aggregateId, 헤더에 eventId · 종류 · 버전")
    @SuppressWarnings("unchecked")
    void publishesInOrder() {
        store.append(row("1", "sample-created"));
        store.append(row("2", "sample-updated"));
        given(kafka.send(any(ProducerRecord.class))).willReturn(ok());

        relay.relayOnce();

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(ProducerRecord::key).containsExactly("1", "2");
        assertThat(sent.getAllValues().getFirst().topic()).isEqualTo("commerce-foundry.test.messaging-test.sample-created");
        assertThat(new String(sent.getAllValues().getFirst().headers().lastHeader("cf-event-type").value(), StandardCharsets.UTF_8)).isEqualTo("sample-created");
        assertThat(sent.getAllValues().getFirst().headers().lastHeader("cf-event-id")).isNotNull();
        assertThat(store.rows()).allSatisfy(row -> assertThat(store.publishedAt(row.id())).isEqualTo(NOW));
    }

    @Test
    @DisplayName("첫 실패에서 그 주기를 멈춘다 — 뒤 행을 먼저 보내지 않고, 실패 행에 시도 수 · 실패 코드(예외 타입)를 남긴다")
    @SuppressWarnings("unchecked")
    void stopsAtFirstFailure() {
        store.append(row("1", "sample-created"));
        store.append(row("1", "sample-updated"));
        given(kafka.send(any(ProducerRecord.class))).willReturn(CompletableFuture.failedFuture(new TimeoutException("broker down MARKER")));

        relay.relayOnce();

        verify(kafka, times(1)).send(any(ProducerRecord.class));
        OutboxRow first = store.rows().getFirst();
        assertThat(first.publishAttempts()).isEqualTo(1);
        assertThat(store.lastErrorCode(first.id())).isEqualTo("TimeoutException");
        assertThat(store.rows()).allSatisfy(row -> assertThat(store.publishedAt(row.id())).isNull());
    }

    @Test
    @DisplayName("실패한 키만 그 주기 동안 건너뛰고 다른 키는 계속 보낸다 — 토픽 하나가 없어도 다른 이벤트가 멈추지 않는다")
    @SuppressWarnings("unchecked")
    void failedAggregateDoesNotBlockOthers() {
        store.append(row("1", "sample-missing"));
        store.append(row("2", "sample-created"));
        store.append(row("1", "sample-created"));
        given(kafka.send(any(ProducerRecord.class))).willAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            return record.topic().endsWith("sample-missing")
                ? CompletableFuture.failedFuture(new TimeoutException("topic missing MARKER"))
                : ok();
        });

        relay.relayOnce();

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(ProducerRecord::key).containsExactly("1", "2");
        List<OutboxRow> rows = store.rows();
        assertThat(store.publishedAt(rows.get(0).id())).isNull();
        assertThat(store.publishedAt(rows.get(1).id())).isEqualTo(NOW);
        assertThat(store.publishedAt(rows.get(2).id())).as("같은 키의 뒤 행은 순서를 지켜 보내지 않는다").isNull();
    }

    @Test
    @DisplayName("예약 시각이 오지 않은 행은 보내지 않는다")
    @SuppressWarnings("unchecked")
    void skipsFutureRows() {
        OutboxRow later = row("1", "sample-created");
        store.append(new OutboxRow(null, later.aggregateType(), later.aggregateId(), later.eventType(), 1, later.envelopeJson(), NOW.plusSeconds(1), 0));

        relay.relayOnce();

        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("정리 — 보존 기간(7일)이 지난 발행 완료 행 · 처리 기록만 지운다")
    void cleanup() {
        store.put(row("old", "sample-created"), NOW.minusSeconds(8 * 24 * 3600));
        store.put(row("recent", "sample-created"), NOW.minusSeconds(3600));
        processed.record("group", UUID.randomUUID(), "sample-created", NOW.minusSeconds(8 * 24 * 3600));
        processed.record("group", UUID.randomUUID(), "sample-created", NOW);

        relay.cleanupOnce();

        assertThat(store.rows()).extracting(OutboxRow::aggregateId).containsExactly("recent");
        assertThat(processed.size()).isEqualTo(1);
    }
}
