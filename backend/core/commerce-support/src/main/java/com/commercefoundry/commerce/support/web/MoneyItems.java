package com.commercefoundry.commerce.support.web;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.shared.commerce.Money;

/** Money → MoneyItem 변환. 진입 adapter 의 Presenter 에서만 쓴다. */
public final class MoneyItems {

    private MoneyItems() {
    }

    /** null 이면 null 이다 (정가가 없는 상품 등). */
    public static MoneyItem of(Money money) {
        if (money == null) {
            return null;
        }
        return MoneyItem.of(money.amount(), money.currency().name());
    }
}
