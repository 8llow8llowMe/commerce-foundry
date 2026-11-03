package com.commercefoundry.domainlayer.payment.adapter.in.event;

import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand.Kind;
import com.commercefoundry.domainlayer.payment.application.port.in.OrderPaymentReplyEventUseCase;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * order-service 의 결제 반영 회신 컨슈머 (#308 PR-C, {@code payment-guide.md} §6) — 그룹 {@code <ns>.payment.order-payment-result},
 * 토픽 {@code <ns>.order.order-paid} · {@code <ns>.order.order-payment-rejected}, 키는 주문 아이디다.
 *
 * <p>메시징({@code commerce.messaging.enabled})과 전용 게이트({@code payment.order-result.consumer.enabled}) 둘 다 켜졌을 때만 빈이 생긴다 — 기본 닫힘.
 * 봉투 형식이 틀리면(종류 · 생산자 · 키 · 아이디 · 사유) 다시 받아도 같으므로 {@link NonRetryableEventException}(바로 DLT)이다. 예외에 원문 · 값을 싣지 않는다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"commerce.messaging.enabled", "payment.order-result.consumer.enabled"}, havingValue = "true")
public class OrderPaymentReplyEventListener {

    private final EventEnvelopeCodec eventEnvelopeCodec;
    private final OrderPaymentReplyEventUseCase orderPaymentReplyEventUseCase;

    @KafkaListener(id = "order-payment-result",
        topics = {"#{@topicNames.topic('order', 'order-paid')}", "#{@topicNames.topic('order', 'order-payment-rejected')}"},
        groupId = "#{@topicNames.consumerGroup('order-payment-result')}")
    public void onOrderPaymentReply(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = eventEnvelopeCodec.decode(record.value());
        if (envelope == null) {
            throw new NonRetryableEventException("missing order payment reply envelope");
        }
        // 종류 · 생산자를 버전보다 먼저 본다 — 위조한 eventType 문자열이 버전 오류 메시지에 실리지 않게
        Kind kind = kindOf(envelope.eventType());
        if (kind == null || !record.topic().endsWith("." + kind.eventType()) || !"order".equals(envelope.producer())
            || !"order".equals(envelope.aggregateType())) {
            throw new NonRetryableEventException("unexpected order payment reply envelope");
        }
        eventEnvelopeCodec.requireVersion(envelope, 1);
        OrderPaymentReplyCommand command;
        try {
            command = kind == Kind.PAID ? paid(envelope, record) : rejected(envelope, record);
        } catch (RuntimeException exception) {
            throw new NonRetryableEventException("invalid order payment reply payload");
        }
        orderPaymentReplyEventUseCase.apply(command);
    }

    private OrderPaymentReplyCommand paid(EventEnvelope envelope, ConsumerRecord<String, String> record) {
        OrderPaidMessage message = eventEnvelopeCodec.payload(envelope, OrderPaidMessage.class);
        requireKey(envelope, record, message == null ? null : message.orderId());
        id(message.stockReservationId());
        if (message.paidAt() == null) {
            throw new IllegalArgumentException();
        }
        return new OrderPaymentReplyCommand(envelope.eventId(), Kind.PAID, id(message.paymentId()), id(message.orderId()), null);
    }

    private OrderPaymentReplyCommand rejected(EventEnvelope envelope, ConsumerRecord<String, String> record) {
        OrderPaymentRejectedMessage message = eventEnvelopeCodec.payload(envelope, OrderPaymentRejectedMessage.class);
        requireKey(envelope, record, message == null ? null : message.orderId());
        return new OrderPaymentReplyCommand(envelope.eventId(), Kind.REJECTED, id(message.paymentId()), id(message.orderId()), message.reason());
    }

    private static void requireKey(EventEnvelope envelope, ConsumerRecord<String, String> record, String orderId) {
        if (orderId == null || !orderId.equals(envelope.aggregateId()) || !orderId.equals(record.key())) {
            throw new IllegalArgumentException();
        }
    }

    private static Kind kindOf(String eventType) {
        for (Kind kind : Kind.values()) {
            if (kind.eventType().equals(eventType)) {
                return kind;
            }
        }
        return null;
    }

    private static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException();
        }
        return Long.parseLong(value);
    }

    /** order-service {@code OrderEventAdapter.OrderPaidMessage} v1 — 식별자는 문자열이다. {@code paidAt} 은 주문 처리 시각이라 PG 승인 시각과 비교하지 않는다. */
    public record OrderPaidMessage(String orderId, String paymentId, String stockReservationId, Instant paidAt) {
    }

    /** order-service {@code OrderEventAdapter.OrderPaymentRejectedMessage} v1. */
    public record OrderPaymentRejectedMessage(String orderId, String paymentId, String reason) {
    }
}
