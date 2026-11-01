package com.commercefoundry.domainlayer.payment.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.model.PayableOrder;
import com.commercefoundry.domainlayer.payment.application.port.out.OrderPaymentSummaryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentSummaryQueryResult;
import com.commercefoundry.global.properties.PaymentPrepareProperties;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 결제할 수 있는 주문 판정 — 없음 → 상태 → 통화 → 기한 순서. */
class OrderSummaryProcessorTest {

    private static final long ORDER_ID = 8_000_000_001L;
    private static final long MEMBER_ID = 7_000_000_001L;
    private static final Instant NOW = Instant.parse("2026-10-21T01:00:00Z");

    private OrderPaymentSummaryPort port;
    private OrderSummaryProcessor processor;

    @BeforeEach
    void setUp() {
        port = mock(OrderPaymentSummaryPort.class);
        processor = new OrderSummaryProcessor(port, new PaymentPrepareProperties(null), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void givenSummary(String status, Money amount, Instant dueAt) {
        given(port.find(ORDER_ID, MEMBER_ID)).willReturn(Optional.of(new OrderPaymentSummaryQueryResult(ORDER_ID, MEMBER_ID, status, amount, "주문", dueAt)));
    }

    private void assertError(PaymentErrorCode errorCode) {
        assertThatThrownBy(() -> processor.getPayableOrder(ORDER_ID, MEMBER_ID))
            .isInstanceOfSatisfying(PaymentException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    @Test
    @DisplayName("결제 대기 · KRW · 기한까지 정확히 10분이면 결제할 수 있다 (기본 min-remaining PT10M)")
    void payable() {
        givenSummary("PENDING_PAYMENT", Money.of(129_000, CurrencyCode.KRW), NOW.plus(Duration.ofMinutes(10)));

        PayableOrder order = processor.getPayableOrder(ORDER_ID, MEMBER_ID);

        assertThat(order.totalAmount()).isEqualTo(Money.of(129_000, CurrencyCode.KRW));
        assertThat(order.paymentDueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("주문 없음(소유 아님 포함) 404 PAYMENT_002")
    void notFound() {
        given(port.find(ORDER_ID, MEMBER_ID)).willReturn(Optional.empty());

        assertError(PaymentErrorCode.NOT_FOUND_ORDER);
    }

    @Test
    @DisplayName("결제 대기가 아니면 409 PAYMENT_003 — 통화 · 기한보다 먼저 본다")
    void notPayable() {
        givenSummary("PAID", Money.of(100, CurrencyCode.USD), NOW.minusSeconds(1));

        assertError(PaymentErrorCode.ORDER_NOT_PAYABLE);
    }

    @Test
    @DisplayName("KRW 가 아니면 409 PAYMENT_005 — 기한보다 먼저 본다")
    void currencyNotSupported() {
        givenSummary("PENDING_PAYMENT", Money.of(8_950, CurrencyCode.USD), NOW.minusSeconds(1));

        assertError(PaymentErrorCode.CURRENCY_NOT_SUPPORTED);
    }

    @Test
    @DisplayName("기한까지 10분 미만이거나 지났으면 409 PAYMENT_004")
    void dueTooClose() {
        givenSummary("PENDING_PAYMENT", Money.of(129_000, CurrencyCode.KRW), NOW.plus(Duration.ofMinutes(10)).minusMillis(1));
        assertError(PaymentErrorCode.PAYMENT_DUE_TOO_CLOSE);

        givenSummary("PENDING_PAYMENT", Money.of(129_000, CurrencyCode.KRW), NOW.minusSeconds(60));
        assertError(PaymentErrorCode.PAYMENT_DUE_TOO_CLOSE);
    }
}
