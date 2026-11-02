package com.commercefoundry.domainlayer.shipment.application.info;

import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatusHistory;
import java.util.List;
import java.util.Objects;

/**
 * 송장 상세 (#18 PR-B2) — 송장과 그 배송 상태 이력(시간순). 한 읽기 트랜잭션에서 함께 읽는다.
 */
public record ShipmentDetailInfo(Shipment shipment, List<ShipmentStatusHistory> histories) {

    public ShipmentDetailInfo {
        Objects.requireNonNull(shipment, "shipment");
        histories = List.copyOf(Objects.requireNonNull(histories, "histories"));
    }
}
