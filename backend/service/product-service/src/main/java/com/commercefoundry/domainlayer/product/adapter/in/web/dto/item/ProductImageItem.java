package com.commercefoundry.domainlayer.product.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "상품 상세 갤러리 미디어 (이미지 · 영상)")
public record ProductImageItem(

    @Schema(description = "미디어 아이디", example = "88001")
    String imageId,

    @Schema(description = "미디어 종류 — IMAGE · VIDEO. 스토어 영상 기능이 꺼져 있으면 VIDEO 는 오지 않는다", allowableValues = {"IMAGE", "VIDEO"}, example = "IMAGE")
    String mediaType,

    @Schema(description = "이미지 URL. VIDEO 면 영상 파일 URL", example = "https://cdn.example.com/p/7301/1.jpg")
    String url,

    @Schema(description = "VIDEO 의 포스터(재생 전 정지) 이미지 URL. IMAGE 면 null", example = "https://cdn.example.com/p/7301/video-poster.jpg", nullable = true)
    String posterUrl,

    @Schema(description = "원본 가로 px. 모르면 null", example = "1200", nullable = true)
    Integer width,

    @Schema(description = "원본 세로 px. 모르면 null", example = "1600", nullable = true)
    Integer height,

    @Schema(description = "대체 텍스트", example = "정면", nullable = true)
    String altText,

    @Schema(description = "노출 순서 (오름차순). 이미지 · 영상이 같은 값이면 아이디 오름차순이다", example = "1")
    int displayOrder
) {
}
