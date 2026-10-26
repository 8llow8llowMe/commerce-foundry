package com.commercefoundry.domainlayer.sizechart.domain.model;

/**
 * 한 사이즈 행의 칸이 템플릿 항목 집합과 다른 이유. 중복을 모르는 항목 · 누락보다 먼저 본다.
 */
public enum SizeChartCellViolation {

    NONE,
    DUPLICATED,
    UNKNOWN,
    MISSING
}
