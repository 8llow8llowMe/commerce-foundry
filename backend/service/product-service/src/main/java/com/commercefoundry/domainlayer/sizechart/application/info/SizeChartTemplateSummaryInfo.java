package com.commercefoundry.domainlayer.sizechart.application.info;

import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;

/** 어드민 템플릿 목록 한 줄. 식별자는 long 이고 문자열 변환은 Presenter 가 한다. */
public record SizeChartTemplateSummaryInfo(long templateId, String name, SizeChartUnit baseUnit, int columnCount) {
}
