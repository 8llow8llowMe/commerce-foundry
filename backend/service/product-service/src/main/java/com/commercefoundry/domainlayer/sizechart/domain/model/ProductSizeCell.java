package com.commercefoundry.domainlayer.sizechart.domain.model;

import java.math.BigDecimal;

/** 행 × 템플릿 항목 칸. 값은 템플릿 기본 단위의 치수다. */
public record ProductSizeCell(long id, long rowId, long columnId, BigDecimal cellValue) {
}
