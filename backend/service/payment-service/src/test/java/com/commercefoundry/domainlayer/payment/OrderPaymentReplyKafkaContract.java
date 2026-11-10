package com.commercefoundry.domainlayer.payment;

import static com.commercefoundry.domainlayer.payment.OrderPaymentReplyUnitTest.source;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.payment.adapter.in.event.OrderPaymentReplyEventListener;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentResultQueryException;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentResultQueryException.Failure;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentResultQueryResult.Result;
import com.commercefoundry.messaging.consume.MessagingErrorHandlers;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.messaging.properties.MessagingProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;

/** 실제 브로커→리스너→기존 Spring Facade/JPA→RECORD ACK. 상속된 C3b 계약도 동일 DB에서 회귀 검증한다. */
abstract class OrderPaymentReplyKafkaContract extends OrderPaymentReplyContract {
    static final String PRIVATE = "PRIVATE_KAFKA_SENTINEL";
    static final TopicNames TOPICS = new TopicNames("test", "payment");
    static final String PAID = TOPICS.topic("order", "order-paid");
    static final String REJECTED = TOPICS.topic("order", "order-payment-rejected");
    final EventEnvelopeCodec codec = new EventEnvelopeCodec(65536);
    protected abstract String bootstrapServers();

    @Test
    void committedAckBothTopicsDuplicateAndDifferentEventPreserveOneBusinessChange() throws Exception {
        try (Harness h = new Harness()) {
            h.start(h.listener::onOrderPaymentReply);
            UUID event = UUID.randomUUID();
            h.acked(h.send(PAID, event));
            assertThat(fresh().orderSynced()).isTrue();
            long version = fresh().version();
            h.acked(h.send(PAID, event));
            h.acked(h.send(PAID, UUID.randomUUID()));
            result(Result.REJECTED_ORDER_CANCELED);
            h.acked(h.send(REJECTED, UUID.randomUUID()));
            assertThat(inboxCount()).isEqualTo(3);
            assertThat(fresh().version()).isEqualTo(version);
            assertThat(fresh().orderSynced()).isTrue();
            verifyNoInteractions(policy);
        }
    }
    @Test
    void unavailableSourceRetriesWithoutAckThenRecovers() throws Exception {
        when(query.query(PAYMENT_ID)).thenThrow(new OrderPaymentResultQueryException(Failure.NOT_READY),
            new OrderPaymentResultQueryException(Failure.UNAUTHORIZED)).thenReturn(source(Result.PAID));
        try (Harness h = new Harness()) {
            h.start(h.listener::onOrderPaymentReply);
            h.acked(h.send(PAID, UUID.randomUUID()));
            assertThat(h.calls).hasValue(3);
            assertThat(fresh().orderSynced()).isTrue();
            assertThat(inboxCount()).isEqualTo(1);
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void exhaustedOrPermanentFailurePublishesDltWithSafeHeadersAndLog(boolean permanent) throws Exception {
        when(query.query(PAYMENT_ID)).thenThrow(new OrderPaymentResultQueryException(permanent ? Failure.CONTRACT_VIOLATION : Failure.UNAVAILABLE));
        Logger logger = (Logger) LoggerFactory.getLogger(MessagingErrorHandlers.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try (Harness h = new Harness(); var dlt = h.deadLetters(PAID)) {
            h.start(h.listener::onOrderPaymentReply);
            var value = h.value(PAID, UUID.randomUUID());
            var record = new ProducerRecord<>(PAID, Long.toString(ORDER_ID), value);
            record.headers().add(EventEnvelopeCodec.HEADER_EVENT_ID, PRIVATE.getBytes(StandardCharsets.UTF_8));
            var sent = h.template.send(record).get(10, TimeUnit.SECONDS).getRecordMetadata();
            var received = h.receive(dlt);
            h.acked(sent);
            assertThat(received.value()).isEqualTo(value); // DLT 원문은 권한 통제 Kafka에만 보존한다.
            assertThat(h.calls).hasValue(permanent ? 1 : 3);
            assertThat(inboxCount()).isZero();
            assertThat(fresh().orderSynced()).isFalse();
            for (var header : received.headers()) {
                assertThat(header.key()).doesNotContain("exception-message", "exception-stacktrace");
            }
            await(() -> !logs.list.isEmpty());
            assertThat(logs.list).anySatisfy(e -> {
                assertThat(e.getFormattedMessage()).contains("event sent to DLT", "eventId=invalid");
            });
            assertThat(logs.list).allSatisfy(e -> {
                assertThat(e.getFormattedMessage()).doesNotContain(PRIVATE, PAYMENT_KEY, Long.toString(PAYMENT_ID), Long.toString(ORDER_ID), "129000");
                assertThat(e.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
    @Test
    void inboxFailureRollsBackBusinessThenKafkaRedeliveryCommitsOnce() throws Exception {
        AtomicInteger inserts = new AtomicInteger();
        tx().executeWithoutResult(status -> doAnswer(call -> {
            call.callRealMethod();
            em.flush();
            if (inserts.incrementAndGet() == 1) {
                throw new IllegalStateException(PRIVATE);
            }
            return null;
        }).when(inbox).record(eq(GROUP), any(), anyString(), any()));
        try (Harness h = new Harness()) {
            h.start(record -> {
                if (h.calls.get() == 2) {
                    assertThat(fresh().orderSynced()).isFalse();
                    assertThat(fresh().version()).isZero();
                    assertThat(inboxCount()).isZero();
                }
                h.listener.onOrderPaymentReply(record);
            });
            h.acked(h.send(PAID, UUID.randomUUID()));
            assertThat(h.calls).hasValue(2);
            assertThat(inboxCount()).isEqualTo(1);
            assertThat(fresh().version()).isEqualTo(1);
        }
    }
    @Test
    void commitBeforeAckInterruptionRedeliversFromBrokerAndDoesNotRepeatHook() throws Exception {
        result(Result.REJECTED_ORDER_CANCELED);
        try (Harness h = new Harness()) {
            CountDownLatch committed = new CountDownLatch(1);
            CountDownLatch interrupt = new CountDownLatch(1);
            h.start(record -> {
                h.listener.onOrderPaymentReply(record);
                committed.countDown();
                waitFor(interrupt);
                // 정상 stop은 처리 완료 레코드를 ACK할 수 있다. Error는 listener 정상 반환 전에 소비 스레드를 중단한다.
                throw new AssertionError("simulated consumer interruption before ack");
            });
            var sent = h.send(REJECTED, UUID.randomUUID());
            waitFor(committed);
            assertThat(inboxCount()).isEqualTo(1);
            assertThat(fresh().orderSyncFailureCode()).isEqualTo("ORDER_CANCELED");
            long version = fresh().version();
            assertThat(h.offset(REJECTED)).isLessThan(sent.offset() + 1);
            interrupt.countDown();
            await(() -> !h.container.isRunning());
            h.container.stop();
            assertThat(h.offset(REJECTED)).isLessThan(sent.offset() + 1);
            h.start(h.listener::onOrderPaymentReply); // 같은 실제 group의 새 container
            h.acked(sent);
            assertThat(h.calls).hasValue(2);
            assertThat(inboxCount()).isEqualTo(1);
            assertThat(fresh().version()).isEqualTo(version);
            verify(policy).onRejected(PAYMENT_ID);
        }
    }
    @Test
    @SuppressWarnings("unchecked")
    void dltSendFailureDoesNotCommitOriginalOffsetAndRecoversAfterRedelivery() throws Exception {
        when(query.query(PAYMENT_ID)).thenThrow(new OrderPaymentResultQueryException(Failure.CONTRACT_VIOLATION));
        try (Harness h = new Harness(); var dlt = h.deadLetters(PAID)) {
            AtomicInteger publishes = new AtomicInteger();
            doAnswer(call -> publishes.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("simulated DLT failure")) : call.callRealMethod())
                .when(h.template).send(any(ProducerRecord.class));
            CountDownLatch retryEntered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            h.start(record -> {
                if (h.calls.get() == 2) { retryEntered.countDown(); waitFor(release); }
                h.listener.onOrderPaymentReply(record);
            });
            var sent = h.send(PAID, UUID.randomUUID());
            try {
                waitFor(retryEntered);
                assertThat(h.offset(PAID)).isLessThan(sent.offset() + 1);
                assertThat(inboxCount()).isZero();
            } finally { release.countDown(); }
            assertThat(h.receive(dlt).value()).isNotEmpty();
            h.acked(sent);
            assertThat(publishes).hasValue(2);
        }
    }
    static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
    static void waitFor(CountDownLatch latch) {
        try { assertThat(latch.await(25, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    final class Harness implements AutoCloseable {
        final AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers()));
        final DefaultKafkaProducerFactory<String, String> producers = new DefaultKafkaProducerFactory<>(
            Map.of("bootstrap.servers", bootstrapServers(), "acks", "all"), new StringSerializer(), new StringSerializer());
        final KafkaTemplate<String, String> template = spy(new KafkaTemplate<>(producers));
        final OrderPaymentReplyEventListener listener = new OrderPaymentReplyEventListener(codec, facade, TOPICS);
        final AtomicInteger calls = new AtomicInteger();
        ConcurrentMessageListenerContainer<String, String> container;
        Harness() throws Exception {
            var existing = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            var missing = List.of(PAID, REJECTED, PAID + ".dlt", REJECTED + ".dlt").stream().filter(t -> !existing.contains(t))
                .map(t -> new NewTopic(t, 1, (short) 1)).toList();
            if (!missing.isEmpty()) admin.createTopics(missing).all().get(10, TimeUnit.SECONDS);
        }
        void start(Consumer<ConsumerRecord<String, String>> action) {
            var properties = new ContainerProperties(PAID, REJECTED);
            properties.setAckMode(ContainerProperties.AckMode.RECORD);
            properties.setStopImmediate(true);
            properties.setSyncCommits(true);
            properties.setMessageListener((MessageListener<String, String>) record -> { calls.incrementAndGet(); action.accept(record); });
            container = new ConcurrentMessageListenerContainer<>(new DefaultKafkaConsumerFactory<>(consumerProperties(GROUP),
                new StringDeserializer(), new StringDeserializer()), properties);
            container.setCommonErrorHandler(MessagingErrorHandlers.create(template,
                new MessagingProperties.Retry(3, Duration.ofMillis(25), 2, Duration.ofMillis(50)), null));
            container.start();
            await(() -> container.getAssignedPartitions() != null && container.getAssignedPartitions().size() == 2);
        }
        Map<String, Object> consumerProperties(String group) {
            return Map.of("bootstrap.servers", bootstrapServers(), "group.id", group, "auto.offset.reset", "earliest",
                "enable.auto.commit", false, "max.poll.records", 1, "allow.auto.create.topics", false);
        }
        String value(String topic, UUID event) {
            var payload = topic.equals(PAID)
                ? Map.of("orderId", Long.toString(ORDER_ID), "paymentId", Long.toString(PAYMENT_ID), "stockReservationId", "42", "paidAt", Instant.now().toString())
                : Map.of("orderId", Long.toString(ORDER_ID), "paymentId", Long.toString(PAYMENT_ID), "reason", "ORDER_CANCELED");
            return codec.encode(new EventEnvelope(event, topic.equals(PAID) ? "order-paid" : "order-payment-rejected", 1,
                Instant.now(), "order", "order", Long.toString(ORDER_ID), codec.toTree(payload)));
        }
        RecordMetadata send(String topic, UUID event) throws Exception {
            return template.send(topic, Long.toString(ORDER_ID), value(topic, event)).get(10, TimeUnit.SECONDS).getRecordMetadata();
        }
        long offset(String topic) {
            try {
                var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
                var offset = offsets.get(new TopicPartition(topic, 0));
                return offset == null ? -1 : offset.offset();
            } catch (Exception e) { throw new AssertionError(e); }
        }
        void acked(RecordMetadata sent) { await(() -> offset(sent.topic()) >= sent.offset() + 1); }
        KafkaConsumer<String, String> deadLetters(String topic) {
            var consumer = new KafkaConsumer<>(consumerProperties(UUID.randomUUID().toString()), new StringDeserializer(), new StringDeserializer());
            var partition = new TopicPartition(topic + ".dlt", 0);
            consumer.assign(List.of(partition)); consumer.seekToEnd(List.of(partition)); consumer.position(partition);
            return consumer;
        }
        ConsumerRecord<String, String> receive(KafkaConsumer<String, String> consumer) {
            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(100));
                if (!records.isEmpty()) return records.iterator().next();
            }
            throw new AssertionError("DLT record not received");
        }
        @Override public void close() { if (container != null) container.stop(); producers.destroy(); admin.close(Duration.ofSeconds(5)); }
    }
}
