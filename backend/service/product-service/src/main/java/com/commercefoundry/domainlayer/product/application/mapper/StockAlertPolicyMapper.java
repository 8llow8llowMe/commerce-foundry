package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockAlertPolicyEntity;
import com.commercefoundry.domainlayer.product.domain.model.StockAlertPolicy;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StockAlertPolicyMapper {

    StockAlertPolicy toDomain(StockAlertPolicyEntity entity);

    StockAlertPolicyEntity toEntity(StockAlertPolicy policy);
}
