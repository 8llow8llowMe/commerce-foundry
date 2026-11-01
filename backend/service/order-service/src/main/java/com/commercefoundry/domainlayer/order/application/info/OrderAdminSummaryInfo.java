package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.domainlayer.order.application.port.out.query.OrderAdminSummaryQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;

/** 어드민 주문 목록 한 줄 (#17) — Processor 가 조회 결과를 이 값으로 바꿔 Presenter 에 넘긴다. 받는 사람은 원문이다(마스킹은 Presenter). */
public record OrderAdminSummaryInfo(
    long orderId, long memberId, OrderStatus status, long totalAmount, CurrencyCode currency, String firstProductName, int itemCount,
    String recipientName, Instant createdAt, Instant paymentDueAt
) {

    public static OrderAdminSummaryInfo from(OrderAdminSummaryQueryResult result) {
        return new OrderAdminSummaryInfo(result.orderId(), result.memberId(), result.status(), result.totalAmount(), result.currency(),
            result.firstProductName(), result.itemCount(), result.recipientName(), result.createdAt(), result.paymentDueAt());
    }

    @Override
    public String toString() {
        return "OrderAdminSummaryInfo[orderId=" + orderId + ", status=" + status + "]";
    }
}
