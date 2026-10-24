package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.ProductSummary;
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

    /** 상품 카드 값을 그대로 옮긴다 — 목록과 전시(메인)가 같은 {@link ProductSummary} 에서 나와 값이 갈라지지 않는다. */
    public static ProductListItemInfo from(ProductSummary summary) {
        return ProductListItemInfo.builder()
            .productId(summary.productId())
            .name(summary.name())
            .thumbnail(summary.thumbnail() == null ? null : ProductImageInfo.from(summary.thumbnail()))
            .salePrice(summary.salePrice())
            .listPrice(summary.listPrice())
            .discountRate(summary.discountRate())
            .hasOptionSurcharge(summary.hasOptionSurcharge())
            .saleStatus(summary.saleStatus())
            .customOrder(summary.customOrder())
            .preorder(summary.preorder())
            .build();
    }
}
