package com.commercefoundry.domainlayer.sizechart.application.command;

import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import java.util.List;
import lombok.Builder;

/** 사이즈표 템플릿 생성 · 전체 교체. 이름은 앞뒤 공백을 뺀 값이다. {@code columnId} 는 쓰는 중일 때만 의미가 있다. */
@Builder
public record SizeChartTemplateUpsertCommand(String name, SizeChartUnit baseUnit, List<Column> columns) {

    @Builder
    public record Column(Long columnId, String name, String description, int displayOrder) {
    }
}
