package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.domain.model.HsCode;
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
    @Mapping(target = "weightGrams", expression = "java(toWeightGrams(entity.getWeightGrams()))")
    Sku toDomain(SkuEntity entity);

    List<Sku> toDomains(List<SkuEntity> entities);

    /** 저장된 조합 키는 {@link SkuOptionKey#fromStored} 로만 읽는다 — 정규형이 아닌 저장값은 고쳐 읽지 않고 실패한다. */
    default SkuOptionKey toOptionKey(String optionValueKey) {
        return SkuOptionKey.fromStored(optionValueKey);
    }

    /** 저장된 HS 코드는 {@link HsCode#fromStoredOrNull} 로 읽는다 — null 은 미입력, 형식이 틀린 저장값은 실패한다. */
    default HsCode toHsCode(String hsCode) {
        return HsCode.fromStoredOrNull(hsCode);
    }

    /**
     * 저장된 무게를 읽는다 — null 은 미입력. 1 미만은 DB check({@code ck_sku_weight_grams_positive})를 비켜 들어온 저장 데이터 오류라
     * 도메인 입력 오류(IllegalArgumentException)가 아니라 {@link HsCode#fromStoredOrNull} 과 같은 IllegalStateException 으로 실패한다.
     */
    default Integer toWeightGrams(Integer weightGrams) {
        if (weightGrams != null && weightGrams <= 0) {
            throw new IllegalStateException("저장된 SKU 무게가 1g 미만입니다: " + weightGrams);
        }
        return weightGrams;
    }
}
