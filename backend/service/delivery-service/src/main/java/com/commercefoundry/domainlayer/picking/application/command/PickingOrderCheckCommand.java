package com.commercefoundry.domainlayer.picking.application.command;

import java.util.Map;

/** 묶음 안에서 요청받은 주문 하나와 옵션별 수량. */
public record PickingOrderCheckCommand(long orderId, Map<Long, Long> quantities, Map<String, Long> barcodeQuantities) {

    public PickingOrderCheckCommand(long orderId, Map<Long, Long> quantities) {
        this(orderId, quantities, Map.of());
    }
}
