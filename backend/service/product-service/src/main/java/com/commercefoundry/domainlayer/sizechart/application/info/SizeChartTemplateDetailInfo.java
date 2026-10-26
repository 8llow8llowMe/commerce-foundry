package com.commercefoundry.domainlayer.sizechart.application.info;

import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import java.util.List;

/** 템플릿 단건. 항목은 노출 순서 → 아이디 오름차순이다. */
public record SizeChartTemplateDetailInfo(long templateId, String name, SizeChartUnit baseUnit, List<Column> columns) {

    public record Column(long columnId, String name, String description, int displayOrder) {
    }
}
