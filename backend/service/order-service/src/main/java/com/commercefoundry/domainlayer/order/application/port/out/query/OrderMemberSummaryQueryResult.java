package com.commercefoundry.domainlayer.order.application.port.out.query;

import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;

/**
 * 고객 주문 내역 한 줄 (#16) — 주문 헤더 · 첫 항목 상품명 · 항목 수만 투영한다. 배송지는 싣지 않는다.
 *
 * @param firstProductName 줄 번호 1의 상품명(주문 시점 스냅숏)
 * @param itemCount 주문 줄 수
 */
public record OrderMemberSummaryQueryResult(
    long orderId, OrderStatus status, long totalAmount, CurrencyCode currency, String firstProductName, int itemCount, Instant createdAt, Instant paymentDueAt
) {
}
