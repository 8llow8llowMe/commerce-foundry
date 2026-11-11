package com.commercefoundry.domainlayer.sizechart.adapter.in.internal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record SizeChartGenerationInputResponse(
    @Schema(description = "초안 식별자")
    String draftId,

    @Schema(description = "저장된 입력 SHA-256")
    String inputHash,

    @Schema(description = "개인정보를 제거한 불변 입력 JSON. IMAGE는 스토리지 키를 뺀 순서 · 해시 · 형식만 담는다")
    String sourceJson,

    @Schema(description = "불변 템플릿 JSON")
    String templateJson,

    @Schema(description = "입력 방식. TEXT 또는 IMAGE")
    String mode,

    @Schema(description = "IMAGE 이미지 수. TEXT는 0")
    int imageCount,

    @Schema(description = "IMAGE 이미지별 메타데이터. 순서가 이미지 읽기의 index다. TEXT는 빈 배열")
    List<Image> images
) {
    public record Image(
        @Schema(description = "이미지 읽기 index(0부터)")
        int index,

        @Schema(description = "승인한 바이트의 SHA-256")
        String sha256,

        @Schema(description = "magic bytes로 판별한 MIME")
        String contentType
    ) {
    }

    @Override
    public String toString() {
        return "SizeChartGenerationInputResponse[redacted]";
    }
}
