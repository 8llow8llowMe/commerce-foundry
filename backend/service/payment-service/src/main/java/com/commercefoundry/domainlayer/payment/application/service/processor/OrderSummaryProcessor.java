package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.model.PayableOrder;
import com.commercefoundry.domainlayer.payment.application.port.out.OrderPaymentSummaryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.OrderPaymentSummaryQueryResult;
import com.commercefoundry.global.properties.PaymentPrepareProperties;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 결제할 주문을 order-service 에서 받아 결제할 수 있는지 판정한다. 원격 호출이라 트랜잭션을 걸지 않는다 ({@code architecture-guide.md} §3 Facade 예외).
 */
@Component
@RequiredArgsConstructor
public class OrderSummaryProcessor {

    /** order-service {@code OrderStatus} 의 결제 대기 이름 — 문자열 계약이다. */
    static final String PAYABLE_ORDER_STATUS = "PENDING_PAYMENT";

    private final OrderPaymentSummaryPort orderPaymentSummaryPort;
    private final PaymentPrepareProperties paymentPrepareProperties;
    private final Clock clock;

    /**
     * 판정 순서: 주문 없음(404 {@code PAYMENT_002}) → 결제 대기가 아님(409 {@code PAYMENT_003}) → KRW 가 아님(409 {@code PAYMENT_005}) →
     * 결제 기한까지 {@code minRemaining} 보다 적게 남음(409 {@code PAYMENT_004}). 통화는 결제 시도를 만들기 전에 본다 — {@code Payment.ready} 의
     * {@link IllegalArgumentException} 이 500 으로 새지 않게.
     *
     * @throws PaymentException 위 오류, 또는 order-service 를 부르지 못했을 때 {@code PAYMENT_011}(503)
     */
    public PayableOrder getPayableOrder(long orderId, long memberId) {
        OrderPaymentSummaryQueryResult summary = orderPaymentSummaryPort.find(orderId, memberId)
            .orElseThrow(() -> new PaymentException(PaymentErrorCode.NOT_FOUND_ORDER));
        if (!PAYABLE_ORDER_STATUS.equals(summary.status())) {
            throw new PaymentException(PaymentErrorCode.ORDER_NOT_PAYABLE);
        }
        if (summary.totalAmount().currency() != CurrencyCode.KRW) {
            throw new PaymentException(PaymentErrorCode.CURRENCY_NOT_SUPPORTED);
        }
        Duration remaining = Duration.between(clock.instant(), summary.paymentDueAt());
        if (remaining.compareTo(paymentPrepareProperties.minRemaining()) < 0) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_DUE_TOO_CLOSE);
        }
        return new PayableOrder(summary.orderId(), summary.totalAmount(), summary.orderName(), summary.paymentDueAt());
    }
}
