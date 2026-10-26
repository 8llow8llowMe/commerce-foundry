package com.commercefoundry.domainlayer.sizechart.application.info;

import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import java.math.BigDecimal;
import java.util.List;

/**
 * 상품 사이즈표. {@code displayUnit} 이 저장 단위와 같으면 {@code value} 는 저장 값이고, 다르면 표시용으로만 바꾼 값이다.
 * 어드민은 항상 저장 단위와 저장 값을 담는다.
 */
public record ProductSizeChartInfo(
    long productId,
    long templateId,
    String templateName,
    SizeChartUnit baseUnit,
    SizeChartUnit displayUnit,
    String note,
    List<SizeChartTemplateDetailInfo.Column> columns,
    List<Row> rows
) {

    public record Row(String sizeLabel, int displayOrder, List<Cell> cells) {
    }

    public record Cell(long columnId, BigDecimal value) {
    }
}
