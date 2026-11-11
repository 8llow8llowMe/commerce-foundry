package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "승인 전 이미지 확인 결과. 확인값을 초안 생성 요청에 그대로 보냅니다")
public record SizeChartImageInspectionResponse(
    @Schema(description = "이미지 아이디", example = "IMG-4611686018427387905")
    String sourceId,

    @Schema(description = "이미지 확인값(SHA-256 소문자 16진수 64자). 이미지가 바뀌면 달라집니다",
        example = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
    String contentHash,

    @Schema(description = "파일 내용으로 판별한 이미지 형식", example = "image/jpeg")
    String contentType,

    @Schema(description = "파일 크기(바이트)", example = "524288")
    long contentLength
) {
}
