package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.application.command.ProductPricingUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductEditValidationMessage;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(description = "상품 기본 판매가 · 정가 전체 교체 요청")
public record ProductPricingUpdateRequest(
    @Schema(description = "편집용 상세 조회에서 받은 상품 버전", example = "0")
    @NotNull(message = ProductEditValidationMessage.VERSION_REQUIRED_OR_MIN)
    @PositiveOrZero(message = ProductEditValidationMessage.VERSION_REQUIRED_OR_MIN) Long version,

    @Schema(description = "기본 판매가. 현재 상품과 같은 통화여야 한다")
    @NotNull(message = ProductValidationMessage.PRODUCT_PRICE_REQUIRED)
    @Valid ProductCreateRequest.MoneyRequest salePrice,

    @Schema(description = "정가. 생략하거나 null 이면 기존 정가를 삭제한다. 판매가와 같은 통화이며 판매가보다 커야 한다", nullable = true)
    @Valid ProductCreateRequest.MoneyRequest listPrice
) {
    public ProductPricingUpdateCommand toCommand(long productId, long actorMemberId) {
        return new ProductPricingUpdateCommand(productId, version, actorMemberId, salePrice.toMoney(), listPrice == null ? null : listPrice.toMoney());
    }
}
