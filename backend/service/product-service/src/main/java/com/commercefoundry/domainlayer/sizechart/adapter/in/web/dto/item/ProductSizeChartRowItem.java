package com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "고객 사이즈표 행")
public record ProductSizeChartRowItem(

    @Schema(description = "사이즈 이름", example = "44")
    String sizeLabel,

    @Schema(description = "사이즈 노출 순서 (0 이상)", example = "0")
    int displayOrder,

    @Schema(description = "항목 순서의 치수")
    List<ProductSizeChartCellItem> cells
) {
}
