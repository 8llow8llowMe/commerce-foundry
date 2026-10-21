package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.validation.ValidCategoryName;
import com.commercefoundry.domainlayer.product.application.command.CategoryUpsertCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Builder;

/**
 * 어드민 카테고리 생성 · 수정 요청. 수정은 전체 교체라 생성과 본문이 같다 — {@code parentId} 를 바꾸면 하위까지 함께 옮긴다.
 */
@Builder
@Schema(description = "어드민 카테고리 생성 · 수정 요청 (수정은 전체 교체)")
public record CategoryUpsertRequest(

    @Schema(
        description = "상위 카테고리 아이디 — 트리 응답의 categoryId. 문자열로 보내도 된다. 최상위면 null 이다. 깊이는 3단계(대·중·소)까지이고, "
            + "수정에서 자기 자신 · 자기 하위를 지정하면 400(PRODUCT_041)이다",
        example = "101", nullable = true
    )
    @Positive(message = ProductValidationMessage.CATEGORY_PARENT_ID_POSITIVE)
    Long parentId,

    @Schema(
        description = "카테고리 이름. 앞뒤 공백(전각 공백 포함)을 빼고 저장하며 뺀 뒤 1~50자다. 공백 · 서식 문자만이면 PRODUCT_132, "
            + "제어 · 서식 문자(개행 · 탭 · 제로폭 공백 등)가 있으면 PRODUCT_138 이다",
        example = "저고리"
    )
    @ValidCategoryName
    String name,

    @Schema(description = "같은 상위 안의 노출 순서 (0 이상, 오름차순). 같으면 아이디 오름차순이다", example = "1")
    @NotNull(message = ProductValidationMessage.CATEGORY_DISPLAY_ORDER_REQUIRED)
    @PositiveOrZero(message = ProductValidationMessage.CATEGORY_DISPLAY_ORDER_MIN)
    Integer displayOrder,

    @Schema(description = "스토어프론트 메뉴 노출 여부. false 면 하위까지 메뉴에서 빠지지만 상품 노출은 막지 않는다", example = "true")
    @NotNull(message = ProductValidationMessage.CATEGORY_VISIBLE_REQUIRED)
    Boolean visible
) {

    public CategoryUpsertCommand toCommand() {
        return CategoryUpsertCommand.builder()
            .parentId(parentId)
            .name(name.strip())
            .displayOrder(displayOrder)
            .visible(visible)
            .build();
    }
}
