package com.commercefoundry.domainlayer.product.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.Objects;

/** 통화별 상품 가격행. 식별자와 통화는 생성 후 변경하지 않는다. */
public record ProductCurrencyPrice(long id, long productId, Money salePrice, Money listPrice) {
    public ProductCurrencyPrice {
        if (id <= 0 || productId <= 0) {
            throw new IllegalArgumentException("가격행과 상품 식별자는 양수여야 합니다");
        }
        Objects.requireNonNull(salePrice, "salePrice");
        ProductPricing.violation(salePrice, listPrice, List.of()).ifPresent(reason -> {
            throw new IllegalArgumentException(reason);
        });
    }
}
