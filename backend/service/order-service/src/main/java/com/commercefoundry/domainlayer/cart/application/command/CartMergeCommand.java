package com.commercefoundry.domainlayer.cart.application.command;

import java.util.List;

/**
 * 로그인 직후 로컬 장바구니를 회원 장바구니에 합치기.
 *
 * @param items 로컬 장바구니 순서. 최대 50개 (요청 검증이 거른다)
 */
public record CartMergeCommand(List<CartItemAddCommand> items) {

    public CartMergeCommand {
        items = List.copyOf(items);
    }
}
