package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response;

import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderStatusChangeResultItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 일괄 상태 변경 결과 (#17) — 요청 순서대로 주문마다 한 줄. 일부가 실패해도 200 이다. */
@Schema(description = "일괄 상태 변경 결과")
public record OrderStatusBulkChangeResponse(

    @Schema(description = "바꾼 주문 수", example = "3")
    int changedCount,

    @Schema(description = "이미 그 상태라 그대로 둔 주문 수", example = "0")
    int unchangedCount,

    @Schema(description = "못 바꾼 주문 수 — results 에서 FAILED 인 건", example = "1")
    int failedCount,

    @Schema(description = "주문별 결과 — 요청 순서")
    List<OrderStatusChangeResultItem> results
) {
}
