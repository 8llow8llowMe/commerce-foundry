package com.commercefoundry.domainlayer.cart.application.info;

import com.commercefoundry.domainlayer.cart.domain.model.CartItemStatus;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOption;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import lombok.Builder;

/**
 * 장바구니 한 줄의 조회 결과 — 저장된 줄 + 조회 시점의 SKU.
 *
 * @param productId SKU 를 찾지 못했으면 null. 상품명 · 옵션명 · 단가 · 줄 금액 · 1회 주문 최대 수량도 같다
 * @param unitPrice 현재 판매가
 * @param lineAmount 단가 × 수량
 * @param quantityExceeded 지금 수량이 현재 상한(99 · 1회 주문 최대 수량)을 넘는가
 */
@Builder
public record CartLineInfo(
    long itemId, long skuId, Long productId, String productName, String optionName, int quantity,
    Money unitPrice, Money lineAmount, CartItemStatus status, Integer maxQuantityPerOrder, boolean customOrder,
    List<CustomOption> customOptions, boolean quantityExceeded
) {

    public CartLineInfo {
        customOptions = List.copyOf(customOptions);
    }

    /** 합계 · 구매 가능 수에 드는가 — 구매 가능하고 수량이 상한 안이다. */
    public boolean countable() {
        return status == CartItemStatus.ON_SALE && !quantityExceeded;
    }
}
