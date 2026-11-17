package com.commercefoundry.domainlayer.order.application.info;

import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import java.util.List;

public record OrderPickingInfo(List<Snapshot> orders) {
    public OrderPickingInfo {
        orders = List.copyOf(orders);
    }

    public record Snapshot(long orderId, OrderStatus status, String countryCode, List<Line> lines) {
        public Snapshot {
            lines = List.copyOf(lines);
        }
    }

    public record Line(long productId, long skuId, long quantity, boolean customOrder) { }
}
