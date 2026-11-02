package com.commercefoundry.domainlayer.shipment.application.port.in;

import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item.ShipmentSummaryAdminItem;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentAdminResponse;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentRegisterAdminResponse;
import com.commercefoundry.domainlayer.shipment.application.command.ShipmentRegisterCommand;
import com.commercefoundry.domainlayer.shipment.application.command.ShipmentStatusChangeCommand;
import com.commercefoundry.domainlayer.shipment.application.model.ShipmentStatusFilter;
import com.commercefoundry.persistence.dto.PageResponse;

public interface ShipmentAdminUseCase {

    /**
     * 송장 일괄 등록(1~20건) — 주문마다 따로 등록하고 order 에 출고를 반영한다. 한 건의 실패가 다른 건을 막거나 되돌리지 않는다. 결과는 요청 순서다.
     * 업무 실패는 예외가 아니라 건별 결과({@code FAILED})다.
     */
    ShipmentRegisterAdminResponse register(ShipmentRegisterCommand command);

    /**
     * 배송 상태 변경 (#18 PR-B2) — 배송 중은 delivery 안에서, 배송 완료는 order 에 반영한다. 같은 상태면 바꾸지 않고 지금 상세다(반영 대기 배송 완료면 다시 반영한다).
     * 업무 실패는 {@code ShipmentException} 이다.
     */
    ShipmentAdminResponse changeStatus(ShipmentStatusChangeCommand command);

    /** 송장 상세 (#18 PR-B2) — 없으면 {@code DELIVERY_023}. */
    ShipmentAdminResponse getDetail(long shipmentId);

    /** 송장 목록 (#18 PR-B2) — 최신순. 인자가 null 이면 그 조건은 걸지 않는다. */
    PageResponse<ShipmentSummaryAdminItem> search(Long orderId, ShipmentStatusFilter status, Boolean synced, int page, int size);
}
