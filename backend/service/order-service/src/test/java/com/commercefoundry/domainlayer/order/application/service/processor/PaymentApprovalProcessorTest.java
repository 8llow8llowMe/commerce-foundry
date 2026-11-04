package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.command.PaymentApprovalCommand;
import com.commercefoundry.domainlayer.order.application.exception.PaymentQueryException;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentQueryResult;
import com.commercefoundry.messaging.consume.NonRetryableEventException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentApprovalProcessorTest {

    private static final Instant TIME = Instant.parse("2026-11-02T01:00:00.123456789Z");
    private final PaymentQueryPort port = mock(PaymentQueryPort.class);
    private final PaymentApprovalProcessor processor = new PaymentApprovalProcessor(port);
    private final PaymentApprovalCommand command = new PaymentApprovalCommand(UUID.randomUUID(), 10, 20, 30, krw(4000), TIME, "CARD");

    @Test
    void comparesAtDatabaseMicrosecondPrecision() {
        when(port.findPayment(10)).thenReturn(Optional.of(payment("DONE", 20, 30, 4000, TIME.minusNanos(789), "CARD")));
        assertThat(processor.verify(command).done()).isTrue();
    }

    @Test
    void missingPaymentIsImmediatelyNonRetryable() {
        when(port.findPayment(10)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> processor.verify(command)).isInstanceOf(NonRetryableEventException.class);
    }

    @ParameterizedTest
    @EnumSource(PaymentQueryException.Failure.class)
    void classifiesAndDropsRemoteCause(PaymentQueryException.Failure failure) {
        when(port.findPayment(10)).thenThrow(new PaymentQueryException(failure, new IllegalStateException("REMOTE_PRIVATE_SENTINEL")));
        assertThatThrownBy(() -> processor.verify(command))
            .isInstanceOf(failure == PaymentQueryException.Failure.UNAUTHORIZED || failure == PaymentQueryException.Failure.UNAVAILABLE
                ? PaymentQueryException.class : NonRetryableEventException.class)
            .hasNoCause().hasMessageNotContaining("REMOTE_PRIVATE_SENTINEL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "member", "amount", "time", "method"})
    void notificationMismatchIsNotBusinessRejection(String field) {
        when(port.findPayment(10)).thenReturn(Optional.of(payment("DONE", field.equals("order") ? 21 : 20,
            field.equals("member") ? 31 : 30, field.equals("amount") ? 4001 : 4000,
            field.equals("time") ? TIME.plusSeconds(1) : TIME, field.equals("method") ? "OTHER" : "CARD")));
        assertThatThrownBy(() -> processor.verify(command)).isInstanceOf(NonRetryableEventException.class).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "PARTIAL_CANCELED", "UNKNOWN"})
    void distinguishesFullyCanceledFromRemainingMoney(String status) {
        when(port.findPayment(10)).thenReturn(Optional.of(payment(status, 20, 30, 4000, TIME, "CARD")));
        assertThat(processor.verify(command).fullyCanceled()).isEqualTo(status.equals("CANCELED"));
        assertThat(processor.verify(command).done()).isFalse();
    }

    private PaymentQueryResult payment(String status, long order, long member, long amount, Instant time, String method) {
        return new PaymentQueryResult(10, order, member, status, krw(amount), time, method);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"DONE,method", "CANCELED,time", "CANCELED,method",
        "PARTIAL_CANCELED,time", "PARTIAL_CANCELED,method", "CANCELED,mismatchTime", "PARTIAL_CANCELED,mismatchMethod"})
    void approvedSourcesRequireOriginalApprovalMetadata(String status, String field) {
        PaymentQueryResult source = payment(status, 20, 30, 4000,
            field.equals("time") ? null : field.equals("mismatchTime") ? TIME.plusSeconds(1) : TIME,
            field.equals("method") ? null : field.equals("mismatchMethod") ? "OTHER" : "CARD");
        when(port.findPayment(10)).thenReturn(Optional.of(source));
        assertThatThrownBy(() -> processor.verify(command)).isInstanceOf(NonRetryableEventException.class);
    }

    @Test
    void doneQueryResultRejectsMissingApprovalBeforeProcessor() {
        assertThatThrownBy(() -> payment("DONE", 20, 30, 4000, null, "CARD"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("approved payment requires approvedAt");
    }
}
