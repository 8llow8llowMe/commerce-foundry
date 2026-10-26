package com.commercefoundry.domainlayer.sizechart.application.command;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

/** 상품 사이즈표 전체 교체. 라벨 · 비고는 앞뒤 공백을 뺀 값이고 비고가 없으면 null 이다. */
@Builder
public record ProductSizeChartUpsertCommand(long templateId, String note, List<Row> rows) {

    @Builder
    public record Row(String sizeLabel, int displayOrder, List<Cell> cells) {
    }

    @Builder
    public record Cell(long columnId, BigDecimal value) {
    }
}
