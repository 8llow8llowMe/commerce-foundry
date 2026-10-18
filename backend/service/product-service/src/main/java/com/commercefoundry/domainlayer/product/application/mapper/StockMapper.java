package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import com.commercefoundry.domainlayer.product.application.port.out.query.StockQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StockMapper {

    Stock toDomain(StockEntity entity);

    List<Stock> toDomains(List<StockEntity> entities);

    StockQueryResult toQueryResult(StockEntity entity);

    /** 새 재고 행. 감사 시각은 INSERT 때 리스너가 채운다. */
    StockEntity toEntity(Stock stock);
}
