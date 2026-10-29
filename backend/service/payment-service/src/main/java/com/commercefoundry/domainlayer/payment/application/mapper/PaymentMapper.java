package com.commercefoundry.domainlayer.payment.application.mapper;

import com.commercefoundry.commerce.support.persistence.MoneyColumns;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * 결제 시도 Entity ↔ Domain. 금액 컬럼 쌍은 {@link MoneyColumns} 로만 묶는다.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PaymentMapper {

    /**
     * 저장된 행을 도메인으로 읽는다 — 도메인 생성자가 상태별 불변식을 다시 본다(어긋나면 {@link IllegalArgumentException}).
     * 활성 주문 아이디는 상태에서 유도하므로 읽지 않는다.
     *
     * <p>손으로 쓴다 — 전이 메서드({@code abort(reason)} 처럼 인자 하나를 받아 {@link Payment} 를 돌려주는 메서드)를 MapStruct 가 fluent setter 로 읽어
     * 매핑 대상으로 잡는다. 칸 순서는 레코드 선언 순서다.
     */
    default Payment toDomain(PaymentEntity entity) {
        return new Payment(
            entity.getId(), entity.getOrderId(), entity.getMemberId(), entity.getPgOrderId(), entity.getGateway(),
            MoneyColumns.required(entity.getTotalAmount(), entity.getTotalCurrency()), entity.getOrderName(), entity.getStatus(),
            entity.getPaymentKey(), entity.getMethod(), entity.getEasyPayProvider(), entity.getPgStatus(), entity.getApprovalStartedAt(), entity.getApprovedAt(),
            entity.getFailureCode(), entity.getFailureMessage(), entity.getUnknownReason(), entity.isOrderSynced(), entity.getOrderSyncFailureCode(),
            entity.getAbortReason(), entity.getVersion()
        );
    }

    /**
     * 새 결제 시도는 버전이 null 이라 INSERT, 읽어 온 결제 시도는 버전이 있어 merge(버전 검사)다. 생성 시각은 매핑하지 않는다 —
     * {@code created_at} 은 {@code updatable = false} 라 merge 가 덮어쓰지 않는다 ({@code coding-conventions.md} §9-1).
     */
    @Mapping(target = "totalAmount", source = "amount.amount")
    @Mapping(target = "totalCurrency", source = "amount.currency")
    @Mapping(target = "activeOrderId", expression = "java(payment.activeOrderId())")
    PaymentEntity toEntity(Payment payment);
}
