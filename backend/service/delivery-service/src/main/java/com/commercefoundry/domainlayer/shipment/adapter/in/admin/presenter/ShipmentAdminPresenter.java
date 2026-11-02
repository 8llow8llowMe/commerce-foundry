package com.commercefoundry.domainlayer.shipment.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item.ShipmentRegisterResultAdminItem;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item.ShipmentStatusHistoryAdminItem;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item.ShipmentSummaryAdminItem;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentAdminResponse;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentRegisterAdminResponse;
import com.commercefoundry.domainlayer.shipment.application.info.ShipmentDetailInfo;
import com.commercefoundry.domainlayer.shipment.application.info.ShipmentRegisterResultInfo;
import com.commercefoundry.domainlayer.shipment.application.info.ShipmentRegisterResultInfo.Outcome;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatusHistory;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/** 아이디는 문자열이다. 코드 값(택배사 · 상태 · 변경 주체)은 {@code {code, name, description}} 이다. */
@Component
public class ShipmentAdminPresenter {

    /** 순서는 그대로 둔다(요청 순서). */
    public ShipmentRegisterAdminResponse toRegisterResponse(List<ShipmentRegisterResultInfo> results) {
        return new ShipmentRegisterAdminResponse(
            count(results, Outcome.REGISTERED), count(results, Outcome.ALREADY_REGISTERED), count(results, Outcome.FAILED),
            results.stream().map(ShipmentAdminPresenter::toItem).toList()
        );
    }

    /** 상세 — 이력은 시간순 그대로. */
    public ShipmentAdminResponse toDetailResponse(ShipmentDetailInfo detail) {
        Shipment shipment = detail.shipment();
        return ShipmentAdminResponse.builder()
            .deliveryId(String.valueOf(shipment.id()))
            .orderId(String.valueOf(shipment.orderId()))
            .carrier(shipment.carrier().toMetadata())
            .trackingNumber(shipment.trackingNumber())
            .status(shipment.status().toMetadata())
            .orderSynced(shipment.orderSynced())
            .completionPending(shipment.completionPending())
            .shippedAt(shipment.shippedAt())
            .deliveredAt(shipment.deliveredAt())
            .histories(detail.histories().stream().map(ShipmentAdminPresenter::toHistoryItem).toList())
            .build();
    }

    /** 목록 — 전체 건수 · 페이지는 저장소가 센 그대로다. */
    public PageResponse<ShipmentSummaryAdminItem> toPageResponse(Page<Shipment> shipments) {
        return PageResponse.of(shipments).map(ShipmentAdminPresenter::toSummaryItem);
    }

    private static ShipmentSummaryAdminItem toSummaryItem(Shipment shipment) {
        return ShipmentSummaryAdminItem.builder()
            .deliveryId(String.valueOf(shipment.id()))
            .orderId(String.valueOf(shipment.orderId()))
            .carrier(shipment.carrier().toMetadata())
            .trackingNumber(shipment.trackingNumber())
            .status(shipment.status().toMetadata())
            .orderSynced(shipment.orderSynced())
            .completionPending(shipment.completionPending())
            .shippedAt(shipment.shippedAt())
            .deliveredAt(shipment.deliveredAt())
            .build();
    }

    private static ShipmentStatusHistoryAdminItem toHistoryItem(ShipmentStatusHistory history) {
        return ShipmentStatusHistoryAdminItem.builder()
            .fromStatus(history.fromStatus() == null ? null : history.fromStatus().toMetadata())
            .toStatus(history.toStatus().toMetadata())
            .changedBy(history.changedBy().toMetadata())
            .changedById(history.changedById() == null ? null : String.valueOf(history.changedById()))
            .reason(history.reason())
            .changedAt(history.changedAt())
            .build();
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
