package com.commercefoundry.domainlayer.order.application.model;

import com.commercefoundry.domainlayer.order.application.info.OrderStatusHistoryInfo;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import java.util.List;
import java.util.Set;

/**
 * 어드민 주문 상세 (#17) — 주문 · 상태 이력(오래된 것부터) · 지금 어드민이 바꿀 수 있는 다음 상태.
 */
public record OrderAdminDetail(Order order, List<OrderStatusHistoryInfo> histories, Set<OrderStatus> nextStatuses) {

    public OrderAdminDetail {
        histories = List.copyOf(histories);
        nextStatuses = Set.copyOf(nextStatuses);
    }
}
