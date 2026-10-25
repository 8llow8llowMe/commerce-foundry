package com.commercefoundry.domainlayer.catalog.adapter.out.client.feign.dto;

import java.math.BigDecimal;

/**
 * {@code GET /internal/v1/skus} 항목의 맞춤 제작 입력 정의. 주문 검증에 쓰는 칸만 읽는다.
 *
 * @param inputType {@code NUMBER} · {@code TEXT}
 * @param max NUMBER 면 최대값, TEXT 면 최대 글자 수
 */
public record ProductCustomOrderFieldInternalItem(
    String fieldKey, String inputType, boolean required, BigDecimal min, BigDecimal max, BigDecimal step
) {
}
