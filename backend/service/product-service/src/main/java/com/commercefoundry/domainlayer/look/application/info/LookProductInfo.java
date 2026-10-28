package com.commercefoundry.domainlayer.look.application.info;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import com.commercefoundry.shared.commerce.Money;

/**
 * 고객 룩의 구성 상품 카드 — 상품 목록 항목과 같은 값이다({@code ProductSummary} 를 옮긴 것). 상품 컨텍스트 domain 은 Presenter 까지 넘기지 않는다 (H8).
 *
 * @param thumbnail 대표 이미지. 없으면 null
 * @param hoverImage 정지 이미지 정렬의 두 번째. 1장 이하면 null
 * @param listPrice 정가. 할인이 없으면 null
 * @param discountRate 할인율(%). 할인이 없으면 null
 * @param saleStatus 판매 상태(판매중 · 품절 · 판매 종료)
 */
public record LookProductInfo(
    long productId, String name, Thumbnail thumbnail, Thumbnail hoverImage,
    Money salePrice, Money listPrice, Integer discountRate, boolean hasOptionSurcharge,
    CodeNameDescribable saleStatus, boolean customOrder, boolean preorder
) {

    /** 상품 대표 · hover 이미지. */
    public record Thumbnail(long imageId, String url, Integer width, Integer height, String altText) {
    }
}
