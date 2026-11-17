package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.info.OrderPickingInfo;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPickingReadPort;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderPickingReadProcessor {
    private final OrderPickingReadPort readPort;

    @Transactional(readOnly = true, timeout = 3)
    public OrderPickingInfo findAll(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty() || orderIds.size() > 20
            || orderIds.stream().anyMatch(id -> id == null || id <= 0) || new HashSet<>(orderIds).size() != orderIds.size()) {
            throw new OrderException(OrderErrorCode.INVALID_REQUEST);
        }
        return new OrderPickingInfo(readPort.findAll(List.copyOf(orderIds)).stream().map(order ->
            new OrderPickingInfo.Snapshot(order.orderId(), order.status(), order.countryCode(), order.lines().stream().map(line ->
                new OrderPickingInfo.Line(line.productId(), line.skuId(), line.quantity(), line.customOrder())).toList())).toList());
    }
}
