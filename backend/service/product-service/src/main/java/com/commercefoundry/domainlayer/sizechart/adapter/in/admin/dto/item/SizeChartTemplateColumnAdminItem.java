package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "사이즈표 템플릿 항목")
public record SizeChartTemplateColumnAdminItem(

    @Schema(description = "항목 아이디", example = "4611686018427387905")
    String columnId,

    @Schema(description = "항목 이름", example = "가슴")
    String name,

    @Schema(description = "항목 설명. 없으면 null", example = "겨드랑이 아래", nullable = true)
    String description,

    @Schema(description = "항목 노출 순서 (0 이상)", example = "0")
    int displayOrder
) {
}
