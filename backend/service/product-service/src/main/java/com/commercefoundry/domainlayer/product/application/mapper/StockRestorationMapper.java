package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockRestorationEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockRestorationItemEntity;
import com.commercefoundry.domainlayer.product.domain.model.StockRestoration;
import com.commercefoundry.domainlayer.product.domain.model.StockRestorationItem;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StockRestorationMapper {

    /** 헤더와 항목(SKU 아이디 오름차순)을 합친다. */
    @Mapping(target = "items", source = "items")
    StockRestoration toDomain(StockRestorationEntity entity, List<StockRestorationItemEntity> items);

    StockRestorationItem toItemDomain(StockRestorationItemEntity entity);

    /** 새 복원의 헤더 행. 감사 시각은 INSERT 때 리스너가 채운다 — 비어 있어 {@code isNew()} 가 true 라 persist 다. */
    StockRestorationEntity toEntity(StockRestoration restoration);

    @Mapping(target = "id", source = "item.id")
    @Mapping(target = "restorationId", source = "restorationId")
    StockRestorationItemEntity toItemEntity(StockRestorationItem item, Long restorationId);

    /** 새 복원의 항목 행. */
    default List<StockRestorationItemEntity> toItemEntities(StockRestoration restoration) {
        return restoration.items().stream().map(item -> toItemEntity(item, restoration.id())).toList();
    }
}
