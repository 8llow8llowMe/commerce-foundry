package com.commercefoundry.domainlayer.product.adapter.in.support;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.shared.commerce.Money;

/**
 * {@code Money → MoneyItem} 변환. common-core 는 shared-commerce 를 의존하지 않아 변환을 서비스 진입 adapter 쪽에 둔다.
 * 고객(web) · 서비스 간(internal) Presenter 가 같은 모양으로 금액을 내리도록 같이 쓴다 — {@code in/support} 에는 변환 함수만 두고 DTO 는 두지 않는다
 * ({@code architecture-guide.md} §2).
 */
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
