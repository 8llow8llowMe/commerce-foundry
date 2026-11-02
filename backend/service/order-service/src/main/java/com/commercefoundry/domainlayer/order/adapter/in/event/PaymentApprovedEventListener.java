package com.commercefoundry.domainlayer.order.adapter.in.event;

import com.commercefoundry.domainlayer.order.application.command.PaymentApprovalCommand;
import com.commercefoundry.domainlayer.order.application.port.in.OrderPaymentEventUseCase;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** C의 복원 복구 검증 전에는 전용 gate를 열지 않는다. 원문/원인 메시지는 예외에 싣지 않는다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"commerce.messaging.enabled", "order.payment-approval.consumer.enabled"}, havingValue = "true")
public class PaymentApprovedEventListener {

    private static final Set<String> METHODS = Set.of("CARD", "EASY_PAY", "TRANSFER", "VIRTUAL_ACCOUNT", "MOBILE_PHONE", "GIFT_CERTIFICATE", "OTHER");
    private final EventEnvelopeCodec eventEnvelopeCodec;
    private final OrderPaymentEventUseCase orderPaymentEventUseCase;

    @KafkaListener(id = "payment-approved", topics = "#{@topicNames.topic('payment', 'payment-approved')}",
        groupId = "#{@topicNames.consumerGroup('payment-approved')}")
    public void onPaymentApproved(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = eventEnvelopeCodec.decode(record.value());
        if (envelope == null) {
            throw new NonRetryableEventException("missing payment approval envelope");
        }
        eventEnvelopeCodec.requireVersion(envelope, 1);
        if (!"payment-approved".equals(envelope.eventType()) || !"payment".equals(envelope.producer()) || !"payment".equals(envelope.aggregateType())) {
            throw new NonRetryableEventException("unexpected payment approval envelope");
        }
        PaymentApprovedMessage message = eventEnvelopeCodec.payload(envelope, PaymentApprovedMessage.class);
        PaymentApprovalCommand command;
        try {
            if (message == null || message.amount() <= 0 || message.approvedAt() == null || !METHODS.contains(message.method())
                || !envelope.aggregateId().equals(message.orderId()) || !envelope.aggregateId().equals(record.key())) {
                throw new IllegalArgumentException();
            }
            command = new PaymentApprovalCommand(envelope.eventId(), id(message.paymentId()), id(message.orderId()), id(message.memberId()),
                Money.of(message.amount(), CurrencyCode.valueOf(message.currency())), message.approvedAt(), message.method());
        } catch (RuntimeException exception) {
            throw new NonRetryableEventException("invalid payment approval payload");
        }
        orderPaymentEventUseCase.approve(command);
    }

    private static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException();
        }
        return Long.parseLong(value);
    }

    public record PaymentApprovedMessage(String paymentId, String orderId, String memberId, long amount, String currency, Instant approvedAt, String method) {
    }
}
