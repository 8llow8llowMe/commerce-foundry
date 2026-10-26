package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.Builder;

@Builder
@Schema(description = "상품 사이즈표 칸 (저장 값)")
public record ProductSizeCellAdminItem(

    @Schema(description = "템플릿 항목 아이디", example = "4611686018427387905")
    String columnId,

    @Schema(description = "치수. 템플릿 저장 단위 그대로인 JSON number", example = "86.50")
    BigDecimal value
) {
}
