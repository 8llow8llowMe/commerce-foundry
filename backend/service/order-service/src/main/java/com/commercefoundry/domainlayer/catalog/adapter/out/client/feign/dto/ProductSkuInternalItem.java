package com.commercefoundry.domainlayer.catalog.adapter.out.client.feign.dto;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;

/**
 * product-service {@code GET /internal/v1/skus} 응답의 SKU 한 건 중 order-service 가 읽는 칸. product-service 의 DTO 를 가져다 쓰지 않는다 —
 * 서비스끼리 모듈 의존을 두지 않고, 원격 계약은 이 adapter 안에서만 안다. 모르는 칸(옵션 목록 · 추가금 등)은 무시한다.
 *
 * @param saleStatus 판매 상태 metadata — code 는 ON_SALE · SOLD_OUT · STOP_SELLING
 * @param purchasable 노출 중이고 판매중일 때만 true
 * @param maxQuantityPerOrder null 이면 제한 없음
 */
public record ProductSkuInternalItem(
    String skuId, String productId, String productName, String optionName, MoneyItem salePrice,
    CodeNameDescriptionMetadata saleStatus, boolean purchasable, Integer maxQuantityPerOrder, boolean customOrder
) {
}
