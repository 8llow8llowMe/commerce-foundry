package com.commercefoundry.domainlayer.sizechart.domain.model;

/**
 * 사이즈표 템플릿. 항목(열)은 별도 값이고 이 레코드는 머리만 가진다.
 */
public record SizeChartTemplate(long id, String name, SizeChartUnit baseUnit) {
}
