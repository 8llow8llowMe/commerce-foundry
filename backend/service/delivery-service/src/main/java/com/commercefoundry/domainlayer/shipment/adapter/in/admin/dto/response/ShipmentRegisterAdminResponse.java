package com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response;

import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item.ShipmentRegisterResultAdminItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 송장 일괄 등록 결과 (#18) — 요청 순서대로 주문마다 한 줄. 일부가 실패해도 200 이다. */
@Schema(description = "송장 일괄 등록 결과")
public record ShipmentRegisterAdminResponse(

    @Schema(description = "등록해 출고를 반영한 주문 수", example = "3")
    int registeredCount,

    @Schema(description = "같은 송장으로 이미 출고된 주문 수", example = "0")
    int alreadyRegisteredCount,

    @Schema(description = "등록하지 못한 주문 수 — results 에서 FAILED 인 건", example = "1")
    int failedCount,

    @Schema(description = "주문별 결과 — 요청 순서")
    List<ShipmentRegisterResultAdminItem> results
) {
}
