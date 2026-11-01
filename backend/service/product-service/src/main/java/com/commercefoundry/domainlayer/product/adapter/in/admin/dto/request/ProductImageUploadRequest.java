package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 상품 이미지 업로드 발급 요청 (#9 F). 칸 검증은 Processor 가 업무 코드로 한다 — 형식이 아니면 {@code PRODUCT_033}, 크기가 0 이하 · 10MB 초과 · 없음이면 {@code 034}.
 * 업로드 자체는 브라우저가 스토리지에 직접 한다(게이트웨이 · BFF 를 지나지 않는다).
 */
@Schema(description = "상품 이미지 업로드 발급 요청")
public record ProductImageUploadRequest(

    @Schema(description = "올릴 파일 형식 — image/jpeg · image/png · image/webp. 업로드 때 같은 Content-Type 으로 보내야 한다", example = "image/jpeg",
        requiredMode = Schema.RequiredMode.REQUIRED)
    String contentType,

    @Schema(description = "올릴 파일 크기 (바이트, 1 ~ 10485760). 서명에 들어가 다른 크기는 스토리지가 거절한다", example = "524288",
        requiredMode = Schema.RequiredMode.REQUIRED)
    Long contentLength
) {
}
