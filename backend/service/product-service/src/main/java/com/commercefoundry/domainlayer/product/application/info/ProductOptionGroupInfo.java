package com.commercefoundry.domainlayer.product.application.info;

import java.util.List;

/**
 * @param values 노출 순서 → 아이디 오름차순
 */
public record ProductOptionGroupInfo(long optionGroupId, String name, int displayOrder, List<ProductOptionValueInfo> values) {

    public ProductOptionGroupInfo {
        values = List.copyOf(values);
    }
}
