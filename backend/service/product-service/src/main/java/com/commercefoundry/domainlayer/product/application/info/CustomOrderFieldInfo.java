package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import java.math.BigDecimal;
import lombok.Builder;

/**
 * 맞춤 제작 입력 항목.
 *
 * @param max NUMBER 면 최대값, TEXT 면 최대 글자 수
 */
@Builder
public record CustomOrderFieldInfo(
    String fieldKey, String label, CustomOrderInputType inputType, String unit, boolean required,
    BigDecimal min, BigDecimal max, BigDecimal step, String helpText
) {
}
