package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 어드민 재고 조정 이력 한 건. {@code adminMemberId} 는 개인정보가 아니라 내부 회원 아이디라 그대로 낸다 — 마스킹 대상은 이름 · 연락처 · 주소 같은
 * 고객 개인정보다 ({@code coding-conventions.md} §8 — 식별이 필요하면 내부 ID 만 남긴다). 어드민 이름 표시는 auth-service 조회가 필요해 이 API 범위 밖이다.
 */
@Builder
@Schema(description = "어드민 재고 조정 이력 한 건")
public record StockAdjustmentHistoryAdminItem(

    @Schema(description = "조정 이력 아이디", example = "4611686018427388100")
    String id,

    @Schema(description = "조정 전 판매 가능 수량. 재고를 처음 등록한 조정은 0 (품절)", example = "10")
    long beforeQuantity,

    @Schema(description = "조정 후 판매 가능 수량", example = "12")
    long afterQuantity,

    @Schema(description = "수량 변화 (afterQuantity − beforeQuantity). 관리 여부만 바꾼 조정은 0", example = "2")
    long delta,

    @Schema(description = "조정 전 재고 관리 여부. 재고를 처음 등록한 조정은 true (품절)", example = "true")
    boolean beforeStockManaged,

    @Schema(description = "조정 후 재고 관리 여부", example = "true")
    boolean afterStockManaged,

    @Schema(
        description = "조정 사유 — RESTOCK(입고) · RECOUNT(실사) · DAMAGE(파손 · 불량) · MANAGEMENT_CHANGE(관리 여부 변경) · OTHER(기타)",
        example = "{\"code\": \"RESTOCK\", \"name\": \"입고\", \"description\": \"새로 들어온 수량을 반영했습니다.\"}"
    )
    CodeNameDescriptionMetadata reason,

    @Schema(description = "조정 메모", example = "10월 입고분", nullable = true)
    String memo,

    @Schema(description = "조정한 어드민 회원 아이디", example = "4611686018427387904")
    String adminMemberId,

    @Schema(description = "조정 시각 (UTC, ISO-8601)", example = "2026-10-18T09:00:00Z")
    Instant createdAt
) {
}
