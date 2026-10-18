package com.commercefoundry.domainlayer.cart.application.port.in;

import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response.CartMergeResponse;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response.CartResponse;
import com.commercefoundry.domainlayer.cart.application.command.CartItemAddCommand;
import com.commercefoundry.domainlayer.cart.application.command.CartMergeCommand;

/**
 * 회원 장바구니 (회원당 하나). memberId 는 컨트롤러가 JWT 주체에서만 꺼낸다. 쓰기는 모두 갱신된 장바구니 전체를 돌려준다.
 */
public interface CartWebUseCase {

    CartResponse getCart(long memberId);

    /**
     * 같은 줄(SKU · 맞춤 제작 입력)이 있으면 수량을 더한다.
     * 거절: {@code CART_SKU_NOT_FOUND}(404) · {@code CART_SKU_NOT_PURCHASABLE}(409) · {@code CART_CUSTOM_OPTION_NOT_ALLOWED}(400) ·
     * {@code CART_QUANTITY_EXCEEDED}(409) · {@code CART_ITEM_LIMIT_EXCEEDED}(409)
     */
    CartResponse addItem(long memberId, CartItemAddCommand command);

    /** 없거나 다른 회원의 줄이면 {@code CART_ITEM_NOT_FOUND}(404), 상한을 넘으면 {@code CART_QUANTITY_EXCEEDED}(409). */
    CartResponse changeQuantity(long memberId, long cartItemId, int quantity);

    /** 없거나 다른 회원의 줄이면 {@code CART_ITEM_NOT_FOUND}(404). */
    CartResponse removeItem(long memberId, long cartItemId);

    /** 담지 못하는 항목은 건너뛰고 항목 단위로 {@code skippedItems} 로 알린다. 수량은 상한에서 자른다. */
    CartMergeResponse merge(long memberId, CartMergeCommand command);
}
