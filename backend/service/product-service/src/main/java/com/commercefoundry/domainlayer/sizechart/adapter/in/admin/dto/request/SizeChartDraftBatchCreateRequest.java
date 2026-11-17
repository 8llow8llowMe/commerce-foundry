package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Schema(description = "상품별 사이즈표 초안 일괄 제출. 재시도할 항목의 UUID와 입력은 그대로 유지합니다")
public record SizeChartDraftBatchCreateRequest(
    @Schema(description = "서로 다른 상품 1~20개. 반환 순서는 요청 순서입니다")
    @NotNull(message = SizeChartDraftValidationMessage.BATCH)
    @Size(min = 1, max = 20, message = SizeChartDraftValidationMessage.BATCH)
    List<@NotNull(message = SizeChartDraftValidationMessage.BATCH) @Valid Item> items
) {
    @Schema(description = "개별 상품의 초안 제출")
    public record Item(
        @Schema(description = "상품 아이디", example = "4611686018427387904")
        @NotNull(message = SizeChartDraftValidationMessage.PRODUCT_ID)
        @Positive(message = SizeChartDraftValidationMessage.PRODUCT_ID)
        Long productId,

        @Schema(description = "항목별 UUID. 같은 항목을 재시도할 때 재사용합니다")
        @NotNull(message = SizeChartDraftValidationMessage.ITEM_KEY)
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", message = SizeChartDraftValidationMessage.ITEM_KEY)
        String idempotencyKey,

        @Schema(description = "단건 생성과 같은 입력. TEXT 검토 스냅숏 또는 IMAGE 확인·승인 입력")
        @NotNull(message = SizeChartDraftValidationMessage.BATCH)
        @Valid
        SizeChartDraftCreateRequest input
    ) {
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = SizeChartDraftValidationMessage.BATCH)
    public boolean isSelectionUnique() {
        if (items == null || items.stream().anyMatch(Objects::isNull)) {
            return true;
        }
        return items.stream().map(Item::productId).distinct().count() == items.size()
            && items.stream().map(Item::idempotencyKey).filter(Objects::nonNull).map(k -> k.toLowerCase(Locale.ROOT)).distinct().count() == items.size();
    }

    public List<SizeChartDraftCreateCommand> toCommands(long actorId) {
        return items.stream().map(item -> item.input().toCommand(item.productId(), actorId, UUID.fromString(item.idempotencyKey()))).toList();
    }

    @Override
    public String toString() {
        return "SizeChartDraftBatchCreateRequest[redacted]";
    }
}
