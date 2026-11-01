package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * 결제 준비에 쓰는 주문 요약 (#14). 재고 선점 아이디는 싣지 않는다 — 결제는 선점을 직접 다루지 않는다.
 *
 * @param status 지금 상태. {@code PENDING_PAYMENT} 가 아니어도 그대로 싣는다 — 결제할 수 있는지는 payment-service 가 판정한다
 * @param totalAmount 서버가 계산해 주문에 스냅숏한 총액 (상품 합계 + 배송비)
 * @param orderName PG 주문명 — {@code OrderPaymentSummaryProcessor} 규칙
 * @param paymentDueAt 결제 기한 (재고 선점 만료 시각, UTC)
 */
public record OrderPaymentSummaryInfo(long orderId, long memberId, OrderStatus status, Money totalAmount, String orderName, Instant paymentDueAt) {

    public OrderPaymentSummaryInfo {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(totalAmount, "totalAmount");
        Objects.requireNonNull(orderName, "orderName");
        Objects.requireNonNull(paymentDueAt, "paymentDueAt");
    }
}
