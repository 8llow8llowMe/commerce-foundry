package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuOptionKey;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SkuMapper {

    @Mapping(target = "optionKey", source = "optionValueKey")
    @Mapping(target = "additionalPrice", expression = "java(toMoney(entity.getAdditionalPriceAmount(), entity.getAdditionalPriceCurrency()))")
    Sku toDomain(SkuEntity entity);

    List<Sku> toDomains(List<SkuEntity> entities);

    /** 조합 키는 {@link SkuOptionKey} 로만 읽는다 — 저장된 문자열을 정규형(오름차순 · 중복 없음)으로 맞춘다. */
    default SkuOptionKey toOptionKey(String optionValueKey) {
        return SkuOptionKey.of(SkuOptionKey.parse(optionValueKey));
    }

    default Money toMoney(long amount, CurrencyCode currency) {
        return Money.of(amount, currency);
    }
}
