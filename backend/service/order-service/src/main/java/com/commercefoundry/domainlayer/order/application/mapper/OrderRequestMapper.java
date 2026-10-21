package com.commercefoundry.domainlayer.order.application.mapper;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderRequestEntity;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OrderRequestMapper {

    OrderRequestEntity toEntity(OrderRequest request);

    /** 손으로 적는다 — 도메인의 {@code endLease(Instant)} 를 MapStruct 가 fluent setter 로 오해한다. */
    default OrderRequest toDomain(OrderRequestEntity entity) {
        return new OrderRequest(entity.getId(), entity.getMemberId(), entity.getIdempotencyKey(), entity.getRequestHash(), entity.getOrderId(),
            entity.getStatus(), entity.getLeaseExpiresAt(), entity.getVersion());
    }
}
