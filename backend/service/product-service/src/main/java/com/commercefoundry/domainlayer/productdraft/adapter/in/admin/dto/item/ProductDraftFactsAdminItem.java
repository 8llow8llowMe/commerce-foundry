package com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/** 운영자가 입력한 사실 — 입력 그대로다(AI 제안과 섞지 않는다). 문구는 평문이다 — HTML 로 해석하지 않는다(텍스트 노드로만 렌더). */
@Builder
@Schema(description = "운영자가 입력한 사실 (입력 그대로)")
public record ProductDraftFactsAdminItem(

    @Schema(description = "상품명 후보. 없으면 null", nullable = true)
    String nameHint,

    @Schema(description = "카테고리 아이디. 없으면 null", example = "12", nullable = true)
    String categoryId,

    @Schema(description = "소재. 없으면 빈 배열")
    List<String> materials,

    @Schema(description = "세탁 · 관리 방법. 없으면 빈 배열")
    List<String> careNotes,

    @Schema(description = "치수 메모 (개행 포함 가능). 없으면 null", nullable = true)
    String measurementsNote,

    @Schema(description = "원산지. 없으면 null", nullable = true)
    String origin,

    @Schema(description = "인증. 없으면 빈 배열")
    List<String> certifications,

    @Schema(description = "문구 분위기. 없으면 null", nullable = true)
    String tone,

    @Schema(description = "꼭 넣을 표현. 없으면 빈 배열")
    List<String> mustInclude,

    @Schema(description = "쓰지 않을 표현. 없으면 빈 배열")
    List<String> mustAvoid
) {
}
