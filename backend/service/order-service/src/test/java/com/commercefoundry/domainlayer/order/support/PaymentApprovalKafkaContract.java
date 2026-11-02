package com.commercefoundry.domainlayer.order.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.adapter.in.event.PaymentApprovedEventListener;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.in.OrderPaymentEventUseCase;
import com.commercefoundry.messaging.consume.MessagingErrorHandlers;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.properties.MessagingProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;

/** 실제 브로커의 listener→error handler→재전달/DLT 계약. 업무 DB 원자성은 별도 persistence 계약에서 검증한다. */
public abstract class PaymentApprovalKafkaContract {
    protected abstract String bootstrapServers();

    @Test
    void transientStockFailureRetriesAndRecovers() throws Exception {
        run(false, false);
    }

    @Test
    void exhaustedStockFailureGoesToDlt() throws Exception {
        run(true, false);
    }

    @Test
    void permanentFailureGoesDirectlyToDltWithoutPrivateExceptionHeaders() throws Exception {
        run(true, true);
    }

    private void run(boolean exhausted, boolean permanent) throws Exception {
        String topic = "commerce-foundry.test.payment.payment-approved-" + UUID.randomUUID();
        String dlt = topic + ".dlt";
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1), new NewTopic(dlt, 1, (short) 1))).all().get();
        }
        var producers = new DefaultKafkaProducerFactory<String, String>(Map.of("bootstrap.servers", bootstrapServers()),
            new StringSerializer(), new StringSerializer());
        var template = new KafkaTemplate<>(producers);
        Map<String, Object> consumerProperties = Map.of("bootstrap.servers", bootstrapServers(), "group.id", UUID.randomUUID().toString(),
            "auto.offset.reset", "earliest", "enable.auto.commit", false, "max.poll.records", 1);
        var factory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties, new StringDeserializer(), new StringDeserializer());
        var properties = new ContainerProperties(topic);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        OrderPaymentEventUseCase useCase = mock(OrderPaymentEventUseCase.class);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            int count = calls.incrementAndGet();
            if (permanent) {
                throw new NonRetryableEventException("PRIVATE_REMOTE_SENTINEL");
            }
            if (exhausted || count < 3) {
                throw new OrderException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE, new IllegalStateException("PRIVATE_REMOTE_SENTINEL"));
            }
            return null;
        }).when(useCase).approve(any());
        var codec = new EventEnvelopeCodec(65536);
        var listener = new PaymentApprovedEventListener(codec, useCase);
        properties.setMessageListener((MessageListener<String, String>) listener::onPaymentApproved);
        var container = new ConcurrentMessageListenerContainer<>(factory, properties);
        container.setCommonErrorHandler(MessagingErrorHandlers.create(template,
            new MessagingProperties.Retry(3, Duration.ofMillis(50), 2, Duration.ofMillis(100)), null));
        container.start();
        try (var deadLetters = new KafkaConsumer<String, String>(consumerProperties, new StringDeserializer(), new StringDeserializer())) {
            deadLetters.subscribe(List.of(dlt));
            EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), "payment-approved", 1, Instant.now(), "payment", "payment", "20",
                codec.toTree(Map.of("paymentId", "10", "orderId", "20", "memberId", "30", "amount", 4000,
                    "currency", "KRW", "approvedAt", "2026-11-02T00:00:00Z", "method", "CARD")));
            template.send(topic, "20", codec.encode(envelope)).get();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            boolean received = false;
            while (System.nanoTime() < deadline) {
                var records = deadLetters.poll(Duration.ofMillis(200));
                if (!records.isEmpty()) {
                    received = true;
                    for (var record : records) {
                        assertThat(codec.decode(record.value()).eventId()).isEqualTo(envelope.eventId());
                        for (var header : record.headers()) {
                            assertThat(new String(header.value(), StandardCharsets.UTF_8)).doesNotContain("PRIVATE_REMOTE_SENTINEL");
                        }
                    }
                    break;
                }
                if (!exhausted && calls.get() >= 3) {
                    break;
                }
            }
            assertThat(received).isEqualTo(exhausted);
            assertThat(calls).hasValue(permanent ? 1 : 3);
        } finally {
            container.stop();
            producers.destroy();
        }
    }
}
