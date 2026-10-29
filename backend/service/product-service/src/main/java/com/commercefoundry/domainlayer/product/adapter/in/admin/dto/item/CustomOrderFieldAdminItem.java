package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import java.math.BigDecimal;
import lombok.Builder;

@Builder
@Schema(description = "어드민 상품 맞춤 제작 입력 항목")
public record CustomOrderFieldAdminItem(

    @Schema(description = "입력 항목 키")
    String fieldKey,

    @Schema(description = "표시 이름")
    String label,

    @Schema(description = "입력 유형")
    CodeNameDescriptionMetadata inputType,

    @Schema(description = "단위", nullable = true)
    String unit,

    @Schema(description = "필수 입력 여부")
    boolean required,

    @Schema(description = "최소값", nullable = true)
    BigDecimal minValue,

    @Schema(description = "최대값 또는 최대 글자 수", nullable = true)
    BigDecimal maxValue,

    @Schema(description = "숫자 증분", nullable = true)
    BigDecimal stepValue,

    @Schema(description = "도움말", nullable = true)
    String helpText,

    @Schema(description = "진열 순서")
    int displayOrder
) {
}
