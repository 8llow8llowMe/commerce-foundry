package com.commercefoundry.domainlayer.order.adapter.in.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.order.application.port.in.OrderPaymentEventUseCase;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import com.commercefoundry.messaging.event.EventEnvelope;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentApprovedEventListenerTest {

    private final EventEnvelopeCodec codec = new EventEnvelopeCodec(65536);
    private final OrderPaymentEventUseCase useCase = mock(OrderPaymentEventUseCase.class);
    private final PaymentApprovedEventListener listener = new PaymentApprovedEventListener(codec, useCase);

    @Test
    void acceptsActualV1Contract() {
        listener.onPaymentApproved(record("20", "CARD", "10"));
        verify(useCase).approve(any());
    }

    @Test
    void jsonLiteralNullIsImmediatelyNonRetryable() {
        assertThatThrownBy(() -> listener.onPaymentApproved(new ConsumerRecord<>("topic", 0, 0, "20", "null")))
            .isInstanceOf(NonRetryableEventException.class).hasNoCause();
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "9223372036854775808", "PRIVATE_SENTINEL\n"})
    void rejectsInvalidIdWithoutLeakingIt(String id) {
        assertThatThrownBy(() -> listener.onPaymentApproved(record("20", "CARD", id)))
            .isInstanceOf(NonRetryableEventException.class).hasMessageNotContaining("PRIVATE_SENTINEL").hasNoCause();
        verifyNoInteractions(useCase);
    }

    @Test
    void rejectsForeignPartitionKeyAndMethod() {
        assertThatThrownBy(() -> listener.onPaymentApproved(record("21", "CARD", "10"))).isInstanceOf(NonRetryableEventException.class);
        assertThatThrownBy(() -> listener.onPaymentApproved(record("20", "PRIVATE_SENTINEL", "10")))
            .isInstanceOf(NonRetryableEventException.class).hasMessageNotContaining("PRIVATE_SENTINEL");
        verifyNoInteractions(useCase);
    }

    private ConsumerRecord<String, String> record(String key, String method, String paymentId) {
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), "payment-approved", 1, Instant.now(), "payment", "payment", "20",
            codec.toTree(Map.of("paymentId", paymentId, "orderId", "20", "memberId", "30", "amount", 4000,
                "currency", "KRW", "approvedAt", "2026-11-02T00:00:00Z", "method", method)));
        return new ConsumerRecord<>("commerce-foundry.test.payment.payment-approved", 0, 0, key, codec.encode(envelope));
    }
}
