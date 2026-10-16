package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProductMapper {

    @Mapping(target = "salePrice", expression = "java(toMoney(entity.getSalePriceAmount(), entity.getSalePriceCurrency()))")
    @Mapping(target = "listPrice", expression = "java(toNullableMoney(entity.getListPriceAmount(), entity.getListPriceCurrency()))")
    Product toDomain(ProductEntity entity);

    List<Product> toDomains(List<ProductEntity> entities);

    default Money toMoney(long amount, CurrencyCode currency) {
        return Money.of(amount, currency);
    }

    /**
     * 정가처럼 없을 수 있는 금액. 금액과 통화는 함께 있거나 함께 없어야 한다 — 한쪽만 있으면 데이터 오류라 조용히 넘기지 않는다.
     */
    default Money toNullableMoney(Long amount, CurrencyCode currency) {
        if (amount == null && currency == null) {
            return null;
        }
        if (amount == null || currency == null) {
            throw new IllegalStateException("금액과 통화는 함께 저장돼야 합니다: amount=%s, currency=%s".formatted(amount, currency));
        }
        return Money.of(amount, currency);
    }
}
