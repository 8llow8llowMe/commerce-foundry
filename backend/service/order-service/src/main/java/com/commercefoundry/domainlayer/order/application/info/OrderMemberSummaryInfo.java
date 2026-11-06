package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.domainlayer.order.application.port.out.query.OrderMemberSummaryQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;

/**
 * 고객 주문 내역 한 줄 (#16) — Presenter 가 쓰는 값. 조회 결과({@link OrderMemberSummaryQueryResult})를 Processor 가 바꿔 넘긴다.
 *
 * @param cancelable 고객이 지금 취소를 요청할 수 있는가 — Processor 가 {@link OrderStatus#isCancelableByCustomer(boolean, boolean)} 로 판정해 채운다
 */
public record OrderMemberSummaryInfo(
    long orderId, OrderStatus status, long totalAmount, CurrencyCode currency, String firstProductName, int itemCount, Instant createdAt, Instant paymentDueAt,
    boolean cancelable
) {

    public static OrderMemberSummaryInfo from(OrderMemberSummaryQueryResult result, boolean cancelable) {
        return new OrderMemberSummaryInfo(result.orderId(), result.status(), result.totalAmount(), result.currency(), result.firstProductName(),
            result.itemCount(), result.createdAt(), result.paymentDueAt(), cancelable);
    }
}
