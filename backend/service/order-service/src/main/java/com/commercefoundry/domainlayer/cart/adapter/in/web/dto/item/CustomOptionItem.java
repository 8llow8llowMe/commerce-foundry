package com.commercefoundry.domainlayer.cart.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "담긴 맞춤 제작 입력 한 칸")
public record CustomOptionItem(

    @Schema(description = "입력 항목 키", example = "chest")
    String fieldKey,

    @Schema(description = "입력값 (앞뒤 공백을 자른 값)", example = "96.5")
    String value
) {
}
