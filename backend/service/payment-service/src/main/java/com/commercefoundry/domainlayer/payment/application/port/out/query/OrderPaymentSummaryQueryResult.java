package com.commercefoundry.domainlayer.payment.application.port.out.query;

import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * order-service 가 알려 준 결제할 주문의 요약 ({@code GET /internal/v1/orders/{orderId}/payment-summary}). 어댑터가 계약(아이디 일치 · 금액 양수 · 주문명 길이 ·
 * 결제 기한)을 확인한 값만 담는다. 결제할 수 있는지(상태 · 통화 · 기한)는 Processor 가 판정한다.
 *
 * @param orderId 주문 아이디 — 요청한 주문과 같다
 * @param memberId 주문한 회원 — 요청한 회원과 같다
 * @param status 주문 상태 이름(order-service {@code OrderStatus}). 결제 대기는 {@code PENDING_PAYMENT} 다 — 모르는 값도 그대로 싣고 판정은 Processor 가 한다
 * @param totalAmount 서버가 계산한 주문 총액 (0 초과) — 결제 금액이다 ({@code payment-guide.md} §3-1)
 * @param orderName PG 결제창 주문명 (1~100자)
 * @param paymentDueAt 결제 기한 (UTC) — 이 시각이 지나면 order-service 가 미결제 주문을 정리한다
 */
public record OrderPaymentSummaryQueryResult(long orderId, long memberId, String status, Money totalAmount, String orderName, Instant paymentDueAt) {

    public OrderPaymentSummaryQueryResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(totalAmount, "totalAmount");
        Objects.requireNonNull(orderName, "orderName");
        Objects.requireNonNull(paymentDueAt, "paymentDueAt");
    }
}
