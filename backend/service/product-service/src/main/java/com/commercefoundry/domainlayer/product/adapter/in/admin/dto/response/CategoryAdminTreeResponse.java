package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.CategoryAdminItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "어드민 카테고리 트리 응답")
public record CategoryAdminTreeResponse(

    @Schema(description = "최상위 카테고리 (숨김 포함). 노출 순서 → 아이디 오름차순. 없으면 빈 배열")
    List<CategoryAdminItem> categories
) {
}
