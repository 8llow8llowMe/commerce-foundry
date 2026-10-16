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

    /** 판매중(ON_SALE) SKU 인가. 상품 판매 상태 · 재고는 보지 않는다 — 판매 가능 여부는 {@link SaleAvailability#ofSku} 가 정한다. */
    public boolean isOnSale() {
        return saleStatus == SaleStatus.ON_SALE;
    }
}
