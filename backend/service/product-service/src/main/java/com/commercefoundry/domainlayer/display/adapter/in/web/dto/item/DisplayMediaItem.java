package com.commercefoundry.domainlayer.display.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "전시 미디어 — 이미지 또는 영상")
public record DisplayMediaItem(

    @Schema(description = "미디어 종류 — 화면 분기용 제어 코드", allowableValues = {"IMAGE", "VIDEO"}, example = "IMAGE")
    String mediaType,

    @Schema(description = "PC 주소", example = "https://cdn.example.com/displays/hero-pc.jpg")
    String pcUrl,

    @Schema(description = "모바일 주소. 없으면 null — pcUrl 을 쓴다", example = "https://cdn.example.com/displays/hero-mobile.jpg", nullable = true)
    String mobileUrl,

    @Schema(description = "영상 포스터(PC). VIDEO 면 항상 있고 IMAGE 면 null", nullable = true)
    String pcPosterUrl,

    @Schema(description = "영상 포스터(모바일). 없으면 null — pcPosterUrl 을 쓴다", nullable = true)
    String mobilePosterUrl,

    @Schema(description = "대체 텍스트. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", example = "가을 생활한복 착용 사진", nullable = true)
    String altText
) {
}
