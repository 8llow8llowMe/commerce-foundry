package com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/** 주문 상태 이력 한 줄 (#17 어드민 상세). */
@Builder
@Schema(description = "주문 상태 이력 한 줄")
public record OrderStatusHistoryAdminItem(

    @Schema(description = "이전 상태 — 주문 접수 줄이면 null", nullable = true)
    CodeNameDescriptionMetadata fromStatus,

    @Schema(description = "바뀐 상태")
    CodeNameDescriptionMetadata toStatus,

    @Schema(description = "바꾼 쪽 — 고객 · 관리자 · 자동 처리 · 연동 처리",
        example = "{\"code\":\"ADMIN\",\"name\":\"관리자\",\"description\":\"운영자가 관리 화면에서 바꿨습니다.\"}")
    CodeNameDescriptionMetadata changedBy,

    @Schema(description = "바꾼 회원 · 관리자 아이디 — 자동 · 연동 처리면 null", nullable = true)
    String changedById,

    @Schema(description = "사유 — 없으면 null", nullable = true, example = "포장 시작")
    String reason,

    @Schema(description = "바뀐 시각 (UTC, ISO-8601)", example = "2026-10-18T10:00:00Z")
    Instant changedAt
) {
}
