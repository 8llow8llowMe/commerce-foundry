package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "어드민 카테고리 생성 · 수정 결과 (하위 목록 없이 자기 자신만)")
public record CategoryAdminResponse(

    @Schema(description = "카테고리 아이디", example = "101")
    String categoryId,

    @Schema(description = "상위 카테고리 아이디. 최상위면 null", example = "1", nullable = true)
    String parentId,

    @Schema(description = "카테고리 이름 (앞뒤 공백을 뺀 값)", example = "저고리")
    String name,

    @Schema(description = "같은 상위 안의 노출 순서 (오름차순)", example = "1")
    int displayOrder,

    @Schema(description = "스토어프론트 메뉴 노출 여부", example = "true")
    boolean visible
) {
}
