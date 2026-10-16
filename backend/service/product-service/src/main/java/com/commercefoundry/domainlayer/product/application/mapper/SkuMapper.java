package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuOptionKey;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SkuMapper {

    @Mapping(target = "optionKey", source = "optionValueKey")
    @Mapping(target = "additionalPrice", expression = "java(MoneyColumns.required(entity.getAdditionalPriceAmount(), entity.getAdditionalPriceCurrency()))")
    Sku toDomain(SkuEntity entity);

    List<Sku> toDomains(List<SkuEntity> entities);

    /** 저장된 조합 키는 {@link SkuOptionKey#fromStored} 로만 읽는다 — 정규형이 아닌 저장값은 고쳐 읽지 않고 실패한다. */
    default SkuOptionKey toOptionKey(String optionValueKey) {
        return SkuOptionKey.fromStored(optionValueKey);
    }
}
