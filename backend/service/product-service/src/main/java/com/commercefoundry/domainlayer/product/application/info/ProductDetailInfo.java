package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import lombok.Builder;

/**
 * 고객 상품 상세.
 *
 * @param categoryPath 대표 카테고리의 최상위 → 대상 경로 중 메뉴에서 닿는 부분 (처음 숨긴 카테고리에서 끊긴다, #164)
 * @param thumbnail 대표 이미지. 없으면 null
 * @param images 갤러리 이미지 — 노출 순서 → 아이디 오름차순
 * @param maxQuantityPerOrder 1회 주문 최대 수량. null 이면 제한 없음
 * @param optionGroups 노출 순서 → 아이디 오름차순
 * @param skus 아이디 오름차순
 * @param customOrder 맞춤 제작 안내. 스토어 기능 플래그 · 상품 설정 중 하나라도 꺼지면 null
 */
@Builder
public record ProductDetailInfo(
    long productId, String name, String summary, List<CategoryPathInfo> categoryPath,
    ProductImageInfo thumbnail, List<ProductImageInfo> images, String descriptionHtml,
    Money salePrice, Money listPrice, Integer discountRate, SaleAvailability saleStatus, Integer maxQuantityPerOrder,
    List<ProductOptionGroupInfo> optionGroups, List<ProductSkuInfo> skus, CustomOrderInfo customOrder
) {

    public ProductDetailInfo {
        categoryPath = List.copyOf(categoryPath);
        images = List.copyOf(images);
        optionGroups = List.copyOf(optionGroups);
        skus = List.copyOf(skus);
    }
}
