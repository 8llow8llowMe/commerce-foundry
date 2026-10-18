package com.commercefoundry.domainlayer.product.adapter.in.internal.dto.request;

import com.commercefoundry.domainlayer.product.application.command.StockReservationCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
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
 * 서비스 간 재고 선점 요청. 같은 SKU 가 여러 번 오면 수량을 합친다 — 상한 100 은 합치기 전 원문 개수다.
 */
@Builder
@Schema(description = "재고 선점 요청 (order-service → product-service)")
public record StockReservationRequest(

    @Schema(description = "주문 아이디. 주문당 활성 선점은 하나이고, 같은 주문 · 같은 항목의 재요청은 기존 선점을 그대로 돌려준다", example = "4611686018427387904")
    @NotNull(message = ProductValidationMessage.STOCK_RESERVATION_ORDER_ID_REQUIRED)
    @Positive(message = ProductValidationMessage.STOCK_RESERVATION_ORDER_ID_POSITIVE)
    Long orderId,

    @Schema(description = "선점할 SKU 와 수량 (1~100개)")
    @NotEmpty(message = ProductValidationMessage.STOCK_RESERVATION_ITEMS_REQUIRED)
    @Size(max = StockReservationRequest.MAX_ITEMS, message = ProductValidationMessage.STOCK_RESERVATION_ITEMS_SIZE_MAX)
    List<@Valid @NotNull(message = ProductValidationMessage.STOCK_RESERVATION_ITEM_REQUIRED) Item> items
) {

    /** 한 번에 받는 항목 상한 — 서비스 간 벌크 조회 상한과 같다 ({@code api-design-guide.md} §1-3). */
    public static final int MAX_ITEMS = 100;

    /**
     * 항목 하나의 수량 상한. 같은 SKU 를 합쳐도 {@code MAX_ITEMS × MAX_QUANTITY = 1,000,000} 이라 int 합산({@code Math.addExact})이 넘치지 않는다 —
     * 상한이 없으면 합산 오버플로가 400 이 아니라 500 으로 샌다. 한 주문에 한 SKU 를 만 개 넘게 사는 경우는 쇼핑몰 주문이 아니다.
     */
    public static final int MAX_QUANTITY = 10_000;

    public StockReservationCommand toCommand() {
        return new StockReservationCommand(orderId, items.stream().map(item -> new StockReservationCommand.Item(item.skuId(), item.quantity())).toList());
    }

    @Builder
    @Schema(description = "선점 항목 한 건")
    public record Item(

        @Schema(description = "SKU 아이디", example = "9004")
        @NotNull(message = ProductValidationMessage.STOCK_RESERVATION_SKU_ID_REQUIRED)
        @Positive(message = ProductValidationMessage.STOCK_RESERVATION_SKU_ID_POSITIVE)
        Long skuId,

        @Schema(description = "수량 (1~10000)", example = "2")
        @NotNull(message = ProductValidationMessage.STOCK_RESERVATION_QUANTITY_REQUIRED)
        @Positive(message = ProductValidationMessage.STOCK_RESERVATION_QUANTITY_POSITIVE)
        @Max(value = StockReservationRequest.MAX_QUANTITY, message = ProductValidationMessage.STOCK_RESERVATION_QUANTITY_MAX)
        Integer quantity
    ) {
    }
}
