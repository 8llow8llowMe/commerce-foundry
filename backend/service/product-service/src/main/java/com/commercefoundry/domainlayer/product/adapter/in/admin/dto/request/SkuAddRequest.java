package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.application.command.SkuAddCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminValidationMessage;
import com.commercefoundry.domainlayer.product.application.model.ProductSaleStatusFilter;
import com.commercefoundry.domainlayer.product.domain.model.HsCode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** {@code POST /api/v1/admin/products/{productId}/skus} — 기존 그룹에 옵션 값을 더하고 새 조합 SKU 를 만든다 (#9 PR-C1). */
@Schema(description = "옵션 값 · 새 SKU 추가 요청")
public record SkuAddRequest(
    @Schema(description = "편집용 상세에서 읽은 상품 버전", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = SkuAdminValidationMessage.VERSION)
    @PositiveOrZero(message = SkuAdminValidationMessage.VERSION)
    @JsonDeserialize(using = SkuBulkUpdateRequest.IntegerLongDeserializer.class)
    Long version,

    @Schema(description = "기존 옵션 그룹에 더할 값 (0~50개). 배열 순서대로 그룹 끝에 붙는다", nullable = true)
    @Size(max = 50, message = SkuAdminValidationMessage.NEW_OPTION_VALUES)
    List<@NotNull(message = SkuAdminValidationMessage.NEW_OPTION_VALUES) @Valid NewOptionValue> newOptionValues,

    @Schema(description = "새 조합 SKU (1~100개)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = SkuAdminValidationMessage.NEW_SKUS)
    @Size(min = 1, max = 100, message = SkuAdminValidationMessage.NEW_SKUS)
    List<@NotNull(message = SkuAdminValidationMessage.NEW_SKUS) @Valid NewSku> skus
) {

    public SkuAddCommand toCommand(long productId, long actorMemberId) {
        return new SkuAddCommand(productId, version, actorMemberId,
            newOptionValues == null ? List.of() : newOptionValues.stream().map(NewOptionValue::toCommand).toList(),
            skus.stream().map(NewSku::toCommand).toList());
    }

    @Schema(description = "기존 그룹에 더할 옵션 값")
    public record NewOptionValue(
        @Schema(description = "기존 옵션 그룹 아이디. 숫자 또는 십진수 문자열", example = "4611686018427387904", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = SkuAdminValidationMessage.ID)
        @Positive(message = SkuAdminValidationMessage.ID)
        @JsonDeserialize(using = SkuBulkUpdateRequest.IntegerLongDeserializer.class)
        Long optionGroupId,

        @Schema(description = "값 이름 (1~50자). 그룹 안에서 이미 쓴 이름(은퇴 값 포함)은 쓸 수 없다", example = "XL", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = SkuAdminValidationMessage.NEW_OPTION_VALUE_NAME)
        @Size(max = 50, message = SkuAdminValidationMessage.NEW_OPTION_VALUE_NAME)
        @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = SkuAdminValidationMessage.NEW_OPTION_VALUE_NAME)
        String name
    ) {
        SkuAddCommand.NewOptionValue toCommand() {
            return new SkuAddCommand.NewOptionValue(optionGroupId, name.strip());
        }
    }

    @Schema(description = "새 조합 SKU")
    public record NewSku(
        @Schema(description = "옵션 그룹 노출 순서대로 고른 값 이름. 옵션 없는 상품에는 SKU 를 더할 수 없다", example = "[\"블랙\", \"XL\"]",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = SkuAdminValidationMessage.OPTION_VALUE_NAMES)
        @Size(min = 1, max = 5, message = SkuAdminValidationMessage.OPTION_VALUE_NAMES)
        List<@NotBlank(message = SkuAdminValidationMessage.OPTION_VALUE_NAMES)
            @Size(max = 50, message = SkuAdminValidationMessage.OPTION_VALUE_NAMES) String> optionValues,

        @Schema(description = "추가금. 상품 통화와 같아야 한다", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = SkuAdminValidationMessage.PRICE_REQUIRED)
        @Valid
        SkuBulkUpdateRequest.Price additionalPrice,

        @Schema(description = "ON_SALE 또는 STOP_SELLING", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = SkuAdminValidationMessage.STATUS)
        ProductSaleStatusFilter saleStatus,

        @Schema(description = "배송 무게(g)", nullable = true)
        @Positive(message = SkuAdminValidationMessage.WEIGHT)
        @JsonDeserialize(using = SkuBulkUpdateRequest.IntegerIntDeserializer.class)
        Integer weightGrams,

        @Schema(description = "HS 코드. 점·하이픈·공백 제거 후 숫자 6~10자리", nullable = true)
        @Size(max = 20, message = SkuAdminValidationMessage.HS_CODE)
        String hsCode,

        @Schema(description = "처음 재고. 없으면 품절로 생긴다", nullable = true)
        @Valid
        InitialStock initialStock
    ) {
        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = SkuAdminValidationMessage.HS_CODE)
        public boolean isHsCodeValid() {
            return hsCode == null || hsCode.isBlank() || normalizedHsCode().matches("[0-9]{6,10}");
        }

        private String normalizedHsCode() {
            return hsCode.replaceAll("[.\\- ]", "");
        }

        SkuAddCommand.NewSku toCommand() {
            return new SkuAddCommand.NewSku(optionValues.stream().map(String::strip).toList(), additionalPrice.toMoney(), saleStatus.toSaleStatus(),
                weightGrams, hsCode == null || hsCode.isBlank() ? null : HsCode.fromStoredOrNull(normalizedHsCode()),
                initialStock == null ? null : new SkuAddCommand.InitialStock(initialStock.stockManaged(), initialStock.quantity()));
        }
    }

    @Schema(description = "처음 재고 — 재고 행과 조정 이력(입고) 1건을 함께 만든다")
    public record InitialStock(
        @Schema(description = "재고를 관리하는가. false 면 무제한 판매", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = SkuAdminValidationMessage.INITIAL_STOCK)
        Boolean stockManaged,

        @Schema(description = "판매 가능 수량 (0 이상)", example = "30", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = SkuAdminValidationMessage.INITIAL_STOCK)
        @PositiveOrZero(message = SkuAdminValidationMessage.INITIAL_STOCK)
        @JsonDeserialize(using = SkuBulkUpdateRequest.IntegerLongDeserializer.class)
        Long quantity
    ) {
    }
}
