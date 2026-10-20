package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockAdjustmentHistoryEntity;
import com.commercefoundry.domainlayer.product.application.port.out.query.StockAdjustmentHistoryQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentHistory;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StockAdjustmentHistoryMapper {

    /** 감사 시각은 INSERT 때 리스너가 채운다. */
    StockAdjustmentHistoryEntity toEntity(StockAdjustmentHistory history);

    /** 조정 시각은 감사 리스너가 채운 {@code created_at} 이다. */
    StockAdjustmentHistoryQueryResult toQueryResult(StockAdjustmentHistoryEntity entity);
}
