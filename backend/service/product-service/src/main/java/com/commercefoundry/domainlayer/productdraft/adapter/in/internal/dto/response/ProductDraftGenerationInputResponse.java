package com.commercefoundry.domainlayer.productdraft.adapter.in.internal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * operations 생성 작업이 읽는 AI 상품 초안 입력 (internal 전용 — 어드민 DTO 와 공유하지 않는다). 스토리지 키 · ETag · 공개 주소 · 만든 어드민을 싣지 않는다 —
 * 바이트는 {@code generation-images/{index}} 로만 읽는다. 카테고리 아이디는 문구 생성에 쓰지 않아 싣지 않는다.
 */
@Schema(description = "AI 상품 초안 생성 입력 (internal)")
public record ProductDraftGenerationInputResponse(

    @Schema(description = "초안 아이디")
    String draftId,

    @Schema(description = "초안 상태 코드 — REQUESTED 또는 GENERATING")
    String status,

    @Schema(description = "보관 만료 시각")
    Instant expiresAt,

    @Schema(description = "운영자가 입력한 사실 — 이 값만 사실이다")
    Facts facts,

    @Schema(description = "이미지 수. 0 이면 사실만으로 만든다")
    int imageCount,

    @Schema(description = "이미지 메타데이터. 순서가 이미지 읽기의 index 다")
    List<Image> images
) {

    @Schema(description = "운영자 입력 사실")
    public record Facts(

        @Schema(description = "상품명 후보", nullable = true)
        String nameHint,

        @Schema(description = "소재")
        List<String> materials,

        @Schema(description = "세탁 · 관리 방법")
        List<String> careNotes,

        @Schema(description = "치수 메모 (개행 허용)", nullable = true)
        String measurementsNote,

        @Schema(description = "원산지", nullable = true)
        String origin,

        @Schema(description = "인증")
        List<String> certifications,

        @Schema(description = "문구 분위기", nullable = true)
        String tone,

        @Schema(description = "꼭 넣을 표현")
        List<String> mustInclude,

        @Schema(description = "쓰지 않을 표현")
        List<String> mustAvoid
    ) {
    }

    @Schema(description = "이미지 메타데이터")
    public record Image(

        @Schema(description = "이미지 읽기 index (0부터)")
        int index,

        @Schema(description = "이미지 유형 — THUMBNAIL · GALLERY")
        String imageType,

        @Schema(description = "고정한 형식 — image/jpeg · image/png · image/webp")
        String contentType,

        @Schema(description = "고정한 바이트 수")
        long byteLength,

        @Schema(description = "원본 가로 px", nullable = true)
        Integer width,

        @Schema(description = "원본 세로 px", nullable = true)
        Integer height
    ) {
    }

    /** 운영자 입력을 로그로 내지 않는다. */
    @Override
    public String toString() {
        return "ProductDraftGenerationInputResponse[redacted]";
    }
}
