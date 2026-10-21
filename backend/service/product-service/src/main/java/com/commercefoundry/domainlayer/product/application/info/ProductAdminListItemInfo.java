package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import lombok.Builder;

/**
 * 어드민 상품 목록 한 항목. 가격은 저장된 값 그대로다 — 고객 목록처럼 판매중 옵션 최저가로 바꾸거나 할인이 아닌 정가를 숨기지 않는다.
 *
 * @param thumbnailUrl 대표 이미지 주소. 없으면 null
 * @param salePrice 기본 판매가 (SKU 추가금의 기준)
 * @param listPrice 정가. 없으면 null
 * @param saleStatus 저장된 판매 상태
 * @param availability 재고를 반영한 판매 가능 상태 — 고객 목록 뱃지와 같은 규칙이다
 * @param skuCount 판매 상태와 무관한 SKU 수
 */
@Builder
public record ProductAdminListItemInfo(
    long productId, String name, String thumbnailUrl, Money salePrice, Money listPrice,
    SaleStatus saleStatus, boolean visible, SaleAvailability availability, long skuCount, Instant updatedAt
) {
}
