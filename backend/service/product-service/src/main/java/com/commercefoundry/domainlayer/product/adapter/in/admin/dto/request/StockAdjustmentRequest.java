package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.product.application.command.StockAdjustmentCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * 어드민 재고 조정 요청. 수량은 더하고 빼는 값이 아니라 바꿀 절대값이고, 화면에서 본 현재 수량을 {@code expectedQuantity} 로 함께 보낸다 —
 * 그 사이 주문이 차감했으면 409 로 알려 어드민이 다시 보고 조정한다.
 *
 * <p>교차 검증은 {@code @AssertTrue} 메서드다. 오류의 {@code field} 는 메서드 이름에서 온다({@code targetPresent} · {@code expectedQuantityPresent}).
 */
@Builder
@Schema(description = "어드민 재고 조정 요청. quantity · stockManaged 중 하나 이상을 보낸다")
public record StockAdjustmentRequest(

    @Schema(description = "바꿀 판매 가능 수량 (절대값, 0 이상). 없으면 수량을 바꾸지 않는다", example = "12", nullable = true)
    @PositiveOrZero(message = ProductValidationMessage.STOCK_QUANTITY_MIN)
    Long quantity,

    @Schema(
        description = "화면에서 본 현재 판매 가능 수량 (0 이상). quantity 를 보낼 때 필수이고, 현재 수량과 다르면 409(PRODUCT_020)다. "
            + "재고를 처음 등록하는 SKU 는 0 이다",
        example = "10", nullable = true
    )
    @PositiveOrZero(message = ProductValidationMessage.STOCK_EXPECTED_QUANTITY_MIN)
    Long expectedQuantity,

    @Schema(description = "바꿀 재고 관리 여부. false 면 무제한 판매. 없으면 바꾸지 않는다 (처음 등록하는 SKU 는 true 로 만든다)", example = "true", nullable = true)
    Boolean stockManaged,

    @Schema(description = "조정 사유 — RESTOCK(입고) · RECOUNT(실사) · DAMAGE(파손·불량) · MANAGEMENT_CHANGE(관리 여부 변경) · OTHER(기타)", example = "RESTOCK")
    @NotNull(message = ProductValidationMessage.STOCK_ADJUSTMENT_REASON_REQUIRED)
    StockAdjustmentReason reason,

    @Schema(description = "조정 메모 (200자 이하)", example = "10월 입고분", nullable = true)
    @Size(max = 200, message = ProductValidationMessage.STOCK_ADJUSTMENT_MEMO_SIZE_MAX)
    String memo
) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = ProductValidationMessage.STOCK_ADJUSTMENT_TARGET_REQUIRED)
    public boolean isTargetPresent() {
        return quantity != null || stockManaged != null;
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = ProductValidationMessage.STOCK_EXPECTED_QUANTITY_REQUIRED)
    public boolean isExpectedQuantityPresent() {
        return quantity == null || expectedQuantity != null;
    }

    public StockAdjustmentCommand toCommand(long skuId, long adminMemberId) {
        return StockAdjustmentCommand.builder()
            .skuId(skuId)
            .quantity(quantity)
            .expectedQuantity(expectedQuantity)
            .stockManaged(stockManaged)
            .reason(reason)
            .memo(memo)
            .adminMemberId(adminMemberId)
            .build();
    }
}
