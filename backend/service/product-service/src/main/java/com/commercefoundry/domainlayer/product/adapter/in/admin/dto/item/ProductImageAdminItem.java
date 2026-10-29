package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import lombok.Builder;

@Builder
@Schema(description = "어드민 상품 미디어")
public record ProductImageAdminItem(

    @Schema(description = "미디어 아이디", example = "4611686018427387904")
    String imageId,

    @Schema(description = "미디어 유형")
    CodeNameDescriptionMetadata imageType,

    @Schema(description = "미디어 URL")
    String url,

    @Schema(description = "너비(px)", nullable = true)
    Integer width,

    @Schema(description = "높이(px)", nullable = true)
    Integer height,

    @Schema(description = "대체 텍스트", nullable = true)
    String altText,

    @Schema(description = "진열 순서")
    int displayOrder,

    @Schema(description = "영상 포스터 URL. 정지 이미지이면 null", nullable = true)
    String posterUrl
) {
}
