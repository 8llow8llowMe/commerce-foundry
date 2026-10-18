package com.commercefoundry.domainlayer.cart.application.model;

import com.commercefoundry.domainlayer.cart.domain.model.CartItem;
import com.commercefoundry.domainlayer.cart.domain.model.CartItemStatus;
import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 장바구니가 보는 SKU 의 현재 모습 — product-service 조회 결과({@code SkuQueryResult})를 Processor 가 바꾼 것이다. 저장하지 않는다.
 *
 * @param optionName 옵션 값 이름을 {@code " / "} 로 이은 표시명. 옵션이 없으면 빈 문자열
 * @param salePrice 현재 판매가 (기본 판매가 + 옵션 추가금)
 * @param purchasable 지금 주문할 수 있는가 (노출 중 · 판매중)
 * @param soldOut 판매 상태가 품절인가
 * @param maxQuantityPerOrder 1회 주문 최대 수량. null 이면 제한 없음
 * @param customOrder 맞춤 제작 상품인가 (스토어 기능 플래그 반영)
 */
public record CartSku(
    long skuId, long productId, String productName, String optionName, Money salePrice,
    boolean purchasable, boolean soldOut, Integer maxQuantityPerOrder, boolean customOrder
) {

    public CartSku {
        Objects.requireNonNull(salePrice, "salePrice");
    }

    /** 줄 수량 상한 — {@link CartItem#maxQuantity}. */
    public int maxQuantity() {
        return CartItem.maxQuantity(maxQuantityPerOrder);
    }

    /** 새로 담을 수 있는가 — 구매 가능하고, 맞춤 제작 입력이 있으면 맞춤 제작 상품이어야 한다. */
    public boolean accepts(boolean hasCustomOptions) {
        return purchasable && (!hasCustomOptions || customOrder);
    }

    public CartItemStatus status() {
        return CartItemStatus.resolve(true, purchasable, soldOut);
    }
}
