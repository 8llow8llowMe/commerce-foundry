package com.commercefoundry.domainlayer.shipment.application.mapper;

import com.commercefoundry.domainlayer.shipment.adapter.out.persistence.entity.ShipmentEntity;
import com.commercefoundry.domainlayer.shipment.adapter.out.persistence.entity.ShipmentStatusHistoryEntity;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatusHistory;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * 송장 · 배송 상태 이력 Entity ↔ Domain. 도메인이 record(생성자 검증)이고 엔티티가 빌더라 변환은 default 메서드로 직접 쓴다 — 칸이 늘면 컴파일 오류로 드러난다.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ShipmentMapper {

    /** 버전이 null 이면 새 행(persist), 있으면 읽어 온 행의 변경(merge · 버전 비교)이다. 생성 · 수정 시각은 감사 리스너가 채운다. */
    default ShipmentEntity toEntity(Shipment shipment) {
        return ShipmentEntity.builder()
            .id(shipment.id())
            .orderId(shipment.orderId())
            .memberId(shipment.memberId())
            .carrier(shipment.carrier())
            .trackingNumber(shipment.trackingNumber())
            .status(shipment.status())
            .shippingScope(shipment.shippingScope())
            .orderSynced(shipment.orderSynced())
            .shippedAt(shipment.shippedAt())
            .deliveredAt(shipment.deliveredAt())
            .version(shipment.version())
            .build();
    }

    default Shipment toDomain(ShipmentEntity entity) {
        return new Shipment(
            entity.getId(), entity.getOrderId(), entity.getMemberId(), entity.getCarrier(), entity.getTrackingNumber(), entity.getStatus(),
            entity.getShippingScope(), entity.isOrderSynced(), entity.getShippedAt(), entity.getDeliveredAt(), entity.getVersion()
        );
    }

    default ShipmentStatusHistoryEntity toEntity(ShipmentStatusHistory history) {
        return ShipmentStatusHistoryEntity.builder()
            .id(history.id())
            .shipmentId(history.shipmentId())
            .fromStatus(history.fromStatus())
            .toStatus(history.toStatus())
            .changedBy(history.changedBy())
            .changedById(history.changedById())
            .build();
    }
}
