package com.commercefoundry.domainlayer.cart.adapter.in.support;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.shared.commerce.Money;

/**
 * {@code Money → MoneyItem} 변환. common-core 는 shared-commerce 를 의존하지 않아 변환을 서비스 진입 adapter 쪽에 둔다
 * ({@code architecture-guide.md} §2 {@code in/support} — 변환 함수만 두고 DTO 는 두지 않는다).
 */
public final class MoneyItems {

    private MoneyItems() {
    }

    /** null 이면 null 이다 (SKU 를 찾지 못한 줄의 단가, 구매 가능한 줄이 없는 합계). */
    public static MoneyItem of(Money money) {
        if (money == null) {
            return null;
        }
        return MoneyItem.of(money.amount(), money.currency().name());
    }
}
