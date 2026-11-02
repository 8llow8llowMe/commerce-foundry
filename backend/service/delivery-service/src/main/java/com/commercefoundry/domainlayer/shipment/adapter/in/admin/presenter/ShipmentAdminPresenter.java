package com.commercefoundry.domainlayer.shipment.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item.ShipmentRegisterResultAdminItem;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentRegisterAdminResponse;
import com.commercefoundry.domainlayer.shipment.application.info.ShipmentRegisterResultInfo;
import com.commercefoundry.domainlayer.shipment.application.info.ShipmentRegisterResultInfo.Outcome;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ShipmentAdminPresenter {

    /** 순서는 그대로 둔다(요청 순서). 아이디는 문자열이다. */
    public ShipmentRegisterAdminResponse toRegisterResponse(List<ShipmentRegisterResultInfo> results) {
        return new ShipmentRegisterAdminResponse(
            count(results, Outcome.REGISTERED), count(results, Outcome.ALREADY_REGISTERED), count(results, Outcome.FAILED),
            results.stream().map(ShipmentAdminPresenter::toItem).toList()
        );
    }

    private static int count(List<ShipmentRegisterResultInfo> results, Outcome outcome) {
        return (int) results.stream().filter(result -> result.outcome() == outcome).count();
    }

    private static ShipmentRegisterResultAdminItem toItem(ShipmentRegisterResultInfo result) {
        return ShipmentRegisterResultAdminItem.builder()
            .orderId(String.valueOf(result.orderId()))
            .deliveryId(result.shipmentId() == null ? null : String.valueOf(result.shipmentId()))
            .result(result.outcome().name())
            .errorCode(result.errorCode())
            .message(result.message())
            .build();
    }
}
