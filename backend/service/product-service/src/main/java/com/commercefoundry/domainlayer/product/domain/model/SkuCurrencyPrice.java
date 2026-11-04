package com.commercefoundry.domainlayer.product.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/** 행 부재가 통화 미지원이며, 존재하는 행의 Money는 항상 필수다. */
public record SkuCurrencyPrice(long id, long skuId, Money additionalPrice) {
    public SkuCurrencyPrice {
        if (id <= 0 || skuId <= 0) {
            throw new IllegalArgumentException("가격행과 SKU 식별자는 양수여야 합니다");
        }
        Objects.requireNonNull(additionalPrice, "additionalPrice");
    }
}
