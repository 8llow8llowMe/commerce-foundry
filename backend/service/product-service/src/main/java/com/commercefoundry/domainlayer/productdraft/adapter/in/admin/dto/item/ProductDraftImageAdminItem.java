package com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/** 초안 근거 이미지 (배열 순서 = 화면 순서). */
@Builder
@Schema(description = "초안 근거 이미지")
public record ProductDraftImageAdminItem(

    @Schema(description = "이미지 주소", example = "https://cdn.example.com/media/drafts/a.jpg")
    String url,

    @Schema(description = "이미지 유형 — THUMBNAIL(대표) · GALLERY(갤러리)", example = "THUMBNAIL", allowableValues = {"THUMBNAIL", "GALLERY"})
    String imageType,

    @Schema(description = "원본 가로 px. 모르면 null", nullable = true)
    Integer width,

    @Schema(description = "원본 세로 px. 모르면 null", nullable = true)
    Integer height
) {
}
