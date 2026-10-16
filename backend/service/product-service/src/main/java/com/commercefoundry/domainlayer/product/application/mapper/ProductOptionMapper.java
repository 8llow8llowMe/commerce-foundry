package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionGroupEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionValueEntity;
import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProductOptionMapper {

    OptionGroup toDomain(ProductOptionGroupEntity entity);

    List<OptionGroup> toGroups(List<ProductOptionGroupEntity> entities);

    OptionValue toDomain(ProductOptionValueEntity entity);

    List<OptionValue> toValues(List<ProductOptionValueEntity> entities);
}
