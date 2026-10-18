package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockAdjustmentHistoryEntity;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentHistory;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StockAdjustmentHistoryMapper {

    /** 감사 시각은 INSERT 때 리스너가 채운다. */
    StockAdjustmentHistoryEntity toEntity(StockAdjustmentHistory history);
}
