package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * 금액 · 통화 컬럼 쌍은 {@link MoneyColumns} 로만 묶는다 — 정가처럼 없을 수 있는 금액도 한쪽만 있으면 데이터 오류로 실패한다.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProductMapper {

    @Mapping(target = "salePrice", expression = "java(MoneyColumns.required(entity.getSalePriceAmount(), entity.getSalePriceCurrency()))")
    @Mapping(target = "listPrice", expression = "java(MoneyColumns.nullable(entity.getListPriceAmount(), entity.getListPriceCurrency()))")
    Product toDomain(ProductEntity entity);

    List<Product> toDomains(List<ProductEntity> entities);
}
