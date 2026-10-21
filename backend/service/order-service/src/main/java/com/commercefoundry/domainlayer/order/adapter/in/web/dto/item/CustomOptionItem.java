package com.commercefoundry.domainlayer.order.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "주문 항목의 맞춤 제작 입력 한 칸")
public record CustomOptionItem(

    @Schema(description = "입력 항목 키", example = "chest")
    String fieldKey,

    @Schema(description = "입력값 (앞뒤 공백을 자르고 NFC 로 정규화한 원문 — 서버가 HTML 을 거르지 않는다. 텍스트로만 그린다)", example = "96.5")
    String value
) {
}
