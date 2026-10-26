package com.commercefoundry.domainlayer.sizechart.domain.model;

/**
 * 템플릿의 치수 항목(열). {@code description} 은 없을 수 있다.
 */
public record SizeChartTemplateColumn(long id, long templateId, String name, String description, int displayOrder) {
}
