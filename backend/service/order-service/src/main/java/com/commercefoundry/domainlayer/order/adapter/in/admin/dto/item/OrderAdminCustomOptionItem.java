package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;

/** 맞춤 제작 입력 한 칸 (#17 어드민 상세). 화면 · 엑셀은 값을 텍스트로 이스케이프한다. */
@Schema(description = "맞춤 제작 입력 한 칸")
public record OrderAdminCustomOptionItem(

    @Schema(description = "입력 항목 키", example = "chest")
    String fieldKey,

    @Schema(description = "입력값", example = "96.5")
    String value
) {
}
