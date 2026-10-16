package com.commercefoundry.domainlayer.product.domain.model;

import java.util.Objects;

/**
 * 옵션 그룹 안의 값 하나 (빨강 · M).
 *
 * @param productId 상품 아이디 — 상세를 상품 단위로 한 번에 읽기 위한 비정규화
 */
public record OptionValue(long id, long optionGroupId, long productId, String name, int displayOrder) {

    public OptionValue {
        Objects.requireNonNull(name, "name");
    }
}
