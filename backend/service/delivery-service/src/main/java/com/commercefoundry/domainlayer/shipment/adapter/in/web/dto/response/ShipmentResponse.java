package com.commercefoundry.domainlayer.shipment.adapter.in.web.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/** 고객 주문의 배송 정보 (#18 PR-B2). 어드민 응답과 나눈다 — 주문 반영 여부 · 이력은 내리지 않는다. */
@Builder
@Schema(description = "주문의 배송 정보")
public record ShipmentResponse(

    @Schema(description = "송장(배송) 아이디", example = "4611686018427387906")
    String deliveryId,

    @Schema(description = "주문 아이디", example = "4611686018427387904")
    String orderId,

    @Schema(description = "택배사", example = "{\"code\":\"CJ_LOGISTICS\",\"name\":\"CJ대한통운\",\"description\":\"CJ대한통운\"}")
    CodeNameDescriptionMetadata carrier,

    @Schema(description = "송장 번호 — 택배사 배송 조회에 쓴다", example = "123456789012")
    String trackingNumber,

    @Schema(description = "배송 상태 — 출고 완료 · 배송 중 · 배송 완료. 배송 완료가 주문에 아직 반영되지 않았으면 배송 중이다(주문 상세와 맞춘다)",
        example = "{\"code\":\"IN_TRANSIT\",\"name\":\"배송 중\",\"description\":\"택배사가 상품을 옮기고 있습니다.\"}")
    CodeNameDescriptionMetadata status,

    @Schema(description = "출고 시각 (UTC, ISO-8601)", example = "2026-11-02T09:30:00.123456Z")
    Instant shippedAt,

    @Schema(description = "배송 완료 시각 (UTC, ISO-8601) — 배송 완료가 아니거나 주문 반영 전이면 null", nullable = true, example = "2026-11-04T02:10:00Z")
    Instant deliveredAt
) {
}
