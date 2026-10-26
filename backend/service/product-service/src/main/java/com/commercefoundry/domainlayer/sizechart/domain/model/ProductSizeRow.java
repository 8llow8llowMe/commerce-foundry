package com.commercefoundry.domainlayer.sizechart.domain.model;

/** 사이즈표의 한 사이즈(행). */
public record ProductSizeRow(long id, long sizeChartId, String sizeLabel, int displayOrder) {
}
