package com.commercefoundry.domainlayer.catalog.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * product-service SKU 의 현재 모습 — 조회 결과({@code SkuQueryResult})를 {@code CatalogSkuProcessor} 가 바꾼 것이다. 저장하지 않는다.
 * 담기 가능 판정 · 수량 상한 · 상태 표시 같은 규칙은 이 값을 받은 컨텍스트(장바구니 · 주문)가 정한다.
 *
 * @param optionName 옵션 값 이름을 {@code " / "} 로 이은 표시명. 옵션이 없으면 빈 문자열
 * @param salePrice 현재 판매가 (기본 판매가 + 옵션 추가금)
 * @param purchasable 지금 주문할 수 있는가 (노출 중 · 판매중)
 * @param soldOut 판매 상태가 품절인가
 * @param maxQuantityPerOrder 1회 주문 최대 수량. null 이면 제한 없음
 * @param customOrder 맞춤 제작 상품인가 (스토어 기능 플래그 반영)
 */
public record CatalogSku(
    long skuId, long productId, String productName, String optionName, Money salePrice,
    boolean purchasable, boolean soldOut, Integer maxQuantityPerOrder, boolean customOrder
) {

    public CatalogSku {
        Objects.requireNonNull(salePrice, "salePrice");
    }
}
