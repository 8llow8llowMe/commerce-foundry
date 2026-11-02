package com.commercefoundry.domainlayer.shipment.application.port.in;

import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentRegisterAdminResponse;
import com.commercefoundry.domainlayer.shipment.application.command.ShipmentRegisterCommand;

public interface ShipmentAdminUseCase {

    /**
     * 송장 일괄 등록(1~20건) — 주문마다 따로 등록하고 order 에 출고를 반영한다. 한 건의 실패가 다른 건을 막거나 되돌리지 않는다. 결과는 요청 순서다.
     * 업무 실패는 예외가 아니라 건별 결과({@code FAILED})다.
     */
    ShipmentRegisterAdminResponse register(ShipmentRegisterCommand command);
}
