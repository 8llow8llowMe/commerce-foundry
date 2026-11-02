package com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/** 배송 상태 이력 한 줄 (#18 PR-B2 어드민 상세). */
@Builder
@Schema(description = "배송 상태 이력 한 줄")
public record ShipmentStatusHistoryAdminItem(

    @Schema(description = "이전 상태 — 송장 등록 줄이면 null", nullable = true)
    CodeNameDescriptionMetadata fromStatus,

    @Schema(description = "바뀐 상태", example = "{\"code\":\"DELIVERED\",\"name\":\"배송 완료\",\"description\":\"상품이 배송되었습니다.\"}")
    CodeNameDescriptionMetadata toStatus,

    @Schema(description = "바꾼 쪽 — 관리자 · 자동 처리(주문 반영이 거절돼 배송 완료를 되돌린 줄 등)",
        example = "{\"code\":\"ADMIN\",\"name\":\"관리자\",\"description\":\"관리자\"}")
    CodeNameDescriptionMetadata changedBy,

    @Schema(description = "바꾼 관리자 아이디 — 자동 처리면 null", nullable = true, example = "4611686018427387904")
    String changedById,

    @Schema(description = "사유 — 없으면 null", nullable = true, example = "고객 수령 확인")
    String reason,

    @Schema(description = "바뀐 시각 (UTC, ISO-8601)", example = "2026-11-02T09:30:00Z")
    Instant changedAt
) {
}
