package com.commercefoundry.domainlayer.order.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPickingInternalResponse;
import com.commercefoundry.domainlayer.order.application.info.OrderPickingInfo;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OrderPickingInternalPresenter {
    public List<OrderPickingInternalResponse> present(OrderPickingInfo info) {
        return info.orders().stream().map(order -> new OrderPickingInternalResponse(Long.toString(order.orderId()),
            order.status().name(), order.countryCode(), order.lines().stream().map(line ->
                new OrderPickingInternalResponse.Line(Long.toString(line.productId()), Long.toString(line.skuId()), line.quantity(), line.customOrder())).toList()))
            .toList();
    }
}
