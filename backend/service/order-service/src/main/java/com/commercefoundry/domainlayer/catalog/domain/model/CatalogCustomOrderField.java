package com.commercefoundry.domainlayer.catalog.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 맞춤 제작 입력 항목 정의. product-service 가 지금 내려준 값이고, 저장하지 않는다.
 *
 * @param max NUMBER 면 최대값, TEXT 면 최대 글자 수. null 이면 그 제한이 없다
 * @param step NUMBER 증분. null · 0 이하면 증분을 보지 않는다
 */
public record CatalogCustomOrderField(
    String fieldKey, CatalogCustomOrderInputType inputType, boolean required, BigDecimal min, BigDecimal max, BigDecimal step
) {

    public CatalogCustomOrderField {
        Objects.requireNonNull(fieldKey, "fieldKey");
        Objects.requireNonNull(inputType, "inputType");
    }
}
