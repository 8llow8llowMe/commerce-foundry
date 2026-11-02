package com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/** 어드민 송장 목록 한 줄 (#18 PR-B2). 이력은 상세({@code GET /api/v1/admin/deliveries/{deliveryId}})에 있다. */
@Builder
@Schema(description = "송장 목록 한 줄")
public record ShipmentSummaryAdminItem(

    @Schema(description = "송장(배송) 아이디", example = "4611686018427387906")
    String deliveryId,

    @Schema(description = "주문 아이디", example = "4611686018427387904")
    String orderId,

    @Schema(description = "택배사", example = "{\"code\":\"CJ_LOGISTICS\",\"name\":\"CJ대한통운\",\"description\":\"CJ대한통운\"}")
    CodeNameDescriptionMetadata carrier,

    @Schema(description = "송장 번호", example = "123456789012")
    String trackingNumber,

    @Schema(description = "배송 상태 — 송장 상태 그대로다(주문 상태와 다를 수 있다)",
        example = "{\"code\":\"SHIPPED\",\"name\":\"출고 완료\",\"description\":\"송장이 등록되어 상품이 출고되었습니다.\"}")
    CodeNameDescriptionMetadata status,

    @Schema(description = "출고가 주문에 반영됐는가 — false 면 같은 송장으로 등록을 다시 보내 반영한다", example = "true")
    boolean orderSynced,

    @Schema(description = "배송 완료를 주문에 반영하기를 기다리는가 — true 면 배송 완료(DELIVERED)를 다시 보내 반영한다", example = "false")
    boolean completionPending,

    @Schema(description = "출고 시각 (UTC, ISO-8601)", example = "2026-11-02T09:30:00.123456Z")
    Instant shippedAt,

    @Schema(description = "배송 완료 시각 (UTC, ISO-8601) — 배송 완료가 아니면 null", nullable = true, example = "2026-11-04T02:10:00Z")
    Instant deliveredAt
) {
}
