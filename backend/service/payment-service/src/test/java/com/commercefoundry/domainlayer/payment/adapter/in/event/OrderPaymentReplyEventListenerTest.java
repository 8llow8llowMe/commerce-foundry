package com.commercefoundry.domainlayer.payment.adapter.in.event;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand.Kind;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException.Reason;
import com.commercefoundry.domainlayer.payment.application.port.in.OrderPaymentReplyEventUseCase;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 보호 4dae listener 시험 hunk의 명령 변환/거절 의도를 현재 handle 계약에 맞춰 다시 검증한다. */
class OrderPaymentReplyEventListenerTest {
    static final String PRIVATE = "PRIVATE_INPUT_SENTINEL";
    static final String ORDER = Long.toString(Long.MAX_VALUE);
    final EventEnvelopeCodec codec = new EventEnvelopeCodec(65536);
    final TopicNames topics = new TopicNames("test", "payment");
    final OrderPaymentReplyEventUseCase useCase = mock(OrderPaymentReplyEventUseCase.class);
    final OrderPaymentReplyEventListener listener = new OrderPaymentReplyEventListener(codec, useCase, topics);
    final UUID event = UUID.randomUUID();

    Map<String, Object> paid() {
        return new LinkedHashMap<>(Map.of("orderId", ORDER, "paymentId", "9100000001", "stockReservationId", "77", "paidAt", "2026-11-10T00:00:00Z"));
    }
    String json(String kind, Object payload) {
        return codec.encode(new EventEnvelope(event, kind, 1, Instant.now(), "order", "order", ORDER, codec.toTree(payload)));
    }
    ConsumerRecord<String, String> record(String kind, Object payload) {
        return new ConsumerRecord<>(topics.topic("order", kind), 0, 0, ORDER, json(kind, payload));
    }
    void invalid(ConsumerRecord<String, String> record) {
        assertThatThrownBy(() -> listener.onOrderPaymentReply(record)).isInstanceOf(NonRetryableEventException.class)
            .hasMessage("invalid order payment reply").hasNoCause();
        verifyNoInteractions(useCase);
    }
    @Test
    void paidCommandPreservesLargeStringIdsAndEventId() {
        listener.onOrderPaymentReply(record("order-paid", paid()));
        verify(useCase).handle(new OrderPaymentReplyCommand(event, Kind.PAID, 9100000001L, Long.MAX_VALUE, null));
    }
    @ParameterizedTest @ValueSource(strings = {"ORDER_CANCELED", "ORDER_NOT_FOUND", "PAYMENT_MISMATCH", "STOCK_UNAVAILABLE", "ALREADY_PAID", "PAYMENT_NOT_DONE"})
    void sixRejectionsMapToCurrentUseCase(String reason) {
        listener.onOrderPaymentReply(record("order-payment-rejected", Map.of("orderId", ORDER, "paymentId", "9100000001", "reason", reason)));
        verify(useCase).handle(new OrderPaymentReplyCommand(event, Kind.REJECTED, 9100000001L, Long.MAX_VALUE, reason));
    }
    @ParameterizedTest @ValueSource(strings = {"orderId", "paymentId", "stockReservationId", "paidAt"})
    void requiredPayloadFieldsRejectMissingAndNumericCoercion(String field) {
        var payload = paid();
        payload.remove(field);
        invalid(record("order-paid", payload));
        payload.put(field, 42);
        invalid(record("order-paid", payload));
    }
    @ParameterizedTest @ValueSource(strings = {"0", "01", "-1", " 1", "+1", "9223372036854775808", PRIVATE})
    void malformedIdentifiersArePermanentAndRedacted(String id) {
        var payload = paid();
        payload.put("paymentId", id);
        invalid(record("order-paid", payload));
    }
    @ParameterizedTest @ValueSource(strings = {"", PRIVATE})
    void invalidRejectionReasonNeverCallsUseCase(String reason) {
        invalid(record("order-payment-rejected", Map.of("orderId", ORDER, "paymentId", "9100000001", "reason", reason)));
    }
    @ParameterizedTest @ValueSource(strings = {"null", "{}", "{broken", "{\"eventId\":\"PRIVATE_INPUT_SENTINEL\"}"})
    void malformedEnvelopeDoesNotLeakSource(String value) {
        invalid(new ConsumerRecord<>(topics.topic("order", "order-paid"), 0, 0, ORDER, value));
    }
    @Test
    void validatesExactTopicKeyProducerAggregateVersionAndTimestamp() {
        var record = record("order-paid", paid());
        invalid(new ConsumerRecord<>("other." + record.topic(), 0, 0, ORDER, record.value()));
        invalid(new ConsumerRecord<>(topics.topic("order", "order-payment-rejected"), 0, 0, ORDER, record.value()));
        invalid(new ConsumerRecord<>(record.topic(), 0, 0, PRIVATE, record.value()));
        for (String field : java.util.List.of("producer", "aggregateType", "aggregateId", "eventType", "eventId", "occurredAt")) {
            ObjectNode wire = (ObjectNode) JsonMapper.builder().build().readTree(record.value());
            wire.put(field, PRIVATE);
            invalid(new ConsumerRecord<>(record.topic(), 0, 0, ORDER, wire.toString()));
        }
        invalid(new ConsumerRecord<>(record.topic(), 0, 0, ORDER, record.value().replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
        var payload = paid();
        payload.put("paidAt", PRIVATE);
        invalid(record("order-paid", payload));
    }
    @Test
    void rejectsNumericEnvelopeIdStringVersionAndShortUuidWithoutCoercion() {
        var record = record("order-paid", paid());
        ObjectNode wire = (ObjectNode) JsonMapper.builder().build().readTree(record.value());
        wire.put("aggregateId", Long.MAX_VALUE);
        invalid(new ConsumerRecord<>(record.topic(), 0, 0, ORDER, wire.toString()));
        wire = (ObjectNode) JsonMapper.builder().build().readTree(record.value());
        wire.put("schemaVersion", "1");
        invalid(new ConsumerRecord<>(record.topic(), 0, 0, ORDER, wire.toString()));
        wire = (ObjectNode) JsonMapper.builder().build().readTree(record.value());
        wire.put("eventId", "1-1-1-1-1");
        invalid(new ConsumerRecord<>(record.topic(), 0, 0, ORDER, wire.toString()));
    }
    @ParameterizedTest @EnumSource(Reason.class)
    void mapsEveryFixedFailureToBoundedRetryOrImmediateDlt(Reason reason) {
        when(useCase.handle(any())).thenThrow(new OrderPaymentReplyException(reason));
        assertThatThrownBy(() -> listener.onOrderPaymentReply(record("order-paid", paid())))
            .isInstanceOf(reason.retryable() ? OrderPaymentReplyException.class : NonRetryableEventException.class)
            .hasMessageNotContaining(ORDER).hasMessageNotContaining("9100000001").hasMessageNotContaining(PRIVATE).hasNoCause();
    }
    @Test
    void unexpectedUseCaseFailureIsSanitizedBeforeKafkaHandler() {
        when(useCase.handle(any())).thenThrow(new IllegalStateException(PRIVATE));
        assertThatThrownBy(() -> listener.onOrderPaymentReply(record("order-paid", paid())))
            .isInstanceOf(OrderPaymentReplyException.class).hasMessageContaining("STORAGE_UNAVAILABLE").hasMessageNotContaining(PRIVATE).hasNoCause();
    }
}
