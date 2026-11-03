package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.application.command.OptionRetirementCommand;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminValidationMessage;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** {@code POST /api/v1/admin/products/{productId}/option-retirements} — 옵션 그룹 · 값 은퇴 (#9 PR-C2). */
@Schema(description = "옵션 은퇴 요청")
public record OptionRetirementRequest(
    @Schema(description = "편집용 상세에서 읽은 상품 버전", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = SkuAdminValidationMessage.VERSION)
    @PositiveOrZero(message = SkuAdminValidationMessage.VERSION)
    @JsonDeserialize(using = SkuBulkUpdateRequest.IntegerLongDeserializer.class)
    Long version,

    @Schema(description = "은퇴할 옵션(그룹) 아이디 (50개까지). 그룹을 은퇴하면 그 옵션을 쓰는 SKU 전부가 판매 종료된다", nullable = true,
        example = "[\"4611686018427387904\"]")
    @Size(max = 50, message = SkuAdminValidationMessage.RETIREMENT_TARGETS)
    List<@NotNull(message = SkuAdminValidationMessage.ID) @Positive(message = SkuAdminValidationMessage.ID) Long> optionGroupIds,

    @Schema(description = "은퇴할 옵션 값 아이디 (50개까지)", nullable = true, example = "[\"4611686018427387905\"]")
    @Size(max = 50, message = SkuAdminValidationMessage.RETIREMENT_TARGETS)
    List<@NotNull(message = SkuAdminValidationMessage.ID) @Positive(message = SkuAdminValidationMessage.ID) Long> optionValueIds
) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = SkuAdminValidationMessage.RETIREMENT_TARGETS)
    public boolean isAnyTarget() {
        return (optionGroupIds != null && !optionGroupIds.isEmpty()) || (optionValueIds != null && !optionValueIds.isEmpty());
    }

    public OptionRetirementCommand toCommand(long productId, long actorMemberId) {
        return new OptionRetirementCommand(productId, version, actorMemberId,
            optionGroupIds == null ? java.util.Set.of() : new LinkedHashSet<>(optionGroupIds),
            optionValueIds == null ? java.util.Set.of() : new LinkedHashSet<>(optionValueIds));
    }
}
