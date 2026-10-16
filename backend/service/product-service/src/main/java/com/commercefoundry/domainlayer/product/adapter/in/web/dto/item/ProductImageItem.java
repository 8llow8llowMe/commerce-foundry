package com.commercefoundry.domainlayer.product.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "상품 상세 갤러리 이미지")
public record ProductImageItem(

    @Schema(description = "이미지 아이디", example = "88001")
    String imageId,

    @Schema(description = "이미지 URL", example = "https://cdn.example.com/p/7301/1.jpg")
    String url,

    @Schema(description = "원본 가로 px. 모르면 null", example = "1200", nullable = true)
    Integer width,

    @Schema(description = "원본 세로 px. 모르면 null", example = "1600", nullable = true)
    Integer height,

    @Schema(description = "대체 텍스트", example = "정면", nullable = true)
    String altText,

    @Schema(description = "갤러리 안의 노출 순서 (오름차순)", example = "1")
    int displayOrder
) {
}
