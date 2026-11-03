package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.application.command.ProductOptionsUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductEditValidationMessage;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "기존 옵션 전체 집합의 이름·노출 순서 편집 요청")
public record ProductOptionsUpdateRequest(
    @Schema(description = "현재 상품 버전", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ProductEditValidationMessage.VERSION_REQUIRED_OR_MIN)
    @PositiveOrZero(message = ProductEditValidationMessage.VERSION_REQUIRED_OR_MIN)
    Long version,

    @Schema(description = "은퇴 행을 포함한 기존 그룹 전체. 배열 순서가 노출 순서이며 추가·누락은 불가", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ProductValidationMessage.PRODUCT_OPTION_GROUP_REQUIRED)
    @Size(max = 5, message = ProductValidationMessage.PRODUCT_OPTION_GROUPS_SIZE_MAX)
    List<@NotNull(message = ProductValidationMessage.PRODUCT_OPTION_GROUP_REQUIRED) @Valid Group> optionGroups
) {
    public ProductOptionsUpdateCommand toCommand(long productId, long actorMemberId) {
        return new ProductOptionsUpdateCommand(productId, version, actorMemberId, optionGroups.stream().map(group ->
            new ProductOptionsUpdateCommand.Group(group.optionGroupId(), group.name().strip(), group.values().stream().map(value ->
                new ProductOptionsUpdateCommand.Value(value.optionValueId(), value.name().strip())).toList())).toList());
    }

    @Schema(description = "기존 옵션 그룹")
    public record Group(
        @Schema(description = "기존 그룹 아이디", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = ProductEditValidationMessage.OPTION_GROUP_ID_REQUIRED_OR_POSITIVE)
        @Positive(message = ProductEditValidationMessage.OPTION_GROUP_ID_REQUIRED_OR_POSITIVE)
        Long optionGroupId,

        @Schema(description = "그룹 이름 (1~30자, 제어·서식 문자 불가)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = ProductValidationMessage.PRODUCT_OPTION_GROUP_NAME_INVALID)
        @Size(max = 30, message = ProductValidationMessage.PRODUCT_OPTION_GROUP_NAME_INVALID)
        @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductValidationMessage.PRODUCT_OPTION_GROUP_NAME_INVALID)
        String name,

        @Schema(description = "은퇴 행을 포함한 그룹의 기존 값 전체 (최대 50개). 배열 순서가 노출 순서", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = ProductValidationMessage.PRODUCT_OPTION_VALUES_SIZE)
        @Size(max = 50, message = ProductValidationMessage.PRODUCT_OPTION_VALUES_SIZE)
        List<@NotNull(message = ProductValidationMessage.PRODUCT_OPTION_VALUE_NAME_INVALID) @Valid Value> values
    ) { }

    @Schema(description = "기존 옵션 값")
    public record Value(
        @Schema(description = "기존 값 아이디. 다른 그룹으로 이동 불가", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = ProductEditValidationMessage.OPTION_VALUE_ID_REQUIRED_OR_POSITIVE)
        @Positive(message = ProductEditValidationMessage.OPTION_VALUE_ID_REQUIRED_OR_POSITIVE)
        Long optionValueId,

        @Schema(description = "값 이름 (1~50자, 제어·서식 문자 불가)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = ProductValidationMessage.PRODUCT_OPTION_VALUE_NAME_INVALID)
        @Size(max = 50, message = ProductValidationMessage.PRODUCT_OPTION_VALUE_NAME_INVALID)
        @Pattern(regexp = ProductValidationMessage.PLAIN_TEXT, message = ProductValidationMessage.PRODUCT_OPTION_VALUE_NAME_INVALID)
        String name
    ) { }
}
