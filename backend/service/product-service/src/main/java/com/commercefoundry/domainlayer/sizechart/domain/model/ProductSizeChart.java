package com.commercefoundry.domainlayer.sizechart.domain.model;

/**
 * 상품 하나의 사이즈표. 상품당 하나이고 치수는 연결된 템플릿의 기본 단위로 저장한다. {@code note} 는 없을 수 있다.
 */
public record ProductSizeChart(long id, long productId, long templateId, String note) {
}
