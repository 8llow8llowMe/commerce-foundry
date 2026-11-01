package com.commercefoundry.domainlayer.order.application.port.out.query;

import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;

/**
 * 어드민 주문 목록 한 줄 (#17) — 주문 헤더 · 첫 항목 상품명 · 항목 수 · 받는 사람(원문 — 마스킹은 Presenter)만 투영한다.
 *
 * @param firstProductName 줄 번호 1의 상품명(주문 시점 스냅숏)
 * @param itemCount 주문 줄 수
 */
public record OrderAdminSummaryQueryResult(
    long orderId, long memberId, OrderStatus status, long totalAmount, CurrencyCode currency, String firstProductName, int itemCount,
    String recipientName, Instant createdAt, Instant paymentDueAt
) {

    @Override
    public String toString() {
        return "OrderAdminSummaryQueryResult[orderId=" + orderId + ", status=" + status + "]";
    }
}
