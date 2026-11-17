package com.commercefoundry.domainlayer.order.application.port.out.query;

import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import java.util.List;

/** 개인정보·가격·맞춤 제작 입력을 제외한 주문 시점 상품 스냅숏이다. */
public record OrderPickingQueryResult(long orderId, OrderStatus status, String countryCode, List<Line> lines) {
    public OrderPickingQueryResult {
        lines = List.copyOf(lines);
    }

    public record Line(long productId, long skuId, long quantity, boolean customOrder) { }
}
