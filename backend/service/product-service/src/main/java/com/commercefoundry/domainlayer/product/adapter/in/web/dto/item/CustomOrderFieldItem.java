package com.commercefoundry.domainlayer.product.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.Builder;

@Builder
@Schema(description = "맞춤 제작 입력 항목")
public record CustomOrderFieldItem(

    @Schema(description = "입력 항목 키. 주문 생성 입력값의 키와 검증 오류 fieldErrors[].field 에 같은 키를 쓴다", example = "chest")
    String fieldKey,

    @Schema(description = "표시 이름", example = "가슴둘레")
    String label,

    @Schema(description = "입력 유형 — NUMBER(숫자, min · max · step 사용) · TEXT(자유 입력, max 는 최대 글자 수)", example = "NUMBER", allowableValues = {"NUMBER", "TEXT"})
    String inputType,

    @Schema(description = "단위", example = "cm", nullable = true)
    String unit,

    @Schema(description = "필수 입력 여부", example = "true")
    boolean required,

    @Schema(description = "최소값 (NUMBER)", example = "60", nullable = true)
    BigDecimal min,

    @Schema(description = "최대값 (NUMBER) · 최대 글자 수 (TEXT)", example = "150", nullable = true)
    BigDecimal max,

    @Schema(description = "증분 (NUMBER)", example = "0.5", nullable = true)
    BigDecimal step,

    @Schema(description = "도움말", example = "겨드랑이 아래 가장 넓은 부분", nullable = true)
    String helpText
) {
}
