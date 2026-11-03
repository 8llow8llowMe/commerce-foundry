package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.application.command.ProductCategoryUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.application.exception.ProductEditValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "상품 대표 · 진열 카테고리 전체 교체 요청")
public record ProductCategoryUpdateRequest(
    @Schema(description = "편집용 상세 조회에서 받은 상품 버전", example = "0")
    @NotNull(message = ProductEditValidationMessage.VERSION_REQUIRED_OR_MIN)
    @PositiveOrZero(message = ProductEditValidationMessage.VERSION_REQUIRED_OR_MIN) Long version,

    @Schema(description = "대표 카테고리 아이디", example = "100")
    @NotNull(message = ProductValidationMessage.PRODUCT_CATEGORY_ID_REQUIRED)
    @Positive(message = ProductValidationMessage.CATEGORY_ID_POSITIVE) Long categoryId,

    @Schema(description = "진열 카테고리 아이디 목록. 최대 20개, 빈 배열 허용. 중복 제거 후 대표를 자동 포함하며 입력 순서는 진열 순서가 아니다.")
    @NotNull(message = ProductValidationMessage.IDS_REQUIRED)
    @Size(max = 20, message = ProductValidationMessage.PRODUCT_CATEGORY_IDS_SIZE_MAX)
    List<@NotNull(message = ProductValidationMessage.PRODUCT_CATEGORY_IDS_ELEMENT_REQUIRED)
        @Positive(message = ProductValidationMessage.CATEGORY_ID_POSITIVE) Long> categoryIds
) {
    public ProductCategoryUpdateCommand toCommand(long productId, long actorMemberId) {
        return new ProductCategoryUpdateCommand(productId, version, actorMemberId, categoryId, List.copyOf(categoryIds));
    }
}
