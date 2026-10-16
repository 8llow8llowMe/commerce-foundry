package com.commercefoundry.domainlayer.category.adapter.in.web.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "카테고리 노드")
public record CategoryItem(

    @Schema(description = "카테고리 아이디", example = "101")
    String categoryId,

    @Schema(description = "카테고리 이름", example = "상의")
    String name,

    @Schema(description = "같은 상위 안의 노출 순서 (오름차순)", example = "1")
    int displayOrder,

    @Schema(description = "하위 카테고리. 노출 순서 → 아이디 오름차순. 없으면 빈 배열")
    List<CategoryItem> children
) {
}
