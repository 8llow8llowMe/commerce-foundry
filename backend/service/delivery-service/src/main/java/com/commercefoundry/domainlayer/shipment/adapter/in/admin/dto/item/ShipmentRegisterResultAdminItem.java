package com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/** 송장 등록 한 건 결과 (#18). 실패한 건만 골라 다시 보내면 된다 — 이미 반영된 건은 같은 송장으로 다시 보내도 ALREADY_REGISTERED 다. */
@Builder
@Schema(description = "송장 등록 한 건 결과")
public record ShipmentRegisterResultAdminItem(

    @Schema(description = "주문 아이디", example = "4611686018427387904")
    String orderId,

    @Schema(description = "송장(배송) 아이디 — 등록 · 이미 등록이면 있다. 실패여도 송장이 주문 반영 전으로 남았으면(다시 시도할 건) 있다", nullable = true,
        example = "4611686018427387906")
    String deliveryId,

    @Schema(description = "결과 — REGISTERED(등록 · 출고 반영) · ALREADY_REGISTERED(같은 송장으로 이미 출고됨) · FAILED(등록 못 함)", example = "REGISTERED",
        allowableValues = {"REGISTERED", "ALREADY_REGISTERED", "FAILED"})
    String result,

    @Schema(description = "못 한 이유의 오류 코드 — 성공이면 null", nullable = true, example = "DELIVERY_015")
    String errorCode,

    @Schema(description = "못 한 이유 — 성공이면 null", nullable = true, example = "취소 요청 상태라 출고할 수 없습니다.")
    String message
) {
}
