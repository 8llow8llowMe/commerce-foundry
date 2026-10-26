package com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.Builder;

@Builder
@Schema(description = "고객 사이즈표 칸")
public record ProductSizeChartCellItem(

    @Schema(description = "항목 아이디", example = "4611686018427387905")
    String columnId,

    @Schema(description = "표시 단위로 바꾼 치수. JSON number 다. 요청 단위가 저장 단위와 같으면 저장 값 그대로다", example = "34.06")
    BigDecimal value
) {
}
