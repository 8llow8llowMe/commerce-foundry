package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.domainlayer.order.application.port.out.query.OrderMemberSummaryQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;

/** 고객 주문 내역 한 줄 (#16) — Presenter 가 쓰는 값. 조회 결과({@link OrderMemberSummaryQueryResult})를 Processor 가 바꿔 넘긴다. */
public record OrderMemberSummaryInfo(
    long orderId, OrderStatus status, long totalAmount, CurrencyCode currency, String firstProductName, int itemCount, Instant createdAt, Instant paymentDueAt
) {

    public static OrderMemberSummaryInfo from(OrderMemberSummaryQueryResult result) {
        return new OrderMemberSummaryInfo(result.orderId(), result.status(), result.totalAmount(), result.currency(), result.firstProductName(),
            result.itemCount(), result.createdAt(), result.paymentDueAt());
    }

    /** 고객이 지금 취소할 수 있는가 — 결제 전만(#16 PR-1). 도메인 {@code Order#isCancelableByCustomer} 와 같은 규칙이다. */
    public boolean cancelable() {
        return status == OrderStatus.PENDING_PAYMENT;
    }
}
