package com.commercefoundry.domainlayer.product.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 맞춤 제작 입력 항목 (가슴둘레 · 요청 사항 등).
 *
 * @param fieldKey 입력 항목 키 (chest, memo). 주문 항목 스냅숏과 검증 오류 field 이름에 쓴다
 * @param maxValue NUMBER 면 최대값, TEXT 면 최대 글자 수
 * @param stepValue NUMBER 증분 (0.5 등)
 */
public record CustomOrderField(
    long id, long productId, String fieldKey, String label, CustomOrderInputType inputType, String unit,
    boolean required, BigDecimal minValue, BigDecimal maxValue, BigDecimal stepValue, String helpText, int displayOrder
) {

    public CustomOrderField {
        Objects.requireNonNull(fieldKey, "fieldKey");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(inputType, "inputType");
    }
}
