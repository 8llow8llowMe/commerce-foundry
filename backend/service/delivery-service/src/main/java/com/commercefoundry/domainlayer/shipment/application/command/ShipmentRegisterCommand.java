package com.commercefoundry.domainlayer.shipment.application.command;

import com.commercefoundry.domainlayer.shipment.domain.model.CarrierCode;
import java.util.List;
import java.util.Objects;

/**
 * 송장 일괄 등록 (#18) — 주문마다 따로 처리한다. 순서는 요청 순서 그대로이고 결과도 그 순서다.
 *
 * @param adminMemberId 등록한 관리자 — access 토큰의 memberId (본문으로 받지 않는다). 배송 상태 이력 · 감사 줄에 남는다
 */
public record ShipmentRegisterCommand(List<Item> items, long adminMemberId) {

    public ShipmentRegisterCommand {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    public record Item(long orderId, CarrierCode carrier, String trackingNumber) {

        public Item {
            Objects.requireNonNull(carrier, "carrier");
            Objects.requireNonNull(trackingNumber, "trackingNumber");
        }
    }
}
