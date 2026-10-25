package com.commercefoundry.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.commercefoundry.messaging.config.MessagingConfigurer;
import com.commercefoundry.messaging.config.MessagingPropertiesConfig;
import com.commercefoundry.messaging.consume.IdempotentEventProcessor;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.publish.DomainEvent;
import com.commercefoundry.messaging.publish.EventPublisher;
import com.commercefoundry.messaging.support.InMemoryOutboxStore;
import com.commercefoundry.messaging.support.InMemoryProcessedEventStore;
import com.commercefoundry.messaging.support.NoOpTransactionManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.LockProvider;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 발행 → Kafka → 소비 왕복 (#306 완료 기준). 내장 Kafka(KRaft)로 Docker 없이 돈다. 서비스 통합 테스트는 testFixtures 의 Kafka 컨테이너를 쓴다.
 * <ul>
 *   <li>업무 트랜잭션이 롤백되면 이벤트가 나가지 않고, 커밋되면 반드시 나간다</li>
 *   <li>같은 이벤트를 두 번 받아도 한 번만 처리한다</li>
 *   <li>재시도 상한을 넘으면 {@code <topic>.dlt} 로 간다 — 헤더에 예외 원문이 없다. 읽을 수 없는 봉투는 재시도 없이 간다</li>
 * </ul>
 */
@SpringBootTest(classes = MessagingKafkaIntegrationTest.TestApplication.class, properties = {
    "spring.application.name=messaging-test",
    "spring.kafka.admin.auto-create=false",
    "commerce.messaging.enabled=true",
    "commerce.messaging.namespace=commerce-foundry.test",
    "commerce.messaging.required-topics=messaging-test.sample-created",
    "commerce.messaging.outbox.relay.interval=100ms",
    "commerce.messaging.retry.attempts=3",
    "commerce.messaging.retry.initial-backoff=10ms",
    "commerce.messaging.retry.max-backoff=50ms"
})
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers", topics = {
    MessagingKafkaIntegrationTest.CREATED, MessagingKafkaIntegrationTest.FAILED, MessagingKafkaIntegrationTest.FAILED + ".dlt",
    MessagingKafkaIntegrationTest.INVALID, MessagingKafkaIntegrationTest.INVALID + ".dlt"
})
class MessagingKafkaIntegrationTest {

    static final String CREATED = "commerce-foundry.test.messaging-test.sample-created";
    static final String FAILED = "commerce-foundry.test.messaging-test.sample-failed";
    static final String INVALID = "commerce-foundry.test.messaging-test.sample-invalid";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    record Sample(String sampleId) {
    }

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EventEnvelopeCodec codec;

    @Autowired
    private SampleListener listener;

    @Test
    @DisplayName("커밋하면 릴레이가 보내고 컨슈머가 받는다 — 롤백한 이벤트는 나가지 않는다")
    void commitPublishesRollbackDoesNot() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        UUID rolledBack = transaction.execute(status -> {
            UUID eventId = eventPublisher.publish(new DomainEvent("sample", "rollback", "sample-created", 1, new Sample("rollback")));
            status.setRollbackOnly();
            return eventId;
        });
        UUID committed = transaction.execute(status -> eventPublisher.publish(new DomainEvent("sample", "commit", "sample-created", 1, new Sample("commit"))));

        await().atMost(TIMEOUT).until(() -> listener.handled.containsKey(committed));
        assertThat(listener.handled).doesNotContainKey(rolledBack);
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 받아도 한 번만 처리한다")
    void duplicateProcessedOnce() {
        String json = codec.encode(envelope("dup", "sample-created"));
        UUID eventId = codec.decode(json).eventId();

        kafkaTemplate.send(CREATED, "dup", json);
        kafkaTemplate.send(CREATED, "dup", json);

        // 이 eventId 의 두 번째 수신까지 본 뒤 판정한다 — 다른 테스트의 수신과 섞이지 않게 eventId 별로 센다
        await().atMost(TIMEOUT).until(() -> listener.receivedCount(eventId) == 2);
        assertThat(listener.handled.get(eventId)).hasValue(1);
    }

    @Test
    @DisplayName("재시도 상한(3회)을 넘으면 <topic>.dlt 로 간다 — 봉투는 그대로, 헤더에 예외 원문이 없다")
    void exhaustedRetriesGoToDeadLetter() {
        String json = codec.encode(envelope("fail", "sample-failed"));

        kafkaTemplate.send(FAILED, "fail", json);

        await().atMost(TIMEOUT).until(() -> listener.deadLetters.containsKey(json));
        assertThat(listener.failedAttempts.get()).isEqualTo(3);
        assertThat(listener.deadLetters.get(json))
            .noneMatch(header -> header.contains("downstream unavailable"))
            .anyMatch(header -> header.contains("IllegalStateException"));
    }

    @Test
    @DisplayName("봉투를 읽을 수 없으면 재시도하지 않고 바로 <topic>.dlt 로 간다")
    void unreadableEnvelopeGoesToDeadLetterWithoutRetry() {
        kafkaTemplate.send(INVALID, "bad", "not-an-envelope");

        await().atMost(TIMEOUT).until(() -> listener.deadLetters.containsKey("not-an-envelope"));
        assertThat(listener.invalidAttempts.get()).isEqualTo(1);
    }

    private EventEnvelope envelope(String aggregateId, String eventType) {
        return new EventEnvelope(UUID.randomUUID(), eventType, 1, Instant.now(), "messaging-test", "sample", aggregateId,
            codec.toTree(new Sample(aggregateId)));
    }

    static class SampleListener {

        static final String GROUP = "commerce-foundry.test.messaging-test.sample";

        final Map<UUID, AtomicInteger> handled = new ConcurrentHashMap<>();
        final Map<UUID, AtomicInteger> received = new ConcurrentHashMap<>();
        final AtomicInteger failedAttempts = new AtomicInteger();
        final AtomicInteger invalidAttempts = new AtomicInteger();
        /** DLT 본문 → 그 레코드의 헤더 값들 */
        final Map<String, List<String>> deadLetters = new ConcurrentHashMap<>();
        private final EventEnvelopeCodec codec;
        private final IdempotentEventProcessor processor;

        SampleListener(EventEnvelopeCodec codec, IdempotentEventProcessor processor) {
            this.codec = codec;
            this.processor = processor;
        }

        @KafkaListener(topics = CREATED, groupId = GROUP)
        void onCreated(String value) {
            EventEnvelope envelope = codec.decode(value);
            received.computeIfAbsent(envelope.eventId(), id -> new AtomicInteger()).incrementAndGet();
            processor.process(GROUP, envelope, () -> handled.computeIfAbsent(envelope.eventId(), id -> new AtomicInteger()).incrementAndGet());
        }

        @KafkaListener(topics = FAILED, groupId = GROUP + "-failed")
        void onFailed(String value) {
            failedAttempts.incrementAndGet();
            throw new IllegalStateException("downstream unavailable");
        }

        @KafkaListener(topics = INVALID, groupId = GROUP + "-invalid")
        void onInvalid(String value) {
            invalidAttempts.incrementAndGet();
            codec.decode(value);
        }

        @KafkaListener(topics = {FAILED + ".dlt", INVALID + ".dlt"}, groupId = GROUP + "-dlt")
        void onDeadLetter(ConsumerRecord<String, String> record) {
            List<String> headers = new ArrayList<>();
            record.headers().forEach(header -> headers.add(new String(header.value(), StandardCharsets.UTF_8)));
            deadLetters.put(record.value(), headers);
        }

        int receivedCount(UUID eventId) {
            AtomicInteger count = received.get(eventId);
            return count == null ? 0 : count.get();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({MessagingPropertiesConfig.class, MessagingConfigurer.class})
    static class TestApplication {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoOpTransactionManager();
        }

        @Bean
        InMemoryOutboxStore outboxStore() {
            return new InMemoryOutboxStore();
        }

        @Bean
        InMemoryProcessedEventStore processedEventStore() {
            return new InMemoryProcessedEventStore();
        }

        /** 단일 실행 락 — 테스트는 인스턴스 하나라 언제나 잡힌다(서비스는 ShedLock JDBC 테이블). */
        @Bean
        LockProvider lockProvider() {
            return configuration -> Optional.of(() -> { });
        }

        @Bean
        SampleListener sampleListener(EventEnvelopeCodec codec, IdempotentEventProcessor processor) {
            return new SampleListener(codec, processor);
        }
    }
}
