package com.commercefoundry.domainlayer.product.domain.model;

import java.util.Objects;

/**
 * 상품 옵션 그룹 (색상 · 사이즈). 상품당 최대 {@link SkuOptionKey#MAX_GROUPS} 개다.
 */
public record OptionGroup(long id, long productId, String name, int displayOrder) {

    public OptionGroup {
        Objects.requireNonNull(name, "name");
    }
}
