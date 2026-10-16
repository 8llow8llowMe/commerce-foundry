package com.commercefoundry.domainlayer.product.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 판매 단위 (상품 × 옵션 값 조합). 판매가는 {@link ProductPricing#skuSalePrice} 로 계산한다.
 *
 * @param optionKey 옵션 값 조합 키. 옵션 없는 상품은 빈 키
 * @param additionalPrice 옵션 추가금 (0 이상). 통화는 상품 기본 판매가와 같다
 */
public record Sku(long id, long productId, SkuOptionKey optionKey, Money additionalPrice, SaleStatus saleStatus) {

    public Sku {
        Objects.requireNonNull(optionKey, "optionKey");
        Objects.requireNonNull(additionalPrice, "additionalPrice");
        Objects.requireNonNull(saleStatus, "saleStatus");
    }
}
