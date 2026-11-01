package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.domainlayer.order.application.port.out.query.OrderStatusHistoryQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.StatusChangedBy;
import java.time.Instant;

/** 주문 상태 이력 한 줄 (#17 어드민 상세). */
public record OrderStatusHistoryInfo(
    OrderStatus fromStatus, OrderStatus toStatus, StatusChangedBy changedByType, Long changedById, String reason, Instant changedAt
) {

    public static OrderStatusHistoryInfo from(OrderStatusHistoryQueryResult result) {
        return new OrderStatusHistoryInfo(result.fromStatus(), result.toStatus(), result.changedByType(), result.changedById(), result.reason(),
            result.changedAt());
    }
}
