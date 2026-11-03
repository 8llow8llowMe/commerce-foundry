package com.commercefoundry.domainlayer.payment.adapter.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.payment.adapter.in.event.OrderPaymentReplyEventListener.OrderPaidMessage;
import com.commercefoundry.domainlayer.payment.adapter.in.event.OrderPaymentReplyEventListener.OrderPaymentRejectedMessage;
import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand;
import com.commercefoundry.domainlayer.payment.application.command.OrderPaymentReplyCommand.Kind;
import com.commercefoundry.domainlayer.payment.application.port.in.OrderPaymentReplyEventUseCase;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.EventEnvelopeException;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 봉투 · 페이로드 검증과 명령 변환 (#308 PR-C). 형식이 틀린 회신은 UseCase 를 부르지 않고 재시도 없이 DLT 로 간다. */
class OrderPaymentReplyEventListenerTest {

    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private static final String PAID_TOPIC = "commerce-foundry.test.order.order-paid";
    private static final String REJECTED_TOPIC = "commerce-foundry.test.order.order-payment-rejected";
    private static final String ORDER_ID = "9223372036854775807";
    private static final String PAYMENT_ID = "9100000001";

    private final EventEnvelopeCodec codec = new EventEnvelopeCodec(65536);
    private final OrderPaymentReplyEventUseCase useCase = mock(OrderPaymentReplyEventUseCase.class);
    private final OrderPaymentReplyEventListener listener = new OrderPaymentReplyEventListener(codec, useCase);

    @Test
    void paidReplyBecomesCommandWithLargeIds() {
        UUID eventId = UUID.randomUUID();
        listener.onOrderPaymentReply(record(PAID_TOPIC, ORDER_ID, envelope(eventId, "order-paid", "order", ORDER_ID,
            new OrderPaidMessage(ORDER_ID, PAYMENT_ID, "77", NOW))));

        verify(useCase).apply(new OrderPaymentReplyCommand(eventId, Kind.PAID, 9_100_000_001L, Long.MAX_VALUE, null));
    }

    @Test
    void rejectedReplyCarriesReason() {
        UUID eventId = UUID.randomUUID();
        listener.onOrderPaymentReply(record(REJECTED_TOPIC, ORDER_ID, envelope(eventId, "order-payment-rejected", "order", ORDER_ID,
            new OrderPaymentRejectedMessage(ORDER_ID, PAYMENT_ID, "STOCK_UNAVAILABLE"))));

        verify(useCase).apply(new OrderPaymentReplyCommand(eventId, Kind.REJECTED, 9_100_000_001L, Long.MAX_VALUE, "STOCK_UNAVAILABLE"));
    }

    static Stream<Arguments> malformedReplies() {
        Object paid = new OrderPaidMessage(ORDER_ID, PAYMENT_ID, "77", NOW);
        return Stream.of(
            Arguments.of("모르는 이벤트 종류", PAID_TOPIC, ORDER_ID, "order-expired", "order", ORDER_ID, paid),
            Arguments.of("토픽과 종류가 다름", REJECTED_TOPIC, ORDER_ID, "order-paid", "order", ORDER_ID, paid),
            Arguments.of("생산자가 order 가 아님", PAID_TOPIC, ORDER_ID, "order-paid", "payment", ORDER_ID, paid),
            Arguments.of("Kafka 키가 주문과 다름", PAID_TOPIC, "1", "order-paid", "order", ORDER_ID, paid),
            Arguments.of("aggregateId 가 주문과 다름", PAID_TOPIC, ORDER_ID, "order-paid", "order", "1", paid),
            Arguments.of("결제 아이디가 0", PAID_TOPIC, ORDER_ID, "order-paid", "order", ORDER_ID, new OrderPaidMessage(ORDER_ID, "0", "77", NOW)),
            Arguments.of("결제 아이디가 long 밖", PAID_TOPIC, ORDER_ID, "order-paid", "order", ORDER_ID,
                new OrderPaidMessage(ORDER_ID, "9223372036854775808", "77", NOW)),
            Arguments.of("선점 아이디 없음", PAID_TOPIC, ORDER_ID, "order-paid", "order", ORDER_ID, new OrderPaidMessage(ORDER_ID, PAYMENT_ID, null, NOW)),
            Arguments.of("처리 시각 없음", PAID_TOPIC, ORDER_ID, "order-paid", "order", ORDER_ID, new OrderPaidMessage(ORDER_ID, PAYMENT_ID, "77", null)),
            Arguments.of("모르는 거절 사유", REJECTED_TOPIC, ORDER_ID, "order-payment-rejected", "order", ORDER_ID,
                new OrderPaymentRejectedMessage(ORDER_ID, PAYMENT_ID, "SOMETHING_ELSE")),
            Arguments.of("거절 사유 없음", REJECTED_TOPIC, ORDER_ID, "order-payment-rejected", "order", ORDER_ID,
                new OrderPaymentRejectedMessage(ORDER_ID, PAYMENT_ID, null))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedReplies")
    void malformedReplyGoesToDltWithoutCallingUseCase(String label, String topic, String key, String eventType, String producer, String aggregateId,
        Object payload) {
        ConsumerRecord<String, String> record = record(topic, key, envelope(UUID.randomUUID(), eventType, producer, aggregateId, payload));

        assertThatThrownBy(() -> listener.onOrderPaymentReply(record)).isInstanceOf(NonRetryableEventException.class)
            .hasMessageNotContaining(PAYMENT_ID);
        verifyNoInteractions(useCase);
    }

    @Test
    void forgedEventTypeIsRejectedBeforeVersionCheckWithoutEchoingIt() {
        String forged = "FORGED_TYPE_PRIVATE_SENTINEL";
        String value = codec.encode(new EventEnvelope(UUID.randomUUID(), forged, 2, NOW, "order", "order", ORDER_ID,
            codec.toTree(new OrderPaidMessage(ORDER_ID, PAYMENT_ID, "77", NOW))));

        assertThatThrownBy(() -> listener.onOrderPaymentReply(record(PAID_TOPIC, ORDER_ID, value)))
            .isInstanceOf(NonRetryableEventException.class).hasMessageNotContaining(forged);
        verifyNoInteractions(useCase);
    }

    @Test
    void unreadableEnvelopeIsNotRetried() {
        assertThatThrownBy(() -> listener.onOrderPaymentReply(new ConsumerRecord<>(PAID_TOPIC, 0, 0, ORDER_ID, "{not-json")))
            .isInstanceOfAny(EventEnvelopeException.class, NonRetryableEventException.class);
        verifyNoInteractions(useCase);
    }

    @Test
    void messageTypesMatchOrderEventAdapterContract() {
        assertThat(OrderPaidMessage.class.getRecordComponents()).extracting(component -> component.getName())
            .containsExactly("orderId", "paymentId", "stockReservationId", "paidAt");
        assertThat(OrderPaymentRejectedMessage.class.getRecordComponents()).extracting(component -> component.getName())
            .containsExactly("orderId", "paymentId", "reason");
    }

    private String envelope(UUID eventId, String eventType, String producer, String aggregateId, Object payload) {
        return codec.encode(new EventEnvelope(eventId, eventType, 1, NOW, producer, "order", aggregateId, codec.toTree(payload)));
    }

    private static ConsumerRecord<String, String> record(String topic, String key, String value) {
        return new ConsumerRecord<>(topic, 0, 0, key, value);
    }
}
