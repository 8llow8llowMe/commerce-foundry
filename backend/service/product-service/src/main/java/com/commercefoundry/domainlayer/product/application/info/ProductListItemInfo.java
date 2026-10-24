package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.shared.commerce.Money;
import lombok.Builder;

/**
 * 고객 상품 목록 한 항목.
 *
 * @param thumbnail 대표 이미지. 없으면 null
 * @param listPrice 정가. 없으면 null
 * @param discountRate 할인율(%). 할인이 없으면 null
 * @param customOrder 스토어 기능 플래그와 상품 설정이 모두 켜졌을 때만 true
 * @param preorder 스토어 기능 플래그와 상품 설정이 모두 켜졌을 때만 true
 */
@Builder
public record ProductListItemInfo(
    long productId, String name, ProductImageInfo thumbnail,
    Money salePrice, Money listPrice, Integer discountRate, boolean hasOptionSurcharge,
    SaleAvailability saleStatus, boolean customOrder, boolean preorder
) {
}
