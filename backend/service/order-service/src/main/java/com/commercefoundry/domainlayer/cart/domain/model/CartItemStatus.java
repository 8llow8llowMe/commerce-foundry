package com.commercefoundry.domainlayer.cart.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 장바구니 줄을 조회한 시점의 구매 가능 상태. 저장하지 않는다 — 조회할 때마다 product-service 의 현재 SKU 로 정한다.
 */
@Getter
@RequiredArgsConstructor
public enum CartItemStatus implements CodeNameDescribable {

    ON_SALE("구매 가능", "지금 주문할 수 있습니다."),
    SOLD_OUT("품절", "재고가 모두 소진되었습니다."),
    STOP_SELLING("판매 중지", "판매하지 않는 상품입니다.");

    private final String displayName;
    private final String description;

    /**
     * 아래 순서로 처음 맞는 것이다.
     *
     * <ol>
     *   <li>SKU 를 찾지 못했다(삭제 등) → 판매 중지</li>
     *   <li>지금 주문할 수 있다 → 구매 가능</li>
     *   <li>품절이다 → 품절 — 숨긴 상품이라도 판매 상태가 품절이면 품절이다(품절이 숨김보다 앞선다)</li>
     *   <li>그 밖(판매 종료 · 품절이 아닌 숨긴 상품) → 판매 중지</li>
     * </ol>
     *
     * @param skuFound SKU 조회 결과가 있는가
     * @param purchasable 상품이 노출 중이고 판매 상태가 판매중인가 (product-service 판정)
     * @param soldOut product-service 의 판매 상태가 품절인가
     */
    public static CartItemStatus resolve(boolean skuFound, boolean purchasable, boolean soldOut) {
        if (!skuFound) {
            return STOP_SELLING;
        }
        if (purchasable) {
            return ON_SALE;
        }
        return soldOut ? SOLD_OUT : STOP_SELLING;
    }
}
