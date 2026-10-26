package com.commercefoundry.domainlayer.sizechart.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "고객 사이즈표 항목")
public record ProductSizeChartColumnItem(

    @Schema(description = "항목 아이디", example = "4611686018427387905")
    String columnId,

    @Schema(description = "항목 이름", example = "가슴")
    String name,

    @Schema(description = "항목 설명. 없으면 null", nullable = true)
    String description,

    @Schema(description = "항목 노출 순서 (0 이상)", example = "0")
    int displayOrder
) {
}
