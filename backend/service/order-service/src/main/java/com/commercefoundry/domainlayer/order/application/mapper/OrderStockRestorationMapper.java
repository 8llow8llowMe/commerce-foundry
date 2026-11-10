package com.commercefoundry.domainlayer.order.application.mapper;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderStockRestorationEntity;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** 결제 반영 재고 복원 작업 Entity ↔ Domain (#582). 레코드 · 빌더 조합이라 default 메서드로 손으로 적는다. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OrderStockRestorationMapper {

    default OrderStockRestorationEntity toEntity(OrderStockRestoration restoration) {
        return OrderStockRestorationEntity.builder()
            .id(restoration.id())
            .orderId(restoration.orderId())
            .paymentId(restoration.paymentId())
            .stockReservationId(restoration.stockReservationId())
            .claimId(restoration.claimId())
            .origin(restoration.origin())
            .status(restoration.status())
            .holdCode(restoration.holdCode())
            .attempts(restoration.attempts())
            .nextAttemptAt(restoration.nextAttemptAt())
            .restoredAt(restoration.restoredAt())
            .build();
    }

    default OrderStockRestoration toDomain(OrderStockRestorationEntity entity) {
        return new OrderStockRestoration(entity.getId(), entity.getOrderId(), entity.getPaymentId(), entity.getStockReservationId(), entity.getClaimId(),
            entity.getOrigin(), entity.getStatus(), entity.getHoldCode(), entity.getAttempts(), entity.getNextAttemptAt(), entity.getRestoredAt(),
            entity.getCreatedAt());
    }
}
