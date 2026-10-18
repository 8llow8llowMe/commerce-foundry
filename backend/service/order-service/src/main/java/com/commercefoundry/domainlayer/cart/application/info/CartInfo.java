package com.commercefoundry.domainlayer.cart.application.info;

import com.commercefoundry.shared.commerce.Money;
import java.util.List;

/**
 * 장바구니 조회 결과.
 *
 * @param lines 최근에 담은 순
 * @param totalAmount 구매 가능하고 수량이 상한 안인 줄의 금액 합. 그런 줄이 없으면 null
 * @param purchasableCount 합계에 든 줄 수
 */
public record CartInfo(List<CartLineInfo> lines, Money totalAmount, int purchasableCount) {

    public CartInfo {
        lines = List.copyOf(lines);
    }
}
