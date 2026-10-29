package com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * SKU 원가 이력 한 행. 원가는 어드민 전용이다 — 고객 · 서비스 간 응답에 이 항목을 넣지 않는다. {@code adminMemberId} 는 내부 회원 아이디라 그대로 낸다
 * (선례: {@code StockAdjustmentHistoryAdminItem}). 어드민 이름 표시는 auth-service 조회가 필요해 이 API 범위 밖이다.
 */
@Builder
@Schema(description = "SKU 원가 이력 한 행")
public record SkuCostAdminItem(

    @Schema(description = "원가 이력 아이디", example = "4611686018427388100")
    String costId,

    @Schema(
        description = "원가 유형 — GOODS(매입 · 제작 원가) · PACKAGING(포장 변동비)",
        example = "{\"code\": \"GOODS\", \"name\": \"매입 · 제작 원가\", \"description\": \"상품 한 개를 매입하거나 제작하는 데 든 원가입니다.\"}"
    )
    CodeNameDescriptionMetadata costType,

    @Schema(description = "원가 — 최소 화폐 단위 정수와 통화. 0 은 0원이라고 명시한 값이다")
    MoneyItem amount,

    @Schema(description = "적용 시작 시각 (포함, UTC ISO-8601). 같은 유형의 다음 행 시작 전까지 적용된다", example = "2026-10-01T00:00:00Z")
    Instant effectiveFrom,

    @Schema(description = "변경 사유", example = "10월 원단 단가 인상")
    String reason,

    @Schema(description = "기록한 어드민 회원 아이디", example = "4611686018427387904")
    String adminMemberId,

    @Schema(description = "기록 시각 (UTC, ISO-8601)", example = "2026-10-28T09:00:00Z")
    Instant createdAt
) {
}
