package com.commercefoundry.domainlayer.catalog.application.port.out.query;

import com.commercefoundry.shared.commerce.Money;

/**
 * product-service 서비스 간 SKU 조회 한 건. 원격 응답 모양(Feign DTO)은 adapter 밖으로 나오지 않고 이 값으로만 나온다.
 *
 * @param optionName 옵션 값 이름을 {@code " / "} 로 이은 표시명. 옵션이 없으면 빈 문자열
 * @param salePrice 현재 판매가 (기본 판매가 + 옵션 추가금)
 * @param purchasable 상품이 노출 중이고 판매 상태가 판매중일 때만 true
 * @param soldOut 판매 상태가 품절(SOLD_OUT)인가
 * @param maxQuantityPerOrder 1회 주문 최대 수량. null 이면 제한 없음
 * @param customOrder 맞춤 제작 상품인가. 스토어 맞춤 제작 기능이 꺼져 있으면 false
 */
public record SkuQueryResult(
    long skuId, long productId, String productName, String optionName, Money salePrice,
    boolean purchasable, boolean soldOut, Integer maxQuantityPerOrder, boolean customOrder
) {
}
