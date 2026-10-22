package com.commercefoundry.domainlayer.product.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 옵션 그룹 안의 값 하나 (빨강 · M).
 *
 * @param productId 상품 아이디 — 상세를 상품 단위로 한 번에 읽기 위한 비정규화
 * @param retiredAt 은퇴 시각. null 이면 사용 중이다. 은퇴한 값과 그 값을 쓰는 SKU 는 고객 상세에서 빠지지만 행은 남는다 —
 *     서비스 간 SKU 조회는 옵션명을 계속 읽는다(주문 · 장바구니 줄의 옵션명이 끊기지 않게)
 */
public record OptionValue(long id, long optionGroupId, long productId, String name, int displayOrder, Instant retiredAt) {

    public OptionValue {
        Objects.requireNonNull(name, "name");
    }

    /** 은퇴한 값인가. 속한 그룹의 은퇴는 보지 않는다 — 그룹까지 보는 판정은 호출한 쪽이 한다. */
    public boolean isRetired() {
        return retiredAt != null;
    }
}
