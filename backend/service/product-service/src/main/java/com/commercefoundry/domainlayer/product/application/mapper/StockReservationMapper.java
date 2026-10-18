package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationItemEntity;
import com.commercefoundry.domainlayer.product.domain.model.StockReservation;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationItem;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StockReservationMapper {

    /** 헤더와 항목(SKU 아이디 오름차순)을 합친다. 활성 주문 아이디는 상태에서 유도하므로 읽지 않는다. */
    @Mapping(target = "items", source = "items")
    StockReservation toDomain(StockReservationEntity entity, List<StockReservationItemEntity> items);

    StockReservationItem toItemDomain(StockReservationItemEntity entity);

    /**
     * 헤더. 새 선점은 버전이 null 이라 INSERT, 읽어 온 선점은 버전이 있어 merge(버전 검사)다. 생성 시각은 매핑하지 않는다 —
     * {@code created_at} 은 {@code updatable = false} 라 merge 가 덮어쓰지 않는다.
     */
    @Mapping(target = "activeOrderId", expression = "java(reservation.activeOrderId())")
    StockReservationEntity toEntity(StockReservation reservation);

    @Mapping(target = "id", source = "item.id")
    @Mapping(target = "reservationId", source = "reservationId")
    StockReservationItemEntity toItemEntity(StockReservationItem item, Long reservationId);

    /** 새 선점의 항목 행. 감사 시각은 INSERT 때 리스너가 채운다. */
    default List<StockReservationItemEntity> toItemEntities(StockReservation reservation) {
        return reservation.items().stream().map(item -> toItemEntity(item, reservation.id())).toList();
    }
}
