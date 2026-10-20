package com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.support;

import com.commercefoundry.common.dto.item.MoneyItem;

/** 진입들이 같이 쓰는 변환 함수. DTO 는 두지 않고, 진입 DTO 대신 공용 타입(MoneyItem)을 돌려준다 — H6 허용. */
public final class GoodsLabelSupport {

    private GoodsLabelSupport() {
    }

    public static String label(String name) {
        return "[" + name + "]";
    }

    public static MoneyItem price(long amount) {
        return MoneyItem.of(amount, "KRW");
    }
}
