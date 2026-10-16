package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductCustomOrderFieldEntity;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProductCustomOrderFieldMapper {

    CustomOrderField toDomain(ProductCustomOrderFieldEntity entity);

    List<CustomOrderField> toDomains(List<ProductCustomOrderFieldEntity> entities);
}
