package com.commercefoundry.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.messaging.properties.MessagingProperties;
import com.commercefoundry.messaging.support.InMemoryOutboxStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;

/** 릴레이 — 아이디 순으로 키 · 헤더를 실어 보내고 표시한다. 실패한 키만 멈춰 순서를 지킨다. 정리는 MessagingCleanupTest. */
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");
    private static final TopicNames TOPICS = new TopicNames("commerce-foundry.test", "messaging-test");

    private final EventEnvelopeCodec codec = new EventEnvelopeCodec(65536);
    private InMemoryOutboxStore store;
    private KafkaOperations<String, String> kafka;
    private OutboxRelay relay;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        store = spy(new InMemoryOutboxStore());
        kafka = mock(KafkaOperations.class);
        MessagingProperties properties = new MessagingProperties(true, "commerce-foundry.test", "messaging-test", List.of(), 65536, null, null, null);
        Clock clock = mock(Clock.class);
        given(clock.instant()).willAnswer(invocation -> now.get());
        relay = new OutboxRelay(store, kafka, new DefaultLockingTaskExecutor(configuration -> Optional.of(() -> { })),
            TOPICS, codec, properties, clock, meters);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
        meters.close();
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
    @DisplayName("1분 정체해도 ERROR 는 10회 경고 한 건, 실패 카운터는 60이고 다른 키는 계속 발행한다")
    @SuppressWarnings("unchecked")
    void throttlesFailuresWithoutDelayingOrReorderingRows() {
        store.append(row("blocked", "sample-missing"));
        store.append(row("blocked", "sample-updated"));
        given(kafka.send(any(ProducerRecord.class))).willAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            return record.key().equals("blocked") ? CompletableFuture.failedFuture(new TimeoutException("PRIVATE MARKER")) : ok();
        });

        for (int second = 0; second < 60; second++) {
            now.set(NOW.plusSeconds(second));
            store.append(row("healthy-" + second, "sample-created"));
            relay.relayOnce();
        }

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR).hasSize(1);
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE MARKER"));
        assertThat(store.rows().getFirst().publishAttempts()).isEqualTo(60);
        assertThat(store.rows().getFirst().availableAt()).isEqualTo(NOW);
        assertThat(store.rows().get(1).publishAttempts()).isZero();
        assertThat(store.rows().subList(2, 62)).allSatisfy(row -> assertThat(store.publishedAt(row.id())).isNotNull());
        assertThat(meters.get("commerce.messaging.outbox.publish.failed.total").counter().count()).isEqualTo(60);
        now.set(NOW.plusSeconds(69)); // 10회 경고 시각 + 정확히 60초
        relay.relayOnce();
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR).hasSize(2);
    }

    @Test
    @DisplayName("100 · 1000회 milestone 은 1분이 지나지 않아도 기록한다")
    @SuppressWarnings("unchecked")
    void logsFailureMilestones() {
        store.append(row("blocked", "sample-missing"));
        given(kafka.send(any(ProducerRecord.class))).willReturn(CompletableFuture.failedFuture(new TimeoutException("missing")));
        for (int attempt = 0; attempt < 1001; attempt++) {
            relay.relayOnce();
        }
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR)
            .extracting(ILoggingEvent::getFormattedMessage)
            .satisfiesExactly(message -> assertThat(message).contains("attempts=10,"),
                message -> assertThat(message).contains("attempts=100,"), message -> assertThat(message).contains("attempts=1000,"));
        assertThat(meters.get("commerce.messaging.outbox.publish.failed.total").counter().count()).isEqualTo(1001);
    }

    @Test
    @DisplayName("markFailed DB 예외로 같은 milestone 시도 수가 반복돼도 ERROR 는 1분에 한 번이다")
    @SuppressWarnings("unchecked")
    void throttlesRepeatedMilestoneWhenFailureCannotBeRecorded() {
        store.append(row("blocked", "sample-missing"));
        long id = store.rows().getFirst().id();
        for (int attempt = 0; attempt < 9; attempt++) {
            store.markFailed(id, "TimeoutException");
        }
        doThrow(new IllegalStateException("PRIVATE DB MARKER")).when(store).markFailed(id, "TimeoutException");
        given(kafka.send(any(ProducerRecord.class))).willReturn(CompletableFuture.failedFuture(new TimeoutException("missing")));

        for (int second = 0; second < 60; second++) {
            now.set(NOW.plusSeconds(second));
            relay.relayOnce();
        }

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR).hasSize(1);
        assertThat(store.rows().getFirst().publishAttempts()).isEqualTo(9);
        assertThat(meters.get("commerce.messaging.outbox.publish.failed.total").counter().count()).isEqualTo(60);
        now.set(NOW.plusSeconds(60));
        relay.relayOnce();
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR).hasSize(2);
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE DB MARKER"));
    }

    @Test
    @DisplayName("Kafka 성공 후 표시 예외는 WARN 과 별도 카운터를 남기고 다음 주기에 같은 봉투를 재발행한다")
    @SuppressWarnings("unchecked")
    void republishesAfterMarkerFailure() {
        store.append(row("1", "sample-created"));
        store.append(row("1", "sample-updated"));
        long id = store.rows().getFirst().id();
        given(kafka.send(any(ProducerRecord.class))).willReturn(ok());
        doThrow(new IllegalStateException("PRIVATE DB MARKER")).doCallRealMethod().when(store).markPublished(id, NOW);

        relay.relayOnce();
        assertThat(store.publishedAt(id)).isNull();
        assertThat(store.rows().getFirst().publishAttempts()).isZero();
        verify(kafka, times(1)).send(any(ProducerRecord.class));
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("publish marker failed").doesNotContain("PRIVATE DB MARKER");
        });
        relay.relayOnce();

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(3)).send(sent.capture());
        assertThat(sent.getAllValues().get(0).value()).isEqualTo(sent.getAllValues().get(1).value());
        assertThat(store.rows()).allSatisfy(row -> assertThat(store.publishedAt(row.id())).isEqualTo(NOW));
        assertThat(meters.get("commerce.messaging.outbox.publish.mark.failed.total").counter().count()).isEqualTo(1);
        assertThat(meters.get("commerce.messaging.outbox.published.total").counter().count()).isEqualTo(2);
        assertThat(meters.find("commerce.messaging.outbox.publish.failed.total").counter()).isNull();
    }

    @Test
    @DisplayName("나이 임계는 기본 60/300초이며 심각도 상승은 즉시, 반복과 임계 진동은 1분간 억제한다")
    void warnsOnOldestAgeWithBoundedRepeats() {
        AtomicReference<Double> age = new AtomicReference<>(59.9);
        Gauge.builder("commerce.messaging.outbox.unpublished.oldest.age", age, AtomicReference::get).register(meters);
        relay.relayOnce();
        assertThat(logs.list).isEmpty();
        age.set(60.0);
        relay.relayOnce();
        age.set(300.0);
        relay.relayOnce();
        for (double value : new double[] {0, 60, 300, Double.NaN}) {
            age.set(value);
            now.set(NOW.plusSeconds(59));
            relay.relayOnce();
        }
        assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.ERROR);
        now.set(NOW.plusSeconds(60));
        age.set(300.0);
        relay.relayOnce();
        assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.ERROR, Level.ERROR);
    }

    @Test
    @DisplayName("나이 경고는 설정한 밀리초 임계를 사용한다")
    void usesConfiguredAgeThresholds() {
        MessagingProperties.Relay settings = new MessagingProperties.Relay(true, Duration.ofSeconds(1), 100, 10,
            Duration.ofSeconds(5), Duration.ofSeconds(30), "shedlock", Duration.ofMillis(500), Duration.ofMillis(500), Duration.ofMillis(1500));
        MessagingProperties properties = new MessagingProperties(true, "commerce-foundry.test", "messaging-test", List.of(), 65536,
            new MessagingProperties.Outbox(settings, null), null, null);
        OutboxRelay configured = new OutboxRelay(store, kafka, new DefaultLockingTaskExecutor(configuration -> Optional.of(() -> { })),
            TOPICS, codec, properties, Clock.fixed(NOW, ZoneOffset.UTC), meters);
        AtomicReference<Double> age = new AtomicReference<>(0.5);
        Gauge.builder("commerce.messaging.outbox.unpublished.oldest.age", age, AtomicReference::get).register(meters);
        configured.relayOnce();
        age.set(1.5);
        configured.relayOnce();
        assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.ERROR);
    }

    @Test
    @DisplayName("게이지 조회 실패가 발행을 막지 않는다")
    @SuppressWarnings("unchecked")
    void observationFailureDoesNotBlockPublishing() {
        Gauge.builder("commerce.messaging.outbox.unpublished.oldest.age", store,
            ignored -> OutboxAgeMetrics.oldestAgeSeconds(() -> {
                throw new IllegalStateException("PRIVATE METRIC MARKER");
            }, Clock.fixed(NOW, ZoneOffset.UTC), meters)).register(meters);
        store.append(row("1", "sample-created"));
        given(kafka.send(any(ProducerRecord.class))).willReturn(ok());
        relay.relayOnce();
        assertThat(store.publishedAt(store.rows().getFirst().id())).isEqualTo(NOW);
    }

    @Test
    @DisplayName("빠른 릴레이도 락을 기본 500ms 유지한다")
    void retainsLockForMinimumDuration() {
        LockingTaskExecutor executor = mock(LockingTaskExecutor.class);
        MessagingProperties properties = new MessagingProperties(false, null, null, null, 65536, null, null, null);
        OutboxRelay lockedRelay = new OutboxRelay(store, kafka, executor, TOPICS, codec, properties, Clock.fixed(NOW, ZoneOffset.UTC), meters);
        lockedRelay.relayOnce();
        ArgumentCaptor<LockConfiguration> configuration = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(executor).executeWithLock(any(Runnable.class), configuration.capture());
        assertThat(configuration.getValue().getLockAtLeastUntil()).isEqualTo(NOW.plusMillis(500));
    }
}
