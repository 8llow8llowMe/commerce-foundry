package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "어드민 전시 미디어")
public record DisplayMediaAdminItem(

    @Schema(description = "미디어 종류", allowableValues = {"IMAGE", "VIDEO"}, example = "IMAGE")
    String mediaType,

    @Schema(description = "PC 주소", example = "https://cdn.example.com/displays/hero-pc.jpg")
    String pcUrl,

    @Schema(description = "모바일 주소. 없으면 null", nullable = true)
    String mobileUrl,

    @Schema(description = "영상 포스터(PC). VIDEO 면 있고 IMAGE 면 null", nullable = true)
    String pcPosterUrl,

    @Schema(description = "영상 포스터(모바일). 없으면 null", nullable = true)
    String mobilePosterUrl,

    @Schema(description = "대체 텍스트. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", nullable = true)
    String altText
) {
}
