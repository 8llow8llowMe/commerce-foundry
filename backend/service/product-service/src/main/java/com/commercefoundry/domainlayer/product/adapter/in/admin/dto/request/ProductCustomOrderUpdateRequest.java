package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.application.command.ProductCustomOrderCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductCustomOrderUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductEditValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/** {@code PUT /api/v1/admin/products/{productId}/custom-order} — 맞춤 제작 설정 전체 교체 (#9 PR-B). 항목 규칙은 등록 본문의 {@code customOrder} 와 같다. */
@Schema(description = "맞춤 제작 설정 전체 교체 요청")
public record ProductCustomOrderUpdateRequest(
    @Schema(description = "현재 상품 버전", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ProductEditValidationMessage.VERSION_REQUIRED_OR_MIN)
    @PositiveOrZero(message = ProductEditValidationMessage.VERSION_REQUIRED_OR_MIN)
    Long version,

    @Schema(description = "맞춤 제작 상품인가. 꺼도 입력 항목은 남는다", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ProductEditValidationMessage.CUSTOM_ORDER_ENABLED_REQUIRED)
    Boolean enabled,

    @Schema(description = "고객 안내 문구 (500자 이하, 줄바꿈 가능). 비우면 지운다", nullable = true)
    @Size(max = 500, message = ProductEditValidationMessage.CUSTOM_ORDER_NOTICE_INVALID)
    @Pattern(regexp = CustomOrderSettingsRequest.NOTICE_TEXT, message = ProductEditValidationMessage.CUSTOM_ORDER_NOTICE_INVALID)
    String notice,

    @Schema(description = "제작 소요일 (1~365). 비우면 지운다", example = "14", nullable = true)
    @Min(value = 1, message = ProductEditValidationMessage.CUSTOM_ORDER_LEAD_TIME_RANGE)
    @Max(value = 365, message = ProductEditValidationMessage.CUSTOM_ORDER_LEAD_TIME_RANGE)
    Integer leadTimeDays,

    @Schema(description = "입력 항목 전체 (0~20개). 배열 순서가 노출 순서, 빠진 키는 삭제", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = ProductEditValidationMessage.CUSTOM_ORDER_FIELDS_SIZE)
    @Size(max = 20, message = ProductEditValidationMessage.CUSTOM_ORDER_FIELDS_SIZE)
    List<@NotNull(message = ProductEditValidationMessage.CUSTOM_ORDER_FIELDS_SIZE) @Valid CustomOrderFieldRequest> fields
) {

    public ProductCustomOrderUpdateCommand toCommand(long productId, long actorMemberId) {
        return new ProductCustomOrderUpdateCommand(productId, version, actorMemberId, new ProductCustomOrderCommand(
            enabled, CustomOrderFieldRequest.blankToNull(notice), leadTimeDays, CustomOrderFieldRequest.toCommands(fields)));
    }
}
