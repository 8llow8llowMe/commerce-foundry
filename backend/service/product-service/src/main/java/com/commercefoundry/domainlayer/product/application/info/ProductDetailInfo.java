package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import lombok.Builder;

/**
 * 고객 상품 상세.
 *
 * @param categoryPath 대표 카테고리의 최상위 → 대상 경로 중 보이는 카테고리
 * @param thumbnail 대표 이미지. 없으면 null
 * @param images 갤러리 미디어 — 이미지와 (영상 플래그가 켜졌을 때만) 영상을 노출 순서 → 아이디 오름차순으로 섞는다. 대표 이미지는 들어가지 않는다
 * @param maxQuantityPerOrder 1회 주문 최대 수량. null 이면 제한 없음
 * @param optionGroups 노출 순서 → 아이디 오름차순
 * @param skus 아이디 오름차순
 * @param customOrder 맞춤 제작 안내. 스토어 기능 플래그 · 상품 설정 중 하나라도 꺼지면 null
 * @param preorder 프리오더 안내. 스토어 기능 플래그 · 상품 설정 중 하나라도 꺼지면 null
 */
@Builder
public record ProductDetailInfo(
    long productId, String name, String summary, List<CategoryPathInfo> categoryPath,
    ProductImageInfo thumbnail, List<ProductImageInfo> images, String descriptionHtml,
    Money salePrice, Money listPrice, Integer discountRate, SaleAvailability saleStatus, Integer maxQuantityPerOrder,
    List<ProductOptionGroupInfo> optionGroups, List<ProductSkuInfo> skus, CustomOrderInfo customOrder, PreorderInfo preorder
) {

    public ProductDetailInfo {
        categoryPath = List.copyOf(categoryPath);
        images = List.copyOf(images);
        optionGroups = List.copyOf(optionGroups);
        skus = List.copyOf(skus);
    }
}
