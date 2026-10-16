package com.commercefoundry.domainlayer.product.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "상품 대표 이미지")
public record ProductThumbnailItem(

    @Schema(description = "이미지 아이디", example = "88000")
    String imageId,

    @Schema(description = "이미지 URL", example = "https://cdn.example.com/p/7301/thumb.jpg")
    String url,

    @Schema(description = "원본 가로 px. 모르면 null", example = "600", nullable = true)
    Integer width,

    @Schema(description = "원본 세로 px. 모르면 null", example = "800", nullable = true)
    Integer height,

    @Schema(description = "대체 텍스트", example = "저고리 정면", nullable = true)
    String altText
) {
}
