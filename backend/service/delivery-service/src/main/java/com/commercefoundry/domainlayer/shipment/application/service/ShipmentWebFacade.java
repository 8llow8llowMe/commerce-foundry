package com.commercefoundry.domainlayer.shipment.application.service;

import com.commercefoundry.domainlayer.shipment.adapter.in.web.dto.response.ShipmentResponse;
import com.commercefoundry.domainlayer.shipment.adapter.in.web.presenter.ShipmentWebPresenter;
import com.commercefoundry.domainlayer.shipment.application.port.in.ShipmentWebUseCase;
import com.commercefoundry.domainlayer.shipment.application.service.processor.ShipmentQueryProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 고객 배송 조회 (#18 PR-B2). 원격 호출이 없다 — 소유는 송장의 회원 스냅숏으로 판정한다. 읽기 트랜잭션은 {@link ShipmentQueryProcessor} 가 연다. */
@Service
@RequiredArgsConstructor
public class ShipmentWebFacade implements ShipmentWebUseCase {

    private final ShipmentQueryProcessor shipmentQueryProcessor;
    private final ShipmentWebPresenter shipmentWebPresenter;

    @Override
    public ShipmentResponse getOrderShipment(long orderId, long memberId) {
        return shipmentQueryProcessor.findCustomerShipment(orderId, memberId).map(shipmentWebPresenter::toResponse).orElse(null);
    }
}
