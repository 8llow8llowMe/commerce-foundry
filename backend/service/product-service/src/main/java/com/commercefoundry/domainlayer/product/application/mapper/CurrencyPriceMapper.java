package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.commerce.support.persistence.MoneyColumns;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductPriceEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuPriceEntity;
import com.commercefoundry.domainlayer.product.domain.model.ProductCurrencyPrice;
import com.commercefoundry.domainlayer.product.domain.model.SkuCurrencyPrice;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(imports = MoneyColumns.class, componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CurrencyPriceMapper {
    @Mapping(target = "salePrice", expression = "java(MoneyColumns.required(entity.getSalePriceAmount(), entity.getSalePriceCurrency()))")
    @Mapping(target = "listPrice", expression = "java(MoneyColumns.nullable(entity.getListPriceAmount(), entity.getListPriceCurrency()))")
    ProductCurrencyPrice toDomain(ProductPriceEntity entity);

    @Mapping(target = "additionalPrice", expression = "java(MoneyColumns.required(entity.getAdditionalPriceAmount(), entity.getAdditionalPriceCurrency()))")
    SkuCurrencyPrice toDomain(SkuPriceEntity entity);

    @Mapping(target = "salePriceAmount", source = "salePrice.amount")
    @Mapping(target = "salePriceCurrency", source = "salePrice.currency")
    @Mapping(target = "listPriceAmount", source = "listPrice.amount")
    @Mapping(target = "listPriceCurrency", source = "listPrice.currency")
    ProductPriceEntity toEntity(ProductCurrencyPrice price);

    @Mapping(target = "additionalPriceAmount", source = "additionalPrice.amount")
    @Mapping(target = "additionalPriceCurrency", source = "additionalPrice.currency")
    SkuPriceEntity toEntity(SkuCurrencyPrice price);
}
