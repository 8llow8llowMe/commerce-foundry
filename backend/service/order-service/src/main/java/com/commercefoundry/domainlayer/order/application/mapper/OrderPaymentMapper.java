package com.commercefoundry.domainlayer.order.application.mapper;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderPaymentEntity;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.shared.commerce.Money;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** 결제 반영 기록 Entity ↔ Domain. 금액이 {@code Money} 한 값에서 칸 두 개로 펼쳐지고 {@code paid_order_id} 가 결과에서 나온다 — default 메서드로 손으로 적는다. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OrderPaymentMapper {

    default OrderPaymentEntity toEntity(OrderPayment orderPayment) {
        return OrderPaymentEntity.builder()
            .id(orderPayment.id())
            .orderId(orderPayment.orderId())
            .paymentId(orderPayment.paymentId())
            .memberId(orderPayment.memberId())
            .totalAmount(orderPayment.totalAmount().amount())
            .totalCurrency(orderPayment.totalAmount().currency())
            .approvedAt(orderPayment.approvedAt())
            .result(orderPayment.result())
            .paidOrderId(orderPayment.paidOrderId())
            .stockReservationId(orderPayment.stockReservationId())
            .eventId(orderPayment.eventId())
            .restorationClaimId(orderPayment.restorationClaimId())
            .restoredAt(orderPayment.restoredAt())
            .build();
    }

    default OrderPayment toDomain(OrderPaymentEntity entity) {
        return new OrderPayment(
            entity.getId(), entity.getOrderId(), entity.getPaymentId(), entity.getMemberId(), Money.of(entity.getTotalAmount(), entity.getTotalCurrency()),
            entity.getApprovedAt(), entity.getResult(), entity.getStockReservationId(), entity.getEventId(), entity.getRestorationClaimId(),
            entity.getRestoredAt(), entity.getCreatedAt());
    }
}
