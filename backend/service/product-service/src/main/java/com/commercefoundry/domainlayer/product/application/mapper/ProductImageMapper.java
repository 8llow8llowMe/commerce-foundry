package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductImageEntity;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProductImageMapper {

    ProductImage toDomain(ProductImageEntity entity);

    List<ProductImage> toDomains(List<ProductImageEntity> entities);
}
