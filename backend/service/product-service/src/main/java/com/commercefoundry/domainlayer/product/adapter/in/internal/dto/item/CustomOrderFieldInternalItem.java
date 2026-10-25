package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.Builder;

@Builder
@Schema(description = "맞춤 제작 입력 항목 정의 — 주문 생성이 입력값과 대조한다")
public record CustomOrderFieldInternalItem(

    @Schema(description = "입력 항목 키", example = "chest")
    String fieldKey,

    @Schema(description = "입력 유형 — NUMBER(숫자, min · max · step) · TEXT(자유 입력, max 는 최대 글자 수)", example = "NUMBER")
    String inputType,

    @Schema(description = "필수 입력 여부", example = "true")
    boolean required,

    @Schema(description = "NUMBER 최소값. TEXT 면 null", example = "60", nullable = true)
    BigDecimal min,

    @Schema(description = "NUMBER 최대값 · TEXT 최대 글자 수", example = "150", nullable = true)
    BigDecimal max,

    @Schema(description = "NUMBER 증분. TEXT 면 null", example = "0.5", nullable = true)
    BigDecimal step
) {
}
