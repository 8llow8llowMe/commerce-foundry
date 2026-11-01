package com.commercefoundry.domainlayer.payment.application.model;

import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * 결제할 수 있다고 판정한 주문 ({@code OrderSummaryProcessor}) — 결제 대기 상태 · KRW · 기한이 넉넉하다. 금액 · 주문명은 order-service 가 정한 값이다.
 *
 * @param orderId 주문 아이디
 * @param totalAmount 결제 금액 (주문 총액)
 * @param orderName PG 결제창 주문명
 * @param paymentDueAt 주문의 결제 기한 (UTC) — 결제 준비 응답에 내려 FE 가 남은 시간을 보인다
 */
public record PayableOrder(long orderId, Money totalAmount, String orderName, Instant paymentDueAt) {

    public PayableOrder {
        Objects.requireNonNull(totalAmount, "totalAmount");
        Objects.requireNonNull(orderName, "orderName");
        Objects.requireNonNull(paymentDueAt, "paymentDueAt");
    }
}
