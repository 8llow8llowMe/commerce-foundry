package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.request;

import com.commercefoundry.domainlayer.product.application.command.StockRestorationCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.domain.model.StockRestorationReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Builder;

/**
 * 서비스 간 결제 후 재고 복원 요청. 같은 SKU 가 여러 번 오면 수량을 합친다 — 상한 100 은 합치기 전 원문 개수다.
 * 항목 상한 · 수량 상한과 그 검증 코드는 선점 요청({@link StockReservationRequest})과 같다.
 */
@Builder
@Schema(description = "결제 후 재고 복원 요청 (order-service → product-service)")
public record StockRestorationRequest(

    @Schema(description = "클레임(결제 후 취소 · 반품) 아이디. 멱등 키다 — 같은 클레임 · 같은 내용의 재요청은 기존 복원을 그대로 돌려준다", example = "4611686018427388201")
    @NotNull(message = ProductValidationMessage.STOCK_RESTORATION_CLAIM_ID_REQUIRED)
    @Positive(message = ProductValidationMessage.STOCK_RESTORATION_CLAIM_ID_POSITIVE)
    Long claimId,

    @Schema(description = "복원 대상 확정(CONFIRMED) 선점 아이디", example = "4611686018427388001")
    @NotNull(message = ProductValidationMessage.STOCK_RESTORATION_RESERVATION_ID_REQUIRED)
    @Positive(message = ProductValidationMessage.STOCK_RESERVATION_ID_POSITIVE)
    Long reservationId,

    @Schema(
        description = "복원 사유 — CANCELLATION(결제 후 취소) · RETURN(반품 입고. 검수 뒤 어드민이 재고로 되돌림을 고른 경우에만 보낸다)",
        allowableValues = {"CANCELLATION", "RETURN"}, example = "CANCELLATION"
    )
    @NotNull(message = ProductValidationMessage.STOCK_RESTORATION_REASON_REQUIRED)
    StockRestorationReason reason,

    @Schema(description = "복원할 SKU 와 수량 (1~100개). SKU 마다 (선점 수량 − 이미 복원한 수량)을 넘지 못한다")
    @NotEmpty(message = ProductValidationMessage.STOCK_RESERVATION_ITEMS_REQUIRED)
    @Size(max = StockReservationRequest.MAX_ITEMS, message = ProductValidationMessage.STOCK_RESERVATION_ITEMS_SIZE_MAX)
    List<@Valid @NotNull(message = ProductValidationMessage.STOCK_RESERVATION_ITEM_REQUIRED) Item> items
) {

    public StockRestorationCommand toCommand() {
        return new StockRestorationCommand(
            claimId, reservationId, reason, items.stream().map(item -> new StockRestorationCommand.Item(item.skuId(), item.quantity())).toList()
        );
    }

    @Builder
    @Schema(description = "복원 항목 한 건")
    public record Item(

        @Schema(description = "SKU 아이디", example = "9004")
        @NotNull(message = ProductValidationMessage.STOCK_RESERVATION_SKU_ID_REQUIRED)
        @Positive(message = ProductValidationMessage.STOCK_RESERVATION_SKU_ID_POSITIVE)
        Long skuId,

        @Schema(description = "수량 (1~10000)", example = "1")
        @NotNull(message = ProductValidationMessage.STOCK_RESERVATION_QUANTITY_REQUIRED)
        @Positive(message = ProductValidationMessage.STOCK_RESERVATION_QUANTITY_POSITIVE)
        @Max(value = StockReservationRequest.MAX_QUANTITY, message = ProductValidationMessage.STOCK_RESERVATION_QUANTITY_MAX)
        Integer quantity
    ) {
    }
}
