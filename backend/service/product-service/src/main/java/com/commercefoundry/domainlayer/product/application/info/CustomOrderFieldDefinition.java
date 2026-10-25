package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import java.math.BigDecimal;

/**
 * 서비스 간 조회에 내리는 맞춤 제작 항목 정의. 주문 생성 검증이 쓰는 칸만 둔다.
 *
 * @param max NUMBER 면 최대값, TEXT 면 최대 글자 수
 * @param step NUMBER 증분. TEXT 면 null
 */
public record CustomOrderFieldDefinition(
    String fieldKey, CustomOrderInputType inputType, boolean required, BigDecimal min, BigDecimal max, BigDecimal step
) {
}
