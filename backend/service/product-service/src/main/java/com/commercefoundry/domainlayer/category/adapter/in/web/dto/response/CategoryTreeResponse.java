package com.commercefoundry.domainlayer.category.adapter.in.web.dto.response;

import com.commercefoundry.domainlayer.category.adapter.in.web.dto.item.CategoryItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "카테고리 트리 응답")
public record CategoryTreeResponse(

    @Schema(description = "보이는 최상위 카테고리. 숨긴 카테고리는 그 하위까지 빠진다. 노출 순서 → 아이디 오름차순. 없으면 빈 배열")
    List<CategoryItem> categories
) {
}
