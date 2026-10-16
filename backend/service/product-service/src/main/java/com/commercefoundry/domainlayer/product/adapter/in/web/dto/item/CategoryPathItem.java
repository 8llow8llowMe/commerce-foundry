package com.commercefoundry.domainlayer.product.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "상품 대표 카테고리 경로의 한 칸")
public record CategoryPathItem(

    @Schema(description = "카테고리 아이디", example = "101")
    String categoryId,

    @Schema(description = "카테고리 이름", example = "상의")
    String name
) {
}
