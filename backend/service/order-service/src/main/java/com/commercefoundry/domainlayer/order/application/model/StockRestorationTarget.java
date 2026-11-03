package com.commercefoundry.domainlayer.order.application.model;

import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import java.util.Objects;

/**
 * 재고 복원이 남은 결제 반영 기록 (#308 PR-C1) — 확정 뒤 취소가 이긴 거절 기록({@link OrderPayment#awaitsRestoration()})과 그 주문(복원 항목은 주문 스냅숏의 SKU 별 수량 합).
 * inbox · 거절 회신은 복원 뒤에 남긴다.
 */
public record StockRestorationTarget(OrderPayment orderPayment, Order order) {

    public StockRestorationTarget {
        Objects.requireNonNull(orderPayment, "orderPayment");
        Objects.requireNonNull(order, "order");
        if (!orderPayment.awaitsRestoration() || orderPayment.orderId() != order.id()) {
            throw new IllegalArgumentException("restorable record of the same order required");
        }
    }
}
