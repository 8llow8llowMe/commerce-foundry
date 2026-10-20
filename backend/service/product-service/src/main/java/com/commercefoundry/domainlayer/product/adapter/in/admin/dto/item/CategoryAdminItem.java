package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "어드민 카테고리 트리 노드 (숨김 포함)")
public record CategoryAdminItem(

    @Schema(description = "카테고리 아이디", example = "101")
    String categoryId,

    @Schema(description = "상위 카테고리 아이디. 최상위면 null", example = "1", nullable = true)
    String parentId,

    @Schema(description = "카테고리 이름", example = "저고리")
    String name,

    @Schema(description = "같은 상위 안의 노출 순서 (오름차순)", example = "1")
    int displayOrder,

    @Schema(description = "스토어프론트 메뉴 노출 여부. 숨긴 카테고리의 하위는 자기 값과 무관하게 메뉴에서 빠진다", example = "true")
    boolean visible,

    @Schema(description = "하위 카테고리 (숨김 포함). 노출 순서 → 아이디 오름차순. 없으면 빈 배열")
    List<CategoryAdminItem> children
) {
}
