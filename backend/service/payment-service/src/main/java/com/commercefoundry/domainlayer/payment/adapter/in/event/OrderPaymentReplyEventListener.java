package com.commercefoundry.domainlayer.payment.adapter.in.event;

import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand.Kind;
import com.commercefoundry.domainlayer.payment.application.exception.OrderPaymentReplyException;
import com.commercefoundry.domainlayer.payment.application.port.in.OrderPaymentReplyEventUseCase;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 회신은 알림이며 기존 UseCase가 원천/로컬을 재검증한다. 정상 반환 뒤 공용 RECORD ACK가 커밋된다.
 * 보호 WIP 4dae63ad의 listener hunk를 현재 handle/실패 계약에 맞춰 선택 인수했다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"commerce.messaging.enabled", "payment.order-result.consumer.enabled"}, havingValue = "true")
public class OrderPaymentReplyEventListener {
    private static final JsonMapper WIRE_MAPPER = JsonMapper.builder().build();
    private final EventEnvelopeCodec eventEnvelopeCodec;
    private final OrderPaymentReplyEventUseCase orderPaymentReplyEventUseCase;
    private final TopicNames topicNames;

    @KafkaListener(id = "order-payment-result",
        topics = {"#{@topicNames.topic('order', 'order-paid')}", "#{@topicNames.topic('order', 'order-payment-rejected')}"},
        groupId = "#{@topicNames.consumerGroup('order-payment-result')}")
    public void onOrderPaymentReply(ConsumerRecord<String, String> record) {
        OrderPaymentReplyCommand command;
        try {
            JsonNode wire = WIRE_MAPPER.readTree(record.value());
            String eventId = text(wire, "eventId");
            text(wire, "aggregateId"); // Jackson의 숫자→문자열 coercion으로 wire 계약을 우회하지 못하게 한다.
            if (!eventId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                || wire.get("schemaVersion") == null || !wire.get("schemaVersion").isIntegralNumber()) {
                throw new IllegalArgumentException();
            }
            EventEnvelope envelope = eventEnvelopeCodec.decode(record.value());
            Kind kind = envelope == null ? null : kindOf(envelope.eventType());
            if (kind == null || !topicNames.topic("order", kind.eventType()).equals(record.topic())
                || !"order".equals(envelope.producer()) || !"order".equals(envelope.aggregateType()) || envelope.schemaVersion() != 1) {
                throw new IllegalArgumentException();
            }
            JsonNode payload = envelope.payload();
            String orderId = text(payload, "orderId");
            if (!orderId.equals(envelope.aggregateId()) || !orderId.equals(record.key())) {
                throw new IllegalArgumentException();
            }
            String reason = null;
            if (kind == Kind.PAID) {
                id(text(payload, "stockReservationId"));
                java.time.Instant.parse(text(payload, "paidAt")); // 주문 처리 시각이며 PG 승인 시각과 비교하지 않는다.
            } else {
                reason = text(payload, "reason");
            }
            command = new OrderPaymentReplyCommand(envelope.eventId(), kind, id(text(payload, "paymentId")), id(orderId), reason);
        } catch (RuntimeException failure) {
            // 역직렬화/입력 예외의 원문·타입·cause를 Kafka 오류 처리기에 넘기지 않는다.
            throw new NonRetryableEventException("invalid order payment reply");
        }
        try {
            orderPaymentReplyEventUseCase.handle(command);
        } catch (OrderPaymentReplyException failure) {
            if (!failure.getReason().retryable()) {
                throw new NonRetryableEventException("order payment reply rejected: " + failure.getReason().name());
            }
            throw failure; // C3b 예외는 고정 enum만 포함하고 원문/cause가 없다.
        } catch (RuntimeException failure) {
            throw new OrderPaymentReplyException(OrderPaymentReplyException.Reason.STORAGE_UNAVAILABLE);
        }
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException();
        }
        return value.asString();
    }

    private static long id(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException();
        }
        return Long.parseLong(value);
    }

    private static Kind kindOf(String eventType) {
        for (Kind kind : Kind.values()) {
            if (kind.eventType().equals(eventType)) {
                return kind;
            }
        }
        return null;
    }
}
